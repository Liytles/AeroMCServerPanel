package com.aerogroup.mcpanel;

import com.sun.net.httpserver.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
    private HttpServer server;
    private ExecutorService executor;
    private String address = "Kapalı";

    public RemoteControlService(ServerManager local, ExarotonPane exaroton, PanelConfig config) { this.local = local; this.exaroton = exaroton; this.config = config; loadUsers(); }
    public synchronized boolean isRunning() { return server != null; }
    public synchronized String getAddress() { return address; }
    public synchronized void start(boolean lan, int port) throws IOException {
        if (server != null) return; if (users.isEmpty()) throw new IllegalStateException("Önce en az bir uzaktan erişim kullanıcısı oluştur.");
        InetAddress bind = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1"); server = HttpServer.create(new InetSocketAddress(bind, port), 20);
        server.createContext("/", this::handle); executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "aeromc-remote"); t.setDaemon(true); return t; }); server.setExecutor(executor); server.start();
        String host = lan ? localAddress() : "127.0.0.1"; address = "http://" + host + ":" + port + "/"; audit("SYSTEM", "remote-start", lan ? "LAN" : "LOCAL", "OK");
    }
    public synchronized void stop() { boolean wasRunning = server != null; if (server != null) { server.stop(1); server = null; } if (executor != null) { executor.shutdownNow(); executor = null; } address = "Kapalı"; if (wasRunning) audit("SYSTEM", "remote-stop", "-", "OK"); }
    public void createUser(String username, char[] password, Role role) throws Exception {
        String clean = username.trim(); if (!clean.matches("[A-Za-z0-9_.-]{3,24}")) throw new IllegalArgumentException("Kullanıcı adı 3-24 karakter olmalı."); if (password.length < 10) throw new IllegalArgumentException("Parola en az 10 karakter olmalı.");
        byte[] salt = new byte[16]; SecureRandom.getInstanceStrong().nextBytes(salt); byte[] hash = derive(password, salt); Arrays.fill(password, '\0'); users.put(clean, new Credential(salt, hash, role)); saveUsers(); audit("SYSTEM", "user-create", clean + ":" + role, "OK");
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
        } catch (Exception error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "ERROR " + rootMessage(error)); send(exchange, 500, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        finally { exchange.close(); }
    }
    private Auth authenticate(HttpExchange exchange, String ip) throws IOException {
        Deque<Long> attempts = failures.computeIfAbsent(ip, key -> new ArrayDeque<>()); long now = System.currentTimeMillis(); synchronized (attempts) { while (!attempts.isEmpty() && now - attempts.peekFirst() > 60_000) attempts.removeFirst(); if (attempts.size() >= 5) { audit("UNKNOWN", "login-blocked", ip, "RATE_LIMIT"); send(exchange, 429, "text/plain", "Çok fazla başarısız deneme. 60 saniye bekle."); return null; } }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Basic ")) try { String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8); int colon = decoded.indexOf(':'); if (colon > 0) { String username = decoded.substring(0, colon); char[] password = decoded.substring(colon + 1).toCharArray(); Credential credential = users.get(username); boolean valid = credential != null && credential.verify(password); Arrays.fill(password, '\0'); if (valid) { synchronized (attempts) { attempts.clear(); } return new Auth(username, credential.role); } } } catch (Exception ignored) { }
        synchronized (attempts) { attempts.addLast(now); } audit("UNKNOWN", "login-failed", ip, "DENIED"); exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"AeroMC Remote\""); send(exchange, 401, "text/plain", "Giriş gerekli"); return null;
    }
    private void action(HttpExchange exchange, Auth auth) throws Exception {
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); String provider = form.getOrDefault("provider", "local"), action = form.getOrDefault("action", ""), value = form.getOrDefault("value", "").strip();
        Role required = switch (action) { case "say", "kick", "ban" -> Role.MODERATOR; default -> Role.ADMIN; }; if (!allows(auth.role, required)) { audit(auth.username, action, provider, "DENIED"); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"Yetki yetersiz\"}"); return; }
        if ("exaroton".equals(provider)) remoteAction(action, value); else localAction(action, value); audit(auth.username, action, provider + (value.isBlank() ? "" : ":" + safeAudit(value)), "OK"); send(exchange, 200, "application/json", "{\"ok\":true}");
    }
    private void localAction(String action, String value) throws Exception {
        switch (action) {
            case "start" -> { Path jar = config.getServerJar(); if (jar == null) throw new IllegalStateException("Yerel JAR seçilmedi"); local.start(jar, config.getMemoryMb()); }
            case "stop" -> local.stop(); case "restart" -> local.restart(config.getServerJar(), config.getMemoryMb()); case "backup" -> local.createBackup();
            case "say" -> local.command("say " + cleanCommand(value)); case "kick" -> local.command("kick " + cleanPlayer(value)); case "ban" -> local.command("ban " + cleanPlayer(value)); case "command" -> local.command(cleanCommand(value));
            default -> throw new IllegalArgumentException("Bilinmeyen işlem");
        }
    }
    private void remoteAction(String action, String value) throws Exception {
        switch (action) { case "start" -> exaroton.startActiveServer().get(30, TimeUnit.SECONDS); case "stop" -> exaroton.stopActiveServer().get(30, TimeUnit.SECONDS); case "restart" -> exaroton.restartActiveServer().get(30, TimeUnit.SECONDS); case "say" -> exaroton.executeAdminCommand("say " + cleanCommand(value)).get(15, TimeUnit.SECONDS); case "kick" -> exaroton.executeAdminCommand("kick " + cleanPlayer(value)).get(15, TimeUnit.SECONDS); case "ban" -> exaroton.modifyPlayerList("banned-players", true, cleanPlayer(value)).get(15, TimeUnit.SECONDS); case "command" -> exaroton.executeAdminCommand(cleanCommand(value)).get(15, TimeUnit.SECONDS); default -> throw new IllegalArgumentException("Bu işlem Exaroton'da desteklenmiyor."); }
    }
    private String statusJson() {
        String remote = "null"; try { ExarotonPane.ProSnapshot s = exaroton.fetchProSnapshot().get(5, TimeUnit.SECONDS); remote = "{\"name\":\"" + json(s.name()) + "\",\"status\":\"" + json(s.status()) + "\",\"players\":" + s.players() + ",\"max\":" + s.maxPlayers() + "}"; } catch (Exception ignored) { }
        return "{\"local\":{\"running\":" + local.isRunning() + ",\"pid\":" + local.getProcessId() + "},\"exaroton\":" + remote + "}";
    }
    private String mobilePage() { return """
        <!doctype html><html lang='tr'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AeroMC Remote</title>
        <style>*{box-sizing:border-box}body{margin:0;background:#0d141b;color:#e7f0f5;font:15px system-ui;padding:18px}.wrap{max-width:620px;margin:auto}.card{background:#15212b;border:1px solid #263642;border-radius:14px;padding:16px;margin:12px 0}h1{color:#55b9ea}button,select,input{border:1px solid #38505f;border-radius:9px;background:#0f1921;color:#e7f0f5;padding:12px;margin:4px}button{background:#2789b8;font-weight:700}button.danger{background:#793b42}.grid{display:grid;grid-template-columns:repeat(2,1fr)}input{width:calc(100% - 8px)}pre{white-space:pre-wrap;color:#9fc1d2}</style></head>
        <body><div class='wrap'><h1>AEROMC Remote</h1><div class='card'><select id='provider'><option value='local'>Yerel JAR</option><option value='exaroton'>Exaroton</option></select><button onclick='status()'>Yenile</button><pre id='state'>Yükleniyor...</pre></div>
        <div class='card grid'><button onclick="act('start')">Başlat</button><button class='danger' onclick="act('stop')">Durdur</button><button onclick="act('restart')">Yeniden Başlat</button><button onclick="act('backup')">Yedek Al</button></div>
        <div class='card'><input id='value' placeholder='Mesaj, oyuncu veya komut'><div class='grid'><button onclick="act('say')">Duyuru</button><button onclick="act('kick')">Kick</button><button class='danger' onclick="act('ban')">Ban</button><button onclick="act('command')">Komut</button></div><pre id='result'></pre></div></div>
        <script>async function status(){let r=await fetch('/api/status');state.textContent=JSON.stringify(await r.json(),null,2)}async function act(a){let b=new URLSearchParams({provider:provider.value,action:a,value:value.value});let r=await fetch('/api/action',{method:'POST',body:b});result.textContent=await r.text();status()}status();setInterval(status,10000)</script></body></html>
        """; }

    private boolean allows(Role actual, Role required) { return actual.ordinal() >= required.ordinal(); }
    private String cleanPlayer(String value) { if (!value.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Geçersiz oyuncu adı"); return value; }
    private String cleanCommand(String value) { String clean = value.replace('\r', ' ').replace('\n', ' ').strip(); if (clean.isEmpty() || clean.length() > 300) throw new IllegalArgumentException("Geçersiz komut veya mesaj"); return clean; }
    private String safeAudit(String value) { String clean = value.replaceAll("[\\r\\n]", " "); return clean.length() > 80 ? clean.substring(0, 80) : clean; }
    private Map<String, String> form(String body) { Map<String, String> values = new HashMap<>(); for (String part : body.split("&")) { String[] pair = part.split("=", 2); values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8)); } return values; }
    private void send(HttpExchange exchange, int status, String type, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self' 'unsafe-inline'"); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    private void audit(String user, String action, String target, String result) { try { Files.createDirectories(DATA); String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\t" + safeAudit(user) + "\t" + safeAudit(action) + "\t" + safeAudit(target) + "\t" + safeAudit(result) + System.lineSeparator(); Files.writeString(AUDIT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) { } }
    private void loadUsers() { Properties p = new Properties(); try { if (Files.exists(USERS)) try (Reader reader = Files.newBufferedReader(USERS)) { p.load(reader); } for (String key : p.stringPropertyNames()) { String[] v = p.getProperty(key).split(":"); users.put(new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8), new Credential(Base64.getDecoder().decode(v[0]), Base64.getDecoder().decode(v[1]), Role.valueOf(v[2]))); } } catch (Exception ignored) { users.clear(); } }
    private void saveUsers() { try { Files.createDirectories(DATA); Properties p = new Properties(); users.forEach((name, c) -> p.setProperty(Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)), Base64.getEncoder().encodeToString(c.salt) + ":" + Base64.getEncoder().encodeToString(c.hash) + ":" + c.role)); try (Writer writer = Files.newBufferedWriter(USERS)) { p.store(writer, "AeroMC remote users - PBKDF2 hashes"); } } catch (IOException ignored) { } }
    private byte[] derive(char[] password, byte[] salt) throws Exception { PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256); try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); } finally { spec.clearPassword(); } }
    private String localAddress() {
        try (DatagramSocket route = new DatagramSocket()) { route.connect(InetAddress.getByName("8.8.8.8"), 53); InetAddress selected = route.getLocalAddress(); if (selected instanceof Inet4Address && !selected.isLoopbackAddress()) return selected.getHostAddress(); } catch (Exception ignored) { }
        try { for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) { if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue; for (InetAddress candidate : Collections.list(network.getInetAddresses())) if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) return candidate.getHostAddress(); } } catch (Exception ignored) { }
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception error) { return "127.0.0.1"; }
    }
    private String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", ""); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
    private final class Credential { final byte[] salt, hash; final Role role; Credential(byte[] salt, byte[] hash, Role role) { this.salt = salt; this.hash = hash; this.role = role; } boolean verify(char[] password) { try { return MessageDigest.isEqual(hash, derive(password, salt)); } catch (Exception error) { return false; } } }
    public record AuditEntry(String time, String user, String action, String target, String result) { }
    private record Auth(String username, Role role) { }
}
