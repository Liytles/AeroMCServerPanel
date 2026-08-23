package com.aerogroup.mcpanel;

import java.util.List;

public final class ExarotonFleetSmoke {
    public static void main(String[] args) {
        var summary = ExarotonFleetEngine.summarize(List.of(
            new ExarotonFleetEngine.ServerState("SMP", "Online", true, false, 4, 20, 5),
            new ExarotonFleetEngine.ServerState("Lobby", "Online", true, false, 2, 50, 3),
            new ExarotonFleetEngine.ServerState("Modlu", "Crashed", false, true, 0, 10, 6)
        ));
        require(summary.totalServers() == 3, "total servers"); require(summary.onlineServers() == 2, "online servers"); require(summary.crashedServers() == 1, "crashed servers");
        require(summary.totalPlayers() == 6, "total players"); require(summary.activeRamGiB() == 8, "active RAM"); require(summary.creditsPerHour() == 8.0, "fleet hourly cost");
        System.out.println("exaroton-fleet-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
