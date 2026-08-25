package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.time.Instant;

public final class CrisisHistorySmoke {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory("aeromc-crisis-history-").resolve("history.properties"); CrisisHistory history = new CrisisHistory(file);
        String id = history.start(Instant.parse("2026-08-25T10:00:00Z"), "Yerel JAR", "TPS 14.2", 16, 90, false); history.finish(id, Instant.parse("2026-08-25T10:02:05Z"), "Toparlandı");
        CrisisHistory.Entry entry = new CrisisHistory(file).entries().get(0); require(entry.endedAt() != null, "end persisted"); require(CrisisHistory.display(entry).contains("2 dk 5 sn"), "duration displayed"); require(CrisisHistory.display(entry).contains("TPS < 16.0"), "threshold displayed");
        String open = history.start(Instant.parse("2026-08-25T11:00:00Z"), "Exaroton", "RAM %95", 15, 88, true); require(open != null, "active entry created"); CrisisHistory reopened = new CrisisHistory(file); reopened.closeInterrupted(Instant.parse("2026-08-25T11:01:00Z")); require(reopened.entries().get(0).result().contains("kesildi"), "interrupted entry closed");
        System.out.println("crisis-history-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
