package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Minecraft alt işlemi için kullanılabilir Java çalıştırıcısını bulur ve sürümünü doğrular. */
public final class JavaRuntimeResolver {
    public record RuntimeInfo(Path executable, int feature, String source) { }

    private static final Pattern VERSION = Pattern.compile("(?:openjdk|java) version \\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
    private JavaRuntimeResolver() { }

    public static RuntimeInfo resolve() throws IOException {
        LinkedHashMap<Path, String> candidates = candidates();
        for (Map.Entry<Path, String> candidate : candidates.entrySet()) {
            Path executable = candidate.getKey();
            if (!usable(executable)) continue;
            int feature = probeFeature(executable);
            if (feature > 0) return new RuntimeInfo(executable.toAbsolutePath().normalize(), feature, candidate.getValue());
        }
        throw new IOException("Minecraft için kullanılabilir Java bulunamadı. AeroMC'yi yeniden kur veya AEROMC_JAVA/JAVA_HOME ile geçerli bir Java yolu belirt.");
    }

    static Optional<Path> firstUsable(List<Path> candidates) {
        return candidates.stream().filter(Objects::nonNull).filter(JavaRuntimeResolver::usable).map(path -> path.toAbsolutePath().normalize()).findFirst();
    }

    static int parseFeature(String output) {
        Matcher matcher = VERSION.matcher(Objects.toString(output, ""));
        if (!matcher.find()) return 0;
        String value = matcher.group(1); String[] parts = value.split("[._+-]");
        try {
            int first = Integer.parseInt(parts[0]);
            return first == 1 && parts.length > 1 ? Integer.parseInt(parts[1]) : first;
        } catch (NumberFormatException ignored) { return 0; }
    }

    private static LinkedHashMap<Path, String> candidates() {
        LinkedHashMap<Path, String> result = new LinkedHashMap<>();
        String command = executableName();
        addExplicit(result, System.getenv("AEROMC_JAVA"), "AEROMC_JAVA");
        addHome(result, System.getProperty("java.home"), command, "AeroMC gömülü Java");
        addHome(result, System.getenv("JAVA_HOME"), command, "JAVA_HOME");
        String path = System.getenv("PATH");
        if (path != null) for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) try { result.putIfAbsent(Path.of(entry).resolve(command), "Sistem PATH"); } catch (InvalidPathException ignored) { }
        }
        return result;
    }

    private static void addExplicit(Map<Path, String> values, String value, String source) {
        if (value == null || value.isBlank()) return;
        try { values.putIfAbsent(Path.of(value), source); } catch (InvalidPathException ignored) { }
    }

    private static void addHome(Map<Path, String> values, String home, String command, String source) {
        if (home == null || home.isBlank()) return;
        try { values.putIfAbsent(Path.of(home).resolve("bin").resolve(command), source); } catch (InvalidPathException ignored) { }
    }

    private static boolean usable(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        return isWindows() || Files.isExecutable(path);
    }

    private static int probeFeature(Path executable) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "-version").redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return 0; }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? parseFeature(output) : 0;
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return 0;
        }
    }

    private static String executableName() { return isWindows() ? "java.exe" : "java"; }
    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
}
