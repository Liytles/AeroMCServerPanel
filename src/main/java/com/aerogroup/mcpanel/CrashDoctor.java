package com.aerogroup.mcpanel;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Çökmeden önceki konsol bağlamından olası kök nedeni ve güvenli eylemleri çıkarır. */
public final class CrashDoctor {
    private static final Pattern JAR = Pattern.compile("(?i)(?:plugins|mods)[/\\\\]([^/\\\\'\\\" ]+\\.jar)");
    private static final Pattern CAUSED_BY = Pattern.compile("(?i)caused by:\\s*([^:]+(?::[^:]+)?)");
    private CrashDoctor() { }

    public static Diagnosis diagnose(List<String> consoleLines) {
        List<String> lines = consoleLines == null ? List.of() : consoleLines;
        String joined = String.join("\n", lines).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> jars = new LinkedHashSet<>();
        String lastCause = "";
        for (String line : lines) {
            Matcher jar = JAR.matcher(line); while (jar.find()) jars.add(jar.group(1));
            Matcher cause = CAUSED_BY.matcher(line); if (cause.find()) lastCause = cause.group(1).strip();
        }

        if (containsAny(joined, "outofmemoryerror", "java heap space", "gc overhead limit"))
            return result("Bellek tükenmesi", "RAM / ağır mod veya eklenti", 96, lastCause,
                    "Sunucu RAM sınırını yükselt.", "Son eklenen ağır modları ve eklentileri geçici olarak kaldır.", "Görüş ve simülasyon mesafesini azalt.");
        if (containsAny(joined, "failed to bind to port", "address already in use"))
            return result("Port çakışması", "server-port veya başka bir sunucu işlemi", 98, lastCause,
                    "Aynı portu kullanan diğer sunucuyu kapat.", "server.properties içindeki server-port değerini değiştir.");
        if (containsAny(joined, "modresolutionexception", "incompatible mod set", "requires version", "requires any version"))
            return result("Mod bağımlılığı uyuşmazlığı", jars.isEmpty() ? "Fabric mod paketi" : String.join(", ", jars), 91, lastCause,
                    "Hata satırında istenen mod ve sürümü kur.", "Sunucu ile oyuncuların mod sürümlerini eşitle.", "Son eklenen modu test sunucusunda devre dışı bırak.");
        if (containsAny(joined, "could not load 'plugins", "invalidpluginexception", "unknown dependency"))
            return result("Eklenti yükleme hatası", jars.isEmpty() ? "Bilinmeyen eklenti" : String.join(", ", jars), 88, lastCause,
                    "Eklentinin Minecraft/Paper sürümüyle uyumunu kontrol et.", "Eksik bağımlılık eklentisini kur.", "Şüpheli JAR dosyasını geçici olarak devre dışı bırak.");
        if (containsAny(joined, "watchdog", "a single server tick took", "can't keep up", "server is overloaded"))
            return result("Sunucu tick kilitlenmesi", jars.isEmpty() ? "Dünya üretimi veya ağır görev" : String.join(", ", jars), 82, lastCause,
                    "Kriz Modu'nu etkinleştir.", "Görüş ve simülasyon mesafesini azalt.", "Zamanlanmış yedekleri ve ağır dünya görevlerini ertele.");
        if (containsAny(joined, "unsupportedclassversionerror", "class file version"))
            return result("Yanlış Java sürümü", "Java çalışma ortamı", 97, lastCause,
                    "Sunucu sürümünün istediği Java sürümünü kur.", "Paneli doğru JAVA_HOME ile yeniden çalıştır.");

        String culprit = !jars.isEmpty() ? String.join(", ", jars) : !lastCause.isBlank() ? lastCause : "Kesin şüpheli belirlenemedi";
        return result("Genel Java çökmesi", culprit, jars.isEmpty() && lastCause.isBlank() ? 42 : 68, lastCause,
                "crash-reports klasöründeki en yeni raporu kontrol et.", "Son eklenen mod/eklentiyi geçici olarak kaldır.", "Değişiklikten önce dünya yedeği al.");
    }

    private static boolean containsAny(String value, String... needles) { for (String needle : needles) if (value.contains(needle)) return true; return false; }
    private static Diagnosis result(String title, String culprit, int confidence, String evidence, String... actions) {
        return new Diagnosis(title, culprit, confidence, evidence == null || evidence.isBlank() ? "Konsol kalıbı eşleşmesi" : evidence, List.of(actions));
    }

    public record Diagnosis(String title, String culprit, int confidence, String evidence, List<String> actions) {
        public String summary() { return title + " • %" + confidence + " güven • Şüpheli: " + culprit; }
    }
}
