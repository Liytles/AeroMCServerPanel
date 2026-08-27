package com.aerogroup.mcpanel;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Discord olay filtrelerini doğrular ve güvenli webhook embed yükleri üretir. */
public final class DiscordNotificationEngine {
    public enum Type { STATUS, CRASH, PLAYER, PERFORMANCE, MAINTENANCE, AUTOMATION, BACKUP, TEST }
    public record Settings(boolean enabled, Set<Type> types, String username, boolean mentionCritical, String roleId) { }
    public record Event(Type type, String title, String description, String provider, String server, boolean critical) { }

    private DiscordNotificationEngine() { }

    public static boolean shouldSend(Settings settings, Event event) { return event.type() == Type.TEST || settings.enabled() && settings.types().contains(event.type()); }
    public static URI validateWebhook(String value) {
        try {
            URI uri = URI.create(Objects.toString(value, "").trim()); String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT), path = Objects.toString(uri.getPath(), "");
            boolean discordHost = host.equals("discord.com") || host.equals("www.discord.com") || host.equals("canary.discord.com") || host.equals("ptb.discord.com") || host.equals("discordapp.com") || host.equals("www.discordapp.com");
            boolean cleanAuthority = uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null;
            boolean webhookPath = path.matches("/api(?:/v\\d+)?/webhooks/\\d{17,20}/[A-Za-z0-9._-]{20,200}");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !discordHost || !cleanAuthority || !webhookPath) throw new IllegalArgumentException("Geçerli bir Discord webhook URL'si gir.");
            return uri;
        } catch (IllegalArgumentException error) { throw new IllegalArgumentException("Geçerli bir Discord webhook URL'si gir."); }
    }
    public static String payload(Settings settings, Event event) {
        String username = clean(settings.username(), 80, "AeroMC"), title = clean(event.title(), 256, "AeroMC bildirimi"), description = clean(event.description(), 3900, "-");
        boolean mention = event.critical() && settings.mentionCritical() && validRole(settings.roleId()); String role = mention ? settings.roleId().trim() : "";
        StringBuilder json = new StringBuilder("{");
        json.append("\"username\":\"").append(escape(username)).append("\",");
        if (mention) json.append("\"content\":\"<@&").append(role).append(">\",");
        json.append("\"allowed_mentions\":").append(mention ? "{\"parse\":[],\"roles\":[\"" + role + "\"]}" : "{\"parse\":[]}").append(',');
        json.append("\"embeds\":[{");
        json.append("\"title\":\"").append(escape(title)).append("\",\"description\":\"").append(escape(description)).append("\",");
        json.append("\"color\":").append(color(event.type(), event.critical())).append(',');
        json.append("\"fields\":[{\"name\":\"Sağlayıcı\",\"value\":\"").append(escape(clean(event.provider(), 100, "-"))).append("\",\"inline\":true},{\"name\":\"Sunucu\",\"value\":\"").append(escape(clean(event.server(), 100, "-"))).append("\",\"inline\":true}],");
        json.append("\"footer\":{\"text\":\"AeroMC Server Panel\"},\"timestamp\":\"").append(Instant.now()).append("\"}]}" );
        return json.toString();
    }
    public static boolean validRole(String value) { return value != null && value.trim().matches("\\d{17,20}"); }
    private static int color(Type type, boolean critical) { if (critical || type == Type.CRASH) return 0xE05260; return switch (type) { case STATUS -> 0x43B581; case PLAYER -> 0x4AA8E8; case PERFORMANCE -> 0xF3A93B; case MAINTENANCE, AUTOMATION -> 0x9B72CF; case BACKUP -> 0x55C2A3; case TEST -> 0x2F9ED1; default -> 0x2F9ED1; }; }
    private static String clean(String value, int max, String fallback) { String text = Objects.toString(value, "").strip().replace("```", "''' "); if (text.isEmpty()) text = fallback; return text.length() <= max ? text : text.substring(0, max - 1) + "…"; }
    private static String escape(String value) { StringBuilder out = new StringBuilder(); for (char c : value.toCharArray()) switch (c) { case '\\' -> out.append("\\\\"); case '"' -> out.append("\\\""); case '\n' -> out.append("\\n"); case '\r' -> { } case '\t' -> out.append("\\t"); default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); } } return out.toString(); }
}
