package com.aerogroup.mcpanel;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Bu işletim sistemi kullanıcısına ve cihaza bağlı, parolasız açılan yerel kimlik kasası. */
public final class DeviceCredentialStore {
    public enum Kind {
        EXAROTON("exaroton", "auto-exaroton.secret"),
        DISCORD("discord", "auto-discord.secret");

        private final String purpose;
        private final String fileName;
        Kind(String purpose, String fileName) { this.purpose = purpose; this.fileName = fileName; }
    }

    private static final Path DIRECTORY = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    private DeviceCredentialStore() { }

    public static boolean exists(Kind kind) { return Files.isRegularFile(file(kind)); }
    public static void save(Kind kind, String value) throws Exception { save(file(kind), kind.purpose, value, deviceFingerprint()); }
    public static String load(Kind kind) throws Exception { return load(file(kind), kind.purpose, deviceFingerprint()); }
    public static void delete(Kind kind) throws IOException { Files.deleteIfExists(file(kind)); }
    public static void deleteAll() throws IOException {
        IOException failure = null;
        for (Kind kind : Kind.values()) try { delete(kind); } catch (IOException error) { failure = error; }
        if (failure != null) throw failure;
    }

    static void save(Path file, String purpose, String value, String fingerprint) throws Exception {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Gizli bilgi boş olamaz.");
        byte[] salt = random(16), iv = random(12), encrypted = null;
        try {
            SecretKey key = derive(fingerprint, purpose, salt, ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(purpose));
            encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("iterations", Integer.toString(ITERATIONS));
            properties.setProperty("salt", Base64.getEncoder().encodeToString(salt));
            properties.setProperty("iv", Base64.getEncoder().encodeToString(iv));
            properties.setProperty("data", Base64.getEncoder().encodeToString(encrypted));
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".aeromc-secret-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "AeroMC device-bound credential"); }
                restrict(temporary);
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                restrict(file);
            } finally { Files.deleteIfExists(temporary); }
        } finally {
            Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    static String load(Path file, String purpose, String fingerprint) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        byte[] salt = Base64.getDecoder().decode(required(properties, "salt"));
        byte[] iv = Base64.getDecoder().decode(required(properties, "iv"));
        byte[] encrypted = Base64.getDecoder().decode(required(properties, "data"));
        byte[] clear = null;
        try {
            int iterations = Integer.parseInt(properties.getProperty("iterations", Integer.toString(ITERATIONS)));
            SecretKey key = derive(fingerprint, purpose, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(purpose));
            clear = cipher.doFinal(encrypted);
            return new String(clear, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); Arrays.fill(encrypted, (byte) 0); if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    private static Path file(Kind kind) { return DIRECTORY.resolve(Objects.requireNonNull(kind).fileName); }
    private static SecretKey derive(String fingerprint, String purpose, byte[] salt, int iterations) throws Exception {
        char[] input = (Objects.requireNonNull(fingerprint) + "\u0000AeroMC\u0000" + purpose).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(input, salt, iterations, 256);
        try { return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES"); }
        finally { Arrays.fill(input, '\0'); spec.clearPassword(); }
    }
    private static byte[] aad(String purpose) { return ("AeroMC/device-vault/v1/" + purpose).getBytes(StandardCharsets.UTF_8); }
    private static byte[] random(int size) { byte[] value = new byte[size]; RANDOM.nextBytes(value); return value; }
    private static String required(Properties values, String key) throws IOException { String value = values.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Kimlik kasası bozuk."); return value; }
    private static void restrict(Path file) { try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }

    private static String deviceFingerprint() throws Exception {
        String machine = firstReadable(Path.of("/etc/machine-id"), Path.of("/var/lib/dbus/machine-id"));
        if (machine.isBlank()) machine = String.join("|", System.getProperty("os.name", ""), System.getenv().getOrDefault("COMPUTERNAME", ""), System.getenv().getOrDefault("HOSTNAME", ""));
        String identity = machine.strip() + "|" + System.getProperty("user.name", "") + "|" + Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    private static String firstReadable(Path... files) {
        for (Path file : files) try { if (Files.isRegularFile(file)) { String value = Files.readString(file, StandardCharsets.UTF_8).trim(); if (!value.isBlank()) return value; } } catch (IOException ignored) { }
        return "";
    }
}
