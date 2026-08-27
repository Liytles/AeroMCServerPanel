package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.DeviceCredentialStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class DeviceCredentialSmoke {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("aeromc-device-vault-");
        Path file = directory.resolve("credential.secret");
        String secret = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz";
        DeviceCredentialStore.save(file, "discord", secret, "device-user-a");
        require(Files.isRegularFile(file), "vault file created");
        require(secret.equals(DeviceCredentialStore.load(file, "discord", "device-user-a")), "same device decrypts");
        require(!Files.readString(file, StandardCharsets.UTF_8).contains(secret), "secret is not stored as plain text");
        requireFails(() -> DeviceCredentialStore.load(file, "discord", "device-user-b"), "different device rejected");
        requireFails(() -> DeviceCredentialStore.load(file, "exaroton", "device-user-a"), "different credential purpose rejected");
        Properties tampered = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { tampered.load(reader); }
        tampered.setProperty("iterations", "2000000000");
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { tampered.store(writer, "tampered"); }
        requireFails(() -> DeviceCredentialStore.load(file, "discord", "device-user-a"), "hostile iteration count rejected");
        try {
            Path target = Files.writeString(directory.resolve("outside.secret"), "secret", StandardCharsets.UTF_8), link = directory.resolve("linked.secret");
            Files.createSymbolicLink(link, target);
            requireFails(() -> DeviceCredentialStore.load(link, "discord", "device-user-a"), "vault symlink rejected");
        } catch (UnsupportedOperationException ignored) { }
        System.out.println("device-credential-vault-ok");
    }
    private static void require(boolean condition, String feature) { if (!condition) throw new IllegalStateException("Smoke test failed: " + feature); }
    private static void requireFails(Checked action, String feature) throws Exception {
        boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; }
        require(failed, feature);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
