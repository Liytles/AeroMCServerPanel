package com.aerogroup.mcpanel;

import java.util.List;

public final class HealthFeaturesSmoke {
    public static void main(String[] args) {
        var healthy = ServerHealthEngine.calculate(true, 19.9, 55, 42, 35, 0, 0);
        require(healthy.score() >= 90 && "Mükemmel".equals(healthy.state()), "healthy score");

        var critical = ServerHealthEngine.calculate(true, 12.5, 97, 98, 420, 2, 4);
        require(critical.score() < 35 && "Kritik".equals(critical.state()), "critical score");
        require(ServerHealthEngine.shouldEnterCrisis(14.0, 70, 0, 16, 90), "TPS crisis threshold");
        require(ServerHealthEngine.shouldEnterCrisis(20.0, 94, 0, 16, 90), "RAM crisis threshold");

        var memory = CrashDoctor.diagnose(List.of("java.lang.OutOfMemoryError: Java heap space", "Crash report saved"));
        require(memory.title().contains("Bellek") && memory.confidence() >= 90, "memory diagnosis");
        var plugin = CrashDoctor.diagnose(List.of("Could not load 'plugins/WorldGuard.jar'", "InvalidPluginException"));
        require(plugin.culprit().contains("WorldGuard.jar") && plugin.title().contains("Eklenti"), "plugin diagnosis");
        String redacted = AppDiagnostics.redact("Authorization: Bearer abc.def API_KEY=secret password: hunter2");
        require(!redacted.contains("abc.def") && !redacted.contains("secret") && !redacted.contains("hunter2"), "diagnostic secret redaction");
        System.out.println("health-crisis-crash-doctor-ok");
    }

    private static void require(boolean value, String name) {
        if (!value) throw new IllegalStateException("Smoke test failed: " + name);
    }
}
