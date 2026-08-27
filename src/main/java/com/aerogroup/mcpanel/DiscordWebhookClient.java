package com.aerogroup.mcpanel;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Discord webhook isteklerini sıraya alır ve 429 hız sınırında bir kez güvenli tekrar yapar. */
public final class DiscordWebhookClient implements AutoCloseable {
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    public record Result(boolean success, int status, String message) { }
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "aeromc-discord-webhook"); thread.setDaemon(true); return thread; });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public CompletableFuture<Result> send(URI webhook, String payload) {
        URI safeWebhook = DiscordNotificationEngine.validateWebhook(webhook == null ? "" : webhook.toString());
        String safePayload = payload == null ? "" : payload;
        if (safePayload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Discord bildirimi güvenli boyut sınırını aştı.");
        return CompletableFuture.supplyAsync(() -> sendNow(safeWebhook, safePayload, true), queue);
    }
    private Result sendNow(URI webhook, String payload, boolean retry) {
        try {
            HttpRequest request = HttpRequest.newBuilder(webhook).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream()); int status = response.statusCode();
            String body = BoundedStreams.readString(response.body(), MAX_RESPONSE_BYTES, StandardCharsets.UTF_8);
            if (status >= 200 && status < 300) return new Result(true, status, "Discord bildirimi gönderildi");
            if (status == 429 && retry) { long delay = retryMillis(body); Thread.sleep(Math.min(30_000, Math.max(250, delay))); return sendNow(webhook, payload, false); }
            return new Result(false, status, status == 429 ? "Discord hız sınırı aşıldı" : "Discord mesajı reddetti: HTTP " + status);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return new Result(false, 0, "Discord gönderimi iptal edildi"); }
        catch (Exception error) { return new Result(false, 0, "Discord ile güvenli bağlantı kurulamadı."); }
    }
    static long retryMillis(String body) { try { double seconds = JsonParser.parseString(body).getAsJsonObject().get("retry_after").getAsDouble(); return Math.round(seconds * 1000); } catch (Exception ignored) { return 1000; } }
    public void close() { queue.shutdownNow(); }
}
