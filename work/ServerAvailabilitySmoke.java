package com.aerogroup.mcpanel;

public final class ServerAvailabilitySmoke {
    public static void main(String[] args) {
        ServerAvailabilityTracker tracker = new ServerAvailabilityTracker();
        require(tracker.success() == ServerAvailabilityTracker.Change.FIRST_ONLINE, "initial online without notification");
        require(tracker.failure() == ServerAvailabilityTracker.Change.VERIFYING_OFFLINE, "single failure ignored");
        require(tracker.success() == ServerAvailabilityTracker.Change.NONE, "transient failure recovered without transition");
        require(tracker.failure() == ServerAvailabilityTracker.Change.VERIFYING_OFFLINE, "offline confirmation first sample");
        require(tracker.failure() == ServerAvailabilityTracker.Change.OFFLINE, "offline confirmation second sample");
        require(tracker.success() == ServerAvailabilityTracker.Change.VERIFYING_ONLINE, "online confirmation first sample");
        require(tracker.failure() == ServerAvailabilityTracker.Change.NONE, "false recovery cancelled");
        require(tracker.success() == ServerAvailabilityTracker.Change.VERIFYING_ONLINE, "online reconfirmation first sample");
        require(tracker.success() == ServerAvailabilityTracker.Change.ONLINE, "online reconfirmation second sample");
        System.out.println("server-availability-debounce-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
