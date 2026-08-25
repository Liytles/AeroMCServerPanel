package com.aerogroup.mcpanel;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** Son yedi günlük ölçümleri uygulanabilir, dürüst bir haftalık özete dönüştürür. */
final class WeeklyReportEngine {
    private static final int MIN_RAM_SAMPLES = 20;
    private static final Duration MAX_FLEET_GAP = Duration.ofMinutes(10);

    static Report generate(Instant now, List<SmartThresholdAdvisor.Sample> performance,
                           List<FleetHealthHistory.Sample> fleet, List<CrisisHistory.Entry> crises,
                           List<DiagnosticHistory.Entry> diagnostics, List<PlayerInput> players) {
        Instant cutoff = now.minus(Duration.ofDays(7));
        List<SmartThresholdAdvisor.Sample> perf = performance.stream().filter(value -> !value.time().isBefore(cutoff)).toList();
        List<FleetHealthHistory.Sample> fleetWeek = fleet.stream().filter(value -> !value.time().isBefore(cutoff)).toList();
        List<CrisisHistory.Entry> crisisWeek = crises.stream().filter(value -> !value.startedAt().isBefore(cutoff)).toList();
        List<DiagnosticHistory.Entry> diagnosticWeek = diagnostics.stream().filter(value -> !value.time().isBefore(cutoff)).toList();
        return new Report(cutoff, now, ramSuggestions(now, perf, fleetWeek), errors(diagnosticWeek), fleetScores(now, fleetWeek, crisisWeek), playerSummary(now, cutoff, players));
    }

    static LocalSummary localSummary(Instant now, String source, List<SmartThresholdAdvisor.Sample> performance, List<CrisisHistory.Entry> crises) {
        Instant cutoff = now.minus(Duration.ofDays(7));
        List<SmartThresholdAdvisor.Sample> samples = performance.stream().filter(value -> !value.time().isBefore(cutoff) && value.source().equals(source)).sorted(Comparator.comparing(SmartThresholdAdvisor.Sample::time)).toList();
        double[] ram = samples.stream().mapToDouble(SmartThresholdAdvisor.Sample::ramPercent).filter(Double::isFinite).sorted().toArray();
        double[] cpu = samples.stream().mapToDouble(SmartThresholdAdvisor.Sample::cpuPercent).filter(Double::isFinite).sorted().toArray();
        double[] tps = samples.stream().mapToDouble(SmartThresholdAdvisor.Sample::tps).filter(Double::isFinite).sorted().toArray();
        List<CrisisHistory.Entry> localCrises = crises.stream().filter(value -> !value.startedAt().isBefore(cutoff) && value.source().equals(source)).toList();
        long crisisSeconds = localCrises.stream().mapToLong(value -> Duration.between(value.startedAt(), value.endedAt() == null ? now : value.endedAt()).toSeconds()).map(value -> Math.max(0, value)).sum();
        return new LocalSummary(samples.size(), average(ram), value(ram, .90), average(cpu), value(cpu, .90), average(tps), value(tps, .10), performanceHours(samples, now), localCrises.size(), crisisSeconds);
    }

