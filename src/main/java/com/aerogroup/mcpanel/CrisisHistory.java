package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Kriz Modu oturumlarını küçük ve kalıcı bir geçmişte tutar. */
public final class CrisisHistory {
    private static final int STORE_LIMIT = 500;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public CrisisHistory(Path file) { this.file = file; load(); }

    public synchronized String start(Instant time, String source, String reason, double tpsThreshold, int ramThreshold, boolean manual) {
        String id = UUID.randomUUID().toString();
        entries.add(0, new Entry(id, time, null, clean(source), clean(reason), tpsThreshold, ramThreshold, manual, "Etkin"));
        trim(); save(); return id;
    }

    public synchronized void finish(String id, Instant time, String result) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.id().equals(id)) { entries.set(i, entry.finish(time, clean(result))); save(); return; }
        }
    }

    public synchronized void closeInterrupted(Instant time) {
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).endedAt() == null) {
            entries.set(i, entries.get(i).finish(time, "AeroMC kapanınca kesildi")); changed = true;
        }
        if (changed) save();
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }

    public static String display(Entry entry) {
        boolean english = LanguageManager.isEnglish();
        String when = entry.startedAt().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"));
        String duration = entry.endedAt() == null ? (english ? "in progress" : "devam ediyor") : duration(Duration.between(entry.startedAt(), entry.endedAt()).toSeconds(), english);
        return String.format(Locale.US, "%s • %s • TPS < %.1f / RAM > %%%d • %s • %s • %s%s", when, LanguageManager.text(entry.source()), entry.tpsThreshold(), entry.ramThreshold(), shortText(LanguageManager.text(entry.reason()), 72), duration, LanguageManager.text(entry.result()), entry.manual() ? (english ? " • Manual" : " • Manuel") : "");
    }

    static String duration(long seconds) { return duration(seconds, false); }
    private static String duration(long seconds, boolean english) {
        seconds = Math.max(0, seconds); long minutes = seconds / 60, rest = seconds % 60;
        if (minutes >= 60) return (minutes / 60) + (english ? " hr " : " sa ") + (minutes % 60) + (english ? " min" : " dk");
        if (minutes > 0) return minutes + (english ? " min " : " dk ") + rest + (english ? " sec" : " sn");
        return rest + (english ? " sec" : " sn");
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader); int count = Math.min(STORE_LIMIT, Integer.parseInt(values.getProperty("count", "0")));
            for (int i = 0; i < count; i++) {
                String key = "entry." + i + "."; Instant started = Instant.parse(values.getProperty(key + "started")); String ended = values.getProperty(key + "ended", "");
                entries.add(new Entry(values.getProperty(key + "id", UUID.randomUUID().toString()), started, ended.isBlank() ? null : Instant.parse(ended), values.getProperty(key + "source", "Bilinmeyen sunucu"), values.getProperty(key + "reason", "Bilinmeyen eşik"), Double.parseDouble(values.getProperty(key + "tps", "16")), Integer.parseInt(values.getProperty(key + "ram", "90")), Boolean.parseBoolean(values.getProperty(key + "manual", "false")), values.getProperty(key + "result", "Etkin")));
            }
        } catch (Exception ignored) { entries.clear(); }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent()); Properties values = new Properties(); values.setProperty("count", Integer.toString(entries.size()));
            for (int i = 0; i < entries.size(); i++) { Entry entry = entries.get(i); String key = "entry." + i + "."; values.setProperty(key + "id", entry.id()); values.setProperty(key + "started", entry.startedAt().toString()); values.setProperty(key + "ended", entry.endedAt() == null ? "" : entry.endedAt().toString()); values.setProperty(key + "source", entry.source()); values.setProperty(key + "reason", entry.reason()); values.setProperty(key + "tps", Double.toString(entry.tpsThreshold())); values.setProperty(key + "ram", Integer.toString(entry.ramThreshold())); values.setProperty(key + "manual", Boolean.toString(entry.manual())); values.setProperty(key + "result", entry.result()); }
            Path temporary = Files.createTempFile(file.getParent(), ".crisis-history-", ".tmp");
            try { try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "AeroMC crisis history"); } try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } } finally { Files.deleteIfExists(temporary); }
        } catch (IOException ignored) { }
    }

    private void trim() { while (entries.size() > STORE_LIMIT) entries.remove(entries.size() - 1); }
    private static String clean(String value) { return Objects.toString(value, "").replace('\n', ' ').replace('\r', ' ').strip(); }
    private static String shortText(String value, int limit) { String text = clean(value); return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 1)) + "…"; }

    public record Entry(String id, Instant startedAt, Instant endedAt, String source, String reason, double tpsThreshold, int ramThreshold, boolean manual, String result) {
        Entry finish(Instant time, String result) { return new Entry(id, startedAt, time.isBefore(startedAt) ? startedAt : time, source, reason, tpsThreshold, ramThreshold, manual, result); }
    }
}
