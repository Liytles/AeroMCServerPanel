package com.aerogroup.mcpanel;

import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Discord webhook isteklerini sıraya alır ve 429 hız sınırında bir kez güvenli tekrar yapar. */
public final class DiscordWebhookClient implements AutoCloseable {
    public record Result(boolean success, int status, String message) { }
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "aeromc-discord-webhook"); thread.setDaemon(true); return thread; });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CompletableFuture<Result> send(URI webhook, String payload) { return CompletableFuture.supplyAsync(() -> sendNow(webhook, payload, true), queue); }
    private Result sendNow(URI webhook, String payload, boolean retry) {
        try {
            HttpRequest request = HttpRequest.newBuilder(webhook).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString()); int status = response.statusCode();
            if (status >= 200 && status < 300) return new Result(true, status, "Discord bildirimi gönderildi");
            if (status == 429 && retry) { long delay = retryMillis(response.body()); Thread.sleep(Math.min(30_000, Math.max(250, delay))); return sendNow(webhook, payload, false); }
            return new Result(false, status, status == 429 ? "Discord hız sınırı aşıldı" : "Discord mesajı reddetti: HTTP " + status);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return new Result(false, 0, "Discord gönderimi iptal edildi"); }
        catch (Exception error) { return new Result(false, 0, "Discord bağlantısı kurulamadı: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())); }
    }
    static long retryMillis(String body) { try { double seconds = JsonParser.parseString(body).getAsJsonObject().get("retry_after").getAsDouble(); return Math.round(seconds * 1000); } catch (Exception ignored) { return 1000; } }
    public void close() { queue.shutdownNow(); }
}
