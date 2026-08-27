package com.aerogroup.mcpanel;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class DiscordNotificationSmoke {
    public static void main(String[] args) throws Exception {
        String url = "https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz";
        require(DiscordNotificationEngine.validateWebhook(url).getHost().equals("discord.com"), "official webhook accepted");
        try { DiscordNotificationEngine.validateWebhook("https://discord.com.evil.example/api/webhooks/1/token"); throw new IllegalStateException("unsafe host accepted"); } catch (IllegalArgumentException expected) { }
        rejected("https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz?redirect=1", "webhook query rejected");
        rejected("https://user@discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz", "webhook userinfo rejected");
        rejected("https://discord.com/api/webhooks/not-an-id/abcdefghijklmnopqrstuvwxyz", "invalid webhook id rejected");
        var settings = new DiscordNotificationEngine.Settings(true, EnumSet.of(DiscordNotificationEngine.Type.CRASH), "AeroMC Test", true, "123456789012345678");
        var event = new DiscordNotificationEngine.Event(DiscordNotificationEngine.Type.CRASH, "Sunucu çöktü", "@everyone test", "Exaroton", "SMP", true);
        require(DiscordNotificationEngine.shouldSend(settings, event), "event filter");
        JsonObject payload = JsonParser.parseString(DiscordNotificationEngine.payload(settings, event)).getAsJsonObject();
        require(payload.getAsJsonArray("embeds").size() == 1, "embed payload"); require(payload.get("content").getAsString().contains("123456789012345678"), "critical role mention");
        JsonObject mentions = payload.getAsJsonObject("allowed_mentions"); require(mentions.getAsJsonArray("parse").isEmpty(), "unsafe mentions disabled"); require(mentions.getAsJsonArray("roles").size() == 1, "one explicit role");
        require(DiscordWebhookClient.retryMillis("{\"retry_after\":1.25}") == 1250, "rate-limit retry parsing");
        Path file = Files.createTempFile("aeromc-discord-", ".secret"); char[] password = "güvenli-parola".toCharArray();
        try { DiscordWebhookStore.save(file, url, password); require(!Files.readString(file).contains(url), "webhook encrypted at rest"); require(DiscordWebhookStore.load(file, password).equals(url), "encrypted webhook roundtrip"); }
        finally { Arrays.fill(password, '\0'); Files.deleteIfExists(file); }
        System.out.println("discord-notification-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
    private static void rejected(String value, String feature) { try { DiscordNotificationEngine.validateWebhook(value); throw new IllegalStateException("Smoke test failed: " + feature); } catch (IllegalArgumentException expected) { } }
}
