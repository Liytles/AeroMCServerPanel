package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.time.Instant;

public final class SparkAnalysisSmoke {
    public static void main(String[] args) {
        require(SparkAnalysisEngine.durationSeconds("Hızlı • 60 saniye") == 60, "quick preset");
        require(SparkAnalysisEngine.durationSeconds("Normal • 3 dakika") == 180, "normal preset");
        require(SparkAnalysisEngine.profilerCommand(60).equals("spark profiler start --timeout 60"), "simple profiler command");
        require(SparkAnalysisEngine.trustedReportUrl("[spark] View: https://spark.lucko.me/AbC123.").orElseThrow().equals("https://spark.lucko.me/AbC123"), "trusted report captured");
        require(SparkAnalysisEngine.trustedReportUrl("spark https://evil.example/report").isEmpty(), "untrusted report rejected");
        require(SparkAnalysisEngine.commandRejected("Unknown or incomplete command"), "missing Spark detected");
        require(SparkAnalysisEngine.tpsHeader("[spark] TPS from last 5s, 10s, 1m, 5m, 15m:"), "TPS header detected");
        require(Math.abs(SparkAnalysisEngine.fiveSecondTps("[12:00:01 INFO]: [⚡]  18.75, 19.1, *20.0, *20.0, 19.9").orElseThrow() - 18.75) < .001, "5 second TPS parsed");
        try {
            Path file = Files.createTempDirectory("aeromc-spark-history-").resolve("history.properties"); SparkReportHistory history = new SparkReportHistory(file);
            history.add(Instant.parse("2026-08-25T10:00:00Z"), "Yerel", "https://spark.lucko.me/a", 17.5); history.add(Instant.parse("2026-08-25T11:00:00Z"), "Yerel", "https://spark.lucko.me/b", 19.0);
            require(Math.abs(history.comparison("Yerel").orElseThrow().delta() - 1.5) < .001, "report TPS comparison");
            require(new SparkReportHistory(file).entries().size() == 2, "report history persisted");
        } catch (Exception error) { throw new IllegalStateException(error); }
        System.out.println("spark-one-click-analysis-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
