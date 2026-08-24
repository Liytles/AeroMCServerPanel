package com.aerogroup.mcpanel;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.*;

/** Sabitlenmiş resmî GitHub Releases kaynağından platform paketini doğrulayarak indirir. */
public final class UpdateService {
    public enum Channel { STABLE, BETA }
    public record Asset(String name, URI url, long size) { }
    public record ReleaseInfo(String version, String tag, String title, String notes, URI page, boolean prerelease, Asset installer, Asset checksum) {
        public boolean isNewerThan(String current) { return "development".equalsIgnoreCase(current) || compareVersions(version, current) > 0; }
    }

    private static final String OWNER = "Liytles", REPOSITORY = "AeroMCServerPanel";
    private static final URI API = URI.create("https://api.github.com/repos/" + OWNER + "/" + REPOSITORY + "/releases");
    private static final long MAX_INSTALLER_BYTES = 750L * 1024 * 1024;
    private static final Pattern CHECKSUM = Pattern.compile("(?i)(?<![a-f0-9])([a-f0-9]{64})(?![a-f0-9])");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,180}");
    private static final Pattern SAFE_TAG = Pattern.compile("[vV]?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public ReleaseInfo check(Channel channel) throws Exception {
        URI endpoint = channel == Channel.STABLE ? URI.create(API + "/latest") : URI.create(API + "?per_page=20");
        HttpRequest request = request(endpoint).header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) throw new IOException("GitHub sürümü bulunamadı. Depo private ise güncelleme merkezi anonim erişemez.");
        if (response.statusCode() / 100 != 2) throw new IOException("GitHub sürüm kontrolü HTTP " + response.statusCode() + " ile reddedildi.");
        if (response.body().length() > 2_000_000) throw new IOException("GitHub sürüm yanıtı beklenenden büyük.");
        JsonElement parsed = JsonParser.parseString(response.body());
        JsonObject release;
        if (channel == Channel.STABLE) release = parsed.getAsJsonObject();
        else {
            release = null;
            for (JsonElement value : parsed.getAsJsonArray()) {
                JsonObject candidate = value.getAsJsonObject();
                if (!bool(candidate, "draft")) { release = candidate; break; }
            }
            if (release == null) throw new IOException("Beta kanalında yayınlanmış sürüm bulunamadı.");
        }
        return parseRelease(release, os(), arch());
    }

    public Path download(ReleaseInfo release, BiConsumer<Long, Long> progress) throws Exception {
        Objects.requireNonNull(release); Asset installer = release.installer(), checksum = release.checksum();
        if (installer == null || checksum == null) throw new IOException("Yayın paketi veya SHA-256 doğrulama dosyası eksik.");
        validateAsset(installer); validateAsset(checksum);
        if (installer.size() <= 0 || installer.size() > MAX_INSTALLER_BYTES) throw new IOException("Kurulum paketinin boyutu güvenli sınır dışında.");
        if (checksum.size() <= 0 || checksum.size() > 16_384) throw new IOException("SHA-256 doğrulama dosyasının boyutu geçersiz.");
        String expected = fetchChecksum(checksum, installer.name());
        Path directory = Path.of(System.getProperty("user.home"), ".aeromc-panel", "updates").toAbsolutePath().normalize(); Files.createDirectories(directory);
        Path target = directory.resolve(installer.name()).normalize();
        if (!target.getParent().equals(directory.toAbsolutePath().normalize())) throw new IOException("Güvensiz güncelleme dosya adı.");
        if (Files.isRegularFile(target) && expected.equalsIgnoreCase(sha256(target))) { if (progress != null) progress.accept(Files.size(target), Files.size(target)); return target; }
        Path temporary = Files.createTempFile(directory, ".aeromc-update-", ".part");
        try {
            HttpResponse<InputStream> response = http.send(request(installer.url()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            validateResponse(response, installer.url());
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0;
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count == 0) continue; total += count;
                    if (total > MAX_INSTALLER_BYTES || total > installer.size() + 1_048_576L) throw new IOException("İndirilen paket bildirilen boyutu aştı.");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count); if (progress != null) progress.accept(total, installer.size());
                }
            }
            if (total != installer.size()) throw new IOException("İndirilen paket boyutu GitHub yayınıyla eşleşmiyor.");
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equalsIgnoreCase(actual)) throw new SecurityException("Güncelleme paketinin SHA-256 doğrulaması başarısız.");
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return target;
        } finally { Files.deleteIfExists(temporary); }
    }

    public void verifyDownloaded(ReleaseInfo release, Path installerFile) throws Exception {
        Objects.requireNonNull(release); Asset installer = release.installer(), checksum = release.checksum();
        if (installer == null || checksum == null || installerFile == null) throw new IOException("Doğrulanacak güncelleme paketi eksik.");
        validateAsset(installer); validateAsset(checksum);
        if (!Files.isRegularFile(installerFile) || Files.size(installerFile) != installer.size()) throw new IOException("İndirilen güncelleme paketinin boyutu değişmiş.");
        String expected = fetchChecksum(checksum, installer.name()), actual = sha256(installerFile);
        if (!expected.equalsIgnoreCase(actual)) throw new SecurityException("Güncelleme paketi açılmadan önceki SHA-256 doğrulamasını geçemedi.");
    }

    static ReleaseInfo parseRelease(JsonObject release, String os, String arch) throws IOException {
        if (bool(release, "draft")) throw new IOException("Taslak yayın güncelleme olarak kullanılamaz.");
        String tag = string(release, "tag_name"), version = normalizeVersion(tag);
        if (!SAFE_TAG.matcher(tag).matches()) throw new IOException("Yayın sürüm numarası geçersiz.");
        List<Asset> assets = new ArrayList<>();
        JsonArray values = release.has("assets") && release.get("assets").isJsonArray() ? release.getAsJsonArray("assets") : new JsonArray();
        for (JsonElement value : values) {
            JsonObject item = value.getAsJsonObject(); String name = string(item, "name"), url = string(item, "browser_download_url");
            if (!SAFE_NAME.matcher(name).matches() || url.isBlank()) continue;
            assets.add(new Asset(name, URI.create(url), item.has("size") ? item.get("size").getAsLong() : -1));
        }
        Asset installer = selectInstaller(assets, os, arch);
        Asset checksum = installer == null ? null : assets.stream().filter(asset -> asset.name().equals(installer.name() + ".sha256")).findFirst().orElse(null);
        URI page = URI.create("https://github.com/" + OWNER + "/" + REPOSITORY + "/releases/tag/" + tag);
        return new ReleaseInfo(version, tag, string(release, "name").isBlank() ? tag : string(release, "name"), string(release, "body"), page, bool(release, "prerelease"), installer, checksum);
    }

    static Asset selectInstaller(List<Asset> assets, String os, String arch) {
        String extension = os.equals("windows") ? ".exe" : os.equals("macos") ? ".dmg" : os.equals("linux") ? ".deb" : "";
        if (extension.isBlank()) return null;
        List<Asset> candidates = assets.stream().filter(asset -> asset.name().toLowerCase(Locale.ROOT).endsWith(extension)).toList();
        if (candidates.size() <= 1) return candidates.stream().findFirst().orElse(null);
        List<String> markers = arch.equals("arm64") ? List.of("arm64", "aarch64") : List.of("x64", "amd64", "x86_64");
        return candidates.stream().filter(asset -> markers.stream().anyMatch(marker -> asset.name().toLowerCase(Locale.ROOT).contains(marker))).findFirst().orElse(candidates.get(0));
    }

    static int compareVersions(String left, String right) {
        Version a = Version.parse(left), b = Version.parse(right);
        int core = Integer.compare(a.major, b.major); if (core == 0) core = Integer.compare(a.minor, b.minor); if (core == 0) core = Integer.compare(a.patch, b.patch); if (core != 0) return core;
        if (a.pre.isBlank() && !b.pre.isBlank()) return 1; if (!a.pre.isBlank() && b.pre.isBlank()) return -1; return comparePre(a.pre, b.pre);
    }
    static String expectedChecksum(String text, String installerName) throws IOException {
        if (text == null || text.length() > 16_384) throw new IOException("SHA-256 dosyası geçersiz.");
        for (String line : text.lines().toList()) if (line.contains(installerName)) { Matcher matcher = CHECKSUM.matcher(line); if (matcher.find()) return matcher.group(1).toLowerCase(Locale.ROOT); }
        Matcher matcher = CHECKSUM.matcher(text); if (matcher.find()) return matcher.group(1).toLowerCase(Locale.ROOT);
        throw new IOException("SHA-256 değeri bulunamadı.");
    }
    static String sha256(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(file)) { byte[] buffer = new byte[64 * 1024]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count); } return HexFormat.of().formatHex(digest.digest()); }

    private String fetchChecksum(Asset checksum, String installerName) throws Exception {
        HttpResponse<String> response = http.send(request(checksum.url()).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII)); validateResponse(response, checksum.url()); return expectedChecksum(response.body(), installerName);
    }
    private HttpRequest.Builder request(URI uri) throws IOException { validateUri(uri); return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent", "AeroMC-Server-Panel/" + BuildInfo.version()); }
    private static void validateAsset(Asset asset) throws IOException { if (!SAFE_NAME.matcher(asset.name()).matches()) throw new IOException("Güvensiz yayın dosya adı."); validateUri(asset.url()); }
    private static void validateUri(URI uri) throws IOException { if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !allowedHost(uri.getHost())) throw new IOException("Güncelleme yalnızca resmî GitHub HTTPS adresinden indirilebilir."); }
    private static void validateResponse(HttpResponse<?> response, URI requested) throws IOException { if (response.statusCode() / 100 != 2) throw new IOException("Güncelleme indirmesi HTTP " + response.statusCode() + " ile başarısız oldu."); validateUri(requested); validateUri(response.uri()); }
    private static boolean allowedHost(String host) { if (host == null) return false; String value = host.toLowerCase(Locale.ROOT); return value.equals("api.github.com") || value.equals("github.com") || value.equals("objects.githubusercontent.com") || value.endsWith(".githubusercontent.com"); }
    private static String os() { String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); return value.contains("win") ? "windows" : value.contains("mac") ? "macos" : value.contains("linux") ? "linux" : "other"; }
    private static String arch() { String value = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT); return value.contains("aarch64") || value.contains("arm64") ? "arm64" : "x64"; }
    private static String normalizeVersion(String value) { return value == null ? "" : value.trim().replaceFirst("^[vV]", ""); }
    private static String string(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().trim() : ""; }
    private static boolean bool(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() && object.get(key).getAsBoolean(); }
    private static int comparePre(String left, String right) { String[] a = left.split("\\."), b = right.split("\\."); for (int i = 0; i < Math.max(a.length, b.length); i++) { if (i >= a.length) return -1; if (i >= b.length) return 1; boolean an = a[i].matches("\\d+"), bn = b[i].matches("\\d+"); int compared = an && bn ? Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i])) : an ? -1 : bn ? 1 : a[i].compareToIgnoreCase(b[i]); if (compared != 0) return compared; } return 0; }
    private record Version(int major, int minor, int patch, String pre) { static Version parse(String value) { Matcher matcher = Pattern.compile("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?.*$").matcher(value == null ? "" : value.trim()); if (!matcher.matches()) return new Version(0, 0, 0, ""); return new Version(Integer.parseInt(matcher.group(1)), number(matcher.group(2)), number(matcher.group(3)), matcher.group(4) == null ? "" : matcher.group(4)); } private static int number(String value) { return value == null ? 0 : Integer.parseInt(value); } }
}
