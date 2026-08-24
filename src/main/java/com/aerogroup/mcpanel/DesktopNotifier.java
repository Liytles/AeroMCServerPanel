package com.aerogroup.mcpanel;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Platforma uygun bildirim yolunu seçer; Linux'ta JavaFX ile AWT/GDK karıştırılmaz. */
public final class DesktopNotifier {
    private DesktopNotifier() { }

    public static void show(String title, String message) { show("Sistem", title, message); }
    public static void show(String source, String title, String message) {
        NotificationCenter center = NotificationCenter.shared(); center.registerServerSource(source); if (!center.accepts(source, title, message)) return;
        center.publish(source, title, message); if (!center.allowDesktopNotification(source, title, message, Instant.now())) return; deliver(title, message);
    }
    public static void showTest(String title, String message) { NotificationCenter.shared().publish(NotificationCenter.Severity.INFO, "Sistem", title, message); deliver(title, message); }
    private static void deliver(String title, String message) {
        String safeTitle = clean(title, 100), safeMessage = clean(message, 360);
        if ("notify-send".equals(backendFor(System.getProperty("os.name")))) showLinux(safeTitle, safeMessage);
        else AwtDesktopNotifier.show(safeTitle, safeMessage);
    }

    static String backendFor(String osName) { return Objects.toString(osName, "").toLowerCase(Locale.ROOT).contains("linux") ? "notify-send" : "system-tray"; }
    static List<String> linuxCommand(String title, String message) {
        return List.of("notify-send", "--app-name=AeroMC Server Panel", "--expire-time=5000", "AeroMC • " + clean(title, 100), clean(message, 360));
    }

    private static void showLinux(String title, String message) {
        try { new ProcessBuilder(linuxCommand(title, message)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException ignored) { /* notify-send yoksa uygulama içi Bildirim Merkezi çalışmaya devam eder. */ }
    }
    private static String clean(String value, int limit) {
        String result = Objects.toString(value, "").replace('\n', ' ').replace('\r', ' ').trim();
        return result.length() <= limit ? result : result.substring(0, Math.max(0, limit - 1)) + "…";
    }
}
