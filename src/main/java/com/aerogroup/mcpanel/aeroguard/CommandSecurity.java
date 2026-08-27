package com.aerogroup.mcpanel.aeroguard;

import java.util.*;

/** AeroGuard Minecraft konsol komutlarını doğrular ve kullanıcı onayı gerektiren risk seviyesine ayırır. */
public final class CommandSecurity {
    public enum Risk { SAFE, SENSITIVE, CRITICAL }
    public record Assessment(String command, String root, Risk risk, String reason) { public boolean needsConfirmation() { return risk != Risk.SAFE; } }

    private static final Set<String> CRITICAL = Set.of("stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "reload", "save-off", "save-on", "fill", "clone", "setblock", "kill", "clear", "data", "execute", "function", "forceload", "schedule", "place", "setidletimeout", "defaultgamemode");
    private static final Set<String> SENSITIVE = Set.of("kick", "whitelist", "gamerule", "gamemode", "difficulty", "give", "tp", "teleport", "worldborder", "time", "weather", "save-all", "spreadplayers", "team", "attribute", "item", "loot", "ride");
    private static final Set<String> REMOTE_READ_ONLY = Set.of("list", "tps", "mspt", "version");

    private CommandSecurity() { }

    public static Assessment assess(String value) {
        String command = Objects.toString(value, "").strip();
        if (command.isEmpty() || command.length() > 300 || command.indexOf('\0') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0 || containsFormatControl(command)) throw new IllegalArgumentException("Komut boş, çok uzun veya görünmez kontrol karakteri içeriyor.");
        if (command.startsWith("/")) command = command.substring(1).strip();
        if (command.isEmpty()) throw new IllegalArgumentException("Komut boş olamaz.");
        String token = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!token.matches("[a-z0-9_:.+-]{1,64}")) throw new IllegalArgumentException("Komut adı geçersiz karakter içeriyor.");
        String root = token.contains(":") ? token.substring(token.lastIndexOf(':') + 1) : token;
        if (CRITICAL.contains(root) || root.equals("whitelist") && command.toLowerCase(Locale.ROOT).matches("(?:[a-z0-9_.+-]+:)?whitelist\\s+off(?:\\s.*)?")) return new Assessment(command, root, Risk.CRITICAL, "Sunucunun çalışmasını, yetkileri veya dünya verisini kalıcı biçimde değiştirebilir.");
        if (SENSITIVE.contains(root)) return new Assessment(command, root, Risk.SENSITIVE, "Oyuncuları veya sunucu ayarlarını etkileyebilir.");
        return new Assessment(command, root, Risk.SAFE, "Salt okunur veya düşük riskli komut.");
    }

    public static String requireRemoteGeneric(String value) {
        Assessment assessment = assess(value);
        if (assessment.risk() != Risk.SAFE || !REMOTE_READ_ONLY.contains(assessment.root())) throw new SecurityException("Uzaktan genel komut alanı yalnızca onaylı salt okunur komutları kabul eder.");
        return assessment.command();
    }

    public static String playerName(String value) {
        String clean = Objects.toString(value, "").strip();
        if (!clean.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Oyuncu adı yalnızca harf, rakam ve alt çizgi içerebilir.");
        return clean;
    }

    public static String singleLine(String value, int max) {
        String clean = Objects.toString(value, "").replace('\r', ' ').replace('\n', ' ').strip();
        if (clean.length() > max || containsFormatControl(clean)) throw new IllegalArgumentException("Metin çok uzun veya görünmez kontrol karakteri içeriyor.");
        return clean;
    }

    private static boolean containsFormatControl(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.FORMAT || codePoint < 0x20 && codePoint != '\t');
    }
}
