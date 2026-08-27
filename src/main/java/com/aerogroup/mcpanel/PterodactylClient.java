package com.aerogroup.mcpanel;

import com.google.gson.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Pterodactyl 1.x Client API için yönlendirme ve gizli bilgi sızıntısına karşı sıkı istemci. */
public final class PterodactylClient {
    public enum PowerSignal {
        START("start"), STOP("stop"), RESTART("restart"), KILL("kill");
        private final String wire;
        PowerSignal(String wire) { this.wire = wire; }
    }

    public enum PowerState { OFFLINE, STARTING, RUNNING, STOPPING, UNKNOWN }

    public record ServerInfo(String identifier, String uuid, String name, String description, String node,
                             String panelStatus, boolean suspended, int memoryLimitMb, int diskLimitMb,
                             double cpuLimitPercent, String allocation) {
        @Override public String toString() { return name + " • " + identifier; }
    }

    public record Resources(PowerState state, boolean suspended, long memoryBytes, double cpuPercent,
                            long diskBytes, long networkRxBytes, long networkTxBytes, long uptimeMillis) { }

    public record ConsoleStats(PowerState state, long memoryBytes, double cpuPercent, long diskBytes,
                               long networkRxBytes, long networkTxBytes, long uptimeMillis) { }

    public interface ConsoleListener {
        default void onConsole(String line) { }
        default void onStats(ConsoleStats stats) { }
        default void onStatus(PowerState state) { }
        default void onClosed(String reason) { }
        default void onError(Throwable error) { }
    }

    public static final class ConsoleSession implements AutoCloseable {
        private final WebSocket socket;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ConsoleSession(WebSocket socket) { this.socket = socket; }
        @Override public void close() { if (closed.compareAndSet(false, true)) socket.sendClose(WebSocket.NORMAL_CLOSURE, "AeroMC kapatıldı"); }
        public boolean isOpen() { return !closed.get() && !socket.isOutputClosed(); }
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int MAX_WEBSOCKET_MESSAGE_CHARS = 1_000_000;
    private final URI panelUri;
    private final String apiKey;
    private final HttpClient http;

    public PterodactylClient(String panelUrl, String apiKey) {
        this(panelUrl, apiKey, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    PterodactylClient(String panelUrl, String apiKey, HttpClient http) {
        this.panelUri = normalizePanelUri(panelUrl);
        this.apiKey = validateApiKey(apiKey);
        this.http = Objects.requireNonNull(http);
    }

    public URI panelUri() { return panelUri; }

    public List<ServerInfo> listServers() throws Exception {
        List<ServerInfo> result = new ArrayList<>();
        int page = 1, totalPages = 1;
        do {
            JsonObject root = get("api/client?per_page=100&page=" + page);
            JsonArray data = array(root, "data");
            for (JsonElement element : data) {
                JsonObject item = object(element), attributes = object(item, "attributes");
                String identifier = string(attributes, "identifier");
                if (!IDENTIFIER.matcher(identifier).matches()) continue;
                JsonObject limits = object(attributes, "limits");
                result.add(new ServerInfo(identifier, string(attributes, "uuid"),
                        fallback(string(attributes, "name"), identifier), string(attributes, "description"),
                        string(attributes, "node"), string(attributes, "status"), bool(attributes, "is_suspended"),
                        integer(limits, "memory"), integer(limits, "disk"), decimal(limits, "cpu"),
                        primaryAllocation(attributes)));
            }
            JsonObject pagination = object(object(root, "meta"), "pagination");
            totalPages = Math.max(1, Math.min(20, integer(pagination, "total_pages")));
            page++;
        } while (page <= totalPages && result.size() < 2_000);
        return List.copyOf(result);
    }

    public Resources resources(String identifier) throws Exception {
        JsonObject attributes = object(get("api/client/servers/" + safeIdentifier(identifier) + "/resources"), "attributes");
        JsonObject resources = object(attributes, "resources");
        return new Resources(powerState(string(attributes, "current_state")), bool(attributes, "is_suspended"),
                longValue(resources, "memory_bytes"), decimal(resources, "cpu_absolute"), longValue(resources, "disk_bytes"),
                longValue(resources, "network_rx_bytes"), longValue(resources, "network_tx_bytes"), longValue(resources, "uptime"));
    }

    public void power(String identifier, PowerSignal signal) throws Exception {
        Objects.requireNonNull(signal);
        post("api/client/servers/" + safeIdentifier(identifier) + "/power", "signal", signal.wire);
    }

    public void command(String identifier, String command) throws Exception {
        String value = Objects.toString(command, "").trim();
        if (value.isEmpty() || value.length() > 1_000 || value.chars().anyMatch(character -> character < 32 && character != '\t'))
            throw new IllegalArgumentException("Pterodactyl konsol komutu boş, çok uzun veya geçersiz karakter içeriyor.");
        post("api/client/servers/" + safeIdentifier(identifier) + "/command", "command", value);
    }

    public String readFile(String identifier, String file) throws Exception {
        String relative = "api/client/servers/" + safeIdentifier(identifier) + "/files/contents?file=" + safeFile(file);
        BoundedResponse response = send(request(relative).GET().build());
        return parseText(response);
    }

    public void writeFile(String identifier, String file, String content) throws Exception {
        String value = Objects.toString(content, "");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Pterodactyl dosya içeriği güvenli boyut sınırını aştı.");
        String relative = "api/client/servers/" + safeIdentifier(identifier) + "/files/write?file=" + safeFile(file);
        BoundedResponse response = send(request(relative).header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8)).build());
        parse(response, true);
    }

