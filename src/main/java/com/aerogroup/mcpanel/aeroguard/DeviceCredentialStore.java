package com.aerogroup.mcpanel.aeroguard;

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

/** AeroGuard tarafından kullanılan, işletim sistemi kullanıcısına ve cihaza bağlı yerel kimlik kasası. */
public final class DeviceCredentialStore {
    public enum Kind {
        EXAROTON("exaroton", "auto-exaroton.secret"),
        DISCORD("discord", "auto-discord.secret"),
        PTERODACTYL("pterodactyl", "auto-pterodactyl.secret");

        private final String purpose;
        private final String fileName;
        Kind(String purpose, String fileName) { this.purpose = purpose; this.fileName = fileName; }
    }

    private static final Path DIRECTORY = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final long MAX_VAULT_BYTES = 64 * 1024;
    private static final int MAX_SECRET_BYTES = 32 * 1024;
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

    public static void save(Path file, String purpose, String value, String fingerprint) throws Exception {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Gizli bilgi boş olamaz.");
        byte[] clearValue = value.getBytes(StandardCharsets.UTF_8);
        if (clearValue.length > MAX_SECRET_BYTES) { Arrays.fill(clearValue, (byte) 0); throw new IllegalArgumentException("Gizli bilgi güvenli boyut sınırını aştı."); }
        byte[] salt = random(16), iv = random(12), encrypted = null;
        try {
            SecretKey key = derive(fingerprint, purpose, salt, ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(purpose));
            encrypted = cipher.doFinal(clearValue);
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("iterations", Integer.toString(ITERATIONS));
            properties.setProperty("salt", Base64.getEncoder().encodeToString(salt));
            properties.setProperty("iv", Base64.getEncoder().encodeToString(iv));
            properties.setProperty("data", Base64.getEncoder().encodeToString(encrypted));
            Files.createDirectories(file.getParent());
            requireSafeDirectory(file.getParent());
            if (Files.isSymbolicLink(file)) throw new IOException("Kimlik kasası simgesel bağlantı olamaz.");
            restrictDirectory(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".aeromc-secret-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "AeroMC device-bound credential"); }
                restrict(temporary);
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                restrict(file);
            } finally { Files.deleteIfExists(temporary); }
        } finally {
            Arrays.fill(clearValue, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    public static String load(Path file, String purpose, String fingerprint) throws Exception {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Kimlik kasası dosyası geçersiz.");
        requireSafeDirectory(file.toAbsolutePath().normalize().getParent());
        if (Files.size(file) > MAX_VAULT_BYTES) throw new IOException("Kimlik kasası dosyası güvenli boyut sınırını aştı.");
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        if (!"1".equals(required(properties, "version"))) throw new IOException("Kimlik kasası sürümü desteklenmiyor.");
        byte[] salt, iv, encrypted;
        try {
            salt = Base64.getDecoder().decode(required(properties, "salt"));
            iv = Base64.getDecoder().decode(required(properties, "iv"));
            encrypted = Base64.getDecoder().decode(required(properties, "data"));
        } catch (IllegalArgumentException error) { throw new IOException("Kimlik kasası kodlaması bozuk.", error); }
        byte[] clear = null;
        try {
            int iterations = Integer.parseInt(properties.getProperty("iterations", Integer.toString(ITERATIONS)));
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || salt.length != 16 || iv.length != 12 || encrypted.length < 16 || encrypted.length > MAX_SECRET_BYTES + 16)
                throw new IOException("Kimlik kasası güvenlik parametreleri geçersiz.");
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
    private static void requireSafeDirectory(Path directory) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Kimlik kasası klasörü geçersiz veya simgesel bağlantı.");
    }
    private static void restrict(Path file) { try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
    private static void restrictDirectory(Path directory) { try { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); } catch (IOException | UnsupportedOperationException ignored) { } }

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
