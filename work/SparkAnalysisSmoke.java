package com.aerogroup.mcpanel;

public final class SparkAnalysisSmoke {
    public static void main(String[] args) {
        require(SparkAnalysisEngine.durationSeconds("Hızlı • 60 saniye") == 60, "quick preset");
        require(SparkAnalysisEngine.durationSeconds("Normal • 3 dakika") == 180, "normal preset");
        require(SparkAnalysisEngine.profilerCommand(60).equals("spark profiler start --timeout 60"), "simple profiler command");
        require(SparkAnalysisEngine.trustedReportUrl("[spark] View: https://spark.lucko.me/AbC123.").orElseThrow().equals("https://spark.lucko.me/AbC123"), "trusted report captured");
        require(SparkAnalysisEngine.trustedReportUrl("spark https://evil.example/report").isEmpty(), "untrusted report rejected");
        require(SparkAnalysisEngine.commandRejected("Unknown or incomplete command"), "missing Spark detected");
        System.out.println("spark-one-click-analysis-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