    private static List<RamSuggestion> ramSuggestions(Instant now, List<SmartThresholdAdvisor.Sample> performance, List<FleetHealthHistory.Sample> fleet) {
        Map<String, List<FleetHealthHistory.Sample>> byServer = fleet.stream().collect(Collectors.groupingBy(FleetHealthHistory.Sample::server, TreeMap::new, Collectors.toList()));
        List<RamSuggestion> result = new ArrayList<>();
        for (var entry : byServer.entrySet()) {
            String server = entry.getKey(); List<FleetHealthHistory.Sample> states = sorted(entry.getValue());
            int allocated = states.stream().mapToInt(FleetHealthHistory.Sample::ramGiB).filter(value -> value > 0).reduce((first, second) -> second).orElse(-1);
            double observedHours = observedOnlineHours(states, now);
            double[] ram = performance.stream().filter(sample -> sourceMatches(sample.source(), server)).mapToDouble(SmartThresholdAdvisor.Sample::ramPercent).filter(Double::isFinite).sorted().toArray();
            if (allocated < 2 || ram.length < MIN_RAM_SAMPLES) {
                result.add(new RamSuggestion(server, allocated, ram.length == 0 ? Double.NaN : Arrays.stream(ram).average().orElse(Double.NaN), ram.length == 0 ? Double.NaN : percentile(ram, .90), allocated, observedHours, 0, ram.length, "INSUFFICIENT"));
                continue;
            }
            double average = Arrays.stream(ram).average().orElse(Double.NaN), p90 = percentile(ram, .90);
            int needed = Math.max(2, (int) Math.ceil(allocated * (p90 / 100.0) / .80));
            int suggested = Math.min(allocated, needed);
            double savings = Math.max(0, allocated - suggested) * observedHours;
            String confidence = ram.length >= 480 && observedHours >= 8 ? "HIGH" : ram.length >= 120 && observedHours >= 3 ? "MEDIUM" : "STARTING";
            result.add(new RamSuggestion(server, allocated, average, p90, suggested, observedHours, savings, ram.length, confidence));
        }
        result.sort(Comparator.comparingDouble(RamSuggestion::weeklySavings).reversed().thenComparing(RamSuggestion::server));
        return List.copyOf(result);
    }

    private static List<ErrorRank> errors(List<DiagnosticHistory.Entry> diagnostics) {
        Map<String, Long> counts = diagnostics.stream().collect(Collectors.groupingBy(DiagnosticHistory.Entry::kind, Collectors.counting()));
        return counts.entrySet().stream().map(entry -> new ErrorRank(entry.getKey(), entry.getValue().intValue()))
                .sorted(Comparator.comparingInt(ErrorRank::count).reversed().thenComparing(ErrorRank::kind)).limit(3).toList();
    }

    private static List<FleetScore> fleetScores(Instant now, List<FleetHealthHistory.Sample> fleet, List<CrisisHistory.Entry> crises) {
        Map<String, List<FleetHealthHistory.Sample>> byServer = fleet.stream().collect(Collectors.groupingBy(FleetHealthHistory.Sample::server, TreeMap::new, Collectors.toList()));
        List<FleetScore> result = new ArrayList<>();
        for (var entry : byServer.entrySet()) {
            String server = entry.getKey(); List<FleetHealthHistory.Sample> values = sorted(entry.getValue());
            int crashTransitions = 0; boolean wasCrashed = false;
            for (FleetHealthHistory.Sample value : values) { if (value.crashed() && !wasCrashed) crashTransitions++; wasCrashed = value.crashed(); }
            int crisisCount = (int) crises.stream().filter(value -> sourceMatches(value.source(), server)).count();
            double observedHours = observedHours(values, now, false), onlineHours = observedHours(values, now, true); int score = Math.max(0, 100 - crashTransitions * 25 - crisisCount * 10);
            result.add(new FleetScore(server, score, crashTransitions, crisisCount, observedHours, onlineHours, values.size()));
        }
        result.sort(Comparator.<FleetScore, Boolean>comparing(FleetScore::ready).reversed().thenComparing(Comparator.comparingInt(FleetScore::score).reversed()).thenComparingInt(FleetScore::crises).thenComparing(FleetScore::server));
        return List.copyOf(result);
    }

    private static PlayerSummary playerSummary(Instant now, Instant cutoff, List<PlayerInput> players) {
        Instant dormantCutoff = now.minus(Duration.ofDays(30)); int fresh = 0, returning = 0, dormant = 0, seen = 0;
        for (PlayerInput player : players) {
            if (player.firstSeen() != null && !player.firstSeen().isBefore(cutoff)) fresh++;
            if (player.returnedAt() != null && !player.returnedAt().isBefore(cutoff)) returning++;
            if (player.lastSeen() != null && player.lastSeen().isBefore(dormantCutoff)) dormant++;
            if (player.lastSeen() != null && !player.lastSeen().isBefore(cutoff)) seen++;
        }
        return new PlayerSummary(fresh, returning, dormant, seen, players.size());
    }

