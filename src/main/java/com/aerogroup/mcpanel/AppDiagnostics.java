package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Beklenmeyen hataları anahtar/parola içermeyen yerel bir tanılama kaydına dönüştürür. */
public final class AppDiagnostics {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization|api[-_ ]?key|password|token)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private AppDiagnostics() { }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> writeCrash(thread == null ? "unknown" : thread.getName(), error));
    }

    public static Path writeCrash(String thread, Throwable error) {
        Path directory = Path.of(System.getProperty("user.home"), ".aeromc-panel", "logs");
        try {
            Files.createDirectories(directory); Path file = directory.resolve("crash-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + ".log");
            try (PrintWriter output = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))) {
                output.println("AeroMC Server Panel " + BuildInfo.displayVersion());
                output.println("Time: " + OffsetDateTime.now()); output.println("Thread: " + thread);
                output.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
                output.println("Java: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
                output.println();
                if (error == null) output.println("Unknown failure");
                else {
                    StringWriter trace = new StringWriter();
                    error.printStackTrace(new PrintWriter(trace));
                    output.print(redact(trace.toString()));
                }
            }
            return file;
        } catch (IOException ignored) { return null; }
    }

    static String redact(String value) {
        if (value == null) return "";
        String masked = BEARER.matcher(value).replaceAll("$1[REDACTED]");
        return SECRET.matcher(masked).replaceAll("$1$2[REDACTED]");
    }
}
