package com.aerogroup.mcpanel.aeroguard;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;

/** AeroGuard kalıcı LAN TLS kimliği oluşturur ve güvenli dosya izinleriyle saklar. */
public final class TlsCertificateManager {
    private static final String ALIAS = "aeromc-remote";
    private final Path storeFile, passwordFile, certificateFile;

    public TlsCertificateManager(Path directory) {
        storeFile = directory.resolve("remote-tls.p12"); passwordFile = directory.resolve("remote-tls.secret"); certificateFile = directory.resolve("remote-tls.crt");
    }

    public synchronized Material loadOrCreate(Collection<String> requestedHosts) throws Exception {
        LinkedHashSet<String> hosts = new LinkedHashSet<>(); hosts.add("localhost"); hosts.add("127.0.0.1");
        requestedHosts.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank()).forEach(hosts::add);
        char[] password = null;
        try {
            if (Files.isRegularFile(storeFile) && Files.isRegularFile(passwordFile)) {
                password = Files.readString(passwordFile, StandardCharsets.US_ASCII).strip().toCharArray();
                KeyStore store = loadStore(password); X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
                if (certificate != null && valid(certificate, hosts)) return material(store, password, certificate);
            }
        } catch (Exception ignored) { }
        finally { if (password != null) Arrays.fill(password, '\0'); }
        return create(hosts);
    }

    public Path certificateFile() { return certificateFile; }

    private Material create(Set<String> hosts) throws Exception {
        Files.createDirectories(storeFile.getParent()); restrict(storeFile.getParent(), true);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072, SecureRandom.getInstanceStrong()); KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now(); X500Name name = new X500Name("CN=AeroMC Local Remote,O=AeroMC");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, new BigInteger(160, new SecureRandom()).setBit(159), Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(825))), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils(); builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.getPublic())); builder.addExtension(Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(pair.getPublic()));
        List<GeneralName> names = new ArrayList<>(); for (String host : hosts) names.add(new GeneralName(ip(host) ? GeneralName.iPAddress : GeneralName.dNSName, host)); builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(GeneralName[]::new)));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate()); X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer)); certificate.checkValidity(); certificate.verify(pair.getPublic());
        char[] password = randomPassword(); try {
            KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password); store.setKeyEntry(ALIAS, pair.getPrivate(), password, new java.security.cert.Certificate[]{certificate});
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(); store.store(encoded, password); atomic(storeFile, encoded.toByteArray()); atomic(passwordFile, new String(password).getBytes(StandardCharsets.US_ASCII));
            String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded()) + "\n-----END CERTIFICATE-----\n"; atomic(certificateFile, pem.getBytes(StandardCharsets.US_ASCII));
            restrict(storeFile, false); restrict(passwordFile, false); restrict(certificateFile, false); return material(store, password, certificate);
        } finally { Arrays.fill(password, '\0'); }
    }

    private KeyStore loadStore(char[] password) throws Exception { KeyStore store = KeyStore.getInstance("PKCS12"); try (InputStream input = Files.newInputStream(storeFile)) { store.load(input, password); } return store; }
    private Material material(KeyStore store, char[] password, X509Certificate certificate) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); keys.init(store, password); SSLContext context = SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(), null, new SecureRandom()); return new Material(context, certificate, fingerprint(certificate), certificateFile);
    }
    private boolean valid(X509Certificate certificate, Set<String> hosts) {
        try { certificate.checkValidity(Date.from(Instant.now().plus(Duration.ofDays(30)))); Set<String> covered = new HashSet<>(); Collection<List<?>> values = certificate.getSubjectAlternativeNames(); if (values != null) for (List<?> value : values) if (value.size() >= 2) covered.add(Objects.toString(value.get(1), "")); return covered.containsAll(hosts); }
        catch (Exception ignored) { return false; }
    }
    private static String fingerprint(X509Certificate certificate) throws CertificateEncodingException, NoSuchAlgorithmException { byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()); StringJoiner value = new StringJoiner(":"); for (byte item : hash) value.add(String.format(Locale.ROOT, "%02X", item)); return value.toString(); }
    private static char[] randomPassword() { byte[] random = new byte[32]; new SecureRandom().nextBytes(random); return Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray(); }
    private static boolean ip(String value) { return value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || value.contains(":"); }
    private static void atomic(Path file, byte[] bytes) throws IOException { Path temporary = Files.createTempFile(file.getParent(), ".remote-tls-", ".tmp"); try { Files.write(temporary, bytes); restrict(temporary, false); try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); } } finally { Files.deleteIfExists(temporary); } }
    private static void restrict(Path path, boolean directory) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }

    public record Material(SSLContext context, X509Certificate certificate, String fingerprint, Path certificateFile) { }
}
