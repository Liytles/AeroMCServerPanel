package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.util.List;

public final class JavaRuntimeSmoke {
    public static void main(String[] args) throws Exception {
        assert JavaRuntimeResolver.parseFeature("openjdk version \"21.0.8\"") == 21;
        assert JavaRuntimeResolver.parseFeature("java version \"1.8.0_451\"") == 8;
        assert JavaRuntimeResolver.parseFeature("bilinmeyen") == 0;
        Path missing = Files.createTempDirectory("aeromc-missing-java").resolve("java");
        Path current = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
        assert JavaRuntimeResolver.firstUsable(List.of(missing, current)).orElseThrow().equals(current.toAbsolutePath().normalize());
        JavaRuntimeResolver.RuntimeInfo runtime = JavaRuntimeResolver.resolve();
        assert Files.isRegularFile(runtime.executable());
        assert runtime.feature() >= 17;
        System.out.println("Java runtime smoke başarılı: Java " + runtime.feature() + " • " + runtime.executable());
    }
}
