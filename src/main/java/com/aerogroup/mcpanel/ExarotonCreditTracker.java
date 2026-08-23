package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Exaroton kredi örneklerini yerelde saklar ve gerçek tüketim hızını hesaplar. */
public final class ExarotonCreditTracker {
    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton-credit-history.csv");
    private static final int MAX_SAMPLES = 10_000;
    private final Path file;
    private final List<Sample> samples = new ArrayList<>();

    public record Sample(Instant time, double credits) { }
    public record Stats(double current, double spentToday, double perHour, double remainingHours, long observedMinutes) { }

    public ExarotonCreditTracker() { this(DEFAULT_FILE); }
    ExarotonCreditTracker(Path file) { this.file = file; load(); }

    public synchronized void record(double credits) { recordAt(Instant.now(), credits); }
    synchronized void recordAt(Instant time, double credits) {
        if (!Double.isFinite(credits) || credits < 0) return;
        Sample last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        if (last != null && Duration.between(last.time, time).toMinutes() < 5 && Math.abs(last.credits - credits) < .0001) return;
        samples.add(new Sample(time, credits)); trim(time); save();
    }

    public synchronized List<Sample> recent(int limit) { return List.copyOf(samples.subList(Math.max(0, samples.size() - Math.max(1, limit)), samples.size())); }
    public synchronized double spentThisWeek() {
        ZoneId zone = ZoneId.systemDefault(); LocalDate today = LocalDate.now(zone); LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        return spentSince(monday.atStartOfDay(zone).toInstant());
    }
    synchronized double spentSince(Instant start) {
        if (samples.isEmpty()) return 0; Sample latest = samples.get(samples.size() - 1);
        Sample first = samples.stream().filter(sample -> !sample.time().isBefore(start)).findFirst().orElse(latest);
        return Math.max(0, first.credits() - latest.credits());
    }
    public static double officialServerRate(boolean online, int ramGiB) { return online && ramGiB > 0 ? ramGiB : 0; }
    public static boolean shouldStopAtThreshold(boolean thresholdEnabled, boolean autoStopEnabled, boolean serverOnline, double credits, double threshold) { return thresholdEnabled && autoStopEnabled && serverOnline && Double.isFinite(credits) && credits <= threshold; }
    public synchronized Stats stats() {
        if (samples.isEmpty()) return new Stats(Double.NaN, 0, 0, Double.POSITIVE_INFINITY, 0);
        Sample latest = samples.get(samples.size() - 1); ZoneId zone = ZoneId.systemDefault(); LocalDate today = LocalDate.now(zone);
        Sample todayFirst = samples.stream().filter(sample -> sample.time.atZone(zone).toLocalDate().equals(today)).findFirst().orElse(latest);
        double spentToday = Math.max(0, todayFirst.credits - latest.credits);
        Instant cutoff = latest.time.minus(Duration.ofHours(24)); Sample rateFirst = samples.stream().filter(sample -> !sample.time.isBefore(cutoff) && sample.credits > latest.credits).findFirst().orElse(null);
        double rate = 0; long observedMinutes = 0;
        if (rateFirst != null) { long seconds = Duration.between(rateFirst.time, latest.time).toSeconds(); double hours = seconds / 3600.0; observedMinutes = Math.max(1, Math.round(seconds / 60.0)); if (seconds >= 60) rate = Math.max(0, (rateFirst.credits - latest.credits) / hours); }
        double remaining = rate > .000001 ? latest.credits / rate : Double.POSITIVE_INFINITY;
        return new Stats(latest.credits, spentToday, rate, remaining, observedMinutes);
    }

    private void trim(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(30)); samples.removeIf(sample -> sample.time.isBefore(cutoff));
        if (samples.size() > MAX_SAMPLES) samples.subList(0, samples.size() - MAX_SAMPLES).clear();
    }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split(",", 2); if (parts.length != 2) continue;
                try { samples.add(new Sample(Instant.ofEpochMilli(Long.parseLong(parts[0])), Double.parseDouble(parts[1]))); } catch (Exception ignored) { }
            }
            samples.sort(Comparator.comparing(Sample::time)); trim(Instant.now());
        } catch (IOException ignored) { }
    }
    private void save() {
        try {
            Files.createDirectories(file.getParent()); StringBuilder text = new StringBuilder();
            for (Sample sample : samples) text.append(sample.time.toEpochMilli()).append(',').append(sample.credits).append(System.lineSeparator());
            Path temp = Files.createTempFile(file.getParent(), ".exaroton-credit-", ".tmp"); Files.writeString(temp, text, StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }
}