    public ConsoleSession openConsole(String identifier, ConsoleListener listener) throws Exception {
        Objects.requireNonNull(listener);
        JsonObject data = object(get("api/client/servers/" + safeIdentifier(identifier) + "/websocket"), "data");
        String token = string(data, "token"); URI socketUri = validateSocketUri(string(data, "socket"));
        if (token.isBlank() || token.length() > 8_192) throw new IOException("Pterodactyl WebSocket bileti geçersiz.");
        SocketListener bridge = new SocketListener(token, listener);
        WebSocket socket;
        try { socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Origin", panelOrigin()).buildAsync(socketUri, bridge).get(15, TimeUnit.SECONDS); }
        catch (ExecutionException error) { throw error.getCause() instanceof Exception exception ? exception : error; }
        return new ConsoleSession(socket);
    }

    static URI normalizePanelUri(String input) {
        String value = Objects.toString(input, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Pterodactyl panel adresi boş olamaz.");
        if (!value.contains("://")) value = "https://" + value;
        try {
            URI parsed = new URI(value);
            String scheme = Objects.toString(parsed.getScheme(), "").toLowerCase(Locale.ROOT);
            String host = Objects.toString(parsed.getHost(), "").toLowerCase(Locale.ROOT);
            if (host.isBlank() || parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null)
                throw new IllegalArgumentException("Pterodactyl panel adresi kullanıcı bilgisi, sorgu veya parça içeremez.");
            if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(host)))
                throw new IllegalArgumentException("Pterodactyl bağlantısı HTTPS kullanmalı. HTTP yalnızca bu bilgisayardaki test paneli için kabul edilir.");
            String path = Objects.toString(parsed.getPath(), "").replaceAll("/+$", "");
            if (path.endsWith("/api/client")) path = path.substring(0, path.length() - "/api/client".length());
            path = path.replaceAll("/+$", "");
            if (path.isBlank()) path = "/"; else path += "/";
            return new URI(scheme, null, host, parsed.getPort(), path, null, null);
        } catch (URISyntaxException error) { throw new IllegalArgumentException("Pterodactyl panel adresi geçersiz.", error); }
    }

    private JsonObject get(String relative) throws Exception {
        BoundedResponse response = send(request(relative).GET().build());
        return parse(response, false);
    }

    private void post(String relative, String key, String value) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty(key, value);
        BoundedResponse response = send(request(relative).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                MAX_RESPONSE_BYTES);
        parse(response, true);
    }

