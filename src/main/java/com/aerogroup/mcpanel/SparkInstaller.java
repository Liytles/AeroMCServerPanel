package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.SafePathGuard;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Eski Paper/Fabric kurulumlarına Spark'ı resmî kaynaktan ve özet doğrulamasıyla ekler. */
public final class SparkInstaller {
    static final String PROJECT_ID = "l6YH9Als";
    private static final URI DOWNLOAD_API = URI.create("https://sparkapi.lucko.me/download");
    private static final String USER_AGENT = "AeroMCServerPanel/" + BuildInfo.version() + " (github.com/Liytles/AeroMCServerPanel)";
    private static final int MAX_API_BYTES = 512 * 1024;
    private static final long MAX_SPARK_BYTES = 128L * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
    private final ModrinthService modrinth = new ModrinthService();
    private final ModInstallManager installer = new ModInstallManager(modrinth);

    public InstallResult install(Path serverRoot, String platform, String gameVersion) throws Exception {
        Path root = serverRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new SecurityException("Sunucu klasörü geçersiz veya simgesel bağlantı.");
        if (!supports(platform, gameVersion)) throw new IllegalArgumentException("Spark otomatik kurulumu yalnızca 1.21 öncesi Paper veya Fabric sunucularında kullanılabilir.");
        if ("Fabric".equalsIgnoreCase(platform)) {
            ModInstallManager.InstallReport report = installer.installLocal(root, "mods", modrinth.resolve(PROJECT_ID, gameVersion, "fabric"));
            return new InstallResult(report.installedFiles(), "Modrinth SHA-512", report.backup());
        }
        JsonObject metadata = get(DOWNLOAD_API).getAsJsonObject().getAsJsonObject("bukkit");
        if (metadata == null) throw new IOException("Spark Bukkit indirme bilgisi alınamadı.");
        String filename = safeFilename(metadata.get("fileName").getAsString()), expectedSha1 = metadata.get("sha1").getAsString(); URI uri = URI.create(metadata.get("url").getAsString());
        if (!trustedLucko(uri) || !expectedSha1.matches("[a-fA-F0-9]{40}")) throw new SecurityException("Spark indirme bilgisi güvenilir değil.");
        Path plugins = SafePathGuard.resolve(root, "plugins", true); Files.createDirectories(plugins); SafePathGuard.requireWithin(root, plugins, false);
        Path temporary = Files.createTempFile(plugins, ".spark-", ".download"), output = plugins.resolve(filename), backup = null;
        try {
            if (Files.isSymbolicLink(output)) throw new SecurityException("Spark hedefi simgesel bağlantı olamaz.");
            HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(3)).GET().build(); HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2 || !trustedLucko(response.uri())) { response.body().close(); throw new IOException("Spark JAR indirilemedi veya doğrulanamadı."); }
            long downloaded = BoundedStreams.copyToFile(response.body(), temporary, MAX_SPARK_BYTES);
            if (downloaded < 100_000) throw new IOException("Spark JAR indirilemedi veya doğrulanamadı.");
            if (!digest(temporary, "SHA-1").equalsIgnoreCase(expectedSha1)) throw new SecurityException("Spark SHA-1 doğrulaması başarısız.");
            if (Files.isRegularFile(output)) { backup = output.resolveSibling(output.getFileName() + ".aeromc-backup"); Files.move(output, backup, StandardCopyOption.REPLACE_EXISTING); }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); return new InstallResult(List.of(output), "Resmî lucko.me SHA-1", backup);
        } finally { Files.deleteIfExists(temporary); }
    }

    static boolean supports(String platform, String gameVersion) { return ("Paper".equalsIgnoreCase(platform) || "Fabric".equalsIgnoreCase(platform)) && isBefore121(gameVersion); }
    static boolean isBefore121(String value) {
        try { String[] parts = Objects.toString(value, "").trim().split("\\."); if (parts.length < 2 || Integer.parseInt(parts[0]) != 1) return false; return Integer.parseInt(parts[1].replaceAll("[^0-9].*", "")) < 21; } catch (Exception ignored) { return false; }
    }

    private JsonElement get(URI uri) throws Exception { if (!DOWNLOAD_API.equals(uri)) throw new SecurityException("Güvensiz Spark API adresi."); HttpRequest request = HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).header("Accept", "application/json").timeout(Duration.ofSeconds(30)).GET().build(); HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); if (response.statusCode() / 100 != 2 || !"sparkapi.lucko.me".equalsIgnoreCase(response.uri().getHost()) || !"https".equalsIgnoreCase(response.uri().getScheme())) { response.body().close(); throw new IOException("Spark API HTTP " + response.statusCode() + " döndürdü."); } return JsonParser.parseString(BoundedStreams.readString(response.body(), MAX_API_BYTES, java.nio.charset.StandardCharsets.UTF_8)); }
    private static boolean trustedLucko(URI uri) { return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && "ci.lucko.me".equalsIgnoreCase(uri.getHost()); }
    private static String safeFilename(String value) { String name = Path.of(value).getFileName().toString(); if (!name.matches("[A-Za-z0-9._+()-]+\\.jar")) throw new SecurityException("Güvensiz Spark dosya adı."); return name; }
    private static String digest(Path file, String algorithm) throws Exception { MessageDigest digest = MessageDigest.getInstance(algorithm); try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read); } StringBuilder value = new StringBuilder(); for (byte item : digest.digest()) value.append(String.format("%02x", item)); return value.toString(); }

    public record InstallResult(List<Path> files, String verification, Path backup) { }
}
