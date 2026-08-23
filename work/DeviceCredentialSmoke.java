package com.aerogroup.mcpanel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        System.out.println("device-credential-vault-ok");
    }
    private static void require(boolean condition, String feature) { if (!condition) throw new IllegalStateException("Smoke test failed: " + feature); }
    private static void requireFails(Checked action, String feature) throws Exception {
        boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; }
        require(failed, feature);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
