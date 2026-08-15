package com.aerogroup.mcpanel;

import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/** Kullanıcının yerel sunucu yolunu ve RAM ayarını saklar. */
public final class PanelConfig {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "config.properties");
    private Path serverJar;
    private final LinkedHashSet<Path> knownServerJars = new LinkedHashSet<>();
    private int memoryMb = 2048;
    private boolean liveMapEnabled = true;
    private boolean crisisModeEnabled;
    private double crisisTpsThreshold = 16.0;
    private double crisisRamThreshold = 90.0;

    public static PanelConfig load() {
        PanelConfig config = new PanelConfig();
        if (!Files.exists(FILE)) return config;
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            values.load(reader);
            String jar = values.getProperty("serverJar", "").trim();
            if (!jar.isEmpty()) { config.serverJar = Path.of(jar); config.knownServerJars.add(config.serverJar.toAbsolutePath().normalize()); }
            int knownCount = Integer.parseInt(values.getProperty("knownServerCount", "0"));
            for (int i = 0; i < knownCount; i++) { String known = values.getProperty("knownServer." + i, "").trim(); if (!known.isEmpty()) config.knownServerJars.add(Path.of(known).toAbsolutePath().normalize()); }
            config.memoryMb = Integer.parseInt(values.getProperty("memoryMb", "2048"));
            config.liveMapEnabled = Boolean.parseBoolean(values.getProperty("liveMapEnabled", "true"));
            config.crisisModeEnabled = Boolean.parseBoolean(values.getProperty("crisisModeEnabled", "false"));
            config.crisisTpsThreshold = Double.parseDouble(values.getProperty("crisisTpsThreshold", "16.0"));
            config.crisisRamThreshold = Double.parseDouble(values.getProperty("crisisRamThreshold", "90.0"));
        } catch (Exception ignored) { }
        return config;
    }
    public void save() throws IOException {
        Files.createDirectories(FILE.getParent());
        Properties values = new Properties();
        values.setProperty("serverJar", serverJar == null ? "" : serverJar.toString());
        values.setProperty("knownServerCount", Integer.toString(knownServerJars.size())); int knownIndex = 0;
        for (Path known : knownServerJars) values.setProperty("knownServer." + knownIndex++, known.toString());
        values.setProperty("memoryMb", Integer.toString(memoryMb));
        values.setProperty("liveMapEnabled", Boolean.toString(liveMapEnabled));
        values.setProperty("crisisModeEnabled", Boolean.toString(crisisModeEnabled));
        values.setProperty("crisisTpsThreshold", Double.toString(crisisTpsThreshold));
        values.setProperty("crisisRamThreshold", Double.toString(crisisRamThreshold));
        try (Writer writer = Files.newBufferedWriter(FILE)) { values.store(writer, "AeroMC Server Panel"); }
    }
    public Path getServerJar() { return serverJar; }
    public void setServerJar(Path value) { serverJar = value == null ? null : value.toAbsolutePath().normalize(); if (serverJar != null) knownServerJars.add(serverJar); }
    public List<Path> getKnownServerJars() { return List.copyOf(knownServerJars); }
    public int getMemoryMb() { return memoryMb; }
    public void setMemoryMb(int value) { memoryMb = value; }
    public boolean isLiveMapEnabled() { return liveMapEnabled; }
    public void setLiveMapEnabled(boolean value) { liveMapEnabled = value; }
    public boolean isCrisisModeEnabled() { return crisisModeEnabled; }
    public void setCrisisModeEnabled(boolean value) { crisisModeEnabled = value; }
    public double getCrisisTpsThreshold() { return crisisTpsThreshold; }
    public void setCrisisTpsThreshold(double value) { crisisTpsThreshold = value; }
    public double getCrisisRamThreshold() { return crisisRamThreshold; }
    public void setCrisisRamThreshold(double value) { crisisRamThreshold = value; }
}
