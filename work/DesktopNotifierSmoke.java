package com.aerogroup.mcpanel;

public final class DesktopNotifierSmoke {
    public static void main(String[] args) {
        require("notify-send".equals(DesktopNotifier.backendFor("Linux")), "Linux native backend");
        require("system-tray".equals(DesktopNotifier.backendFor("Windows 11")), "Windows tray backend");
        var command = DesktopNotifier.linuxCommand("Sunucu hazır", "Oyuncular bağlanabilir");
        require(command.size() == 5 && command.get(0).equals("notify-send"), "argument-safe Linux command");
        require(command.get(3).equals("AeroMC • Sunucu hazır") && command.get(4).equals("Oyuncular bağlanabilir"), "title and body are separate arguments");
        System.out.println("desktop-notifier-platform-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
