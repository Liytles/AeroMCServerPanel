package com.aerogroup.mcpanel;

import java.util.*;

/** Minecraft konsol komutlarını doğrular ve kullanıcı onayı gerektiren risk seviyesine ayırır. */
final class CommandSecurity {
    enum Risk { SAFE, SENSITIVE, CRITICAL }
    record Assessment(String command, String root, Risk risk, String reason) { boolean needsConfirmation() { return risk != Risk.SAFE; } }

    private static final Set<String> CRITICAL = Set.of("stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "reload", "save-off", "fill", "clone", "setblock", "kill", "clear", "data", "execute", "function");
    private static final Set<String> SENSITIVE = Set.of("kick", "whitelist", "gamerule", "gamemode", "difficulty", "give", "tp", "teleport", "worldborder", "time", "weather", "save-all");

    private CommandSecurity() { }

    static Assessment assess(String value) {
        String command = Objects.toString(value, "").strip();
        if (command.isEmpty() || command.length() > 300 || command.indexOf('\0') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0) throw new IllegalArgumentException("Komut boş, çok uzun veya satır sonu içeriyor.");
        if (command.startsWith("/")) command = command.substring(1).strip();
        if (command.isEmpty()) throw new IllegalArgumentException("Komut boş olamaz.");
        String token = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!token.matches("[a-z0-9_:.+-]{1,64}")) throw new IllegalArgumentException("Komut adı geçersiz karakter içeriyor.");
        String root = token.contains(":") ? token.substring(token.lastIndexOf(':') + 1) : token;
        if (CRITICAL.contains(root) || root.equals("whitelist") && command.toLowerCase(Locale.ROOT).matches("(?:[a-z0-9_.+-]+:)?whitelist\\s+off(?:\\s.*)?")) return new Assessment(command, root, Risk.CRITICAL, "Sunucunun çalışmasını, yetkileri veya dünya verisini kalıcı biçimde değiştirebilir.");
        if (SENSITIVE.contains(root)) return new Assessment(command, root, Risk.SENSITIVE, "Oyuncuları veya sunucu ayarlarını etkileyebilir.");
        return new Assessment(command, root, Risk.SAFE, "Salt okunur veya düşük riskli komut.");
    }

    static String requireRemoteGeneric(String value) {
        Assessment assessment = assess(value);
        if (assessment.risk() != Risk.SAFE) throw new SecurityException("Riskli komutlar uzaktan genel komut alanından çalıştırılamaz. AeroMC içindeki özel ve günlüklenen işlemi kullan.");
        return assessment.command();
    }

    static String playerName(String value) {
        String clean = Objects.toString(value, "").strip();
        if (!clean.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Oyuncu adı yalnızca harf, rakam ve alt çizgi içerebilir.");
        return clean;
    }

    static String singleLine(String value, int max) {
        String clean = Objects.toString(value, "").replace('\r', ' ').replace('\n', ' ').strip();
        if (clean.length() > max) throw new IllegalArgumentException("Metin en fazla " + max + " karakter olabilir.");
        return clean;
    }
}
