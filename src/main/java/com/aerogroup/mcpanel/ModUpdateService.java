package com.aerogroup.mcpanel;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Kurulu JAR'ları hash ile tanır; uyumlu güncellemeleri ve bağımlılık çakışmalarını raporlar. */
public final class ModUpdateService {
    private final ModrinthService modrinth;
    public ModUpdateService(ModrinthService modrinth) { this.modrinth = modrinth; }

    public ScanReport scan(Path serverRoot, String contentDirectory, String gameVersion, String loader) throws Exception {
        Path root = serverRoot.toAbsolutePath().normalize(), directory = root.resolve(contentDirectory).normalize();
        if (!directory.startsWith(root) || !("mods".equals(contentDirectory) || "plugins".equals(contentDirectory))) throw new SecurityException("Geçersiz içerik klasörü.");
        Files.createDirectories(directory); List<Path> jars;
        try (var files = Files.list(directory)) { jars = files.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList(); }
        LinkedHashMap<String, Path> filesByHash = new LinkedHashMap<>();
        for (Path jar : jars) filesByHash.put(ModrinthService.sha512(jar), jar);
        Map<String, ModrinthService.VersionMatch> matches = modrinth.checkUpdates(filesByHash.keySet(), gameVersion, loader);
        return analyze(root, filesByHash, matches, gameVersion, loader);
    }

