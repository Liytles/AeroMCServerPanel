package com.aerogroup.mcpanel;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.time.Duration;
import java.util.*;

/** Modrinth'in herkese açık API'sinde arama, sürüm/bağımlılık çözme ve doğrulanmış indirme yapar. */
public final class ModrinthService {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "AeroMCServerPanel/2.1 (desktop Minecraft server manager)";
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    public List<Project> search(String query, String gameVersion, String loader, String projectType) throws Exception {
        JsonArray facets = new JsonArray(); facets.add(group(("plugin".equals(projectType) ? "all_project_types:" : "project_type:") + projectType)); facets.add(group("versions:" + gameVersion)); facets.add(group("categories:" + loader)); facets.add(group("server_side!=unsupported"));
        String url = API + "/search?limit=30&index=downloads&query=" + encode(query) + "&facets=" + encode(facets.toString());
        return parseSearch(getJson(url).getAsJsonObject());
    }

    public Resolution resolve(String projectId, String gameVersion, String loader) throws Exception {
        LinkedHashMap<String, ResolvedFile> files = new LinkedHashMap<>(); resolveProject(projectId, gameVersion, loader, files, new HashSet<>()); return new Resolution(List.copyOf(files.values()));
    }

    public Resolution resolveVersions(Collection<String> versionIds, String gameVersion, String loader) throws Exception {
        LinkedHashMap<String, ResolvedFile> files = new LinkedHashMap<>(); Set<String> visiting = new HashSet<>();
        for (String versionId : new LinkedHashSet<>(versionIds)) resolveVersionId(versionId, gameVersion, loader, files, visiting);
        return new Resolution(List.copyOf(files.values()));
    }

    /** Dosya SHA-512 değerlerinden kurulu ve hedefe uygun en yeni sürümleri toplu olarak bulur. */
    public Map<String, VersionMatch> checkUpdates(Collection<String> hashes, String gameVersion, String loader) throws Exception {
        if (hashes.isEmpty()) return Map.of();
        JsonObject base = new JsonObject(); JsonArray hashArray = new JsonArray(); new LinkedHashSet<>(hashes).forEach(hashArray::add); base.add("hashes", hashArray); base.addProperty("algorithm", "sha512");
        JsonObject currentResponse = postJson(API + "/version_files", base).getAsJsonObject();
        JsonObject updateRequest = base.deepCopy(); JsonArray loaders = new JsonArray(); loaders.add(loader); JsonArray versions = new JsonArray(); versions.add(gameVersion); updateRequest.add("loaders", loaders); updateRequest.add("game_versions", versions);
        JsonObject latestResponse = postJson(API + "/version_files/update", updateRequest).getAsJsonObject();
        Map<String, VersionData> current = parseVersionMap(currentResponse), latest = parseVersionMap(latestResponse); LinkedHashMap<String, VersionMatch> result = new LinkedHashMap<>();
        for (String hash : hashes) result.put(hash, new VersionMatch(current.get(hash), latest.get(hash)));
        return result;
    }

    private void resolveProject(String projectId, String gameVersion, String loader, LinkedHashMap<String, ResolvedFile> files, Set<String> visiting) throws Exception {
        if (!visiting.add("project:" + projectId) || files.containsKey(projectId)) return;
        String url = API + "/project/" + encode(projectId) + "/version?loaders=" + encode("[\"" + loader + "\"]") + "&game_versions=" + encode("[\"" + gameVersion + "\"]") + "&include_changelog=false";
        JsonArray versions = getJson(url).getAsJsonArray(); JsonObject selected = selectVersion(versions); if (selected == null) throw new IOException("Bu proje için " + gameVersion + " / " + loader + " uyumlu sürüm bulunamadı.");
        resolveVersion(selected, gameVersion, loader, files, visiting); visiting.remove("project:" + projectId);
    }

    private void resolveVersionId(String versionId, String gameVersion, String loader, LinkedHashMap<String, ResolvedFile> files, Set<String> visiting) throws Exception {
        if (!visiting.add("version:" + versionId)) return; resolveVersion(getJson(API + "/version/" + encode(versionId)).getAsJsonObject(), gameVersion, loader, files, visiting); visiting.remove("version:" + versionId);
    }

