package com.aerogroup.mcpanel;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Yerel Minecraft sunucusunu başlatmadan önce güvenli ve çevrimdışı kontroller yapar. */
public final class PreflightEngine {
    public enum Severity { CRITICAL, WARNING, OK }
    public record Issue(Severity severity, String title, String detail, boolean fixable) {
        @Override public String toString() {
            String icon = severity == Severity.CRITICAL ? "⛔" : severity == Severity.WARNING ? "⚠" : "✓";
            return icon + "  " + title + " — " + detail;
        }
    }
    public record Report(List<Issue> issues) {
        public boolean hasCritical() { return issues.stream().anyMatch(issue -> issue.severity == Severity.CRITICAL); }
        public boolean hasWarnings() { return issues.stream().anyMatch(issue -> issue.severity == Severity.WARNING); }
        public boolean hasFixable() { return issues.stream().anyMatch(Issue::fixable); }
        public long criticalCount() { return issues.stream().filter(issue -> issue.severity == Severity.CRITICAL).count(); }
        public long warningCount() { return issues.stream().filter(issue -> issue.severity == Severity.WARNING).count(); }
    }

    private PreflightEngine() { }

    public static Report inspect(Path jar, int memoryMb) {
        List<Issue> issues = new ArrayList<>();
        if (jar == null || !Files.isRegularFile(jar)) {
            issues.add(new Issue(Severity.CRITICAL, "Sunucu JAR", "Geçerli bir server.jar seçilmedi.", false));
            return new Report(List.copyOf(issues));
        }
        Path folder = jar.toAbsolutePath().normalize().getParent();
        inspectJar(jar, issues); inspectJava(folder, issues); inspectEula(folder, issues); inspectPort(folder, issues);
        inspectMemory(memoryMb, issues); inspectDisk(folder, issues); inspectBackup(folder, issues); inspectContent(folder, issues);
        if (issues.isEmpty()) issues.add(new Issue(Severity.OK, "Başlatma kontrolü", "Belirgin bir sorun bulunmadı; sunucu başlatılabilir.", false));
        return new Report(List.copyOf(issues));
    }

    public static void applySafeFixes(Path jar, Report report) throws IOException {
        if (jar == null || jar.getParent() == null) return;
        if (report.issues.stream().anyMatch(issue -> issue.fixable && "Minecraft EULA".equals(issue.title))) {
            Files.writeString(jar.toAbsolutePath().getParent().resolve("eula.txt"), "eula=true" + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static void inspectJar(Path jar, List<Issue> issues) {
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.size() == 0) issues.add(new Issue(Severity.CRITICAL, "Sunucu JAR", "JAR dosyası boş görünüyor.", false));
        } catch (Exception error) { issues.add(new Issue(Severity.CRITICAL, "Sunucu JAR", "Dosya bozuk veya okunamıyor: " + message(error), false)); }
    }

    private static void inspectJava(Path folder, List<Issue> issues) {
        Properties metadata = load(folder.resolve(".aeromc-server.properties")); String version = metadata.getProperty("minecraftVersion", "").trim();
        if (version.isEmpty()) return;
        int current = Runtime.version().feature(), required = requiredJava(version);
        if (current < required) issues.add(new Issue(Severity.CRITICAL, "Java sürümü", "Minecraft " + version + " yaklaşık Java " + required + " gerektiriyor; panel Java " + current + " kullanıyor.", false));
    }

    private static void inspectEula(Path folder, List<Issue> issues) {
        Properties eula = load(folder.resolve("eula.txt"));
        if (!Boolean.parseBoolean(eula.getProperty("eula", "false"))) issues.add(new Issue(Severity.CRITICAL, "Minecraft EULA", "eula=true değil. Açmak için EULA'yı açıkça kabul etmelisin.", true));
    }

    private static void inspectPort(Path folder, List<Issue> issues) {
        Properties properties = load(folder.resolve("server.properties")); int port = number(properties.getProperty("server-port"), 25565);
        if (port < 1 || port > 65535) { issues.add(new Issue(Severity.CRITICAL, "Sunucu portu", "server-port geçersiz: " + port, false)); return; }
        try (ServerSocket socket = new ServerSocket()) { socket.setReuseAddress(false); socket.bind(new InetSocketAddress("0.0.0.0", port)); }
        catch (IOException error) { issues.add(new Issue(Severity.CRITICAL, "Sunucu portu", port + " portu başka bir uygulama tarafından kullanılıyor.", false)); }
    }

    private static void inspectMemory(int memoryMb, List<Issue> issues) {
        if (memoryMb < 1024) issues.add(new Issue(Severity.WARNING, "RAM", memoryMb + " MB çoğu modern sunucu için düşük olabilir.", false));
        long availableMb = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        if (memoryMb > Math.max(4096, availableMb * 8)) issues.add(new Issue(Severity.WARNING, "RAM", memoryMb + " MB ayrımı bu bilgisayar için yüksek olabilir; açılış başarısız olursa değeri düşür.", false));
    }

    private static void inspectDisk(Path folder, List<Issue> issues) {
        try { long freeMb = Files.getFileStore(folder).getUsableSpace() / 1024 / 1024; if (freeMb < 1024) issues.add(new Issue(Severity.WARNING, "Disk alanı", "Yalnızca " + freeMb + " MB kullanılabilir alan kaldı.", false)); }
        catch (IOException ignored) { }
    }

    private static void inspectBackup(Path folder, List<Issue> issues) {
        Path backups = folder.resolve("backups");
        try (Stream<Path> files = Files.isDirectory(backups) ? Files.list(backups) : Stream.empty()) {
            Optional<Path> newest = files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")).max(Comparator.comparingLong(PreflightEngine::modified));
            if (newest.isEmpty()) issues.add(new Issue(Severity.WARNING, "Dünya yedeği", "Bu sunucu için henüz ZIP yedeği bulunamadı.", false));
            else { long hours = Duration.between(Instant.ofEpochMilli(modified(newest.get())), Instant.now()).toHours(); if (hours >= 24) issues.add(new Issue(Severity.WARNING, "Dünya yedeği", "En yeni yedek yaklaşık " + hours + " saat önce alınmış.", false)); }
        } catch (IOException ignored) { }
    }

    private static void inspectContent(Path folder, List<Issue> issues) {
        for (String directory : List.of("plugins", "mods")) {
            Path content = folder.resolve(directory); if (!Files.isDirectory(content)) continue;
            try (Stream<Path> files = Files.list(content)) {
                for (Path file : files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                    try (JarFile ignored = new JarFile(file.toFile())) { }
                    catch (Exception error) { issues.add(new Issue(Severity.WARNING, directory, file.getFileName() + " bozuk veya okunamıyor.", false)); }
                }
            } catch (IOException ignored) { }
        }
    }

    static int requiredJava(String version) {
        try {
            String[] parts = version.split("\\."); int major = Integer.parseInt(parts[0]), minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            String patchText = parts.length > 2 ? parts[2].replaceAll("[^0-9].*", "") : "0"; int patch = patchText.isEmpty() ? 0 : Integer.parseInt(patchText);
            if (major > 1 || minor >= 26) return 25; if (minor > 20 || minor == 20 && patch >= 5) return 21; if (minor >= 18) return 17; return 8;
        } catch (Exception ignored) { return 17; }
    }

    private static Properties load(Path file) { Properties values = new Properties(); try { if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); } } catch (IOException ignored) { } return values; }
    private static int number(String value, int fallback) { try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; } }
    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0; } }
    private static String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
