package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Seyrekleştirilmiş performans geçmişinden güvenli Kriz Modu eşikleri önerir. */
final class SmartThresholdAdvisor {
    record Sample(Instant time, String source, double tps, double ramPercent, double cpuPercent) {
        Sample(Instant time, String source, double tps, double ramPercent) { this(time, source, tps, ramPercent, Double.NaN); }
    }
    record Recommendation(double tps, int ramPercent, int triggerSeconds, int samples, String confidence, String explanation) { }

    private static final Duration RETENTION = Duration.ofDays(7), SAMPLE_INTERVAL = Duration.ofSeconds(30);
    private final Path file;
    private final List<Sample> samples = new ArrayList<>();
    private final Map<String, Instant> lastRecorded = new HashMap<>();

    SmartThresholdAdvisor(Path file) { this.file = file; load(); }

    synchronized void record(Instant now, String source, double tps, double ramPercent) {
        record(now, source, tps, ramPercent, Double.NaN);
    }

    synchronized void record(Instant now, String source, double tps, double ramPercent, double cpuPercent) {
        if (!Double.isFinite(tps) && !Double.isFinite(ramPercent) && !Double.isFinite(cpuPercent)) return;
        Instant previous = lastRecorded.get(source); if (previous != null && Duration.between(previous, now).compareTo(SAMPLE_INTERVAL) < 0) return;
        samples.add(new Sample(now, source, tps, ramPercent, cpuPercent)); lastRecorded.put(source, now); prune(now); save();
    }

    synchronized Optional<Recommendation> recommend(String source, Instant now) {
        List<Sample> relevant = samples.stream().filter(sample -> sample.source().equals(source) && !sample.time().isBefore(now.minus(RETENTION))).toList();
        if (relevant.size() < 20) return Optional.empty();
        double[] tpsValues = relevant.stream().mapToDouble(Sample::tps).filter(Double::isFinite).sorted().toArray();
        double[] ramValues = relevant.stream().mapToDouble(Sample::ramPercent).filter(Double::isFinite).sorted().toArray();
        if (tpsValues.length < 12 && ramValues.length < 12) return Optional.empty();
        double tps = tpsValues.length < 12 ? 16.0 : roundHalf(clamp(percentile(tpsValues, .10) - 1.0, 12.0, 18.5));
        int ram = ramValues.length < 12 ? 90 : (int) Math.round(clamp(percentile(ramValues, .90) + 7.0, 78.0, 95.0));
        int trigger = relevant.size() >= 240 ? 12 : 16;
        String confidence = relevant.size() >= 480 ? "Yüksek" : relevant.size() >= 120 ? "Orta" : "Başlangıç";
        String explanation = "Son 7 gündeki " + relevant.size() + " seyrekleştirilmiş örnekten TPS alt %10 ve RAM üst %10 davranışı hesaplandı; ani sıçramalar için güvenli pay eklendi.";
        return Optional.of(new Recommendation(tps, ram, trigger, relevant.size(), confidence, explanation));
    }

    synchronized List<Sample> samplesSince(Instant cutoff) {
        return samples.stream().filter(sample -> !sample.time().isBefore(cutoff)).toList();
    }

    static Recommendation recommend(List<Sample> relevant) {
        SmartThresholdAdvisor advisor = new SmartThresholdAdvisor(null); advisor.samples.clear(); advisor.samples.addAll(relevant);
        String source = relevant.isEmpty() ? "" : relevant.get(0).source(); Instant now = relevant.stream().map(Sample::time).max(Comparator.naturalOrder()).orElse(Instant.now());
        return advisor.recommend(source, now).orElseThrow();
    }

    private void prune(Instant now) { samples.removeIf(sample -> sample.time().isBefore(now.minus(RETENTION))); if (samples.size() > 20000) samples.subList(0, samples.size() - 20000).clear(); }
    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try { for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) { String[] p = line.split("\\|", 5); if (p.length >= 4) samples.add(new Sample(Instant.ofEpochMilli(Long.parseLong(p[0])), new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8), Double.parseDouble(p[2]), Double.parseDouble(p[3]), p.length > 4 ? Double.parseDouble(p[4]) : Double.NaN)); } prune(Instant.now()); for (Sample sample : samples) lastRecorded.merge(sample.source(), sample.time(), (a, b) -> a.isAfter(b) ? a : b); } catch (Exception ignored) { samples.clear(); lastRecorded.clear(); }
    }
    private void save() {
        if (file == null) return;
        try { Files.createDirectories(file.getParent()); List<String> lines = samples.stream().map(sample -> sample.time().toEpochMilli() + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(sample.source().getBytes(StandardCharsets.UTF_8)) + "|" + sample.tps() + "|" + sample.ramPercent() + "|" + sample.cpuPercent()).toList(); Path temp = file.resolveSibling(file.getFileName() + ".tmp"); Files.write(temp, lines, StandardCharsets.UTF_8); try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); } } catch (IOException ignored) { }
    }
    private static double percentile(double[] sorted, double percentile) { if (sorted.length == 1) return sorted[0]; double position = percentile * (sorted.length - 1), lower = sorted[(int) Math.floor(position)], upper = sorted[(int) Math.ceil(position)]; return lower + (upper - lower) * (position - Math.floor(position)); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static double roundHalf(double value) { return Math.round(value * 2.0) / 2.0; }
}
