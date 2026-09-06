package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.MasterPasswordManager;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** Password-encrypted, intentionally whitelisted AeroMC preferences transfer. */
public final class AeroMCSettingsTransfer {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000;
    private static final byte[] AAD = "AeroMC/settings-transfer/v1".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_TOTAL = 8 * 1024 * 1024, MAX_FILE = 1024 * 1024;
    private static final Set<String> FILES = Set.of("config.properties", "ui.properties", "favorites.properties", "scheduled-jobs.properties", "discord-notifications.properties", "exaroton-credit-guard.properties", "exaroton-automation.properties", "player-history.properties", "spark-report-history.properties", "crisis-history.properties", "notification-settings.properties", "exaroton.token", "discord-webhook.secret");
    private AeroMCSettingsTransfer() { }

    public static void exportTo(Path destination, char[] password) throws Exception {
        if (destination == null) throw new IllegalArgumentException("Aktarım dosyası seçilmedi.");
        Path target = destination.toAbsolutePath().normalize(); if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) throw new IOException("Aktarım hedefi simgesel bağlantı olamaz.");
        Properties payload = new Properties(); payload.setProperty("format", "AeroMC-Settings"); payload.setProperty("version", "1");
        int total = 0;
        for (String name : FILES) { Path source = MasterPasswordManager.dataDirectory().resolve(name); if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) continue; long size = Files.size(source); if (size > MAX_FILE || total + size > MAX_TOTAL) throw new IOException("Aktarım için ayar dosyası güvenli boyut sınırını aştı: " + name); byte[] bytes = Files.readAllBytes(source); total += bytes.length; payload.setProperty("file." + name, Base64.getEncoder().encodeToString(bytes)); Arrays.fill(bytes, (byte) 0); }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(); payload.store(raw, "AeroMC Settings Transfer"); byte[] clear = raw.toByteArray(); byte[] salt = random(16), iv = random(12), encrypted = null;
        try { encrypted = encrypt(clear, password, salt, iv); Properties envelope = new Properties(); envelope.setProperty("format", "AeroMC-Settings-Backup"); envelope.setProperty("version", "1"); envelope.setProperty("iterations", Integer.toString(ITERATIONS)); envelope.setProperty("salt", Base64.getEncoder().encodeToString(salt)); envelope.setProperty("iv", Base64.getEncoder().encodeToString(iv)); envelope.setProperty("data", Base64.getEncoder().encodeToString(encrypted)); write(target, envelope); }
        finally { wipe(clear); wipe(salt); wipe(iv); wipe(encrypted); }
    }
    public static int importFrom(Path source, char[] password) throws Exception {
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) throw new IOException("Geçerli bir AeroMC ayar aktarım dosyası seç."); if (Files.size(source) > MAX_TOTAL * 2L) throw new IOException("Aktarım dosyası çok büyük.");
        Properties envelope = new Properties(); try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) { envelope.load(reader); }
        if (!"AeroMC-Settings-Backup".equals(envelope.getProperty("format")) || !"1".equals(envelope.getProperty("version"))) throw new IOException("Bu dosya geçerli bir AeroMC aktarımı değil.");
        int iterations = Integer.parseInt(envelope.getProperty("iterations", "0")); if (iterations < 100_000 || iterations > 1_000_000) throw new IOException("Aktarım şifreleme parametreleri geçersiz.");
        byte[] salt = decode(envelope, "salt"), iv = decode(envelope, "iv"), data = decode(envelope, "data"), clear = null;
        try { if (salt.length != 16 || iv.length != 12 || data.length < 16 || data.length > MAX_TOTAL * 2) throw new IOException("Aktarım şifreleme verisi bozuk."); clear = decrypt(data, password, salt, iv, iterations); if (clear.length > MAX_TOTAL) throw new IOException("Aktarım içeriği çok büyük."); Properties payload = new Properties(); payload.load(new ByteArrayInputStream(clear)); if (!"AeroMC-Settings".equals(payload.getProperty("format")) || !"1".equals(payload.getProperty("version"))) throw new IOException("Aktarım içeriği geçersiz."); int count = 0; for (String name : FILES) { String encoded = payload.getProperty("file." + name); if (encoded == null) continue; byte[] bytes = Base64.getDecoder().decode(encoded); try { if (bytes.length > MAX_FILE) throw new IOException("Aktarım içindeki dosya çok büyük: " + name); writeBytes(MasterPasswordManager.dataDirectory().resolve(name), bytes); count++; } finally { wipe(bytes); } } return count; }
        finally { wipe(salt); wipe(iv); wipe(data); wipe(clear); }
    }
    private static byte[] encrypt(byte[] clear, char[] password, byte[] salt, byte[] iv) throws Exception { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), new GCMParameterSpec(128, iv)); cipher.updateAAD(AAD); return cipher.doFinal(clear); }
    private static byte[] decrypt(byte[] encrypted, char[] password, byte[] salt, byte[] iv, int iterations) throws Exception { Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), new GCMParameterSpec(128, iv)); cipher.updateAAD(AAD); return cipher.doFinal(encrypted); }
    private static SecretKeySpec key(char[] password, byte[] salt, int iterations) throws Exception { if (password == null || password.length < 12) throw new IllegalArgumentException("Ana parola en az 12 karakter olmalı."); PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256); byte[] encoded = null; try { encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); return new SecretKeySpec(encoded, "AES"); } finally { spec.clearPassword(); wipe(encoded); } }
    private static byte[] decode(Properties values, String key) throws IOException { try { return Base64.getDecoder().decode(Objects.requireNonNull(values.getProperty(key), key)); } catch (IllegalArgumentException | NullPointerException error) { throw new IOException("Aktarım kodlaması bozuk.", error); } }
    private static byte[] random(int size) { byte[] value = new byte[size]; RANDOM.nextBytes(value); return value; }
    private static void write(Path target, Properties properties) throws IOException { ByteArrayOutputStream buffer = new ByteArrayOutputStream(); properties.store(buffer, "AeroMC encrypted settings backup"); writeBytes(target, buffer.toByteArray()); }
    private static void writeBytes(Path target, byte[] bytes) throws IOException { Path parent = target.getParent(); if (parent == null) throw new IOException("Aktarım hedefi geçersiz."); Files.createDirectories(parent); if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(target)) throw new IOException("Güvenlik nedeniyle simgesel bağlantıya yazılamaz."); Path temporary = Files.createTempFile(parent, ".aeromc-transfer-", ".tmp"); try { Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING); restrict(temporary); try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); } restrict(target); } finally { Files.deleteIfExists(temporary); } }
    private static void restrict(Path path) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
