package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

/** Mod dosyalarını doğrulandıktan sonra yedek, manifest ve geri alma korumasıyla kurar. */
public final class ModInstallManager {
    private static final String MANIFEST = ".aeromc-installed.properties";
    private final FileDownloader downloader;
    public ModInstallManager(ModrinthService modrinth) { this(modrinth::downloadVerified); }
    ModInstallManager(FileDownloader downloader) { this.downloader = downloader; }

    public InstallReport installLocal(Path serverRoot, String contentDirectory, ModrinthService.Resolution resolution) throws Exception {
        return installLocal(serverRoot, contentDirectory, resolution, Map.of());
    }

    public InstallReport installLocal(Path serverRoot, String contentDirectory, ModrinthService.Resolution resolution, Map<String, String> detectedInstalledFiles) throws Exception {
        Path root = serverRoot.toAbsolutePath().normalize(), target = root.resolve(contentDirectory).normalize(); if (!target.startsWith(root) || !("mods".equals(contentDirectory) || "plugins".equals(contentDirectory))) throw new SecurityException("Geçersiz içerik klasörü.");
        Files.createDirectories(target); Path staging = Files.createTempDirectory(root, ".aeromc-install-"), rollback = Files.createDirectories(staging.resolve("rollback")); List<Path> installed = new ArrayList<>(); Properties previousManifest = load(target.resolve(MANIFEST)); detectedInstalledFiles.forEach(previousManifest::setProperty); Properties nextManifest = new Properties(); nextManifest.putAll(previousManifest); Path backup = null;
        try {
            LinkedHashMap<ModrinthService.ResolvedFile, Path> downloads = new LinkedHashMap<>(); Set<String> filenames = new HashSet<>();
            for (ModrinthService.ResolvedFile file : resolution.files()) { if (!filenames.add(file.filename().toLowerCase(Locale.ROOT))) throw new IOException("İki proje aynı JAR dosya adını kullanıyor: " + file.filename()); Path bucket = Files.createDirectories(staging.resolve("download-" + downloads.size())); downloads.put(file, downloader.download(file, bucket)); }
            backup = createContentBackup(root, target, contentDirectory); Set<Path> affected = new LinkedHashSet<>();
            for (var entry : downloads.entrySet()) { ModrinthService.ResolvedFile file = entry.getKey(); Path destination = safeTarget(target, file.filename()); affected.add(destination); String old = previousManifest.getProperty(file.projectId(), ""); if (!old.isBlank()) { String oldName = old.split("\\|", 2)[0]; Path obsolete = safeTarget(target, oldName); if (!obsolete.equals(destination) && Files.isRegularFile(obsolete)) affected.add(obsolete); } }
            for (Path file : affected) if (Files.isRegularFile(file)) Files.copy(file, rollback.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            for (var entry : downloads.entrySet()) { ModrinthService.ResolvedFile file = entry.getKey(); Path destination = safeTarget(target, file.filename()); String old = previousManifest.getProperty(file.projectId(), ""); if (!old.isBlank()) { Path obsolete = safeTarget(target, old.split("\\|", 2)[0]); if (!obsolete.equals(destination)) Files.deleteIfExists(obsolete); } Files.move(entry.getValue(), destination, StandardCopyOption.REPLACE_EXISTING); installed.add(destination); nextManifest.setProperty(file.projectId(), destination.getFileName() + "|" + file.versionNumber()); }
            saveAtomic(target.resolve(MANIFEST), nextManifest); return new InstallReport(List.copyOf(installed), backup, resolution.dependencyCount(), true);
        } catch (Exception error) {
            for (Path path : installed) Files.deleteIfExists(path); if (Files.isDirectory(rollback)) try (var files = Files.list(rollback)) { for (Path file : files.toList()) Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING); } throw error;
        } finally { deleteTree(staging); }
    }

    public List<Path> downloadToTemporary(ModrinthService.Resolution resolution, Path temporaryDirectory) throws Exception { Files.createDirectories(temporaryDirectory); List<Path> result = new ArrayList<>(); List<Path> buckets = new ArrayList<>(); Set<String> filenames = new HashSet<>(); try { for (ModrinthService.ResolvedFile file : resolution.files()) { if (!filenames.add(file.filename().toLowerCase(Locale.ROOT))) throw new IOException("İki proje aynı JAR dosya adını kullanıyor: " + file.filename()); Path bucket = Files.createDirectories(temporaryDirectory.resolve("download-" + result.size())); buckets.add(bucket); result.add(downloader.download(file, bucket)); } return result; } catch (Exception error) { for (Path bucket : buckets) deleteTree(bucket); throw error; } }

    static Path createContentBackup(Path serverRoot, Path target, String type) throws IOException {
        Path directory = serverRoot.resolve(".aeromc-content-backups"); Files.createDirectories(directory); Path output = directory.resolve(type + "-before-install-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) { if (Files.isDirectory(target)) try (var files = Files.list(target)) { for (Path file : files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) { zip.putNextEntry(new ZipEntry(file.getFileName().toString())); Files.copy(file, zip); zip.closeEntry(); } } } return output;
    }
    private static Path safeTarget(Path directory, String filename) { Path target = directory.resolve(Path.of(filename).getFileName()).normalize(); if (!target.getParent().equals(directory) || !target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) throw new SecurityException("Güvensiz mod dosya adı."); return target; }
    private static Properties load(Path file) { Properties values = new Properties(); try { if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); } } catch (IOException ignored) { } return values; }
    private static void saveAtomic(Path file, Properties values) throws IOException { Path temporary = Files.createTempFile(file.getParent(), ".aeromc-manifest-", ".tmp"); try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "AeroMC managed Modrinth projects"); } try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } }
    static void deleteTree(Path root) { if (root == null || !Files.exists(root)) return; try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } catch (IOException ignored) { } }
    @FunctionalInterface interface FileDownloader { Path download(ModrinthService.ResolvedFile file, Path directory) throws Exception; }
    public record InstallReport(List<Path> installedFiles, Path backup, int dependencies, boolean rollbackProtected) { }
}
