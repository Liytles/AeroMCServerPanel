package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Doğrulanmış kurucuyu AWT kullanmadan işletim sisteminin güvenli dosya açıcısına teslim eder. */
public final class InstallerLauncher {
    private InstallerLauncher() { }

    public static void launch(Path installer) throws IOException {
        List<String> command = commandFor(installer, platform());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                throw new IOException("Kurucu açılamadı" + (output.isBlank() ? "." : ": " + output));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("Kurucu açma işlemi kesildi.", error);
        }
    }

    static List<String> commandFor(Path installer, String platform) throws IOException {
        if (installer == null || !Files.isRegularFile(installer) || Files.size(installer) <= 0) throw new IOException("Doğrulanmış kurulum paketi bulunamadı.");
        Path path = installer.toAbsolutePath().normalize(); String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (platform) {
            case "windows" -> {
                if (!name.endsWith(".exe")) throw new IOException("Windows güncellemesi .exe olmalı.");
                yield List.of("rundll32.exe", "url.dll,FileProtocolHandler", path.toString());
            }
            case "macos" -> {
                if (!name.endsWith(".dmg")) throw new IOException("macOS güncellemesi .dmg olmalı.");
                yield List.of("/usr/bin/open", path.toString());
            }
            case "linux" -> {
                if (!name.endsWith(".deb")) throw new IOException("Linux güncellemesi .deb olmalı.");
                if (Files.isExecutable(Path.of("/usr/bin/xdg-open"))) yield List.of("/usr/bin/xdg-open", path.toString());
                if (Files.isExecutable(Path.of("/usr/bin/gio"))) yield List.of("/usr/bin/gio", "open", path.toString());
                throw new IOException("Kurucuyu açmak için xdg-open veya gio bulunamadı. Paketi dosya yöneticisinden açabilirsin: " + path);
            }
            default -> throw new IOException("Bu işletim sisteminde otomatik kurucu açma desteklenmiyor.");
        };
    }

    static String platform() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return value.contains("win") ? "windows" : value.contains("mac") ? "macos" : value.contains("linux") ? "linux" : "other";
    }
}