    private void resolveVersion(JsonObject version, String gameVersion, String loader, LinkedHashMap<String, ResolvedFile> files, Set<String> visiting) throws Exception {
        if (version.has("dependencies")) for (JsonElement element : version.getAsJsonArray("dependencies")) { JsonObject dependency = element.getAsJsonObject(); if (!"required".equals(string(dependency, "dependency_type", ""))) continue; String versionId = nullableString(dependency, "version_id"), projectId = nullableString(dependency, "project_id"); if (versionId != null) resolveVersionId(versionId, gameVersion, loader, files, visiting); else if (projectId != null) resolveProject(projectId, gameVersion, loader, files, visiting); }
        JsonObject file = primaryFile(version.getAsJsonArray("files")); if (file == null) throw new IOException("Modrinth sürümünde indirilebilir ana JAR bulunamadı.");
        String projectId = string(version, "project_id", "unknown-" + string(version, "id", UUID.randomUUID().toString())); String sha512 = file.getAsJsonObject("hashes").get("sha512").getAsString();
        ResolvedFile resolved = new ResolvedFile(projectId, string(version, "id", ""), string(version, "name", string(version, "version_number", "?")), string(version, "version_number", "?"), string(file, "filename", projectId + ".jar"), URI.create(string(file, "url", "")), sha512, file.has("size") ? file.get("size").getAsLong() : -1);
        ResolvedFile existing = files.putIfAbsent(projectId, resolved); if (existing != null && !existing.versionId().equals(resolved.versionId())) throw new IOException("Çakışan bağımlılık sürümleri: " + existing.versionNumber() + " / " + resolved.versionNumber());
    }

