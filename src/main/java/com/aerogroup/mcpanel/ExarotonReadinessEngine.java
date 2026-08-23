package com.aerogroup.mcpanel;

import java.util.*;

/** Exaroton sunucusunun başlatılabilirlik ve operasyon durumunu sade bir rapora dönüştürür. */
public final class ExarotonReadinessEngine {
    public enum Severity { CRITICAL, WARNING, OK }
    public record Check(Severity severity, String title, String detail) {
        @Override public String toString() {
            String icon = severity == Severity.CRITICAL ? "⛔" : severity == Severity.WARNING ? "⚠" : "✓";
            return icon + "  " + title + " — " + detail;
        }
    }
    public record Report(List<Check> checks) {
        public boolean hasCritical() { return checks.stream().anyMatch(check -> check.severity == Severity.CRITICAL); }
        public boolean hasWarnings() { return checks.stream().anyMatch(check -> check.severity == Severity.WARNING); }
        public long criticalCount() { return checks.stream().filter(check -> check.severity == Severity.CRITICAL).count(); }
        public long warningCount() { return checks.stream().filter(check -> check.severity == Severity.WARNING).count(); }
    }

    private ExarotonReadinessEngine() { }

    public static Report inspect(ExarotonPane.ProSnapshot snapshot, double credits) {
        List<Check> checks = new ArrayList<>();
        if (snapshot == null) return new Report(List.of(new Check(Severity.CRITICAL, "Sunucu", "Exaroton sunucusu seçilmedi veya durumu alınamadı.")));
        checks.add(new Check(Severity.OK, "Sunucu", snapshot.name() + " • " + snapshot.status()));
        if (snapshot.address() == null || snapshot.address().isBlank()) checks.add(new Check(Severity.CRITICAL, "Sunucu adresi", "Exaroton geçerli bir adres döndürmedi."));
        else checks.add(new Check(Severity.OK, "Sunucu adresi", snapshot.address()));
        if (snapshot.softwareName() == null || snapshot.softwareName().isBlank()) checks.add(new Check(Severity.WARNING, "Yazılım", "Sunucu yazılımı veya kurulumu henüz belirlenemedi."));
        else checks.add(new Check(Severity.OK, "Yazılım", snapshot.softwareName() + (snapshot.softwareVersion().isBlank() ? "" : " " + snapshot.softwareVersion())));
        if (snapshot.ramGiB() <= 0) checks.add(new Check(Severity.WARNING, "RAM", "Ayrılan RAM bilgisi alınamadı."));
        else checks.add(new Check(Severity.OK, "RAM", snapshot.ramGiB() + " GiB ayrılmış"));
        if (Double.isFinite(credits)) {
            if (credits <= 0) checks.add(new Check(Severity.WARNING, "Hesap kredisi", "Kredi 0 görünüyor; başlatma Exaroton tarafından reddedilebilir."));
            else if (credits < 1) checks.add(new Check(Severity.WARNING, "Hesap kredisi", String.format(Locale.US, "Yalnızca %.2f kredi kaldı.", credits)));
            else checks.add(new Check(Severity.OK, "Hesap kredisi", String.format(Locale.US, "%.2f kredi", credits)));
        } else checks.add(new Check(Severity.WARNING, "Hesap kredisi", "Kredi bilgisi henüz alınamadı."));
        if (snapshot.status().toLowerCase(Locale.ROOT).contains("crash")) checks.add(new Check(Severity.WARNING, "Son durum", "Sunucu çökmüş görünüyor; başlatınca konsolu ve Çökme Doktoru'nu izle."));
        if (snapshot.players() > 0) checks.add(new Check(Severity.OK, "Oyuncular", snapshot.players() + " / " + snapshot.maxPlayers() + " oyuncu çevrimiçi"));
        return new Report(List.copyOf(checks));
    }
}
