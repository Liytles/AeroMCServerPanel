package com.aerogroup.mcpanel;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Tek tık Spark analizinin komut, süre ve güvenilir rapor bağlantısı kuralları. */
public final class SparkAnalysisEngine {
    private static final Pattern URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private SparkAnalysisEngine() { }

    public static int durationSeconds(String preset) {
        if (preset != null && preset.startsWith("Detaylı")) return 300;
        if (preset != null && preset.startsWith("Normal")) return 180;
        return 60;
    }

    public static String profilerCommand(int seconds) {
        return "spark profiler start --timeout " + Math.max(30, Math.min(900, seconds));
    }

    public static Optional<String> trustedReportUrl(String line) {
        Matcher matcher = URL.matcher(Objects.toString(line, ""));
        while (matcher.find()) {
            String value = matcher.group().replaceAll("[).,;]+$", "");
            try {
                URI uri = URI.create(value);
                if ("https".equalsIgnoreCase(uri.getScheme()) && "spark.lucko.me".equalsIgnoreCase(uri.getHost())) return Optional.of(uri.toString());
            } catch (IllegalArgumentException ignored) { }
        }
        return Optional.empty();
    }

    public static boolean commandRejected(String line) {
        String value = Objects.toString(line, "").toLowerCase(Locale.ROOT);
        return value.contains("unknown command") || value.contains("unknown or incomplete command") || value.contains("incorrect argument");
    }

    public static boolean tpsHeader(String line) {
        return Objects.toString(line, "").toLowerCase(Locale.ROOT).contains("tps from last 5s");
    }

    /** Spark'ın TPS başlığından sonraki satırındaki ilk (son 5 saniye) TPS değerini okur. */
    public static OptionalDouble fiveSecondTps(String line) {
        String value = Objects.toString(line, "").replace("*", "");
        Matcher matcher = Pattern.compile("(?<![\\d.])(\\d{1,2}(?:\\.\\d+)?)(?![\\d.])").matcher(value); List<Double> numbers = new ArrayList<>();
        while (matcher.find()) { double number = Double.parseDouble(matcher.group(1)); if (number >= 0 && number <= 20.1) numbers.add(number); }
        if (numbers.size() < 5) return OptionalDouble.empty();
        return OptionalDouble.of(numbers.get(numbers.size() - 5));
    }
}