    private BoundedResponse send(HttpRequest request) throws Exception { return send(request, MAX_RESPONSE_BYTES); }
    private BoundedResponse send(HttpRequest request, int maximumBytes) throws Exception {
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new BoundedResponse(response.statusCode(), BoundedStreams.readString(response.body(), maximumBytes, StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String relative) {
        return HttpRequest.newBuilder(endpoint(relative)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("User-Agent", "AeroMC-Server-Panel/" + BuildInfo.version());
    }

    private String parseText(BoundedResponse response) throws IOException {
        String body = Objects.toString(response.body(), "");
        if (response.statusCode() / 100 != 2) { parse(response, true); }
        return body;
    }

    private JsonObject parse(BoundedResponse response, boolean allowEmpty) throws IOException {
        int status = response.statusCode(); String body = Objects.toString(response.body(), "");
        if (status >= 300 && status < 400) throw new IOException("Pterodactyl paneli başka bir adrese yönlendirdi. API anahtarını korumak için yönlendirme izlenmedi; son HTTPS panel adresini gir.");
        if (status / 100 != 2) throw new IOException(apiError(status, body));
        if (body.isBlank()) { if (allowEmpty) return new JsonObject(); throw new IOException("Pterodactyl API boş yanıt verdi."); }
        try { return JsonParser.parseString(body).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("Pterodactyl API geçersiz JSON döndürdü.", error); }
    }

    private URI endpoint(String relative) {
        String clean = Objects.toString(relative, "").replaceFirst("^/+", "");
        int queryStart = clean.indexOf('?');
        String path = queryStart >= 0 ? clean.substring(0, queryStart) : clean;
        String query = queryStart >= 0 ? clean.substring(queryStart + 1) : null;
        try { return new URI(panelUri.getScheme(), null, panelUri.getHost(), panelUri.getPort(), panelUri.getPath() + path, query, null); }
        catch (URISyntaxException error) { throw new IllegalArgumentException("Pterodactyl API yolu oluşturulamadı.", error); }
    }

    private String panelOrigin() { String defaultPort = panelUri.getScheme().equals("https") ? "443" : "80"; return panelUri.getScheme() + "://" + panelUri.getHost() + (panelUri.getPort() < 0 || Integer.toString(panelUri.getPort()).equals(defaultPort) ? "" : ":" + panelUri.getPort()); }

    private static String validateApiKey(String input) {
        String value = Objects.toString(input, "").trim();
        if (value.length() < 20 || value.length() > 512 || value.chars().anyMatch(character -> character <= 32 || character == 127))
            throw new IllegalArgumentException("Geçerli bir Pterodactyl Client API anahtarı gir.");
        return value;
    }

    private static String safeIdentifier(String value) {
        String identifier = Objects.toString(value, "").trim();
        if (!IDENTIFIER.matcher(identifier).matches()) throw new IllegalArgumentException("Pterodactyl sunucu kimliği geçersiz.");
        return identifier;
    }

    private static String safeFile(String value) {
        String file = Objects.toString(value, "").trim().replace('\\', '/');
        if (!file.startsWith("/") || file.length() > 512 || file.contains("..") || !file.matches("/[A-Za-z0-9_./-]+"))
            throw new IllegalArgumentException("Pterodactyl dosya yolu geçersiz.");
        return file;
    }

    private static URI validateSocketUri(String value) throws IOException {
        try {
            URI uri = new URI(value); String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT), host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
            if (host.isBlank() || uri.getUserInfo() != null || uri.getFragment() != null || (!scheme.equals("wss") && !(scheme.equals("ws") && isLoopback(host))))
                throw new IOException("Pterodactyl WebSocket adresi güvenli değil.");
            return uri;
        } catch (URISyntaxException error) { throw new IOException("Pterodactyl WebSocket adresi geçersiz.", error); }
    }

    private static final class SocketListener implements WebSocket.Listener {
        private final String token;
        private final ConsoleListener listener;
        private final StringBuilder message = new StringBuilder();
        private SocketListener(String token, ConsoleListener listener) { this.token = token; this.listener = listener; }
        @Override public void onOpen(WebSocket socket) { authenticate(socket); socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (data.length() > MAX_WEBSOCKET_MESSAGE_CHARS - message.length()) {
                message.setLength(0);
                listener.onError(new IOException("Pterodactyl WebSocket mesajı güvenli boyut sınırını aştı."));
                socket.sendClose(1009, "message too large");
                return null;
            }
            message.append(data);
            if (last) { String complete = message.toString(); message.setLength(0); handle(socket, complete); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) { listener.onClosed(reason == null || reason.isBlank() ? "WebSocket kapandı (" + statusCode + ")" : reason); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { listener.onError(error); }
        private void handle(WebSocket socket, String text) {
            try {
                JsonObject root = JsonParser.parseString(text).getAsJsonObject(); String event = string(root, "event"); JsonArray args = array(root, "args");
                if (event.equals("auth success")) { send(socket, "send logs"); send(socket, "send stats"); return; }
                if (event.equals("token expiring") || event.equals("token expired")) { listener.onClosed("Pterodactyl konsol bileti yenilenmeli"); socket.sendClose(WebSocket.NORMAL_CLOSURE, "token refresh"); return; }
                if (args.isEmpty()) return; String first = args.get(0).isJsonPrimitive() ? args.get(0).getAsString() : args.get(0).toString();
                if (event.equals("console output")) listener.onConsole(first);
                else if (event.equals("status")) listener.onStatus(powerState(first));
                else if (event.equals("stats")) {
                    JsonObject stats = JsonParser.parseString(first).getAsJsonObject();
                    listener.onStats(new ConsoleStats(powerState(string(stats, "state")), longValue(stats, "memory_bytes"), decimal(stats, "cpu_absolute"), longValue(stats, "disk_bytes"), longValue(stats, "network_rx_bytes"), longValue(stats, "network_tx_bytes"), longValue(stats, "uptime")));
                }
            } catch (RuntimeException error) { listener.onError(new IOException("Pterodactyl WebSocket mesajı çözümlenemedi.", error)); }
        }
        private void send(WebSocket socket, String event) { JsonObject value = new JsonObject(); value.addProperty("event", event); JsonArray args = new JsonArray(); args.add(JsonNull.INSTANCE); value.add("args", args); socket.sendText(value.toString(), true); }
        private void authenticate(WebSocket socket) { JsonObject value = new JsonObject(); value.addProperty("event", "auth"); JsonArray args = new JsonArray(); args.add(token); value.add("args", args); socket.sendText(value.toString(), true); }
    }

    private static PowerState powerState(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "offline" -> PowerState.OFFLINE;
            case "starting" -> PowerState.STARTING;
            case "running" -> PowerState.RUNNING;
            case "stopping" -> PowerState.STOPPING;
            default -> PowerState.UNKNOWN;
        };
    }

    private static String primaryAllocation(JsonObject attributes) {
        JsonArray allocations = array(object(object(attributes, "relationships"), "allocations"), "data");
        JsonObject fallback = null;
        for (JsonElement element : allocations) {
            JsonObject allocation = object(object(element), "attributes"); if (fallback == null) fallback = allocation;
            if (bool(allocation, "is_default")) return allocationText(allocation);
        }
        return fallback == null ? "-" : allocationText(fallback);
    }

    private static String allocationText(JsonObject allocation) {
        String host = fallback(string(allocation, "alias"), string(allocation, "ip")); int port = integer(allocation, "port");
        if (host.isBlank()) return "-"; return port > 0 ? host + ":" + port : host;
    }

    private static String apiError(int status, String body) {
        String detail = "";
        try {
            JsonArray errors = array(JsonParser.parseString(body).getAsJsonObject(), "errors");
            if (!errors.isEmpty()) detail = string(object(errors.get(0)), "detail");
        } catch (RuntimeException ignored) { }
        if (status == 401 || status == 403) return "Pterodactyl API anahtarı reddedildi veya gerekli sunucu izni yok.";
        if (status == 404) return "Pterodactyl API yolu ya da sunucu bulunamadı. Panel adresini ve sunucu yetkisini kontrol et.";
        if (status == 429) return "Pterodactyl API istek sınırına ulaşıldı; kısa süre sonra yeniden dene.";
        String safeDetail = detail.replace('\n', ' ').replace('\r', ' '); if (safeDetail.length() > 300) safeDetail = safeDetail.substring(0, 300) + "…";
        return "Pterodactyl API HTTP " + status + (safeDetail.isBlank() ? " ile başarısız oldu." : " • " + safeDetail);
    }

    private static boolean isLoopback(String host) { return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1"); }
    private static JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject(); }
    private static JsonObject object(JsonObject value, String key) { return value != null && value.has(key) ? object(value.get(key)) : new JsonObject(); }
    private static JsonArray array(JsonObject value, String key) { return value != null && value.has(key) && value.get(key).isJsonArray() ? value.getAsJsonArray(key) : new JsonArray(); }
    private static String string(JsonObject value, String key) { try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString().trim() : ""; } catch (RuntimeException ignored) { return ""; } }
    private static boolean bool(JsonObject value, String key) { try { return value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean(); } catch (RuntimeException ignored) { return false; } }
    private static int integer(JsonObject value, String key) { try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : 0; } catch (RuntimeException ignored) { return 0; } }
    private static long longValue(JsonObject value, String key) { try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsLong() : 0; } catch (RuntimeException ignored) { return 0; } }
    private static double decimal(JsonObject value, String key) { try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsDouble() : 0; } catch (RuntimeException ignored) { return 0; } }
    private static String fallback(String value, String other) { return value == null || value.isBlank() ? Objects.toString(other, "") : value; }
    private record BoundedResponse(int statusCode, String body) { }
}
