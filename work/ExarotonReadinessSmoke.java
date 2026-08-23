package com.aerogroup.mcpanel;

import java.util.List;

public final class ExarotonReadinessSmoke {
    public static void main(String[] args) {
        var ready = new ExarotonPane.ProSnapshot("SMP", "Offline", false, 0, 20, List.of(), 4, "smp.exaroton.me", "Paper", "1.21.8");
        var readyReport = ExarotonReadinessEngine.inspect(ready, 12.5);
        require(!readyReport.hasCritical(), "ready Exaroton server");

        var lowCredit = ExarotonReadinessEngine.inspect(ready, 0);
        require(lowCredit.hasWarnings(), "zero credit warning");

        var invalid = new ExarotonPane.ProSnapshot("SMP", "Crashed", false, 0, 20, List.of(), -1, "", "", "");
        var invalidReport = ExarotonReadinessEngine.inspect(invalid, Double.NaN);
        require(invalidReport.hasCritical() && invalidReport.hasWarnings(), "missing address and metadata");
        System.out.println("exaroton-readiness-ok");
    }

    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
