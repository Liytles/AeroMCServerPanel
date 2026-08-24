package com.aerogroup.mcpanel;

import java.time.*;
import java.util.ArrayDeque;
import java.util.Deque;

/** Kısa sürede tekrarlanan çökmelerde otomatik yeniden başlatma döngüsünü keser. */
final class CrashLoopGuard {
    record Decision(boolean restartAllowed, int recentCrashes, Instant lockedUntil, String message) { }
    private final int limit;
    private final Duration window, lockDuration;
    private final Deque<Instant> crashes = new ArrayDeque<>();
    private Instant lockedUntil = Instant.EPOCH;

    CrashLoopGuard() { this(3, Duration.ofMinutes(5), Duration.ofMinutes(15)); }
    CrashLoopGuard(int limit, Duration window, Duration lockDuration) { this.limit = limit; this.window = window; this.lockDuration = lockDuration; }

    synchronized Decision record(Instant now) {
        while (!crashes.isEmpty() && crashes.peekFirst().isBefore(now.minus(window))) crashes.removeFirst();
        crashes.addLast(now);
        if (!now.isBefore(lockedUntil) && crashes.size() >= limit) lockedUntil = now.plus(lockDuration);
        boolean allowed = now.isAfter(lockedUntil) || now.equals(lockedUntil);
        String message = allowed ? "Otomatik yeniden başlatma güvenli" : "Çökme döngüsü algılandı; otomatik yeniden başlatma " + Math.max(1, Duration.between(now, lockedUntil).toMinutes()) + " dakika kilitlendi";
        return new Decision(allowed, crashes.size(), lockedUntil, message);
    }

    synchronized boolean isLocked(Instant now) { return now.isBefore(lockedUntil); }
}
