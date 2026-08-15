package com.aerogroup.mcpanel;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class SyncBackupSecuritySmoke {
    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("aeromc-sync-");
        Path first = Files.createDirectories(workspace.resolve("Alpha"));
        Path second = Files.createDirectories(workspace.resolve("Beta"));
        Path firstJar = Files.write(first.resolve("server.jar"), new byte[]{1});
        Path secondJar = Files.write(second.resolve("server.jar"), new byte[]{2});

        Path plugins = Files.createDirectories(first.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("ExamplePlugin.JAR")))) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write("name: ExamplePlugin\nversion: 1.0\ndepend: [Vault]\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        var scan = NextGenPane.inspectJars(first);
        require(scan.size() == 1 && "UYARI".equals(scan.get(0).level()) && scan.get(0).message().contains("Vault"), "synced compatibility scan");
        require("BİLGİ".equals(NextGenPane.inspectJars(second).get(0).level()), "empty compatibility explanation");

        Files.createDirectories(first.resolve("world")); Files.writeString(first.resolve("world/level.dat"), "alpha");
        Files.createDirectories(second.resolve("world")); Files.writeString(second.resolve("world/level.dat"), "beta");
        Path alphaBackup = NextGenPane.createOfflineBackup(first), betaBackup = NextGenPane.createOfflineBackup(second);
        require(alphaBackup.getParent().equals(first.resolve("backups")), "alpha backup isolation");
        require(betaBackup.getParent().equals(second.resolve("backups")), "beta backup isolation");
        require(NextGenPane.isSafeBackupPath(first.resolve("backups"), alphaBackup), "safe backup deletion path");
        require(!NextGenPane.isSafeBackupPath(first.resolve("backups"), first.resolve("outside.zip")), "unsafe backup deletion path");

        PanelConfig config = new PanelConfig(); config.setServerJar(firstJar); config.setServerJar(secondJar); config.save();
        require(PanelConfig.load().getKnownServerJars().size() == 2, "persistent server synchronization");

        var audit = RemoteControlService.parseAuditLine("2026-08-15T10:20:30\thasan\tbackup\tlocal\tOK");
        require("hasan".equals(audit.user()) && "backup".equals(audit.action()) && "OK".equals(audit.result()), "structured security log");
        System.out.println("sync-backup-security-ok");
    }

    private static void require(boolean value, String name) {
        if (!value) throw new IllegalStateException("Smoke test failed: " + name);
    }
}
