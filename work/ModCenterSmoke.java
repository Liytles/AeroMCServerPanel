package com.aerogroup.mcpanel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.scene.Group;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

public final class ModCenterSmoke {
    public static void main(String[] args) throws Exception {
        verifySearchParsing();
        verifyVersionSelection();
        verifyTranslations();
        verifyConflictDetector();
        verifySafeLocalInstall();
        verifyRollback();
        System.out.println("mod-center-ok");
    }

    private static void verifySearchParsing() {
        JsonObject response = new JsonObject();
        JsonArray hits = new JsonArray();
        hits.add(hit("voice", "Simple Voice Chat", "required", "plugin"));
        hits.add(hit("client-only", "Client Only", "unsupported", "mod"));
        response.add("hits", hits);
        List<ModrinthService.Project> parsed = ModrinthService.parseSearch(response);
        require(parsed.size() == 1, "server compatibility filter");
        require("plugin".equals(parsed.get(0).projectType()), "all project types parsing");
    }

    private static JsonObject hit(String id, String title, String serverSide, String type) {
        JsonObject hit = new JsonObject();
        hit.addProperty("project_id", id); hit.addProperty("slug", id); hit.addProperty("title", title);
        hit.addProperty("description", "test"); hit.addProperty("author", "Aero"); hit.addProperty("downloads", 42);
        hit.addProperty("server_side", serverSide); hit.addProperty("project_type", "mod");
        JsonArray types = new JsonArray(); types.add(type); hit.add("all_project_types", types);
        return hit;
    }

    private static void verifyVersionSelection() {
        JsonArray versions = new JsonArray();
        JsonObject beta = new JsonObject(); beta.addProperty("version_type", "beta"); beta.addProperty("id", "beta");
        JsonObject release = new JsonObject(); release.addProperty("version_type", "release"); release.addProperty("id", "release");
        versions.add(beta); versions.add(release);
        require("release".equals(ModrinthService.selectVersion(versions).get("id").getAsString()), "stable version preference");
    }

    private static void verifyTranslations() {
        LanguageManager.apply(new Group(), "en");
        require("Search Modrinth".equals(LanguageManager.text("Modrinth'te Ara")), "English screen translation");
        require("12 compatible projects found.".equals(LanguageManager.text("12 uyumlu proje bulundu.")), "English dynamic translation");
        LanguageManager.apply(new Group(), "tr");
        require("12 uyumlu proje bulundu.".equals(LanguageManager.text("12 compatible projects found.")), "Turkish reverse translation");
    }

    private static void verifyConflictDetector() throws Exception {
        Path root = Files.createTempDirectory("aeromc-conflicts-"); Path mods = Files.createDirectories(root.resolve("mods"));
        Path alpha = fabricJar(mods.resolve("alpha.jar"), "alpha"), conflict = fabricJar(mods.resolve("conflict.jar"), "conflict");
        var required = new ModrinthService.Dependency("required-project", null, null, "required");
        var incompatible = new ModrinthService.Dependency("conflict-project", null, null, "incompatible");
        var currentAlpha = version("alpha-project", "alpha-old", "1.0", "alpha.jar", List.of());
        var latestAlpha = version("alpha-project", "alpha-new", "2.0", "alpha-new.jar", List.of(required, incompatible));
        var currentConflict = version("conflict-project", "conflict-current", "1.0", "conflict.jar", List.of());
        Map<String, Path> files = Map.of("hash-a", alpha, "hash-c", conflict);
        Map<String, ModrinthService.VersionMatch> matches = Map.of("hash-a", new ModrinthService.VersionMatch(currentAlpha, latestAlpha), "hash-c", new ModrinthService.VersionMatch(currentConflict, currentConflict));
        var report = ModUpdateService.analyze(root, files, matches, "1.21.1", "fabric");
        require(report.updates().size() == 1, "update candidate detection");
        require(report.conflicts().stream().anyMatch(item -> item.severity() == ModUpdateService.Severity.WARNING && item.message().contains("required-project")), "missing dependency warning");
        require(report.conflicts().stream().anyMatch(item -> item.severity() == ModUpdateService.Severity.ERROR && item.message().contains("conflict-project")), "incompatible dependency error");
        ModInstallManager.deleteTree(root);
    }

