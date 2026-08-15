package com.aerogroup.mcpanel;

import java.util.ArrayList;
import java.util.List;

/** Canlı ölçümleri anlaşılır, test edilebilir bir 0-100 sağlık puanına dönüştürür. */
public final class ServerHealthEngine {
    private ServerHealthEngine() { }

    public static Snapshot calculate(boolean online, double tps, double ramPercent, double cpuPercent,
                                     double latencyMs, int recentCrashes, int overloadWarnings) {
        if (!online) return new Snapshot(0, "Sunucu kapalı", "offline", List.of("Puanlama için sunucuyu başlat."));

        int score = 100;
        List<String> reasons = new ArrayList<>();
        if (Double.isFinite(tps)) {
            if (tps < 14) { score -= 42; reasons.add("TPS kritik seviyede: " + one(tps)); }
            else if (tps < 16) { score -= 28; reasons.add("TPS çok düşük: " + one(tps)); }
            else if (tps < 18) { score -= 16; reasons.add("TPS düşüyor: " + one(tps)); }
            else if (tps < 19.5) { score -= 6; reasons.add("TPS idealin biraz altında: " + one(tps)); }
        }
        if (Double.isFinite(ramPercent)) {
            if (ramPercent >= 95) { score -= 30; reasons.add("RAM kullanımı kritik: %" + whole(ramPercent)); }
            else if (ramPercent >= 88) { score -= 20; reasons.add("RAM kullanımı çok yüksek: %" + whole(ramPercent)); }
            else if (ramPercent >= 78) { score -= 10; reasons.add("RAM kullanımı yüksek: %" + whole(ramPercent)); }
        }
        if (Double.isFinite(cpuPercent)) {
            if (cpuPercent >= 95) { score -= 18; reasons.add("CPU uzun süre doygun olabilir: %" + whole(cpuPercent)); }
            else if (cpuPercent >= 82) { score -= 9; reasons.add("CPU kullanımı yüksek: %" + whole(cpuPercent)); }
        }
        if (Double.isFinite(latencyMs)) {
            if (latencyMs >= 350) { score -= 18; reasons.add("Gecikme kritik: " + whole(latencyMs) + " ms"); }
            else if (latencyMs >= 180) { score -= 9; reasons.add("Gecikme yüksek: " + whole(latencyMs) + " ms"); }
        }
        if (recentCrashes > 0) { int penalty = Math.min(30, recentCrashes * 12); score -= penalty; reasons.add("Bu oturumdaki çökme sayısı: " + recentCrashes); }
        if (overloadWarnings > 0) { int penalty = Math.min(20, overloadWarnings * 4); score -= penalty; reasons.add("Yakalanan aşırı yük uyarısı: " + overloadWarnings); }
        score = Math.max(0, Math.min(100, score));
        if (reasons.isEmpty()) reasons.add("TPS, bellek ve işlemci değerleri sağlıklı görünüyor.");

        String state = score >= 90 ? "Mükemmel" : score >= 75 ? "Sağlıklı" : score >= 55 ? "Dikkat" : score >= 35 ? "Riskli" : "Kritik";
        String tone = score >= 75 ? "good" : score >= 55 ? "warning" : "critical";
        return new Snapshot(score, state, tone, List.copyOf(reasons));
    }

    public static boolean shouldEnterCrisis(double tps, double ramPercent, int overloadBurst,
                                            double tpsThreshold, double ramThreshold) {
        return Double.isFinite(tps) && tps < tpsThreshold
                || Double.isFinite(ramPercent) && ramPercent >= ramThreshold
                || overloadBurst >= 3;
    }

    private static String one(double value) { return String.format(java.util.Locale.US, "%.1f", value); }
    private static String whole(double value) { return Long.toString(Math.round(value)); }

    public record Snapshot(int score, String state, String tone, List<String> reasons) { }
}
