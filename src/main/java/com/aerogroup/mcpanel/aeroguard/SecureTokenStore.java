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
import java.security.SecureRandom;
import java.util.*;

/** AeroGuard Exaroton API anahtarını kullanıcı parolasından türetilen AES-GCM anahtarıyla saklar. */
public final class SecureTokenStore {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton.token");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int VERSION = 2;
    private static final int ITERATIONS = 600_000;
    private static final int MIN_ITERATIONS = 100_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int MAX_SECRET_BYTES = 16 * 1024;
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final byte[] V2_AAD = "AeroMC/manual-token/v2/exaroton".getBytes(StandardCharsets.UTF_8);
    private SecureTokenStore() { }

    public static boolean exists() { return Files.isRegularFile(FILE, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(FILE); }

    public static void save(String token, char[] password) throws Exception {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("API anahtarı boş olamaz.");
        validatePassword(password, true);
        byte[] clear = token.getBytes(StandardCharsets.UTF_8);
        if (clear.length > MAX_SECRET_BYTES) { Arrays.fill(clear, (byte) 0); throw new IllegalArgumentException("API anahtarı güvenli boyut sınırını aştı."); }
        byte[] salt = random(16), iv = random(12), encrypted = null;
        try {
            SecretKey key = derive(password, salt, ITERATIONS);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            cipher.updateAAD(V2_AAD);
            encrypted = cipher.doFinal(clear);
            Properties values = new Properties();
            values.setProperty("version", Integer.toString(VERSION));
            values.setProperty("kdf", "PBKDF2WithHmacSHA256");
            values.setProperty("cipher", "AES/GCM/NoPadding");
            values.setProperty("iterations", Integer.toString(ITERATIONS));
            values.setProperty("salt", Base64.getEncoder().encodeToString(salt));
            values.setProperty("iv", Base64.getEncoder().encodeToString(iv));
            values.setProperty("data", Base64.getEncoder().encodeToString(encrypted));
            prepareDirectory();
            if (Files.isSymbolicLink(FILE)) throw new IOException("API anahtarı kasası simgesel bağlantı olamaz.");
            Path temporary = Files.createTempFile(FILE.getParent(), ".aeromc-token-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "AeroGuard V2.3 encrypted credential"); }
                restrict(temporary, false);
                try { Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING); }
                restrict(FILE, false);
            } finally { Files.deleteIfExists(temporary); }
        } finally {
            Arrays.fill(clear, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    public static String load(char[] password) throws Exception {
        validatePassword(password, false);
        if (Files.isSymbolicLink(FILE) || !Files.isRegularFile(FILE, LinkOption.NOFOLLOW_LINKS)) throw new IOException("API anahtarı kasası geçersiz.");
        requireSafeDirectory(FILE.getParent());
        if (Files.size(FILE) > MAX_FILE_BYTES) throw new IOException("API anahtarı kasası güvenli boyut sınırını aştı.");
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { values.load(reader); }
        int version = integer(values, "version");
        if (version != 1 && version != VERSION) throw new IOException("API anahtarı kasası sürümü desteklenmiyor.");
        if (version == VERSION && (!"PBKDF2WithHmacSHA256".equals(required(values, "kdf")) || !"AES/GCM/NoPadding".equals(required(values, "cipher"))))
            throw new IOException("API anahtarı kasası algoritmaları geçersiz.");
        int iterations = integer(values, "iterations");
        byte[] salt = decoded(values, "salt"), iv = decoded(values, "iv"), encrypted = decoded(values, "data"), clear = null;
        try {
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || salt.length != 16 || iv.length != 12 || encrypted.length < 16 || encrypted.length > MAX_SECRET_BYTES + 16)
                throw new IOException("API anahtarı kasası güvenlik parametreleri geçersiz.");
            SecretKey key = derive(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            if (version == VERSION) cipher.updateAAD(V2_AAD);
            clear = cipher.doFinal(encrypted);
            return new String(clear, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); Arrays.fill(encrypted, (byte) 0);
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    public static void delete() throws IOException { Files.deleteIfExists(FILE); }

    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256); byte[] encoded = null;
        try { encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return new SecretKeySpec(encoded, "AES"); }
        finally { spec.clearPassword(); if (encoded != null) Arrays.fill(encoded, (byte) 0); }
    }
    private static void prepareDirectory() throws IOException {
        Path directory = FILE.getParent();
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) requireSafeDirectory(directory); else Files.createDirectories(directory);
        requireSafeDirectory(directory); restrict(directory, true);
    }
    private static void requireSafeDirectory(Path directory) throws IOException {
        if (directory == null || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AeroMC veri klasörü geçersiz veya simgesel bağlantı.");
    }
    private static String required(Properties values, String key) throws IOException { String value = values.getProperty(key); if (value == null || value.isBlank()) throw new IOException("API anahtarı kasası bozuk."); return value; }
    private static int integer(Properties values, String key) throws IOException { try { return Integer.parseInt(required(values, key)); } catch (NumberFormatException error) { throw new IOException("API anahtarı kasası sayısal alanı bozuk.", error); } }
    private static byte[] decoded(Properties values, String key) throws IOException { try { return Base64.getDecoder().decode(required(values, key)); } catch (IllegalArgumentException error) { throw new IOException("API anahtarı kasası kodlaması bozuk.", error); } }
    private static byte[] random(int size) { byte[] value = new byte[size]; RANDOM.nextBytes(value); return value; }
    private static void validatePassword(char[] password, boolean creating) {
        int minimum = creating ? 12 : 8;
        if (password == null || password.length < minimum) throw new IllegalArgumentException("Ana parola en az " + minimum + " karakter olmalı.");
        if (password.length > 256) throw new IllegalArgumentException("Ana parola en fazla 256 karakter olabilir.");
    }
    private static void restrict(Path path, boolean directory) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
}
