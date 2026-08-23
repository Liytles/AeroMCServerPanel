package com.aerogroup.mcpanel;

import java.util.*;

/** Birden fazla Exaroton sunucusunu tek operasyon özeti altında toplar. */
public final class ExarotonFleetEngine {
    public record ServerState(String name, String status, boolean online, boolean crashed, int players, int maxPlayers, int ramGiB) { }
    public record Summary(int totalServers, int onlineServers, int crashedServers, int totalPlayers, int activeRamGiB, double creditsPerHour) { }
    private ExarotonFleetEngine() { }
    public static Summary summarize(List<ServerState> servers) {
        int online = 0, crashed = 0, players = 0, ram = 0;
        for (ServerState server : servers) {
            if (server.online) { online++; players += Math.max(0, server.players); ram += Math.max(0, server.ramGiB); }
            if (server.crashed) crashed++;
        }
        return new Summary(servers.size(), online, crashed, players, ram, ram);
    }
}
