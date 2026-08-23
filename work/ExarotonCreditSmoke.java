package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.time.*;

public final class ExarotonCreditSmoke {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("aeromc-credit-", ".csv");
        try {
            ExarotonCreditTracker tracker = new ExarotonCreditTracker(file); Instant now = Instant.now();
            tracker.recordAt(now.minus(Duration.ofMinutes(3)), 10.0); tracker.recordAt(now, 9.85);
            ExarotonCreditTracker.Stats stats = tracker.stats();
            require(Math.abs(stats.current() - 9.85) < .001, "current credits");
            require(stats.perHour() > 2.9 && stats.perHour() < 3.1, "short-window hourly rate");
            require(stats.remainingHours() > 3.2 && stats.remainingHours() < 3.4, "remaining hours");
            require(stats.observedMinutes() == 3, "observation duration");
            require(ExarotonCreditTracker.officialServerRate(true, 5) == 5.0, "official five GiB rate");
            require(ExarotonCreditTracker.officialServerRate(false, 5) == 0.0, "offline server rate");
            require(ExarotonCreditTracker.shouldStopAtThreshold(true, true, true, 4.9, 5.0), "threshold auto stop");
            require(!ExarotonCreditTracker.shouldStopAtThreshold(false, true, true, 4.9, 5.0), "disabled threshold");
            require(!ExarotonCreditTracker.shouldStopAtThreshold(true, true, false, 4.9, 5.0), "offline server is not stopped again");
            require(tracker.recent(10).size() == 2, "credit history persistence");
            System.out.println("exaroton-credit-ok");
        } finally { Files.deleteIfExists(file); }
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
