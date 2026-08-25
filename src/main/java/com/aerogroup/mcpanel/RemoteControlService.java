package com.aerogroup.mcpanel;

import com.sun.net.httpserver.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.SSLParameters;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Parola, rol, hız sınırı ve güvenlik günlüğü bulunan küçük mobil kontrol sunucusu. */
public final class RemoteControlService {
    public enum Role { VIEWER, MODERATOR, ADMIN }
    private static final Path DATA = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final Path USERS = DATA.resolve("remote-users.properties"), AUDIT = DATA.resolve("security.log");
    private static final int ITERATIONS = 180_000;
    private final ServerManager local;
    private final ExarotonPane exaroton;
    private final PanelConfig config;
    private final Map<String, Credential> users = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> actions = new ConcurrentHashMap<>();
    private final TlsCertificateManager tls = new TlsCertificateManager(DATA);
    private HttpServer server;
    private ExecutorService executor;
    private String address = "Kapalı";
    private String csrfToken = "", cspNonce = "", certificateFingerprint = "";

    public RemoteControlService(ServerManager local, ExarotonPane exaroton, PanelConfig config) { this.local = local; this.exaroton = exaroton; this.config = config; loadUsers(); }
    public synchronized boolean isRunning() { return server != null; }
    public synchronized String getAddress() { return address; }
    public synchronized String getCertificateFingerprint() { return certificateFingerprint; }
    public Path getCertificateFile() { return tls.certificateFile(); }
    public synchronized void start(boolean lan, int port) throws IOException {
        if (server != null) return; if (users.isEmpty()) throw new IllegalStateException("Önce en az bir uzaktan erişim kullanıcısı oluştur.");
        InetAddress bind = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1"); String host = lan ? localAddress() : "127.0.0.1";
        TlsCertificateManager.Material identity;
        try { identity = tls.loadOrCreate(List.of(host)); }
        catch (Exception error) { throw new IOException("TLS kimliği hazırlanamadı: " + rootMessage(error), error); }
        csrfToken = randomToken(32); cspNonce = randomToken(18);
        HttpsServer https = HttpsServer.create(new InetSocketAddress(bind, port), 20);
        https.setHttpsConfigurator(new HttpsConfigurator(identity.context()) {
            @Override public void configure(HttpsParameters parameters) {
                SSLParameters secure = identity.context().getDefaultSSLParameters();
                secure.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                secure.setNeedClientAuth(false);
                parameters.setSSLParameters(secure);
            }
        });
        server = https;
        server.createContext("/", this::handle); executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "aeromc-remote"); t.setDaemon(true); return t; }); server.setExecutor(executor); server.start();
        address = "https://" + host + ":" + port + "/"; certificateFingerprint = identity.fingerprint(); audit("SYSTEM", "remote-start", (lan ? "LAN" : "LOCAL") + " HTTPS", "OK");
    }
    public synchronized void stop() { boolean wasRunning = server != null; if (server != null) { server.stop(1); server = null; } if (executor != null) { executor.shutdownNow(); executor = null; } address = "Kapalı"; csrfToken = cspNonce = ""; failures.clear(); actions.clear(); if (wasRunning) audit("SYSTEM", "remote-stop", "-", "OK"); }
    public void createUser(String username, char[] password, Role role) throws Exception {
        String clean = username.trim(); if (!clean.matches("[A-Za-z0-9_.-]{3,24}")) throw new IllegalArgumentException("Kullanıcı adı 3-24 karakter olmalı."); if (password.length < 10) throw new IllegalArgumentException("Parola en az 10 karakter olmalı.");
        byte[] salt = new byte[16]; SecureRandom.getInstanceStrong().nextBytes(salt); byte[] hash;
        try { hash = derive(password, salt); } finally { Arrays.fill(password, '\0'); }
        users.put(clean, new Credential(salt, hash, role)); saveUsers(); audit("SYSTEM", "user-create", clean + ":" + role, "OK");
    }
    public void deleteUser(String username) { Credential removed = users.remove(username); if (removed != null) { saveUsers(); audit("SYSTEM", "user-delete", username, "OK"); } }
    public Map<String, Role> listUsers() { Map<String, Role> result = new TreeMap<>(); users.forEach((name, credential) -> result.put(name, credential.role)); return result; }
    public List<String> readAudit(int max) { try { if (!Files.exists(AUDIT)) return List.of(); List<String> lines = Files.readAllLines(AUDIT, StandardCharsets.UTF_8); List<String> result = new ArrayList<>(lines.subList(Math.max(0, lines.size() - max), lines.size())); Collections.reverse(result); return result; } catch (IOException error) { return List.of("Günlük okunamadı: " + error.getMessage()); } }
    public List<AuditEntry> readAuditEntries(int max) { List<AuditEntry> entries = new ArrayList<>(); for (String line : readAudit(max)) entries.add(parseAuditLine(line)); return entries; }
    static AuditEntry parseAuditLine(String line) { String[] fields = line.split("\\t", 5); return fields.length == 5 ? new AuditEntry(fields[0], fields[1], fields[2], fields[3], fields[4]) : new AuditEntry("-", "SYSTEM", "log-error", "-", line); }
    public void clearAudit() throws IOException { Files.deleteIfExists(AUDIT); audit("SYSTEM", "audit-cleared", "-", "OK"); }

    private void handle(HttpExchange exchange) throws IOException {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress(); Auth auth = authenticate(exchange, ip); if (auth == null) return;
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) && "GET".equals(exchange.getRequestMethod())) send(exchange, 200, "text/html; charset=utf-8", mobilePage());
            else if ("/api/status".equals(path) && "GET".equals(exchange.getRequestMethod())) send(exchange, 200, "application/json", statusJson());
            else if ("/api/action".equals(path) && "POST".equals(exchange.getRequestMethod())) action(exchange, auth);
            else send(exchange, 404, "text/plain", "Bulunamadı");
        } catch (SecurityException error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "DENIED " + rootMessage(error)); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        catch (IllegalArgumentException error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "INVALID " + rootMessage(error)); send(exchange, 400, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        catch (Exception error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "ERROR " + rootMessage(error)); send(exchange, 500, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        finally { exchange.close(); }
    }
    private Auth authenticate(HttpExchange exchange, String ip) throws IOException {
        Deque<Long> attempts = failures.computeIfAbsent(ip, key -> new ArrayDeque<>()); long now = System.currentTimeMillis(); synchronized (attempts) { while (!attempts.isEmpty() && now - attempts.peekFirst() > 60_000) attempts.removeFirst(); if (attempts.size() >= 5) { audit("UNKNOWN", "login-blocked", ip, "RATE_LIMIT"); send(exchange, 429, "text/plain", "Çok fazla başarısız deneme. 60 saniye bekle."); return null; } }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Basic ")) try { String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8); int colon = decoded.indexOf(':'); if (colon > 0) { String username = decoded.substring(0, colon); char[] password = decoded.substring(colon + 1).toCharArray(); Credential credential = users.get(username); boolean valid = credential != null && credential.verify(password); Arrays.fill(password, '\0'); if (valid) { synchronized (attempts) { attempts.clear(); } return new Auth(username, credential.role); } } } catch (Exception ignored) { }
        synchronized (attempts) { attempts.addLast(now); } audit("UNKNOWN", "login-failed", ip, "DENIED"); exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"AeroMC Remote\""); send(exchange, 401, "text/plain", "Giriş gerekli"); return null;
    }
    private void action(HttpExchange exchange, Auth auth) throws Exception {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress(); if (!allowAction(ip)) { audit(auth.username, "action-blocked", ip, "RATE_LIMIT"); send(exchange, 429, "application/json", "{\"ok\":false,\"message\":\"Çok fazla işlem gönderildi\"}"); return; }
        Map<String, String> form = form(new String(readLimited(exchange.getRequestBody(), 4096), StandardCharsets.UTF_8));
        if (!constantEquals(csrfToken, form.remove("_csrf"))) { audit(auth.username, "csrf", ip, "DENIED"); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"Güvenlik belirteci geçersiz\"}"); return; }
        String provider = form.getOrDefault("provider", "local"), action = form.getOrDefault("action", ""), value = form.getOrDefault("value", "").strip();
        Role required = switch (action) { case "say", "kick", "ban" -> Role.MODERATOR; default -> Role.ADMIN; }; if (!allows(auth.role, required)) { audit(auth.username, action, provider, "DENIED"); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"Yetki yetersiz\"}"); return; }
        if ("exaroton".equals(provider)) remoteAction(action, value); else localAction(action, value); audit(auth.username, action, provider + (value.isBlank() ? "" : ":" + safeAudit(value)), "OK"); send(exchange, 200, "application/json", "{\"ok\":true}");
    }
    private void localAction(String action, String value) throws Exception {
        switch (action) {
            case "start" -> { Path jar = config.getServerJar(); if (jar == null) throw new IllegalStateException("Yerel JAR seçilmedi"); local.start(jar, config.getMemoryMb()); }
            case "stop" -> local.stop(); case "restart" -> local.restart(config.getServerJar(), config.getMemoryMb()); case "backup" -> local.createBackup();
            case "say" -> local.command("say " + cleanMessage(value)); case "kick" -> local.command("kick " + cleanPlayer(value)); case "ban" -> local.command("ban " + cleanPlayer(value)); case "command" -> local.command(CommandSecurity.requireRemoteGeneric(value));
            default -> throw new IllegalArgumentException("Bilinmeyen işlem");
        }
    }
    private void remoteAction(String action, String value) throws Exception {
        switch (action) { case "start" -> exaroton.startActiveServer().get(30, TimeUnit.SECONDS); case "stop" -> exaroton.stopActiveServer().get(30, TimeUnit.SECONDS); case "restart" -> exaroton.restartActiveServer().get(30, TimeUnit.SECONDS); case "say" -> exaroton.executeAdminCommand("say " + cleanMessage(value)).get(15, TimeUnit.SECONDS); case "kick" -> exaroton.executeAdminCommand("kick " + cleanPlayer(value)).get(15, TimeUnit.SECONDS); case "ban" -> exaroton.modifyPlayerList("banned-players", true, cleanPlayer(value)).get(15, TimeUnit.SECONDS); case "command" -> exaroton.executeAdminCommand(CommandSecurity.requireRemoteGeneric(value)).get(15, TimeUnit.SECONDS); default -> throw new IllegalArgumentException("Bu işlem Exaroton'da desteklenmiyor."); }
    }
    private String statusJson() {
        String remote = "null"; try { ExarotonPane.ProSnapshot s = exaroton.fetchProSnapshot().get(5, TimeUnit.SECONDS); remote = "{\"name\":\"" + json(s.name()) + "\",\"status\":\"" + json(s.status()) + "\",\"players\":" + s.players() + ",\"max\":" + s.maxPlayers() + "}"; } catch (Exception ignored) { }
        return "{\"local\":{\"running\":" + local.isRunning() + ",\"pid\":" + local.getProcessId() + "},\"exaroton\":" + remote + "}";
    }
    private String mobilePage() { return """
        <!doctype html><html lang='tr'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AeroMC Remote</title>
        <style nonce='%s'>*{box-sizing:border-box}body{margin:0;background:#0d141b;color:#e7f0f5;font:15px system-ui;padding:18px}.wrap{max-width:620px;margin:auto}.card{background:#15212b;border:1px solid #263642;border-radius:14px;padding:16px;margin:12px 0}h1{color:#55b9ea}button,select,input{border:1px solid #38505f;border-radius:9px;background:#0f1921;color:#e7f0f5;padding:12px;margin:4px}button{background:#2789b8;font-weight:700}button.danger{background:#793b42}.grid{display:grid;grid-template-columns:repeat(2,1fr)}input{width:calc(100%% - 8px)}pre{white-space:pre-wrap;color:#9fc1d2}</style></head>
        <body><div class='wrap'><h1>AEROMC Remote</h1><div class='card'><select id='provider'><option value='local'>Yerel JAR</option><option value='exaroton'>Exaroton</option></select><button id='refresh'>Yenile</button><pre id='state'>Yükleniyor...</pre></div>
        <div class='card grid'><button data-action='start'>Başlat</button><button class='danger' data-action='stop'>Durdur</button><button data-action='restart'>Yeniden Başlat</button><button data-action='backup'>Yedek Al</button></div>
        <div class='card'><input id='value' placeholder='Mesaj, oyuncu veya komut'><div class='grid'><button data-action='say'>Duyuru</button><button data-action='kick'>Kick</button><button class='danger' data-action='ban'>Ban</button><button data-action='command'>Komut</button></div><pre id='result'></pre></div></div>
        <script nonce='%s'>const csrf='%s',provider=document.getElementById('provider'),value=document.getElementById('value'),state=document.getElementById('state'),result=document.getElementById('result');async function status(){let r=await fetch('/api/status',{cache:'no-store'});state.textContent=JSON.stringify(await r.json(),null,2)}async function act(a){let b=new URLSearchParams({_csrf:csrf,provider:provider.value,action:a,value:value.value});let r=await fetch('/api/action',{method:'POST',body:b,headers:{'Content-Type':'application/x-www-form-urlencoded'}});result.textContent=await r.text();status()}document.getElementById('refresh').addEventListener('click',status);document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>act(b.dataset.action)));status();setInterval(status,10000)</script></body></html>
        """.formatted(cspNonce, cspNonce, csrfToken); }

    private boolean allows(Role actual, Role required) { return actual.ordinal() >= required.ordinal(); }
    private String cleanPlayer(String value) { return CommandSecurity.playerName(value); }
    private String cleanMessage(String value) { String clean = CommandSecurity.singleLine(value, 300); if (clean.isEmpty()) throw new IllegalArgumentException("Mesaj boş olamaz"); return clean; }
    private String safeAudit(String value) { String clean = value.replaceAll("[\\r\\n]", " "); return clean.length() > 80 ? clean.substring(0, 80) : clean; }
    private Map<String, String> form(String body) { Map<String, String> values = new HashMap<>(); for (String part : body.split("&")) { String[] pair = part.split("=", 2); values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8)); } return values; }
    private void send(HttpExchange exchange, int status, String type, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.getResponseHeaders().set("X-Frame-Options", "DENY"); exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer"); exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()"); exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin"); exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-" + cspNonce + "'; script-src 'nonce-" + cspNonce + "'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    private void audit(String user, String action, String target, String result) { try { Files.createDirectories(DATA); restrict(DATA, true); String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\t" + safeAudit(user) + "\t" + safeAudit(action) + "\t" + safeAudit(target) + "\t" + safeAudit(result) + System.lineSeparator(); Files.writeString(AUDIT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); restrict(AUDIT, false); } catch (IOException ignored) { } }
    private void loadUsers() { Properties p = new Properties(); try { if (Files.exists(USERS)) try (Reader reader = Files.newBufferedReader(USERS)) { p.load(reader); } for (String key : p.stringPropertyNames()) { String[] v = p.getProperty(key).split(":"); users.put(new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8), new Credential(Base64.getDecoder().decode(v[0]), Base64.getDecoder().decode(v[1]), Role.valueOf(v[2]))); } } catch (Exception ignored) { users.clear(); } }
    private void saveUsers() { try { Files.createDirectories(DATA); restrict(DATA, true); Properties p = new Properties(); users.forEach((name, c) -> p.setProperty(Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)), Base64.getEncoder().encodeToString(c.salt) + ":" + Base64.getEncoder().encodeToString(c.hash) + ":" + c.role)); try (Writer writer = Files.newBufferedWriter(USERS)) { p.store(writer, "AeroMC remote users - PBKDF2 hashes"); } restrict(USERS, false); } catch (IOException ignored) { } }
    private byte[] derive(char[] password, byte[] salt) throws Exception { PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256); try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); } finally { spec.clearPassword(); } }
    private String localAddress() {
        try (DatagramSocket route = new DatagramSocket()) { route.connect(InetAddress.getByName("8.8.8.8"), 53); InetAddress selected = route.getLocalAddress(); if (selected instanceof Inet4Address && !selected.isLoopbackAddress()) return selected.getHostAddress(); } catch (Exception ignored) { }
        try { for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) { if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue; for (InetAddress candidate : Collections.list(network.getInetAddresses())) if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) return candidate.getHostAddress(); } } catch (Exception ignored) { }
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception error) { return "127.0.0.1"; }
    }
    private String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", ""); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
    private boolean allowAction(String ip) { Deque<Long> values = actions.computeIfAbsent(ip, key -> new ArrayDeque<>()); long now = System.currentTimeMillis(); synchronized (values) { while (!values.isEmpty() && now - values.peekFirst() > 60_000) values.removeFirst(); if (values.size() >= 30) return false; values.addLast(now); return true; } }
    private static byte[] readLimited(InputStream input, int max) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int total = 0, count; while ((count = input.read(buffer)) >= 0) { total += count; if (total > max) throw new IOException("İstek gövdesi çok büyük."); output.write(buffer, 0, count); } return output.toByteArray(); }
    private static boolean constantEquals(String expected, String actual) { if (expected == null || actual == null) return false; return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)); }
    private static String randomToken(int bytes) { byte[] value = new byte[bytes]; new SecureRandom().nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static void restrict(Path path, boolean directory) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
    private final class Credential { final byte[] salt, hash; final Role role; Credential(byte[] salt, byte[] hash, Role role) { this.salt = salt; this.hash = hash; this.role = role; } boolean verify(char[] password) { byte[] candidate = null; try { candidate = derive(password, salt); return MessageDigest.isEqual(hash, candidate); } catch (Exception error) { return false; } finally { if (candidate != null) Arrays.fill(candidate, (byte) 0); } } }
    public record AuditEntry(String time, String user, String action, String target, String result) { }
    private record Auth(String username, Role role) { }
}