    public Path downloadVerified(ResolvedFile file, Path directory) throws Exception {
        if (!trustedDownload(file.url())) throw new SecurityException("Modrinth dışındaki bir indirme adresi reddedildi.");
        Files.createDirectories(directory); Path output = directory.resolve(safeFilename(file.filename())); HttpRequest request = HttpRequest.newBuilder(file.url()).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(3)).GET().build(); HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(output));
        if (response.statusCode() / 100 != 2) { Files.deleteIfExists(output); throw new IOException("Dosya indirilemedi: HTTP " + response.statusCode()); }
        if (!trustedDownload(response.uri()) || file.size() >= 0 && Files.size(output) != file.size()) { Files.deleteIfExists(output); throw new SecurityException("Dosya boyutu veya indirme adresi doğrulanamadı: " + file.filename()); }
        String actual = digest(output); if (!actual.equalsIgnoreCase(file.sha512())) { Files.deleteIfExists(output); throw new SecurityException("SHA-512 doğrulaması başarısız: " + file.filename()); }
        return output;
    }

    private JsonElement getJson(String url) throws Exception { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).header("Accept", "application/json").timeout(Duration.ofSeconds(30)).GET().build(); return sendJson(request); }
    private JsonElement postJson(String url, JsonObject body) throws Exception { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT).header("Accept", "application/json").header("Content-Type", "application/json").timeout(Duration.ofSeconds(45)).POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(); return sendJson(request); }
    private JsonElement sendJson(HttpRequest request) throws Exception { HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString()); if (response.statusCode() / 100 != 2) throw new IOException("Modrinth API HTTP " + response.statusCode() + " döndürdü."); return JsonParser.parseString(response.body()); }
    static List<Project> parseSearch(JsonObject response) { List<Project> result = new ArrayList<>(); if (response == null || !response.has("hits")) return result; for (JsonElement element : response.getAsJsonArray("hits")) { JsonObject hit = element.getAsJsonObject(); String serverSide = string(hit, "server_side", "unknown"); if ("unsupported".equals(serverSide)) continue; String type = string(hit, "project_type", "mod"); if (hit.has("all_project_types") && hit.get("all_project_types").isJsonArray()) for (JsonElement item : hit.getAsJsonArray("all_project_types")) if ("plugin".equals(item.getAsString())) type = "plugin"; result.add(new Project(string(hit, "project_id", ""), string(hit, "slug", ""), string(hit, "title", "İsimsiz"), string(hit, "description", ""), string(hit, "author", "?"), hit.has("downloads") ? hit.get("downloads").getAsLong() : 0, nullableString(hit, "icon_url"), serverSide, type)); } return result; }
    static JsonObject selectVersion(JsonArray versions) { if (versions == null || versions.isEmpty()) return null; for (JsonElement element : versions) if ("release".equals(string(element.getAsJsonObject(), "version_type", ""))) return element.getAsJsonObject(); return versions.get(0).getAsJsonObject(); }
    static Map<String, VersionData> parseVersionMap(JsonObject response) { LinkedHashMap<String, VersionData> result = new LinkedHashMap<>(); if (response == null) return result; for (var entry : response.entrySet()) if (entry.getValue().isJsonObject()) result.put(entry.getKey(), parseVersion(entry.getValue().getAsJsonObject())); return result; }
    static VersionData parseVersion(JsonObject version) { if (version == null) return null; JsonObject file = primaryFile(version.getAsJsonArray("files")); ResolvedFile resolved = null; String projectId = string(version, "project_id", ""), versionId = string(version, "id", ""); if (file != null && file.has("hashes") && file.getAsJsonObject("hashes").has("sha512")) resolved = new ResolvedFile(projectId, versionId, string(version, "name", string(version, "version_number", "?")), string(version, "version_number", "?"), string(file, "filename", projectId + ".jar"), URI.create(string(file, "url", "")), file.getAsJsonObject("hashes").get("sha512").getAsString(), file.has("size") ? file.get("size").getAsLong() : -1); List<Dependency> dependencies = new ArrayList<>(); if (version.has("dependencies")) for (JsonElement item : version.getAsJsonArray("dependencies")) { JsonObject dependency = item.getAsJsonObject(); dependencies.add(new Dependency(nullableString(dependency, "project_id"), nullableString(dependency, "version_id"), nullableString(dependency, "file_name"), string(dependency, "dependency_type", "optional"))); } return new VersionData(projectId, versionId, string(version, "version_number", "?"), string(version, "version_type", "unknown"), strings(version, "loaders"), strings(version, "game_versions"), List.copyOf(dependencies), resolved); }
    private static JsonObject primaryFile(JsonArray files) { if (files == null || files.isEmpty()) return null; for (JsonElement element : files) { JsonObject file = element.getAsJsonObject(); if (file.has("primary") && file.get("primary").getAsBoolean() && string(file, "filename", "").toLowerCase(Locale.ROOT).endsWith(".jar")) return file; } for (JsonElement element : files) if (string(element.getAsJsonObject(), "filename", "").toLowerCase(Locale.ROOT).endsWith(".jar")) return element.getAsJsonObject(); return null; }
    private static JsonArray group(String value) { JsonArray group = new JsonArray(); group.add(value); return group; }
    private static String safeFilename(String value) { String name = Path.of(value).getFileName().toString(); if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IllegalArgumentException("Yalnızca JAR dosyaları kurulabilir."); return name.replaceAll("[^A-Za-z0-9._+()\\[\\]-]", "_"); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String string(JsonObject object, String key, String fallback) { return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; }
    private static String nullableString(JsonObject object, String key) { String value = string(object, key, null); return value == null || value.isBlank() ? null : value; }
    private static List<String> strings(JsonObject object, String key) { if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return List.of(); List<String> values = new ArrayList<>(); for (JsonElement item : object.getAsJsonArray(key)) values.add(item.getAsString()); return List.copyOf(values); }
    private static boolean trustedDownload(URI uri) { String host = uri == null ? null : uri.getHost(); return "https".equalsIgnoreCase(uri == null ? null : uri.getScheme()) && host != null && (host.equals("modrinth.com") || host.endsWith(".modrinth.com")); }
    static String sha512(Path file) throws Exception { MessageDigest hash = MessageDigest.getInstance("SHA-512"); try (InputStream input = new DigestInputStream(Files.newInputStream(file), hash)) { input.transferTo(OutputStream.nullOutputStream()); } return hex(hash.digest()); }
    private static String digest(Path file) throws Exception { return sha512(file); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(bytes.length * 2); for (byte item : bytes) value.append(String.format("%02x", item)); return value.toString(); }

    public record Project(String id, String slug, String title, String description, String author, long downloads, String iconUrl, String serverSide, String projectType) { @Override public String toString() { return title + "  •  " + author + "  •  " + downloads + " indirme"; } }
    public record ResolvedFile(String projectId, String versionId, String versionName, String versionNumber, String filename, URI url, String sha512, long size) { }
    public record Resolution(List<ResolvedFile> files) { public int dependencyCount() { return Math.max(0, files.size() - 1); } }
    public record Dependency(String projectId, String versionId, String filename, String type) { }
    public record VersionData(String projectId, String versionId, String versionNumber, String releaseType, List<String> loaders, List<String> gameVersions, List<Dependency> dependencies, ResolvedFile primaryFile) { }
    public record VersionMatch(VersionData current, VersionData latest) { public boolean recognized() { return current != null; } public boolean updateAvailable() { return current != null && latest != null && !current.versionId().equals(latest.versionId()); } }
}