    static ScanReport analyze(Path serverRoot, Map<String, Path> filesByHash, Map<String, ModrinthService.VersionMatch> matches, String gameVersion, String loader) throws IOException {
        List<UpdateItem> items = new ArrayList<>(); List<Conflict> conflicts = new ArrayList<>(); Map<String, List<UpdateItem>> byProject = new LinkedHashMap<>();
        for (var entry : filesByHash.entrySet()) {
            Path file = entry.getValue(); ModrinthService.VersionMatch match = matches.getOrDefault(entry.getKey(), new ModrinthService.VersionMatch(null, null)); ModrinthService.VersionData current = match.current(), latest = match.latest();
            Status status = current == null ? Status.UNKNOWN : latest == null ? Status.INCOMPATIBLE : match.updateAvailable() ? Status.UPDATE : Status.CURRENT;
            UpdateItem item = new UpdateItem(file, entry.getKey(), current, latest, status); items.add(item);
            if (current != null) byProject.computeIfAbsent(current.projectId(), ignored -> new ArrayList<>()).add(item);
            if (status == Status.UNKNOWN) conflicts.add(new Conflict(Severity.INFO, file.getFileName().toString(), "Modrinth'te tanınmadı; otomatik güncellenmeyecek."));
            if (status == Status.INCOMPATIBLE) conflicts.add(new Conflict(Severity.ERROR, file.getFileName().toString(), gameVersion + " / " + loader + " için uyumlu sürüm bulunamadı."));
            if (current != null && (!current.gameVersions().isEmpty() && !current.gameVersions().contains(gameVersion) || !current.loaders().isEmpty() && !current.loaders().contains(loader))) conflicts.add(new Conflict(Severity.ERROR, file.getFileName().toString(), "Kurulu sürüm hedef Minecraft/loader ile uyumlu görünmüyor."));
        }
        for (var entry : byProject.entrySet()) if (entry.getValue().size() > 1) conflicts.add(new Conflict(Severity.ERROR, entry.getKey(), "Aynı Modrinth projesine ait birden fazla JAR kurulu: " + joinFiles(entry.getValue())));

        Map<String, UpdateItem> installed = new HashMap<>(); byProject.forEach((id, values) -> installed.put(id, values.get(0))); Set<String> intendedVersionIds = new HashSet<>(); Map<String, List<String>> outputNames = new HashMap<>();
        for (UpdateItem item : items) { ModrinthService.VersionData intended = item.intended(); if (intended == null) continue; intendedVersionIds.add(intended.versionId()); if (intended.primaryFile() != null) outputNames.computeIfAbsent(intended.primaryFile().filename().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(item.filename()); }
        outputNames.forEach((name, owners) -> { if (owners.size() > 1) conflicts.add(new Conflict(Severity.ERROR, name, "Birden fazla proje aynı hedef JAR adını kullanıyor: " + String.join(", ", owners))); });

        for (UpdateItem item : items) {
            ModrinthService.VersionData intended = item.intended(); if (intended == null) continue;
            for (ModrinthService.Dependency dependency : intended.dependencies()) {
                boolean installedProject = dependency.projectId() != null && installed.containsKey(dependency.projectId()), installedVersion = dependency.versionId() != null && intendedVersionIds.contains(dependency.versionId());
                String identity = dependency.projectId() != null ? dependency.projectId() : dependency.versionId() != null ? dependency.versionId() : Objects.toString(dependency.filename(), "bilinmeyen");
                if ("required".equals(dependency.type()) && !installedProject && !installedVersion) conflicts.add(new Conflict(Severity.WARNING, item.filename(), "Zorunlu bağımlılık eksik: " + identity + " • güncellemede otomatik eklenecek."));
                if ("required".equals(dependency.type()) && dependency.versionId() != null && installedProject && !installedVersion) conflicts.add(new Conflict(Severity.WARNING, item.filename(), "Bağımlılık sürümü hizalanmalı: " + identity + " • kurulum öncesi kesin çözüm yapılacak."));
                if ("incompatible".equals(dependency.type()) && (installedProject || installedVersion)) conflicts.add(new Conflict(Severity.ERROR, item.filename(), "Uyumsuz proje birlikte kurulu: " + identity));
            }
        }

        try {
            Set<String> scannedNames = new HashSet<>(); items.forEach(item -> scannedNames.add(item.filename()));
            for (NextGenPane.CompatResult result : NextGenPane.inspectJars(serverRoot)) if (scannedNames.contains(result.file()) && ("HATA".equals(result.level()) || "UYARI".equals(result.level()))) conflicts.add(new Conflict("HATA".equals(result.level()) ? Severity.ERROR : Severity.WARNING, result.file(), result.message()));
        } catch (IOException error) { conflicts.add(new Conflict(Severity.WARNING, "JAR incelemesi", error.getMessage())); }

        items.sort(Comparator.comparing((UpdateItem item) -> item.status() != Status.UPDATE).thenComparing(UpdateItem::filename));
        conflicts.sort(Comparator.comparingInt(value -> switch (value.severity()) { case ERROR -> 0; case WARNING -> 1; case INFO -> 2; }));
        return new ScanReport(List.copyOf(items), List.copyOf(conflicts));
    }

    private static String joinFiles(List<UpdateItem> items) { return String.join(", ", items.stream().map(UpdateItem::filename).toList()); }

    public enum Status { UPDATE, CURRENT, UNKNOWN, INCOMPATIBLE }
    public enum Severity { ERROR, WARNING, INFO }
    public record UpdateItem(Path file, String sha512, ModrinthService.VersionData current, ModrinthService.VersionData latest, Status status) {
        public String filename() { return file.getFileName().toString(); }
        public ModrinthService.VersionData intended() { return latest != null ? latest : current; }
        public boolean updateAvailable() { return status == Status.UPDATE && latest != null && latest.primaryFile() != null; }
        public String projectId() { return current == null ? "" : current.projectId(); }
        public String currentVersion() { return current == null ? "?" : current.versionNumber(); }
        public String latestVersion() { return latest == null ? "-" : latest.versionNumber(); }
    }
    public record Conflict(Severity severity, String source, String message) { }
    public record ScanReport(List<UpdateItem> items, List<Conflict> conflicts) {
        public List<UpdateItem> updates() { return items.stream().filter(UpdateItem::updateAvailable).toList(); }
        public boolean hasErrors() { return conflicts.stream().anyMatch(value -> value.severity() == Severity.ERROR); }
        public Map<String, String> installedFilesByProject() { LinkedHashMap<String, String> result = new LinkedHashMap<>(); for (UpdateItem item : items) if (!item.projectId().isBlank()) result.putIfAbsent(item.projectId(), item.filename() + "|" + item.currentVersion()); return result; }
    }
}
