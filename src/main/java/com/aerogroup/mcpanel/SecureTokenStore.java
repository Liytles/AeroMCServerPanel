package com.aerogroup.mcpanel;

import javax.crypto.*;
import javax.crypto.spec.*;
import javax.crypto.SecretKeyFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Exaroton API anahtarını kullanıcı parolasından türetilen AES-GCM anahtarıyla saklar. */
public final class SecureTokenStore {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton.token");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210_000;
    private SecureTokenStore() { }

    public static boolean exists() { return Files.isRegularFile(FILE); }
    public static void save(String token, char[] password) throws Exception {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("API anahtarı boş olamaz.");
        validatePassword(password);
        byte[] salt = random(16), iv = random(12);
        SecretKey key = derive(password, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        Properties values = new Properties();
        values.setProperty("version", "1"); values.setProperty("iterations", Integer.toString(ITERATIONS));
        values.setProperty("salt", Base64.getEncoder().encodeToString(salt)); values.setProperty("iv", Base64.getEncoder().encodeToString(iv)); values.setProperty("data", Base64.getEncoder().encodeToString(encrypted));
        Files.createDirectories(FILE.getParent());
        try (var writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) { values.store(writer, "Encrypted AeroMC credential"); }
        try { Files.setPosixFilePermissions(FILE, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
        Arrays.fill(encrypted, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0);
    }
    public static String load(char[] password) throws Exception {
        validatePassword(password);
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { values.load(reader); }
        byte[] salt = Base64.getDecoder().decode(values.getProperty("salt")); byte[] iv = Base64.getDecoder().decode(values.getProperty("iv")); byte[] encrypted = Base64.getDecoder().decode(values.getProperty("data"));
        int iterations = Integer.parseInt(values.getProperty("iterations", Integer.toString(ITERATIONS)));
        SecretKey key = derive(password, salt, iterations);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] clear = cipher.doFinal(encrypted); String token = new String(clear, StandardCharsets.UTF_8);
        Arrays.fill(clear, (byte) 0); Arrays.fill(encrypted, (byte) 0); Arrays.fill(salt, (byte) 0); Arrays.fill(iv, (byte) 0); return token;
    }
    public static void delete() throws Exception { Files.deleteIfExists(FILE); }
    private static SecretKey derive(char[] password, byte[] salt) throws Exception { return derive(password, salt, ITERATIONS); }
    private static SecretKey derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES"); }
        finally { spec.clearPassword(); }
    }
    private static byte[] random(int size) { byte[] value = new byte[size]; RANDOM.nextBytes(value); return value; }
    private static void validatePassword(char[] password) { if (password == null || password.length < 8) throw new IllegalArgumentException("Ana parola en az 8 karakter olmalı."); }
}
