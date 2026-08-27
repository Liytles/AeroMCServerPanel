package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.CommandSecurity;
import com.aerogroup.mcpanel.aeroguard.TlsCertificateManager;

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
    private static final int ITERATIONS = 600_000, MIN_ITERATIONS = 100_000, MAX_ITERATIONS = 1_000_000;
    private static final long MAX_USERS_BYTES = 256 * 1024, MAX_AUDIT_BYTES = 2L * 1024 * 1024;
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
    private String csrfToken = "", certificateFingerprint = "";

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
        csrfToken = randomToken(32);
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
        ThreadFactory remoteThreads = r -> { Thread t = new Thread(r, "aeromc-remote"); t.setDaemon(true); return t; };
        executor = new ThreadPoolExecutor(2, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), remoteThreads, new ThreadPoolExecutor.CallerRunsPolicy());
        server.createContext("/", this::handle); server.setExecutor(executor); server.start();
        address = "https://" + host + ":" + port + "/"; certificateFingerprint = identity.fingerprint(); audit("SYSTEM", "remote-start", (lan ? "LAN" : "LOCAL") + " HTTPS", "OK");
    }
    public synchronized void stop() { boolean wasRunning = server != null; if (server != null) { server.stop(1); server = null; } if (executor != null) { executor.shutdownNow(); executor = null; } address = "Kapalı"; csrfToken = ""; failures.clear(); actions.clear(); if (wasRunning) audit("SYSTEM", "remote-stop", "-", "OK"); }
    public void createUser(String username, char[] password, Role role) throws Exception {
        String clean = Objects.toString(username, "").trim(); if (!clean.matches("[A-Za-z0-9_.-]{3,24}")) throw new IllegalArgumentException("Kullanıcı adı 3-24 karakter olmalı.");
        if (password == null || password.length < 12) throw new IllegalArgumentException("Parola en az 12 karakter olmalı.");
        if (password.length > 256) throw new IllegalArgumentException("Parola en fazla 256 karakter olabilir.");
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt); byte[] hash;
        try { hash = derive(password, salt, ITERATIONS); } finally { Arrays.fill(password, '\0'); }
        Credential created = new Credential(salt, hash, Objects.requireNonNull(role), ITERATIONS), previous = users.put(clean, created);
        try { saveUsers(); } catch (IOException error) { if (previous == null) users.remove(clean, created); else users.put(clean, previous); throw error; }
        audit("SYSTEM", "user-create", clean + ":" + role, "OK");
    }
    public void deleteUser(String username) { Credential removed = users.remove(username); if (removed != null) try { saveUsers(); audit("SYSTEM", "user-delete", username, "OK"); } catch (IOException error) { users.put(username, removed); audit("SYSTEM", "user-delete", username, "ERROR"); } }
    public Map<String, Role> listUsers() { Map<String, Role> result = new TreeMap<>(); users.forEach((name, credential) -> result.put(name, credential.role)); return result; }
    public List<String> readAudit(int max) { try { if (!safeRegularFile(AUDIT)) return List.of(); if (Files.size(AUDIT) > MAX_AUDIT_BYTES) return List.of("Günlük güvenli boyut sınırını aştı; yeni kayıtlar döndürülmedi."); List<String> lines = Files.readAllLines(AUDIT, StandardCharsets.UTF_8); int limit = Math.max(0, Math.min(max, 2_000)); List<String> result = new ArrayList<>(lines.subList(Math.max(0, lines.size() - limit), lines.size())); Collections.reverse(result); return result; } catch (IOException error) { return List.of("Günlük okunamadı: " + error.getMessage()); } }
    public List<AuditEntry> readAuditEntries(int max) { List<AuditEntry> entries = new ArrayList<>(); for (String line : readAudit(max)) entries.add(parseAuditLine(line)); return entries; }
    static AuditEntry parseAuditLine(String line) { String[] fields = line.split("\\t", 5); return fields.length == 5 ? new AuditEntry(fields[0], fields[1], fields[2], fields[3], fields[4]) : new AuditEntry("-", "SYSTEM", "log-error", "-", line); }
    public void clearAudit() throws IOException { if (Files.isSymbolicLink(AUDIT)) throw new IOException("Güvenlik günlüğü simgesel bağlantı olamaz."); Files.deleteIfExists(AUDIT); audit("SYSTEM", "audit-cleared", "-", "OK"); }

    private void handle(HttpExchange exchange) throws IOException {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress(); Auth auth = authenticate(exchange, ip); if (auth == null) return;
        try {
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestURI().getRawQuery() != null) throw new IllegalArgumentException("Sorgu parametreleri desteklenmiyor.");
            if ("/".equals(path)) {
                if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET"); return; }
                String nonce = randomToken(18); send(exchange, 200, "text/html; charset=utf-8", mobilePage(nonce), nonce);
            } else if ("/api/status".equals(path)) {
                if (!"GET".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange, "GET"); return; }
                send(exchange, 200, "application/json", statusJson());
            } else if ("/api/action".equals(path)) {
                if (!"POST".equals(exchange.getRequestMethod())) { methodNotAllowed(exchange, "POST"); return; }
                action(exchange, auth);
            } else send(exchange, 404, "text/plain", "Bulunamadı");
        } catch (SecurityException error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "DENIED " + rootMessage(error)); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        catch (IllegalArgumentException error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "INVALID " + rootMessage(error)); send(exchange, 400, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        catch (Exception error) { audit(auth.username, "request", exchange.getRequestURI().getPath(), "ERROR " + rootMessage(error)); send(exchange, 500, "application/json", "{\"ok\":false,\"message\":\"" + json(rootMessage(error)) + "\"}"); }
        finally { exchange.close(); }
    }
    private Auth authenticate(HttpExchange exchange, String ip) throws IOException {
        if (failures.size() > 512) failures.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        Deque<Long> attempts = failures.computeIfAbsent(ip, key -> new ArrayDeque<>()); long now = System.currentTimeMillis(); synchronized (attempts) { while (!attempts.isEmpty() && now - attempts.peekFirst() > 60_000) attempts.removeFirst(); if (attempts.size() >= 5) { audit("UNKNOWN", "login-blocked", ip, "RATE_LIMIT"); send(exchange, 429, "text/plain", "Çok fazla başarısız deneme. 60 saniye bekle."); return null; } }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Basic ")) try {
            byte[] decodedBytes = Base64.getDecoder().decode(header.substring(6));
            if (decodedBytes.length > 1024) throw new IllegalArgumentException("Kimlik bilgisi çok büyük.");
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8); Arrays.fill(decodedBytes, (byte) 0);
            int colon = decoded.indexOf(':');
            if (colon > 0) {
                String username = decoded.substring(0, colon); char[] password = decoded.substring(colon + 1).toCharArray();
                Credential credential = users.get(username), checked = credential != null ? credential : users.values().stream().findFirst().orElse(null);
                boolean passwordValid;
                try { passwordValid = checked != null && checked.verify(password); } finally { Arrays.fill(password, '\0'); }
                if (credential != null && passwordValid) { synchronized (attempts) { attempts.clear(); } return new Auth(username, credential.role); }
            }
        } catch (Exception ignored) { }
        synchronized (attempts) { attempts.addLast(now); } audit("UNKNOWN", "login-failed", ip, "DENIED"); exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"AeroMC Remote\""); send(exchange, 401, "text/plain", "Giriş gerekli"); return null;
    }
    private void action(HttpExchange exchange, Auth auth) throws Exception {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress(); if (!allowAction(ip)) { audit(auth.username, "action-blocked", ip, "RATE_LIMIT"); send(exchange, 429, "application/json", "{\"ok\":false,\"message\":\"Çok fazla işlem gönderildi\"}"); return; }
        String contentType = Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "").toLowerCase(Locale.ROOT);
        if (!(contentType.equals("application/x-www-form-urlencoded") || contentType.startsWith("application/x-www-form-urlencoded;"))) { send(exchange, 415, "application/json", "{\"ok\":false,\"message\":\"Desteklenmeyen içerik türü\"}"); return; }
        Map<String, String> form = form(new String(readLimited(exchange.getRequestBody(), 4096), StandardCharsets.UTF_8));
        if (!constantEquals(csrfToken, form.remove("_csrf"))) { audit(auth.username, "csrf", ip, "DENIED"); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"Güvenlik belirteci geçersiz\"}"); return; }
        String provider = form.getOrDefault("provider", "local"), action = form.getOrDefault("action", ""), value = form.getOrDefault("value", "").strip();
        if (!Set.of("local", "exaroton").contains(provider)) throw new IllegalArgumentException("Bilinmeyen sunucu sağlayıcısı.");
        if (!Set.of("start", "stop", "restart", "backup", "say", "kick", "ban", "command").contains(action)) throw new IllegalArgumentException("Bilinmeyen işlem.");
        Role required = switch (action) { case "say", "kick", "ban" -> Role.MODERATOR; default -> Role.ADMIN; }; if (!allows(auth.role, required)) { audit(auth.username, action, provider, "DENIED"); send(exchange, 403, "application/json", "{\"ok\":false,\"message\":\"Yetki yetersiz\"}"); return; }
        if ("exaroton".equals(provider)) remoteAction(action, value); else localAction(action, value);
        audit(auth.username, action, provider, "OK"); send(exchange, 200, "application/json", "{\"ok\":true}");
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
    private String mobilePage(String nonce) { return """
        <!doctype html><html lang='tr'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>AeroMC Remote</title>
        <style nonce='%s'>*{box-sizing:border-box}body{margin:0;background:#0d141b;color:#e7f0f5;font:15px system-ui;padding:18px}.wrap{max-width:620px;margin:auto}.card{background:#15212b;border:1px solid #263642;border-radius:14px;padding:16px;margin:12px 0}h1{color:#55b9ea}button,select,input{border:1px solid #38505f;border-radius:9px;background:#0f1921;color:#e7f0f5;padding:12px;margin:4px}button{background:#2789b8;font-weight:700}button.danger{background:#793b42}.grid{display:grid;grid-template-columns:repeat(2,1fr)}input{width:calc(100%% - 8px)}pre{white-space:pre-wrap;color:#9fc1d2}</style></head>
        <body><div class='wrap'><h1>AEROMC Remote</h1><div class='card'><select id='provider'><option value='local'>Yerel JAR</option><option value='exaroton'>Exaroton</option></select><button id='refresh'>Yenile</button><pre id='state'>Yükleniyor...</pre></div>
        <div class='card grid'><button data-action='start'>Başlat</button><button class='danger' data-action='stop'>Durdur</button><button data-action='restart'>Yeniden Başlat</button><button data-action='backup'>Yedek Al</button></div>
        <div class='card'><input id='value' placeholder='Mesaj, oyuncu veya komut'><div class='grid'><button data-action='say'>Duyuru</button><button data-action='kick'>Kick</button><button class='danger' data-action='ban'>Ban</button><button data-action='command'>Komut</button></div><pre id='result'></pre></div></div>
        <script nonce='%s'>const csrf='%s',provider=document.getElementById('provider'),value=document.getElementById('value'),state=document.getElementById('state'),result=document.getElementById('result');async function status(){let r=await fetch('/api/status',{cache:'no-store'});state.textContent=JSON.stringify(await r.json(),null,2)}async function act(a){let b=new URLSearchParams({_csrf:csrf,provider:provider.value,action:a,value:value.value});let r=await fetch('/api/action',{method:'POST',body:b,headers:{'Content-Type':'application/x-www-form-urlencoded'}});result.textContent=await r.text();status()}document.getElementById('refresh').addEventListener('click',status);document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>act(b.dataset.action)));status();setInterval(status,10000)</script></body></html>
        """.formatted(nonce, nonce, csrfToken); }

    private boolean allows(Role actual, Role required) { return actual.ordinal() >= required.ordinal(); }
    private String cleanPlayer(String value) { return CommandSecurity.playerName(value); }
    private String cleanMessage(String value) { String clean = CommandSecurity.singleLine(value, 300); if (clean.isEmpty()) throw new IllegalArgumentException("Mesaj boş olamaz"); return clean; }
    private String safeAudit(String value) { String clean = value.replaceAll("[\\r\\n]", " "); return clean.length() > 80 ? clean.substring(0, 80) : clean; }
    private Map<String, String> form(String body) {
        Map<String, String> values = new HashMap<>(); if (body.isBlank()) return values;
        String[] parts = body.split("&", -1); if (parts.length > 8) throw new IllegalArgumentException("Çok fazla form alanı.");
        for (String part : parts) { String[] pair = part.split("=", 2); String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8), value = URLDecoder.decode(pair.length > 1 ? pair[1] : "", StandardCharsets.UTF_8); if (key.isBlank() || values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Yinelenen veya boş form alanı."); }
        return values;
    }
    private void methodNotAllowed(HttpExchange exchange, String allow) throws IOException { exchange.getResponseHeaders().set("Allow", allow); send(exchange, 405, "text/plain", "Yönteme izin verilmiyor"); }
    private void send(HttpExchange exchange, int status, String type, String body) throws IOException { send(exchange, status, type, body, randomToken(18)); }
    private void send(HttpExchange exchange, int status, String type, String body, String nonce) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", type); exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.getResponseHeaders().set("X-Frame-Options", "DENY"); exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer"); exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()"); exchange.getResponseHeaders().set("Cross-Origin-Resource-Policy", "same-origin"); exchange.getResponseHeaders().set("Cross-Origin-Opener-Policy", "same-origin"); exchange.getResponseHeaders().set("X-Permitted-Cross-Domain-Policies", "none"); exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-" + nonce + "'; script-src 'nonce-" + nonce + "'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    private void audit(String user, String action, String target, String result) {
        try {
            prepareData();
            if (Files.isSymbolicLink(AUDIT)) throw new IOException("Güvenlik günlüğü simgesel bağlantı olamaz.");
            if (Files.isRegularFile(AUDIT, LinkOption.NOFOLLOW_LINKS) && Files.size(AUDIT) >= MAX_AUDIT_BYTES) {
                Path rotated = DATA.resolve("security.log.1"); if (Files.isSymbolicLink(rotated)) throw new IOException("Döndürülmüş günlük simgesel bağlantı olamaz.");
                Files.deleteIfExists(rotated); Files.move(AUDIT, rotated, StandardCopyOption.REPLACE_EXISTING); restrict(rotated, false);
            }
            String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\t" + safeAudit(user) + "\t" + safeAudit(action) + "\t" + safeAudit(target) + "\t" + safeAudit(result) + System.lineSeparator();
            Files.writeString(AUDIT, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); restrict(AUDIT, false);
        } catch (IOException ignored) { }
    }
    private void loadUsers() {
        Properties p = new Properties();
        try {
            if (!Files.exists(USERS, LinkOption.NOFOLLOW_LINKS)) return;
            if (!safeRegularFile(USERS) || Files.size(USERS) > MAX_USERS_BYTES) throw new IOException("Uzaktan kullanıcı dosyası geçersiz.");
            try (Reader reader = Files.newBufferedReader(USERS, StandardCharsets.UTF_8)) { p.load(reader); }
            if (p.size() > 100) throw new IOException("Çok fazla uzaktan kullanıcı kaydı.");
            for (String key : p.stringPropertyNames()) {
                String username = new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8); if (!username.matches("[A-Za-z0-9_.-]{3,24}")) throw new IOException("Geçersiz kullanıcı adı.");
                String[] v = p.getProperty(key).split(":", -1); int iterations; byte[] salt, hash; Role role;
                if (v.length == 3) { iterations = 180_000; salt = Base64.getDecoder().decode(v[0]); hash = Base64.getDecoder().decode(v[1]); role = Role.valueOf(v[2]); }
                else if (v.length == 4) { iterations = Integer.parseInt(v[0]); salt = Base64.getDecoder().decode(v[1]); hash = Base64.getDecoder().decode(v[2]); role = Role.valueOf(v[3]); }
                else throw new IOException("Uzaktan kullanıcı kaydı bozuk.");
                if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || salt.length != 16 || hash.length != 32) throw new IOException("Uzaktan kullanıcı güvenlik parametreleri geçersiz.");
                users.put(username, new Credential(salt, hash, role, iterations));
            }
        } catch (Exception ignored) { users.clear(); }
    }
    private void saveUsers() throws IOException {
        prepareData(); if (Files.isSymbolicLink(USERS)) throw new IOException("Uzaktan kullanıcı dosyası simgesel bağlantı olamaz.");
        Properties p = new Properties(); users.forEach((name, c) -> p.setProperty(Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)), c.iterations + ":" + Base64.getEncoder().encodeToString(c.salt) + ":" + Base64.getEncoder().encodeToString(c.hash) + ":" + c.role));
        Path temporary = Files.createTempFile(DATA, ".remote-users-", ".tmp");
        try { try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { p.store(writer, "AeroGuard V2.3 remote users - PBKDF2 hashes"); } restrict(temporary, false); try { Files.move(temporary, USERS, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, USERS, StandardCopyOption.REPLACE_EXISTING); } restrict(USERS, false); }
        finally { Files.deleteIfExists(temporary); }
    }
    private byte[] derive(char[] password, byte[] salt, int iterations) throws Exception { PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256); try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); } finally { spec.clearPassword(); } }
    private String localAddress() {
        try (DatagramSocket route = new DatagramSocket()) { route.connect(InetAddress.getByName("8.8.8.8"), 53); InetAddress selected = route.getLocalAddress(); if (selected instanceof Inet4Address && !selected.isLoopbackAddress()) return selected.getHostAddress(); } catch (Exception ignored) { }
        try { for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) { if (!network.isUp() || network.isLoopback() || network.isVirtual()) continue; for (InetAddress candidate : Collections.list(network.getInetAddresses())) if (candidate instanceof Inet4Address && candidate.isSiteLocalAddress()) return candidate.getHostAddress(); } } catch (Exception ignored) { }
        try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception error) { return "127.0.0.1"; }
    }
    private String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", ""); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
    private boolean allowAction(String ip) { if (actions.size() > 512) actions.entrySet().removeIf(entry -> entry.getValue().isEmpty()); Deque<Long> values = actions.computeIfAbsent(ip, key -> new ArrayDeque<>()); long now = System.currentTimeMillis(); synchronized (values) { while (!values.isEmpty() && now - values.peekFirst() > 60_000) values.removeFirst(); if (values.size() >= 30) return false; values.addLast(now); return true; } }
    private static byte[] readLimited(InputStream input, int max) throws IOException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int total = 0, count; while ((count = input.read(buffer)) >= 0) { total += count; if (total > max) throw new IOException("İstek gövdesi çok büyük."); output.write(buffer, 0, count); } return output.toByteArray(); }
    private static boolean constantEquals(String expected, String actual) { if (expected == null || actual == null) return false; return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)); }
    private static String randomToken(int bytes) { byte[] value = new byte[bytes]; new SecureRandom().nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static void prepareData() throws IOException { if (Files.exists(DATA, LinkOption.NOFOLLOW_LINKS)) { if (Files.isSymbolicLink(DATA) || !Files.isDirectory(DATA, LinkOption.NOFOLLOW_LINKS)) throw new IOException("AeroMC veri klasörü geçersiz veya simgesel bağlantı."); } else Files.createDirectories(DATA); restrict(DATA, true); }
    private static boolean safeRegularFile(Path file) { return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file); }
    private static void restrict(Path path, boolean directory) { try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); } catch (IOException | UnsupportedOperationException ignored) { } }
    private final class Credential { final byte[] salt, hash; final Role role; final int iterations; Credential(byte[] salt, byte[] hash, Role role, int iterations) { this.salt = salt; this.hash = hash; this.role = role; this.iterations = iterations; } boolean verify(char[] password) { byte[] candidate = null; try { candidate = derive(password, salt, iterations); return MessageDigest.isEqual(hash, candidate); } catch (Exception error) { return false; } finally { if (candidate != null) Arrays.fill(candidate, (byte) 0); } } }
    public record AuditEntry(String time, String user, String action, String target, String result) { }
    private record Auth(String username, Role role) { }
}
