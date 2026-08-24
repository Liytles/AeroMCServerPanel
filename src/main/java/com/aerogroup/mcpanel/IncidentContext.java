package com.aerogroup.mcpanel;

import java.time.*;
import java.util.*;

/** Bir kapanma veya çökmeden önceki ölçümleri ve anlamlı konsol belirtilerini tek vakada toplar. */
final class IncidentContext {
    record Metric(Instant time, double tps, double ramPercent, double cpuPercent) { }
    record Report(String headline, String detail) { }

    private final Deque<Metric> metrics = new ArrayDeque<>();
    private final Deque<String> console = new ArrayDeque<>();

    synchronized void recordMetric(Instant time, double tps, double ramPercent, double cpuPercent) {
        metrics.addLast(new Metric(time, tps, ramPercent, cpuPercent));
        trim(time.minus(Duration.ofMinutes(15)));
        while (metrics.size() > 450) metrics.removeFirst();
    }

    synchronized void recordConsole(String line) {
        String safe = NotificationCenter.safeText(line, 190);
        if (safe.isBlank()) return;
        console.addLast(safe);
        while (console.size() > 80) console.removeFirst();
    }

    synchronized Report report(Instant now) {
        trim(now.minus(Duration.ofMinutes(10)));
        List<Metric> recent = List.copyOf(metrics);
        double latestTps = latest(recent, Metric::tps), latestRam = latest(recent, Metric::ramPercent);
        double minTps = recent.stream().mapToDouble(Metric::tps).filter(Double::isFinite).min().orElse(Double.NaN);
        double maxRam = recent.stream().mapToDouble(Metric::ramPercent).filter(Double::isFinite).max().orElse(Double.NaN);
        double maxCpu = recent.stream().mapToDouble(Metric::cpuPercent).filter(Double::isFinite).max().orElse(Double.NaN);
        List<String> clues = console.stream().filter(IncidentContext::suspicious).skip(Math.max(0, console.stream().filter(IncidentContext::suspicious).count() - 3)).toList();
        String headline = "Son 10 dk: TPS " + range(latestTps, minTps, false) + " • RAM " + range(latestRam, maxRam, true) + " • CPU tepe " + percent(maxCpu);
        String detail = headline + (clues.isEmpty() ? " • Konsolda belirgin hata yakalanmadı" : " • Konsol: " + String.join(" ⇢ ", clues));
        return new Report(headline, detail);
    }

    synchronized void clear() { metrics.clear(); console.clear(); }

    private void trim(Instant cutoff) { while (!metrics.isEmpty() && metrics.peekFirst().time().isBefore(cutoff)) metrics.removeFirst(); }
    private static double latest(List<Metric> values, java.util.function.ToDoubleFunction<Metric> getter) { for (int i = values.size() - 1; i >= 0; i--) { double value = getter.applyAsDouble(values.get(i)); if (Double.isFinite(value)) return value; } return Double.NaN; }
    private static String range(double latest, double extreme, boolean percent) { String suffix = percent ? "%" : ""; return number(latest) + suffix + " (" + (percent ? "tepe " : "dip ") + number(extreme) + suffix + ")"; }
    private static String percent(double value) { return Double.isFinite(value) ? number(value) + "%" : "veri yok"; }
    private static String number(double value) { return Double.isFinite(value) ? String.format(Locale.US, "%.1f", value) : "veri yok"; }
    private static boolean suspicious(String line) { String value = line.toLowerCase(Locale.ROOT); return value.contains("error") || value.contains("exception") || value.contains("crash") || value.contains("can't keep up") || value.contains("overloaded") || value.contains("watchdog") || value.contains("outofmemory"); }
}
