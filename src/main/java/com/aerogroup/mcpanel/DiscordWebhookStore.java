package com.aerogroup.mcpanel;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Discord webhook URL'sini ana paroladan türetilen AES-256-GCM anahtarıyla saklar. */
public final class DiscordWebhookStore {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "discord-webhook.secret");
    private static final SecureRandom RANDOM = new SecureRandom(); private static final int ITERATIONS = 210_000;
    private DiscordWebhookStore() { }
    public static boolean exists() { return Files.isRegularFile(FILE); }
    public static void save(String value, char[] password) throws Exception { Files.createDirectories(FILE.getParent()); try { Files.setPosixFilePermissions(FILE.getParent(), PosixFilePermissions.fromString("rwx------")); } catch (UnsupportedOperationException ignored) { } save(FILE, value, password); }
    public static String load(char[] password) throws Exception { return load(FILE, password); }
    public static void delete() throws Exception { Files.deleteIfExists(FILE); }
    static void save(Path file, String value, char[] password) throws Exception {
        DiscordNotificationEngine.validateWebhook(value); validatePassword(password); byte[] salt = random(16), iv = random(12); SecretKey key = derive(password, salt, ITERATIONS); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        Properties properties = new Properties(); properties.setProperty("version", "1"); properties.setProperty("iterations", Integer.toString(ITERATIONS)); properties.setProperty("salt", Base64.getEncoder().encodeToString(salt)); properties.setProperty("iv", Base64.getEncoder().encodeToString(iv)); properties.setProperty("data", Base64.getEncoder().encodeToString(encrypted)); Files.createDirectories(file.getParent()); try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { properties.store(writer, "Encrypted AeroMC Discord webhook"); } try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { } finally { Arrays.fill(encrypted, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); }
    }
    static String load(Path file, char[] password) throws Exception {
        validatePassword(password); Properties properties = new Properties(); try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); } byte[] salt = Base64.getDecoder().decode(properties.getProperty("salt")), iv = Base64.getDecoder().decode(properties.getProperty("iv")), encrypted = Base64.getDecoder().decode(properties.getProperty("data")); SecretKey key = derive(password, salt, Integer.parseInt(properties.getProperty("iterations", Integer.toString(ITERATIONS)))); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv)); byte[] clear = cipher.doFinal(encrypted); try { String result = new String(clear, StandardCharsets.UTF_8); DiscordNotificationEngine.validateWebhook(result); return result; } finally { Arrays.fill(clear, (byte) 0); Arrays.fill(encrypted, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); }
    }
    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws Exception { PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256); try { return new SecretKeySpec(javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES"); } finally { spec.clearPassword(); } }
    private static byte[] random(int length) { byte[] value = new byte[length]; RANDOM.nextBytes(value); return value; }
    private static void validatePassword(char[] password) { if (password == null || password.length < 8) throw new IllegalArgumentException("Ana parola en az 8 karakter olmalı."); }
}
