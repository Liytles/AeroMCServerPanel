package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class WeeklyReportSmoke {
    public static void main(String[] args) throws Exception {
        Instant now = Instant.parse("2026-08-25T12:00:00Z");
        List<SmartThresholdAdvisor.Sample> performance = new ArrayList<>();
        for (int i = 0; i < 120; i++) performance.add(new SmartThresholdAdvisor.Sample(now.minus(Duration.ofMinutes(120 - i)), "Sunucu • Exaroton • Alpha", 19.8, 40));

        List<FleetHealthHistory.Sample> fleet = new ArrayList<>();
        for (int i = 0; i <= 144; i++) {
            Instant time = now.minus(Duration.ofHours(12)).plus(Duration.ofMinutes(i * 5L));
            fleet.add(new FleetHealthHistory.Sample(time, "Alpha", true, false, 2, 6));
            fleet.add(new FleetHealthHistory.Sample(time, "Beta", i > 100, i == 100, 0, 4));
            fleet.add(new FleetHealthHistory.Sample(time, "Gamma", false, false, 0, 4));
        }
        List<CrisisHistory.Entry> crises = List.of(new CrisisHistory.Entry("1", now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), "Sunucu • Exaroton • Alpha", "RAM", 16, 90, false, "Bitti"));
        List<DiagnosticHistory.Entry> diagnostics = List.of(
                new DiagnosticHistory.Entry(now.minusSeconds(30), "Alpha", "OOM"),
                new DiagnosticHistory.Entry(now.minusSeconds(60), "Alpha", "OOM"),
                new DiagnosticHistory.Entry(now.minusSeconds(90), "Alpha", "OOM"),
                new DiagnosticHistory.Entry(now.minusSeconds(120), "Beta", "PORT"));
        List<WeeklyReportEngine.PlayerInput> players = List.of(
                new WeeklyReportEngine.PlayerInput("New", now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(1)), null),
                new WeeklyReportEngine.PlayerInput("Return", now.minus(Duration.ofDays(100)), now.minus(Duration.ofHours(2)), now.minus(Duration.ofDays(2))),
                new WeeklyReportEngine.PlayerInput("Dormant", now.minus(Duration.ofDays(100)), now.minus(Duration.ofDays(40)), null));

        WeeklyReportEngine.Report report = WeeklyReportEngine.generate(now, performance, fleet, crises, diagnostics, players);
        WeeklyReportEngine.RamSuggestion ram = report.ramSuggestions().stream().filter(value -> value.server().equals("Alpha")).findFirst().orElseThrow();
        check(ram.suggestedGiB() == 4, "6 GiB / %40 kullanım güvenli payla 4 GiB önermeli");
        check(Math.abs(ram.weeklySavings() - 24) < .25, "gözlenen 12 saatte yaklaşık 24 kredi tasarruf hesaplanmalı");
        check(report.errors().get(0).kind().equals("OOM") && report.errors().get(0).count() == 3, "OOM haftanın en sık hatası olmalı");
        check(report.fleet().get(0).server().equals("Alpha") && report.fleet().get(0).crises() == 1, "filo sıralaması kriz ve çökme sayılarını kullanmalı");
        check(report.fleet().stream().filter(value -> value.server().equals("Gamma")).noneMatch(WeeklyReportEngine.FleetScore::ready), "hiç online görülmeyen sunucu stabil sayılmamalı");
        check(report.players().newPlayers() == 1 && report.players().returningPlayers() == 1 && report.players().dormantPlayers() == 1, "oyuncu özeti yeni/dönen/pasif ayrımını yapmalı");

        String localSource = NotificationCenter.serverSource("Yerel JAR", ""); List<SmartThresholdAdvisor.Sample> localPerformance = new ArrayList<>();
        for (int i = 0; i < 120; i++) localPerformance.add(new SmartThresholdAdvisor.Sample(now.minus(Duration.ofMinutes(120 - i)), localSource, Double.NaN, 50, 30));
        CrisisHistory.Entry localCrisis = new CrisisHistory.Entry("local", now.minus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(15)), localSource, "CPU", 16, 90, false, "Bitti");
        WeeklyReportEngine.LocalSummary local = WeeklyReportEngine.localSummary(now, localSource, localPerformance, List.of(localCrisis));
        check(local.ready() && Math.abs(local.averageRamPercent() - 50) < .01 && Math.abs(local.averageCpuPercent() - 30) < .01, "yerel RAM ve CPU haftalık özeti hesaplanmalı");
        check(local.crises() == 1 && local.crisisSeconds() == 300 && local.observedHours() > 1.9, "yerel çalışma ve kriz süreleri özetlenmeli");

        Path file = Files.createTempDirectory("aeromc-weekly-").resolve("fleet.log");
        FleetHealthHistory history = new FleetHealthHistory(file);
        ExarotonFleetEngine.ServerState state = new ExarotonFleetEngine.ServerState("Alpha", "Online", true, false, 1, 20, 6);
        history.record(now.minusSeconds(60), List.of(state)); history.record(now, List.of(state));
        check(new FleetHealthHistory(file).since(now.minus(Duration.ofHours(1))).size() == 1, "aynı filo durumu beş dakikadan sık yazılmamalı");
        System.out.println("WeeklyReportSmoke OK");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
