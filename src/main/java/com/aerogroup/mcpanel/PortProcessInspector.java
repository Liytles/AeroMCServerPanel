package com.aerogroup.mcpanel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Small, best-effort local-only lookup for the process listening on a TCP port. */
final class PortProcessInspector {
    record Owner(String command, long pid) {
        String display() { return command + " (PID " + pid + ")"; }
    }

    private PortProcessInspector() { }

    static Optional<Owner> findListener(int port) {
        if (port < 1 || port > 65535) return Optional.empty();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? windows(port) : lsof(port);
    }

    private static Optional<Owner> lsof(int port) {
        List<String> lines = execute(List.of("lsof", "-nP", "-iTCP:" + port, "-sTCP:LISTEN"));
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2 && parts[1].chars().allMatch(Character::isDigit)) {
                try { return Optional.of(new Owner(safeName(parts[0]), Long.parseLong(parts[1]))); }
                catch (NumberFormatException ignored) { }
            }
        }
        return Optional.empty();
    }

    private static Optional<Owner> windows(int port) {
        for (String line : execute(List.of("netstat", "-ano", "-p", "tcp"))) {
            String normalized = line.trim().replaceAll("\\s+", " ");
            String[] parts = normalized.split(" ");
            if (parts.length < 5 || !normalized.toUpperCase(Locale.ROOT).contains("LISTENING") || !parts[1].endsWith(":" + port)) continue;
            try {
                long pid = Long.parseLong(parts[parts.length - 1]);
                String command = execute(List.of("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")).stream()
                        .filter(value -> !value.startsWith("INFO:"))
                        .map(PortProcessInspector::csvFirstValue)
                        .filter(value -> !value.isBlank()).findFirst().orElse("Bilinmeyen işlem");
                return Optional.of(new Owner(safeName(command), pid));
            } catch (NumberFormatException ignored) { }
        }
        return Optional.empty();
    }

    private static List<String> execute(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            String output = new String(process.getInputStream().readNBytes(12_000), StandardCharsets.UTF_8);
            return output.isBlank() ? List.of() : output.lines().toList();
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static String csvFirstValue(String line) {
        String value = line == null ? "" : line.trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf("\"", 1);
            if (end > 1) return value.substring(1, end);
        }
        return value.split(",", 2)[0];
    }

    private static String safeName(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._ -]", "").trim();
        if (cleaned.isBlank()) return "Bilinmeyen işlem";
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }
}
