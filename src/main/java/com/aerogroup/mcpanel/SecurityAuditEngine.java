package com.aerogroup.mcpanel;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Yerel AeroMC yapılandırmasını salt okunur tarar ve izinleri isteğe bağlı sertleştirir. */
final class SecurityAuditEngine {
    enum Level { PASS, WARNING, CRITICAL }
    record Finding(Level level, String title, String detail) { }
    record Report(int score, List<Finding> findings) { String state() { return score >= 90 ? "Güçlü" : score >= 70 ? "İyi" : score >= 45 ? "Dikkat" : "Kritik"; } }

    private static final Path DATA = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final List<String> SENSITIVE = List.of("auto-exaroton.secret", "auto-discord.secret", "exaroton.token", "discord-webhook.secret", "remote-users.properties", "security.log", "config.properties");
    private SecurityAuditEngine() { }

    static Report scan(PanelConfig config) {
        List<Finding> findings = new ArrayList<>(); int score = 100;
        if (Files.exists(DATA) && !privatePermissions(DATA, true)) { score -= 25; findings.add(new Finding(Level.CRITICAL, "AeroMC veri klasörü fazla izinli", "Diğer yerel kullanıcılar ayar ve güvenlik dosyalarına erişebilir.")); }
        else findings.add(new Finding(Level.PASS, "Yerel veri klasörü", "Klasör izinleri kullanıcıyla sınırlandırılmış veya platform POSIX izinlerini kullanmıyor."));
        for (String name : SENSITIVE) {
            Path file = DATA.resolve(name); if (!Files.exists(file)) continue;
            if (Files.isSymbolicLink(file)) { score -= 25; findings.add(new Finding(Level.CRITICAL, name, "Güvenlik dosyası simgesel bağlantı; kullanım engellenmeli.")); }
            else if (!privatePermissions(file, false)) { score -= 12; findings.add(new Finding(Level.WARNING, name, "Dosya izinleri yalnızca kullanıcıyla sınırlandırılmamış.")); }
        }
        if (config.isAutomaticCredentialVaultEnabled()) { findings.add(new Finding(Level.PASS, "Kimlik kasası", "Gizli bilgiler cihaz/kullanıcı bağlı AES-256-GCM kasasında tutuluyor.")); score -= 5; findings.add(new Finding(Level.WARNING, "İşletim sistemi anahtar zinciri", "Cihaz kasası şifreli ve izinleri sınırlı; ancak henüz Windows Credential Manager, macOS Keychain veya Linux Secret Service kullanmıyor.")); }
        else { score -= 8; findings.add(new Finding(Level.WARNING, "Kimlik kasası kapalı", "API anahtarı ve webhook her oturumda elle giriliyor; otomatik güvenli kasa kullanılmıyor.")); }
        if (config.isAutomaticUpdateCheckEnabled()) findings.add(new Finding(Level.PASS, "Güncelleme denetimi", "Yeni güvenlik sürümleri açılışta kontrol ediliyor."));
        else { score -= 6; findings.add(new Finding(Level.WARNING, "Otomatik güncelleme kontrolü kapalı", "Güvenlik düzeltmeleri gecikebilir.")); }
        Path jar = config.getServerJar();
        if (jar != null) try { SafePathGuard.serverJar(jar); findings.add(new Finding(Level.PASS, "Sunucu JAR yolu", "Gerçek dosya doğrulandı; simgesel bağlantı kullanılmıyor.")); }
        catch (IOException error) { score -= 20; findings.add(new Finding(Level.CRITICAL, "Sunucu JAR yolu güvensiz", error.getMessage())); }
        if (Files.exists(DATA.resolve("remote-users.properties"))) { score -= 4; findings.add(new Finding(Level.WARNING, "Uzaktan erişim kullanıcıları mevcut", "LAN modu HTTP kullanır; yalnızca güvenilen yerel ağda aç ve güçlü parola kullan.")); }
        findings.add(new Finding(Level.WARNING, "Güncelleme yayın imzası", "SHA-256 bütünlük doğrulaması etkin; yayıncı kimliğini kanıtlayan sabitlenmiş Ed25519 anahtarı henüz yapılandırılmadı.")); score -= 7;
        return new Report(Math.max(0, score), List.copyOf(findings));
    }

    static int hardenPermissions() throws IOException {
        Files.createDirectories(DATA); int changed = 0;
        changed += restrict(DATA, true) ? 1 : 0;
        for (String name : SENSITIVE) { Path file = DATA.resolve(name); if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) changed += restrict(file, false) ? 1 : 0; }
        return changed;
    }

    private static boolean privatePermissions(Path path, boolean directory) {
        try { Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS); Set<PosixFilePermission> expected = directory ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------"); return permissions.equals(expected); }
        catch (IOException | UnsupportedOperationException ignored) { return true; }
    }
    private static boolean restrict(Path path, boolean directory) throws IOException {
        try { Set<PosixFilePermission> expected = directory ? PosixFilePermissions.fromString("rwx------") : PosixFilePermissions.fromString("rw-------"); if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) return false; Files.setPosixFilePermissions(path, expected); return true; }
        catch (UnsupportedOperationException ignored) { return false; }
    }
}
