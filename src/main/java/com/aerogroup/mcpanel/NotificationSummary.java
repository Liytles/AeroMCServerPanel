package com.aerogroup.mcpanel;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Bildirim geçmişini kısa, okunabilir bir dönem özetine dönüştürür. */
final class NotificationSummary {
    record Digest(int total, int crashes, int playerJoins, int offline, int performance, String text) { }

    private NotificationSummary() { }

    static Digest since(List<NotificationCenter.Entry> entries, Instant since) {
        int total = 0, crashes = 0, joins = 0, offline = 0, performance = 0;
        for (NotificationCenter.Entry entry : entries) {
            if (entry.time().isBefore(since)) continue;
            total++;
            NotificationCenter.EventType type = NotificationCenter.inferEventType(entry.title(), entry.message());
            if (type == NotificationCenter.EventType.CRASH) crashes++;
            else if (type == NotificationCenter.EventType.OFFLINE) offline++;
            else if (type == NotificationCenter.EventType.PERFORMANCE) performance++;
            if (type == NotificationCenter.EventType.PLAYER && contains(entry.message(), "katıldı", "joined", "yeni oyuncu")) joins++;
        }
        String text;
        if (LanguageManager.isEnglish()) text = total == 0 ? "No important events in the last hour" : "Last hour • " + total + " events • " + crashes + " crashes • " + joins + " player joins • " + offline + " shutdowns" + (performance > 0 ? " • " + performance + " performance warnings" : "");
        else text = total == 0 ? "Son 1 saatte önemli olay yok" : "Son 1 saat • " + total + " olay • " + crashes + " çökme • " + joins + " oyuncu girişi • " + offline + " kapanma" + (performance > 0 ? " • " + performance + " performans uyarısı" : "");
        return new Digest(total, crashes, joins, offline, performance, text);
    }

    private static boolean contains(String value, String... needles) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) if (lower.contains(needle)) return true;
        return false;
    }
}
