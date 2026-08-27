package com.aerogroup.mcpanel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class PterodactylApiSmoke {
    private static final String KEY = "ptlc_AeroMCOfflineSmokeKey123456789";

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> powerBody = new AtomicReference<>(""), commandBody = new AtomicReference<>(""), fileBody = new AtomicReference<>("");
        server.createContext("/api/client", exchange -> {
            requireAuth(exchange);
            if (!"GET".equals(exchange.getRequestMethod())) { respond(exchange, 405, "{}"); return; }
            require("/api/client".equals(exchange.getRequestURI().getPath()), "server list API path");
            require("per_page=100&page=1".equals(exchange.getRequestURI().getRawQuery()), "server list query");
            respond(exchange, 200, """
                    {"object":"list","data":[{"object":"server","attributes":{
                      "identifier":"a1b2c3d4","uuid":"a1b2c3d4-0000-0000-0000-000000000000","name":"Aero Test",
                      "description":"Smoke server","node":"Node-1","status":null,"is_suspended":false,
                      "limits":{"memory":4096,"disk":10240,"cpu":200},
                      "relationships":{"allocations":{"data":[{"attributes":{"ip":"127.0.0.1","alias":"play.example.test","port":25565,"is_default":true}}]}}
                    }}],"meta":{"pagination":{"total_pages":1}}}
                    """);
        });
        server.createContext("/api/client/servers/a1b2c3d4/resources", exchange -> {
            requireAuth(exchange); respond(exchange, 200, """
                    {"object":"stats","attributes":{"current_state":"running","is_suspended":false,
                    "resources":{"memory_bytes":1073741824,"cpu_absolute":24.5,"disk_bytes":2147483648,
                    "network_rx_bytes":1000,"network_tx_bytes":2000,"uptime":3600000}}}
                    """);
        });
        server.createContext("/api/client/servers/a1b2c3d4/power", exchange -> {
            requireAuth(exchange); powerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); respond(exchange, 204, "");
        });
        server.createContext("/api/client/servers/a1b2c3d4/command", exchange -> {
            requireAuth(exchange); commandBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); respond(exchange, 204, "");
        });
        server.createContext("/api/client/servers/a1b2c3d4/files/contents", exchange -> {
            requireAuth(exchange); require("file=/server.properties".equals(exchange.getRequestURI().getRawQuery()), "file read query"); respond(exchange, 200, "motd=AeroMC\\nmax-players=20\\n");
        });
        server.createContext("/api/client/servers/a1b2c3d4/files/write", exchange -> {
            requireAuth(exchange); require("file=/server.properties".equals(exchange.getRequestURI().getRawQuery()), "file write query"); fileBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); respond(exchange, 204, "");
        });
        server.createContext("/large/api/client", exchange -> { requireAuth(exchange); respond(exchange, 200, "x".repeat(2_100_000)); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            PterodactylClient client = new PterodactylClient(url + "/api/client/", KEY);
            var servers = client.listServers();
            require(servers.size() == 1, "server list parsed");
            require("Aero Test".equals(servers.get(0).name()), "server name parsed");
            require("play.example.test:25565".equals(servers.get(0).allocation()), "primary allocation parsed");
            require(servers.get(0).memoryLimitMb() == 4096 && servers.get(0).cpuLimitPercent() == 200, "limits parsed");
            var resources = client.resources("a1b2c3d4");
            require(resources.state() == PterodactylClient.PowerState.RUNNING, "power state parsed");
            require(resources.memoryBytes() == 1073741824L && resources.cpuPercent() == 24.5, "resource usage parsed");
            client.power("a1b2c3d4", PterodactylClient.PowerSignal.RESTART);
            require(powerBody.get().contains("\"signal\":\"restart\""), "power payload sent");
            client.command("a1b2c3d4", "say AeroMC V4");
            require(commandBody.get().contains("\"command\":\"say AeroMC V4\""), "command payload sent");
            require(client.readFile("a1b2c3d4", "/server.properties").contains("motd=AeroMC"), "remote file read");
            client.writeFile("a1b2c3d4", "/server.properties", "motd=AeroMC V4\\n");
            require(fileBody.get().contains("motd=AeroMC V4"), "remote file write");
            requireFails(() -> PterodactylClient.normalizePanelUri("http://panel.example.com"), "remote HTTP rejected");
            requireFails(() -> PterodactylClient.normalizePanelUri("https://user:pass@panel.example.com"), "userinfo rejected");
            requireFails(() -> new PterodactylClient(url, "short"), "short API key rejected");
            requireFails(() -> client.resources("../admin"), "unsafe server identifier rejected");
            requireFails(() -> client.readFile("a1b2c3d4", "/../etc/passwd"), "unsafe remote file rejected");
            requireFails(() -> new PterodactylClient(url + "/large", KEY).listServers(), "oversized API response rejected");
            System.out.println("pterodactyl-client-api-ok");
        } finally { server.stop(0); }
    }

    private static void requireAuth(HttpExchange exchange) {
        require(("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization")), "bearer authentication header");
        require("application/json".equals(exchange.getRequestHeaders().getFirst("Accept")), "JSON accept header");
    }
    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length); if (status != 204) exchange.getResponseBody().write(bytes); exchange.close();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static void require(boolean condition, String feature) { if (!condition) throw new IllegalStateException("Smoke test failed: " + feature); }
    private static void requireFails(Checked action, String feature) throws Exception { boolean failed = false; try { action.run(); } catch (Exception expected) { failed = true; } require(failed, feature); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
