package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Tekrarlanan konsol ve çökme belirtilerini haftalık rapor için sınıflandırır. */
final class DiagnosticHistory {
    private static final Duration RETENTION = Duration.ofDays(30), DUPLICATE_WINDOW = Duration.ofSeconds(15);
    private static final DateTimeFormatter LEGACY_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    DiagnosticHistory(Path file) { this.file = file; load(); }

    synchronized void record(Instant time, String source, String detail) {
        String kind = classify(detail); if (kind == null) return;
        if (!entries.isEmpty()) { Entry last = entries.get(entries.size() - 1); if (last.kind().equals(kind) && last.source().equals(source) && Duration.between(last.time(), time).abs().compareTo(DUPLICATE_WINDOW) <= 0) return; }
        entries.add(new Entry(time, clean(source), kind)); prune(time); save();
    }

    synchronized void importTimeline(Path timeline) {
        if (!Files.isRegularFile(timeline)) return;
        Set<String> existing = new HashSet<>(); for (Entry entry : entries) existing.add(entry.time().getEpochSecond() + "|" + entry.kind());
        boolean changed = false;
        try {
            for (String line : Files.readAllLines(timeline, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\s+•\\s+", 3); if (p.length != 3) continue;
                Instant time = LocalDateTime.parse(p[0].strip(), LEGACY_TIME).atZone(ZoneId.systemDefault()).toInstant();
                String kind = classify(p[2]); String key = time.getEpochSecond() + "|" + kind;
                if (kind != null && existing.add(key)) { entries.add(new Entry(time, "Geçmiş olay", kind)); changed = true; }
            }
        } catch (Exception ignored) { }
        if (changed) { entries.sort(Comparator.comparing(Entry::time)); prune(Instant.now()); save(); }
    }

    synchronized List<Entry> since(Instant cutoff) { return entries.stream().filter(entry -> !entry.time().isBefore(cutoff)).toList(); }

    static String classify(String value) {
        String text = Objects.toString(value, "").toLowerCase(Locale.ROOT);
        if (text.contains("outofmemory") || text.contains("bellek tüken") || text.contains("ram yetersiz")) return "OOM";
        if (text.contains("address already in use") || text.contains("port çakış")) return "PORT";
        if (text.contains("mod bağıml") || text.contains("mod resolution")) return "MOD_DEPENDENCY";
        if (text.contains("eklenti yüklen") || text.contains("could not load plugin") || text.contains("could not load 'plugins")) return "PLUGIN";
        if (text.contains("can't keep up") || text.contains("watchdog") || text.contains("tick kilit") || text.contains("performans uyar")) return "TICK";
        if (text.contains("unsupportedclassversion") || text.contains("yanlış java")) return "JAVA_VERSION";
        if (text.contains("exception") || text.contains("java hatası") || text.contains("çökme raporu")) return "JAVA_CRASH";
        return null;
    }

    private void prune(Instant now) { entries.removeIf(entry -> entry.time().isBefore(now.minus(RETENTION))); if (entries.size() > 5000) entries.subList(0, entries.size() - 5000).clear(); }
    private void load() {
        if (!Files.isRegularFile(file)) return;
        try { for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) { String[] p = line.split("\\|", 3); if (p.length == 3) entries.add(new Entry(Instant.ofEpochMilli(Long.parseLong(p[0])), decode(p[1]), p[2])); } prune(Instant.now()); }
        catch (Exception ignored) { entries.clear(); }
    }
    private void save() {
        try { Files.createDirectories(file.getParent()); List<String> lines = entries.stream().map(entry -> entry.time().toEpochMilli() + "|" + encode(entry.source()) + "|" + entry.kind()).toList(); Path temporary = file.resolveSibling(file.getFileName() + ".tmp"); Files.write(temporary, lines, StandardCharsets.UTF_8); try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } }
        catch (IOException ignored) { }
    }
    private static String clean(String value) { return Objects.toString(value, "Bilinmeyen").replace('\n', ' ').replace('\r', ' ').strip(); }
    private static String encode(String text) { return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String text) { return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8); }

    record Entry(Instant time, String source, String kind) { }
}
