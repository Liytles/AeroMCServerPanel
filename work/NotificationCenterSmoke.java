package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.time.Instant;

public final class NotificationCenterSmoke {
    public static void main(String[] args) throws Exception {
        var original = new NotificationCenter.Entry("event-1", Instant.parse("2026-08-24T10:00:00Z"), NotificationCenter.Severity.WARNING, "Exaroton", "Düşük kredi", "1.2 kredi kaldı", false);
        var decoded = NotificationCenter.decode(NotificationCenter.encode(original)).orElseThrow();
        require(decoded.equals(original), "notification round-trip");
        require(NotificationCenter.inferSeverity("Sunucu çöktü", "Başlatılamadı") == NotificationCenter.Severity.CRITICAL, "critical inference");
        require(NotificationCenter.inferSeverity("Yedek hazır", "İşlem tamamlandı") == NotificationCenter.Severity.SUCCESS, "success inference");
        String hidden = NotificationCenter.safeText("https://discord.com/api/webhooks/123/secret-token", 320);
        require(hidden.equals("[gizli webhook]"), "webhook redaction");
        require(NotificationCenter.safeText("api_key=super-secret-value", 320).equals("api_key=[gizli]"), "named secret redaction");
        Path file = Files.createTempFile("aeromc-notifications", ".log"); Files.writeString(file, NotificationCenter.encode(original) + System.lineSeparator() + "bozuk");
        require(NotificationCenter.load(file).size() == 1, "malformed row ignored");
        Path preferencesTest = Files.createTempDirectory("aeromc-notification-settings").resolve("notifications.log"); NotificationCenter center = new NotificationCenter(preferencesTest);
        String local = NotificationCenter.serverSource("Yerel JAR", ""), remote = NotificationCenter.serverSource("Exaroton", "SMP"); center.registerServerSource(remote);
        require(center.serverSources().contains(local) && center.serverSources().contains(remote), "server source registration");
        center.setServerSourceEnabled(remote, false); require(!center.accepts(remote) && center.accepts(local), "per-server notification filter");
        center.setServerSourceEnabled(remote, true); center.setEventEnabled(remote, NotificationCenter.EventType.PLAYER, false);
        require(!center.accepts(remote, "Oyuncu katıldı", "Hasan sunucuya katıldı") && center.accepts(remote, "Sunucu online", "Hazır"), "per-event server rule");
        require(NotificationCenter.inferEventType("Sunucu çöktü", "crash-report") == NotificationCenter.EventType.CRASH, "crash event classification");
        require(NotificationCenter.isQuietTime(java.time.LocalTime.of(23, 30), true, java.time.LocalTime.of(23, 0), java.time.LocalTime.of(8, 0)), "overnight quiet hours");
        require(!NotificationCenter.isQuietTime(java.time.LocalTime.of(12, 0), true, java.time.LocalTime.of(23, 0), java.time.LocalTime.of(8, 0)), "daytime outside quiet hours");
        center.configureDelivery(false, java.time.LocalTime.of(23, 0), java.time.LocalTime.of(8, 0), 60); java.time.Instant now = java.time.Instant.parse("2026-08-24T12:00:00Z");
        require(center.allowDesktopNotification(remote, "Sunucu online", "Hazır", now), "first desktop notification allowed");
        require(!center.allowDesktopNotification(remote, "Sunucu online", "Hazır", now.plusSeconds(30)), "duplicate desktop notification cooled down");
        require(center.allowDesktopNotification(remote, "Sunucu online", "Hazır", now.plusSeconds(61)), "desktop notification allowed after cooldown");
        center.setCollapsed(true); center.setEnabled(false); NotificationCenter reloaded = new NotificationCenter(preferencesTest);
        require(!reloaded.isEnabled() && reloaded.isCollapsed() && !reloaded.isEventEnabled(remote, NotificationCenter.EventType.PLAYER), "persistent notification preferences");
        System.out.println("notification-center-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
