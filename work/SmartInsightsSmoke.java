package com.aerogroup.mcpanel;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SmartInsightsSmoke {
    public static void main(String[] args) throws Exception {
        Instant now = Instant.parse("2026-08-24T18:00:00Z");
        String source = NotificationCenter.serverSource("Yerel JAR", "");
        List<NotificationCenter.Entry> entries = List.of(
                new NotificationCenter.Entry("1", now.minusSeconds(50), NotificationCenter.Severity.CRITICAL, source, "Sunucu çöktü", "Crash raporu oluştu", false),
                new NotificationCenter.Entry("2", now.minusSeconds(40), NotificationCenter.Severity.INFO, source, "Oyuncu katıldı", "Hasan sunucuya katıldı", false),
                new NotificationCenter.Entry("3", now.minusSeconds(30), NotificationCenter.Severity.WARNING, source, "Sunucu durumu", "Sunucu kapandı", false),
                new NotificationCenter.Entry("4", now.minusSeconds(3700), NotificationCenter.Severity.INFO, source, "Eski olay", "Oyuncu katıldı", false));
        NotificationSummary.Digest digest = NotificationSummary.since(entries, now.minusSeconds(3600));
        require(digest.total() == 3 && digest.crashes() == 1 && digest.playerJoins() == 1 && digest.offline() == 1, "hourly notification digest");

        IncidentContext context = new IncidentContext();
        context.recordMetric(now.minusSeconds(30), 18.5, 72, 45); context.recordMetric(now.minusSeconds(10), 14.0, 94, 98);
        context.recordConsole("[Server thread/WARN]: Can't keep up! Is the server overloaded?");
        context.recordConsole("java.lang.OutOfMemoryError: Java heap space");
        IncidentContext.Report report = context.report(now);
        require(report.detail().contains("dip 14.0") && report.detail().contains("tepe 94.0%") && report.detail().contains("OutOfMemoryError"), "incident context chain");

        var file = Files.createTempDirectory("aeromc-smart-threshold").resolve("history.log");
        SmartThresholdAdvisor advisor = new SmartThresholdAdvisor(file);
        for (int i = 0; i < 40; i++) advisor.record(now.minusSeconds((39L - i) * 31), source, 19.0 + i % 3 * .25, 64 + i % 6);
        SmartThresholdAdvisor.Recommendation recommendation = advisor.recommend(source, now).orElseThrow();
        require(recommendation.samples() == 40 && recommendation.tps() >= 17.5 && recommendation.ramPercent() >= 75, "smart crisis thresholds");
        require(new SmartThresholdAdvisor(file).recommend(source, now).isPresent(), "performance history persistence");
        System.out.println("smart-insights-ok");
    }

    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
