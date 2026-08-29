package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/** Kullanıcının yerel sunucu yolunu ve RAM ayarını saklar. */
public final class PanelConfig {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "config.properties");
    private Path serverJar;
    private Path javaExecutable;
    private final LinkedHashSet<Path> knownServerJars = new LinkedHashSet<>();
    private int memoryMb = 2048;
    private boolean liveMapEnabled = true;
    private boolean automaticCredentialVaultEnabled;
    private boolean exarotonReadinessCheckEnabled = true;
    private boolean inGameCommandsEnabled;
    private boolean automaticUpdateCheckEnabled = true;
    private boolean featureTourCompleted;
    private String updateChannel = "stable";
    private String pterodactylPanelUrl = "";
    private String serverProfile = "friends";
    private boolean crisisModeEnabled;
    private double crisisTpsThreshold = 16.0;
    private double crisisRamThreshold = 90.0;
    private int crisisTriggerSeconds = 10;
    private int crisisRecoverySeconds = 30;
    private int crisisCooldownSeconds = 60;

    public static PanelConfig load() {
        PanelConfig config = new PanelConfig();
        if (!Files.exists(FILE)) return config;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            values.load(reader);
            String jar = values.getProperty("serverJar", "").trim();
            if (!jar.isEmpty()) { config.serverJar = Path.of(jar); config.knownServerJars.add(config.serverJar.toAbsolutePath().normalize()); }
            String java = values.getProperty("javaExecutable", "").trim();
            if (!java.isEmpty()) config.javaExecutable = Path.of(java).toAbsolutePath().normalize();
            int knownCount = Integer.parseInt(values.getProperty("knownServerCount", "0"));
            for (int i = 0; i < knownCount; i++) { String known = values.getProperty("knownServer." + i, "").trim(); if (!known.isEmpty()) config.knownServerJars.add(Path.of(known).toAbsolutePath().normalize()); }
            config.memoryMb = Integer.parseInt(values.getProperty("memoryMb", "2048"));
            config.liveMapEnabled = Boolean.parseBoolean(values.getProperty("liveMapEnabled", "true"));
            config.automaticCredentialVaultEnabled = Boolean.parseBoolean(values.getProperty("automaticCredentialVaultEnabled", "false"));
            config.exarotonReadinessCheckEnabled = Boolean.parseBoolean(values.getProperty("exarotonReadinessCheckEnabled", "true"));
            config.inGameCommandsEnabled = Boolean.parseBoolean(values.getProperty("inGameCommandsEnabled", "false"));
            config.automaticUpdateCheckEnabled = Boolean.parseBoolean(values.getProperty("automaticUpdateCheckEnabled", "true"));
            config.featureTourCompleted = Boolean.parseBoolean(values.getProperty("featureTourCompleted", "false"));
            config.updateChannel = "beta".equalsIgnoreCase(values.getProperty("updateChannel", "stable")) ? "beta" : "stable";
            config.pterodactylPanelUrl = values.getProperty("pterodactylPanelUrl", "").trim();
            config.serverProfile = values.getProperty("serverProfile", "friends").trim();
            config.crisisModeEnabled = Boolean.parseBoolean(values.getProperty("crisisModeEnabled", "false"));
            config.crisisTpsThreshold = Double.parseDouble(values.getProperty("crisisTpsThreshold", "16.0"));
            config.crisisRamThreshold = Double.parseDouble(values.getProperty("crisisRamThreshold", "90.0"));
            config.crisisTriggerSeconds = bounded(values.getProperty("crisisTriggerSeconds"), 10, 4, 60);
            config.crisisRecoverySeconds = bounded(values.getProperty("crisisRecoverySeconds"), 30, 10, 180);
            config.crisisCooldownSeconds = bounded(values.getProperty("crisisCooldownSeconds"), 60, 0, 600);
        } catch (Exception ignored) { }
        return config;
    }
    public void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        restrict(FILE.getParent(), "rwx------");
        Properties values = new Properties();
        values.setProperty("serverJar", serverJar == null ? "" : serverJar.toString());
        values.setProperty("javaExecutable", javaExecutable == null ? "" : javaExecutable.toString());
        values.setProperty("knownServerCount", Integer.toString(knownServerJars.size())); int knownIndex = 0;
        for (Path known : knownServerJars) values.setProperty("knownServer." + knownIndex++, known.toString());
        values.setProperty("memoryMb", Integer.toString(memoryMb));
        values.setProperty("liveMapEnabled", Boolean.toString(liveMapEnabled));
        values.setProperty("automaticCredentialVaultEnabled", Boolean.toString(automaticCredentialVaultEnabled));
        values.setProperty("exarotonReadinessCheckEnabled", Boolean.toString(exarotonReadinessCheckEnabled));
        values.setProperty("inGameCommandsEnabled", Boolean.toString(inGameCommandsEnabled));
        values.setProperty("automaticUpdateCheckEnabled", Boolean.toString(automaticUpdateCheckEnabled));
        values.setProperty("featureTourCompleted", Boolean.toString(featureTourCompleted));
        values.setProperty("updateChannel", updateChannel);
        values.setProperty("pterodactylPanelUrl", pterodactylPanelUrl);
        values.setProperty("serverProfile", serverProfile);
        values.setProperty("crisisModeEnabled", Boolean.toString(crisisModeEnabled));
        values.setProperty("crisisTpsThreshold", Double.toString(crisisTpsThreshold));
        values.setProperty("crisisRamThreshold", Double.toString(crisisRamThreshold));
        values.setProperty("crisisTriggerSeconds", Integer.toString(crisisTriggerSeconds));
        values.setProperty("crisisRecoverySeconds", Integer.toString(crisisRecoverySeconds));
        values.setProperty("crisisCooldownSeconds", Integer.toString(crisisCooldownSeconds));
        Path temporary = Files.createTempFile(FILE.getParent(), ".aeromc-config-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary)) { values.store(writer, "AeroMC Server Panel"); }
            restrict(temporary, "rw-------");
            try { Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING); }
            restrict(FILE, "rw-------");
        } finally { Files.deleteIfExists(temporary); }
    }
    public Path getServerJar() { return serverJar; }
    public void setServerJar(Path value) { serverJar = value == null ? null : value.toAbsolutePath().normalize(); if (serverJar != null) knownServerJars.add(serverJar); }
    public Path getJavaExecutable() { return javaExecutable; }
    public void setJavaExecutable(Path value) { javaExecutable = value == null ? null : value.toAbsolutePath().normalize(); }
    public List<Path> getKnownServerJars() { return List.copyOf(knownServerJars); }
    public int getMemoryMb() { return memoryMb; }
    public void setMemoryMb(int value) { memoryMb = value; }
    public boolean isLiveMapEnabled() { return liveMapEnabled; }
    public void setLiveMapEnabled(boolean value) { liveMapEnabled = value; }
    public boolean isAutomaticCredentialVaultEnabled() { return automaticCredentialVaultEnabled; }
    public void setAutomaticCredentialVaultEnabled(boolean value) { automaticCredentialVaultEnabled = value; }
    public boolean isExarotonReadinessCheckEnabled() { return exarotonReadinessCheckEnabled; }
    public void setExarotonReadinessCheckEnabled(boolean value) { exarotonReadinessCheckEnabled = value; }
    public boolean isInGameCommandsEnabled() { return inGameCommandsEnabled; }
    public void setInGameCommandsEnabled(boolean value) { inGameCommandsEnabled = value; }
    public boolean isAutomaticUpdateCheckEnabled() { return automaticUpdateCheckEnabled; }
    public void setAutomaticUpdateCheckEnabled(boolean value) { automaticUpdateCheckEnabled = value; }
    public boolean isFeatureTourCompleted() { return featureTourCompleted; }
    public void setFeatureTourCompleted(boolean value) { featureTourCompleted = value; }
    public String getUpdateChannel() { return updateChannel; }
    public void setUpdateChannel(String value) { updateChannel = "beta".equalsIgnoreCase(value) ? "beta" : "stable"; }
    public String getPterodactylPanelUrl() { return pterodactylPanelUrl; }
    public void setPterodactylPanelUrl(String value) { pterodactylPanelUrl = value == null ? "" : value.trim(); }
    public String getServerProfile() { return serverProfile; }
    public void setServerProfile(String value) { serverProfile = value == null || value.isBlank() ? "friends" : value.trim(); }
    public boolean isCrisisModeEnabled() { return crisisModeEnabled; }
    public void setCrisisModeEnabled(boolean value) { crisisModeEnabled = value; }
    public double getCrisisTpsThreshold() { return crisisTpsThreshold; }
    public void setCrisisTpsThreshold(double value) { crisisTpsThreshold = value; }
    public double getCrisisRamThreshold() { return crisisRamThreshold; }
    public void setCrisisRamThreshold(double value) { crisisRamThreshold = value; }
    public int getCrisisTriggerSeconds() { return crisisTriggerSeconds; }
    public void setCrisisTriggerSeconds(int value) { crisisTriggerSeconds = Math.max(4, Math.min(60, value)); }
    public int getCrisisRecoverySeconds() { return crisisRecoverySeconds; }
    public void setCrisisRecoverySeconds(int value) { crisisRecoverySeconds = Math.max(10, Math.min(180, value)); }
    public int getCrisisCooldownSeconds() { return crisisCooldownSeconds; }
    public void setCrisisCooldownSeconds(int value) { crisisCooldownSeconds = Math.max(0, Math.min(600, value)); }
    private static int bounded(String value, int fallback, int min, int max) { try { return Math.max(min, Math.min(max, Integer.parseInt(value))); } catch (Exception ignored) { return fallback; } }
    private static void restrict(Path path, String permissions) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)); } catch (IOException | UnsupportedOperationException ignored) { } }
}