    private static List<FleetHealthHistory.Sample> sorted(List<FleetHealthHistory.Sample> values) { return values.stream().sorted(Comparator.comparing(FleetHealthHistory.Sample::time)).toList(); }
    private static double observedOnlineHours(List<FleetHealthHistory.Sample> values, Instant now) { return observedHours(values, now, true); }
    private static double observedHours(List<FleetHealthHistory.Sample> values, Instant now, boolean onlineOnly) {
        double seconds = 0;
        for (int i = 0; i < values.size(); i++) {
            FleetHealthHistory.Sample sample = values.get(i); Instant end = i + 1 < values.size() ? values.get(i + 1).time() : now;
            long duration = Math.max(0, Math.min(MAX_FLEET_GAP.toSeconds(), Duration.between(sample.time(), end).toSeconds()));
            if (!onlineOnly || sample.online()) seconds += duration;
        }
        return seconds / 3600.0;
    }

    private static boolean sourceMatches(String source, String server) {
        String a = Objects.toString(source, "").toLowerCase(Locale.ROOT), b = Objects.toString(server, "").toLowerCase(Locale.ROOT);
        return a.equals(b) || a.endsWith(" • " + b) || a.endsWith(" - " + b);
    }
    private static double percentile(double[] sorted, double percentile) { if (sorted.length == 1) return sorted[0]; double position = percentile * (sorted.length - 1); int low = (int) Math.floor(position), high = (int) Math.ceil(position); return sorted[low] + (sorted[high] - sorted[low]) * (position - low); }
    private static double average(double[] values) { return values.length == 0 ? Double.NaN : Arrays.stream(values).average().orElse(Double.NaN); }
    private static double value(double[] values, double percentile) { return values.length == 0 ? Double.NaN : percentile(values, percentile); }
    private static double performanceHours(List<SmartThresholdAdvisor.Sample> values, Instant now) {
        if (values.isEmpty()) return 0; long seconds = 0;
        for (int i = 0; i < values.size(); i++) {
            Instant end = i + 1 < values.size() ? values.get(i + 1).time() : now;
            seconds += Math.max(0, Math.min(120, Duration.between(values.get(i).time(), end).toSeconds()));
        }
        return seconds / 3600.0;
    }

    record PlayerInput(String name, Instant firstSeen, Instant lastSeen, Instant returnedAt) { }
    record LocalSummary(int samples, double averageRamPercent, double p90RamPercent, double averageCpuPercent, double p90CpuPercent,
                        double averageTps, double p10Tps, double observedHours, int crises, long crisisSeconds) { boolean ready() { return samples >= 20; } }
    record RamSuggestion(String server, int allocatedGiB, double averagePercent, double p90Percent, int suggestedGiB, double observedOnlineHours, double weeklySavings, int samples, String confidence) { boolean ready() { return !"INSUFFICIENT".equals(confidence); } }
    record ErrorRank(String kind, int count) { }
    record FleetScore(String server, int score, int crashes, int crises, double observedHours, double onlineHours, int samples) {
        boolean ready() { return samples >= 3 && observedHours >= (10.0 / 60.0) && onlineHours >= (5.0 / 60.0); }
    }
    record PlayerSummary(int newPlayers, int returningPlayers, int dormantPlayers, int seenThisWeek, int trackedPlayers) { }
    record Report(Instant from, Instant to, List<RamSuggestion> ramSuggestions, List<ErrorRank> errors, List<FleetScore> fleet, PlayerSummary players) { }
}