    private static ModrinthService.VersionData version(String project, String id, String number, String filename, List<ModrinthService.Dependency> dependencies) {
        return new ModrinthService.VersionData(project, id, number, "release", List.of("fabric"), List.of("1.21.1"), dependencies, file(project, filename, number));
    }

    private static Path fabricJar(Path output, String id) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output))) { jar.putNextEntry(new JarEntry("fabric.mod.json")); jar.write(("{\"id\":\"" + id + "\",\"version\":\"1.0\"}").getBytes(StandardCharsets.UTF_8)); jar.closeEntry(); } return output;
    }

    private static void verifySafeLocalInstall() throws Exception {
        Path root = Files.createTempDirectory("aeromc-mod-install-");
        Path mods = Files.createDirectories(root.resolve("mods"));
        Files.writeString(mods.resolve("old.jar"), "old", StandardCharsets.UTF_8);
        Properties managed = new Properties(); managed.setProperty("main", "old.jar|1.0");
        try (var writer = Files.newBufferedWriter(mods.resolve(".aeromc-installed.properties"), StandardCharsets.UTF_8)) { managed.store(writer, "test"); }

        ModInstallManager installer = new ModInstallManager((file, directory) -> Files.writeString(directory.resolve(file.filename()), "payload-" + file.projectId(), StandardCharsets.UTF_8));
        var dependency = file("dependency", "dependency.jar", "1.1");
        var main = file("main", "main-2.jar", "2.0");
        ModInstallManager.InstallReport report = installer.installLocal(root, "mods", new ModrinthService.Resolution(List.of(dependency, main)));

        require(Files.isRegularFile(mods.resolve("dependency.jar")) && Files.isRegularFile(mods.resolve("main-2.jar")), "installed files");
        require(!Files.exists(mods.resolve("old.jar")), "managed update replaces old filename");
        require(report.dependencies() == 1 && report.rollbackProtected(), "install report");
        try (ZipFile backup = new ZipFile(report.backup().toFile())) { require(backup.getEntry("old.jar") != null, "pre-install backup"); }
        ModInstallManager.deleteTree(root);

        Path unmanagedRoot = Files.createTempDirectory("aeromc-unmanaged-update-"); Path unmanagedMods = Files.createDirectories(unmanagedRoot.resolve("mods")); Files.writeString(unmanagedMods.resolve("legacy.jar"), "legacy", StandardCharsets.UTF_8);
        ModInstallManager unmanagedInstaller = new ModInstallManager((file, directory) -> Files.writeString(directory.resolve(file.filename()), "updated", StandardCharsets.UTF_8));
        unmanagedInstaller.installLocal(unmanagedRoot, "mods", new ModrinthService.Resolution(List.of(file("legacy-project", "modern.jar", "2"))), Map.of("legacy-project", "legacy.jar|1"));
        require(!Files.exists(unmanagedMods.resolve("legacy.jar")) && Files.exists(unmanagedMods.resolve("modern.jar")), "hash-detected unmanaged update replacement");
        ModInstallManager.deleteTree(unmanagedRoot);
    }

    private static void verifyRollback() throws Exception {
        Path root = Files.createTempDirectory("aeromc-mod-rollback-");
        Path mods = Files.createDirectories(root.resolve("mods"));
        Path existing = Files.writeString(mods.resolve("existing.jar"), "original", StandardCharsets.UTF_8);
        Path blockedManifest = Files.createDirectories(mods.resolve(".aeromc-installed.properties"));
        Files.writeString(blockedManifest.resolve("keep"), "forces final manifest write to fail", StandardCharsets.UTF_8);
        ModInstallManager installer = new ModInstallManager((file, directory) -> Files.writeString(directory.resolve(file.filename()), "replacement", StandardCharsets.UTF_8));
        boolean failed = false;
        try { installer.installLocal(root, "mods", new ModrinthService.Resolution(List.of(file("good", "existing.jar", "2")))); }
        catch (Exception expected) { failed = true; }
        require(failed, "failure propagated");
        require("original".equals(Files.readString(existing, StandardCharsets.UTF_8)), "original restored after commit failure");
        ModInstallManager.deleteTree(root);
    }

    private static ModrinthService.ResolvedFile file(String project, String filename, String version) {
        return new ModrinthService.ResolvedFile(project, project + "-version", version, version, filename, URI.create("https://example.invalid/" + filename), "00", 1);
    }

    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
