package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Spark raporlarının AeroMC tarafından ölçülen TPS özetini kalıcı tutar. */
public final class SparkReportHistory {
    private static final int LIMIT = 20;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    public SparkReportHistory(Path file) { this.file = file; load(); }

    public synchronized Entry add(Instant time, String source, String url, double tps) {
        Entry entry = new Entry(time, clean(source), clean(url), Double.isFinite(tps) ? Math.max(0, Math.min(20, tps)) : Double.NaN);
        entries.add(0, entry); while (entries.size() > LIMIT) entries.remove(entries.size() - 1); save(); return entry;
    }

    public synchronized Optional<Comparison> comparison(String source) {
        List<Entry> matching = entries.stream().filter(entry -> entry.source().equals(clean(source)) && Double.isFinite(entry.tps())).limit(2).toList();
        if (matching.size() < 2) return Optional.empty();
        double delta = matching.get(0).tps() - matching.get(1).tps(); return Optional.of(new Comparison(matching.get(0).tps(), matching.get(1).tps(), delta));
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }

    public static String comparisonText(Optional<Comparison> comparison, boolean currentMeasured) {
        boolean english = LanguageManager.isEnglish();
        if (comparison.isPresent()) { Comparison value = comparison.get(); String direction = value.delta() > .15 ? (english ? "Improvement" : "İyileşme") : value.delta() < -.15 ? (english ? "Regression" : "Kötüleşme") : (english ? "Stable" : "Kararlı"); return String.format(Locale.US, english ? "TPS %+.2f since previous report • %.2f → %.2f • %s" : "Önceki rapora göre TPS %+.2f • %.2f → %.2f • %s", value.delta(), value.previous(), value.current(), direction); }
        return currentMeasured ? (english ? "First measured Spark report saved • TPS difference will appear after the next report" : "İlk ölçümlü Spark raporu kaydedildi • Sonraki raporda TPS farkı gösterilecek") : (english ? "Report saved • Spark health measurement was unavailable for TPS comparison" : "Rapor kaydedildi • TPS karşılaştırması için Spark sağlık ölçümü alınamadı");
    }

    private void load() {
        if (!Files.isRegularFile(file)) return; Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); int count = Math.min(LIMIT, Integer.parseInt(values.getProperty("count", "0"))); for (int i = 0; i < count; i++) { String key = "entry." + i + "."; entries.add(new Entry(Instant.parse(values.getProperty(key + "time")), values.getProperty(key + "source", "Bilinmeyen sunucu"), values.getProperty(key + "url", ""), Double.parseDouble(values.getProperty(key + "tps", "NaN")))); } } catch (Exception ignored) { entries.clear(); }
    }

    private void save() {
        try { Files.createDirectories(file.getParent()); Properties values = new Properties(); values.setProperty("count", Integer.toString(entries.size())); for (int i = 0; i < entries.size(); i++) { Entry entry = entries.get(i); String key = "entry." + i + "."; values.setProperty(key + "time", entry.time().toString()); values.setProperty(key + "source", entry.source()); values.setProperty(key + "url", entry.url()); values.setProperty(key + "tps", Double.toString(entry.tps())); } Path temporary = Files.createTempFile(file.getParent(), ".spark-history-", ".tmp"); try { try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "AeroMC Spark report history"); } try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } } finally { Files.deleteIfExists(temporary); } } catch (IOException ignored) { }
    }

    private static String clean(String value) { return Objects.toString(value, "").replace('\n', ' ').replace('\r', ' ').strip(); }
    public record Entry(Instant time, String source, String url, double tps) { }
    public record Comparison(double current, double previous, double delta) { }
}
