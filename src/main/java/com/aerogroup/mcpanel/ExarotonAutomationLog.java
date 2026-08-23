package com.aerogroup.mcpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Exaroton otomasyon ve durum olaylarını yerelde, gizli bilgi içermeden saklar. */
public final class ExarotonAutomationLog {
    private static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton-automation-events.tsv");
    private static final int MAX_ENTRIES = 2_000;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public record Entry(Instant time, String server, String source, String detail) { }

    public ExarotonAutomationLog() { this(DEFAULT_FILE); }
    ExarotonAutomationLog(Path file) { this.file = file; load(); }

    public synchronized void add(String server, String source, String detail) {
        entries.add(new Entry(Instant.now(), clean(server), clean(source), clean(detail)));
        trim(); save();
    }
    public synchronized List<Entry> recent(int limit) { return List.copyOf(entries.subList(Math.max(0, entries.size() - Math.max(1, limit)), entries.size())); }
    public synchronized void clear() { entries.clear(); save(); }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] part = line.split("\\t", 4); if (part.length != 4) continue;
                try { entries.add(new Entry(Instant.ofEpochMilli(Long.parseLong(part[0])), part[1], part[2], part[3])); } catch (Exception ignored) { }
            }
            trim();
        } catch (IOException ignored) { }
    }
    private void save() {
        try {
            Files.createDirectories(file.getParent()); StringBuilder value = new StringBuilder();
            for (Entry entry : entries) value.append(entry.time().toEpochMilli()).append('\t').append(entry.server()).append('\t').append(entry.source()).append('\t').append(entry.detail()).append(System.lineSeparator());
            Path temporary = Files.createTempFile(file.getParent(), ".exaroton-events-", ".tmp"); Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }
    private void trim() { if (entries.size() > MAX_ENTRIES) entries.subList(0, entries.size() - MAX_ENTRIES).clear(); }
    private static String clean(String value) { return Objects.toString(value, "-").replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }
}
