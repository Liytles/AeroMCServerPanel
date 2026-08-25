package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Exaroton filosunu seyrek örnekleyerek haftalık karşılaştırma verisi üretir. */
final class FleetHealthHistory {
    private static final Duration RETENTION = Duration.ofDays(14), INTERVAL = Duration.ofMinutes(5);
    private static final int LIMIT = 20_000;
    private final Path file;
    private final List<Sample> samples = new ArrayList<>();

    FleetHealthHistory() {
        this(Path.of(System.getProperty("user.home"), ".aeromc-panel", "fleet-health-history.log"));
    }

    FleetHealthHistory(Path file) { this.file = file; load(); }

    synchronized void record(Instant now, List<ExarotonFleetEngine.ServerState> states) {
        boolean changed = false;
        for (ExarotonFleetEngine.ServerState state : states) {
            Sample previous = latest(state.name());
            Sample next = new Sample(now, clean(state.name()), state.online(), state.crashed(), Math.max(0, state.players()), state.ramGiB());
            boolean stateChanged = previous == null || previous.online() != next.online() || previous.crashed() != next.crashed()
                    || previous.players() != next.players() || previous.ramGiB() != next.ramGiB();
            if (stateChanged || Duration.between(previous.time(), now).compareTo(INTERVAL) >= 0) {
                samples.add(next); changed = true;
            }
        }
        if (changed) { prune(now); save(); }
    }

    synchronized List<Sample> since(Instant cutoff) {
        return samples.stream().filter(sample -> !sample.time().isBefore(cutoff)).toList();
    }

    private Sample latest(String server) {
        for (int i = samples.size() - 1; i >= 0; i--) if (samples.get(i).server().equalsIgnoreCase(server)) return samples.get(i);
        return null;
    }

    private void prune(Instant now) {
        samples.removeIf(sample -> sample.time().isBefore(now.minus(RETENTION)));
        if (samples.size() > LIMIT) samples.subList(0, samples.size() - LIMIT).clear();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|", 6); if (p.length != 6) continue;
                samples.add(new Sample(Instant.ofEpochMilli(Long.parseLong(p[0])), decode(p[1]), Boolean.parseBoolean(p[2]), Boolean.parseBoolean(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5])));
            }
            prune(Instant.now());
        } catch (Exception ignored) { samples.clear(); }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = samples.stream().map(sample -> sample.time().toEpochMilli() + "|" + encode(sample.server()) + "|" + sample.online() + "|" + sample.crashed() + "|" + sample.players() + "|" + sample.ramGiB()).toList();
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); Files.write(temporary, lines, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }

    private static String encode(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String text) { return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8); }
    private static String clean(String value) { return Objects.toString(value, "Bilinmeyen").replace('\n', ' ').replace('\r', ' ').strip(); }

    record Sample(Instant time, String server, boolean online, boolean crashed, int players, int ramGiB) { }
}
