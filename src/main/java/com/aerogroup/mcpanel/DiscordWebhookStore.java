package com.aerogroup.mcpanel;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Discord webhook URL'sini ana paroladan türetilen AES-256-GCM anahtarıyla saklar. */
public final class DiscordWebhookStore {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "discord-webhook.secret");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000, MIN_ITERATIONS = 100_000, MAX_ITERATIONS = 1_000_000;
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final byte[] V2_AAD = "AeroMC/manual-token/v2/discord".getBytes(StandardCharsets.UTF_8);
    private DiscordWebhookStore() { }

    public static boolean exists() { return Files.isRegularFile(FILE, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(FILE); }
    public static void save(String value, char[] password) throws Exception { save(FILE, value, password); }
    public static String load(char[] password) throws Exception { return load(FILE, password); }
    public static void delete() throws IOException { Files.deleteIfExists(FILE); }

    static void save(Path file, String value, char[] password) throws Exception {
        DiscordNotificationEngine.validateWebhook(value); validatePassword(password, true);
        byte[] clear = value.getBytes(StandardCharsets.UTF_8), salt = random(16), iv = random(12), encrypted = null;
        try {
            SecretKey key = derive(password, salt, ITERATIONS); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); cipher.updateAAD(V2_AAD); encrypted = cipher.doFinal(clear);
            Properties properties = new Properties(); properties.setProperty("version", "2"); properties.setProperty("kdf", "PBKDF2WithHmacSHA256"); properties.setProperty("cipher", "AES/GCM/NoPadding"); properties.setProperty("iterations", Integer.toString(ITERATIONS)); properties.setProperty("salt", Base64.getEncoder().encodeToString(salt)); properties.setProperty("iv", Base64.getEncoder().encodeToString(iv)); properties.setProperty("data", Base64.getEncoder().encodeToString(encrypted));
            prepareDirectory(file.getParent()); if (Files.isSymbolicLink(file)) throw new IOException("Discord webhook kasası simgesel bağlantı olamaz.");
            Path temporary = Files.createTempFile(file.getParent(), ".discord-vault-", ".tmp");
            try { try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "AeroGuard V2.3 encrypted Discord webhook"); } restrict(temporary, false); try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } restrict(file, false); }
            finally { Files.deleteIfExists(temporary); }
        } finally { Arrays.fill(clear, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); if (encrypted != null) Arrays.fill(encrypted, (byte) 0); }
    }

    static String load(Path file, char[] password) throws Exception {
        validatePassword(password, false); if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Discord webhook kasası geçersiz."); requireSafeDirectory(file.getParent()); if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Discord webhook kasası güvenli boyut sınırını aştı.");
        Properties properties = new Properties(); try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        int version = integer(properties, "version"); if (version != 1 && version != 2) throw new IOException("Discord webhook kasası sürümü desteklenmiyor.");
        if (version == 2 && (!"PBKDF2WithHmacSHA256".equals(required(properties, "kdf")) || !"AES/GCM/NoPadding".equals(required(properties, "cipher")))) throw new IOException("Discord webhook kasası algoritmaları geçersiz.");
        int iterations = integer(properties, "iterations"); byte[] salt = decoded(properties, "salt"), iv = decoded(properties, "iv"), encrypted = decoded(properties, "data"), clear = null;
        try {
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || salt.length != 16 || iv.length != 12 || encrypted.length < 16 || encrypted.length > 16 * 1024) throw new IOException("Discord webhook kasası güvenlik parametreleri geçersiz.");
            SecretKey key = derive(password, salt, iterations); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv)); if (version == 2) cipher.updateAAD(V2_AAD); clear = cipher.doFinal(encrypted);
            String result = new String(clear, StandardCharsets.UTF_8); DiscordNotificationEngine.validateWebhook(result); return result;
        } finally { Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); Arrays.fill(encrypted, (byte) 0); if (clear != null) Arrays.fill(clear, (byte) 0); }
    }

    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws Exception { PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256); byte[] encoded = null; try { encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return new SecretKeySpec(encoded, "AES"); } finally { spec.clearPassword(); if (encoded != null) Arrays.fill(encoded, (byte) 0); } }
    private static void prepareDirectory(Path directory) throws IOException { if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) requireSafeDirectory(directory); else Files.createDirectories(directory); requireSafeDirectory(directory); restrict(directory, true); }
    private static void requireSafeDirectory(Path directory) throws IOException { if (directory == null || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AeroMC veri klasörü geçersiz veya simgesel bağlantı."); }
    private static String required(Properties values, String key) throws IOException { String value = values.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Discord webhook kasası bozuk."); return value; }
    private static int integer(Properties values, String key) throws IOException { try { return Integer.parseInt(required(values, key)); } catch (NumberFormatException error) { throw new IOException("Discord webhook kasası sayısal alanı bozuk.", error); } }
    private static byte[] decoded(Properties values, String key) throws IOException { try { return Base64.getDecoder().decode(required(values, key)); } catch (IllegalArgumentException error) { throw new IOException("Discord webhook kasası kodlaması bozuk.", error); } }
    private static byte[] random(int length) { byte[] value = new byte[length]; RANDOM.nextBytes(value); return value; }
    private static void validatePassword(char[] password, boolean creating) { int minimum = creating ? 12 : 8; if (password == null || password.length < minimum) throw new IllegalArgumentException("Ana parola en az " + minimum + " karakter olmalı."); if (password.length > 256) throw new IllegalArgumentException("Ana parola en fazla 256 karakter olabilir."); }
    private static void restrict(Path path, boolean directory) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
}
