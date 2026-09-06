package com.aerogroup.mcpanel.aeroguard;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Stores only a password verifier; the AeroMC master password itself is never written to disk. */
public final class MasterPasswordManager {
    private static final Path DATA = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final Path FILE = DATA.resolve("master-password.properties");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000;
    private static final byte[] AAD = "AeroMC/master-password/v1".getBytes(StandardCharsets.UTF_8);

    private MasterPasswordManager() { }
    public static Path dataDirectory() { return DATA; }
    public static boolean isConfigured() { return Files.isRegularFile(FILE, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(FILE); }

    public static void create(char[] password) throws Exception {
        validate(password); prepareDirectory();
        byte[] salt = random(16), derived = null, verifier = null;
        try {
            derived = derive(password, salt, ITERATIONS);
            verifier = digest(derived);
            Properties properties = new Properties();
            properties.setProperty("version", "1"); properties.setProperty("iterations", Integer.toString(ITERATIONS));
            properties.setProperty("salt", Base64.getEncoder().encodeToString(salt)); properties.setProperty("verifier", Base64.getEncoder().encodeToString(verifier));
            write(FILE, properties);
        } finally { wipe(salt); wipe(derived); wipe(verifier); }
    }

    public static boolean verify(char[] password) throws Exception {
        if (!isConfigured() || password == null) return false;
        if (Files.size(FILE) > 8192) throw new IOException("Ana parola doğrulama dosyası güvenli boyut sınırını aştı.");
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) { values.load(reader); }
        int iterations = Integer.parseInt(values.getProperty("iterations", "0"));
        if (iterations < 100_000 || iterations > 1_000_000) throw new IOException("Ana parola doğrulama parametreleri geçersiz.");
        byte[] salt = Base64.getDecoder().decode(required(values, "salt")); byte[] expected = Base64.getDecoder().decode(required(values, "verifier"));
        byte[] derived = null, actual = null;
        try {
            if (salt.length != 16 || expected.length != 32) throw new IOException("Ana parola doğrulama dosyası bozuk.");
            derived = derive(password, salt, iterations); actual = digest(derived);
            return MessageDigest.isEqual(expected, actual);
        } finally { wipe(salt); wipe(expected); wipe(derived); wipe(actual); }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static byte[] digest(byte[] value) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(AAD); return digest.digest(value); }
    private static String required(Properties values, String key) throws IOException { String value = values.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Ana parola doğrulama dosyası bozuk."); return value; }
    private static void validate(char[] password) { if (password == null || password.length < 12) throw new IllegalArgumentException("AeroMC ana parolası en az 12 karakter olmalı."); if (password.length > 256) throw new IllegalArgumentException("AeroMC ana parolası en fazla 256 karakter olabilir."); }
    private static byte[] random(int size) { byte[] value = new byte[size]; RANDOM.nextBytes(value); return value; }
    private static void prepareDirectory() throws IOException { if (Files.exists(DATA, LinkOption.NOFOLLOW_LINKS)) { if (Files.isSymbolicLink(DATA) || !Files.isDirectory(DATA, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AeroMC veri klasörü güvenli değil."); } else Files.createDirectories(DATA); restrict(DATA, true); }
    private static void write(Path file, Properties properties) throws IOException { if (Files.isSymbolicLink(file)) throw new IOException("Ana parola dosyası simgesel bağlantı olamaz."); Path temporary = Files.createTempFile(DATA, ".aeromc-master-", ".tmp"); try { try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { properties.store(writer, "AeroMC master password verifier"); } restrict(temporary, false); try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } restrict(file, false); } finally { Files.deleteIfExists(temporary); } }
    private static void restrict(Path path, boolean directory) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
