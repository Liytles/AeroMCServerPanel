package com.aerogroup.mcpanel;

public final class AternosStatusSmoke {
    public static void main(String[] args) {
        require("play.example.net".equals(MinecraftPing.normalizeInput("https://play.example.net/")), "URL normalization");
        require("server.aternos.me:25570".equals(MinecraftPing.normalizeInput(" server.aternos.me:25570 ")), "address and port normalization");
        require("2001:db8::1".equals(MinecraftPing.normalizeInput("[2001:db8::1]")), "IPv6 bracket normalization");
        System.out.println("aternos-status-ok");
    }

    private static void require(boolean value, String name) {
        if (!value) throw new IllegalStateException("Smoke test failed: " + name);
    }
}
