package com.aerogroup.mcpanel;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eklenti gerektirmeden Minecraft sohbet satırlarında yazılan .aeromc komutlarını işler.
 * Yetki vanilla {@code ops.json} listesinden okunur; oyuncu girdisi asla konsol komutuna
 * doğrudan eklenmez.
 */
final class InGameAeroMCBridge {
    enum Provider { LOCAL, EXAROTON, PTERODACTYL }

    record Snapshot(boolean online, int players, int maximumPlayers, double tps, double ramPercent,
                    double latencyMs, boolean crisisActive, double cpuPercent, long memoryMb, long memoryLimitMb) { }

    private static final Pattern CHAT = Pattern.compile("<([A-Za-z0-9_]{3,16})>\\s+\\.aeromc(?:\\s+([A-Za-zçğıöşüÇĞİÖŞÜ]+)(?:\\s+(.+?))?)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Duration OPS_CACHE_TTL = Duration.ofSeconds(45);
    private static final long PLAYER_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final PanelConfig config;
    private final Function<Provider, CompletableFuture<String>> opsReader;
    private final Function<Provider, CompletableFuture<Snapshot>> snapshotReader;
    private final BiConsumer<Provider, String> commandSender;
    private final Consumer<String> audit;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> { Thread thread = new Thread(runnable, "aeromc-ingame-bridge"); thread.setDaemon(true); return thread; });
    private final Map<Provider, OpsCache> opsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    InGameAeroMCBridge(PanelConfig config, Function<Provider, CompletableFuture<String>> opsReader,
                        Function<Provider, CompletableFuture<Snapshot>> snapshotReader,
                        BiConsumer<Provider, String> commandSender, Consumer<String> audit) {
        this.config = Objects.requireNonNull(config); this.opsReader = Objects.requireNonNull(opsReader);
        this.snapshotReader = Objects.requireNonNull(snapshotReader); this.commandSender = Objects.requireNonNull(commandSender);
        this.audit = audit == null ? ignored -> { } : audit;
    }

    void accept(Provider provider, String line) {
        if (!config.isInGameCommandsEnabled() || line == null || line.length() > 600) return;
        Matcher match = CHAT.matcher(line.trim()); if (!match.find()) return;
        String player = match.group(1), operation = normalize(match.group(2)), argument = match.group(3);
        if (operation == null || !allowPlayer(provider, player)) return;
        allowed(provider, player).thenCompose(allowed -> {
            if (!allowed) { audit.accept("Oyun içi AeroMC isteği reddedildi: " + player + " OP değil"); return CompletableFuture.completedFuture(null); }
            if ("announce".equals(operation)) {
                String announcement = announcement(argument);
                if (announcement == null) {
                    commandSender.accept(provider, tellraw(player, "§c[AeroMC] §fKullanım: §e.aeromc duyur <mesaj> §7(1-160 karakter)"));
                    return CompletableFuture.completedFuture(null);
                }
                commandSender.accept(provider, tellrawAll("§6[AeroMC Duyuru] §f" + announcement));
                commandSender.accept(provider, tellraw(player, "§a[AeroMC] §fDuyuru tüm oyunculara gönderildi."));
                audit.accept("Oyun içi AeroMC duyurusu: " + player);
                return CompletableFuture.completedFuture(null);
            }
            return snapshotReader.apply(provider);
        }).thenAccept(snapshot -> {
            if (snapshot == null) return;
            String response = response(operation, snapshot);
            commandSender.accept(provider, tellraw(player, response));
            audit.accept("Oyun içi AeroMC komutu: " + player + " • " + operation);
        }).exceptionally(error -> { audit.accept("Oyun içi AeroMC komutu okunamadı: " + root(error)); return null; });
    }

    private boolean allowPlayer(Provider provider, String player) {
        String key = provider.name() + ':' + player.toLowerCase(Locale.ROOT); long now = System.nanoTime();
        Long previous = cooldowns.put(key, now); if (previous != null && now - previous < PLAYER_COOLDOWN_NANOS) return false;
        if (cooldowns.size() > 300) cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > TimeUnit.MINUTES.toNanos(5));
        return true;
    }

    private CompletableFuture<Boolean> allowed(Provider provider, String player) {
        OpsCache cached = opsCache.get(provider);
        if (cached != null && !cached.expired()) return CompletableFuture.completedFuture(cached.players().contains(player.toLowerCase(Locale.ROOT)));
        return opsReader.apply(provider).thenApplyAsync(InGameAeroMCBridge::parseOperators, worker).handle((operators, error) -> {
            if (error != null) { audit.accept("ops.json okunamadı: " + root(error)); return Set.<String>of(); }
            opsCache.put(provider, new OpsCache(operators, System.nanoTime())); return operators;
        }).thenApply(operators -> operators.contains(player.toLowerCase(Locale.ROOT)));
    }

    static Set<String> parseOperators(String raw) {
        if (raw == null || raw.length() > 131_072) return Set.of();
        try {
            Set<String> names = new HashSet<>();
            for (JsonElement item : JsonParser.parseString(raw).getAsJsonArray()) {
                if (!item.isJsonObject() || !item.getAsJsonObject().has("name")) continue;
                String name = item.getAsJsonObject().get("name").getAsString();
                if (name.matches("[A-Za-z0-9_]{3,16}")) names.add(name.toLowerCase(Locale.ROOT));
            }
            return Set.copyOf(names);
        } catch (RuntimeException ignored) { return Set.of(); }
    }

    private static String normalize(String input) {
        String value = input == null || input.isBlank() ? "help" : input.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "sağlık", "saglik", "health" -> "health";
            case "durum", "status", "bilgi", "info" -> "status";
            case "oyuncu", "oyuncular", "players" -> "players";
            case "performans", "performance", "lag", "metrics" -> "performance";
            case "kriz", "crisis" -> "crisis";
            case "duyur", "announce", "broadcast" -> "announce";
            case "yardım", "yardim", "help" -> "help";
            default -> null;
        };
    }
    private static String response(String operation, Snapshot value) {
        if ("help".equals(operation)) return "§b[AeroMC] §fAna komut: §e.aeromc sağlık §7(tüm özet) §f• §e.aeromc duyur <mesaj> §7• Diğerleri: performans, oyuncular, kriz, durum";
        String state = value.online() ? "§aÇevrimiçi" : "§cKapalı";
        String players = value.maximumPlayers() > 0 ? value.players() + "/" + value.maximumPlayers() : Integer.toString(Math.max(0, value.players()));
        if ("status".equals(operation)) return "§b[AeroMC] §fDurum: " + state + " §7• §fOyuncu: §b" + players;
        if ("players".equals(operation)) return "§b[AeroMC] §fÇevrimiçi oyuncu: §b" + players + (value.online() ? "" : " §7• Sunucu kapalı");
        if ("crisis".equals(operation)) return "§b[AeroMC] §fKriz Modu: " + (value.crisisActive() ? "§cETKİN §7• Koruyucu ayarlar uygulanıyor" : "§aBeklemede");
        String tps = Double.isFinite(value.tps()) ? String.format(Locale.ROOT, "%.1f", value.tps()) : "-";
        String ram = memory(value);
        String latency = Double.isFinite(value.latencyMs()) ? String.format(Locale.ROOT, "%.0f ms", value.latencyMs()) : "-";
        String cpu = Double.isFinite(value.cpuPercent()) ? String.format(Locale.ROOT, "%.1f%%", value.cpuPercent()) : "-";
        if ("performance".equals(operation)) return "§b[AeroMC] §fTPS: §b" + tps + " §7• §fCPU: §b" + cpu + " §7• §fRAM: §b" + ram + " §7• §fGecikme: §b" + latency;
        int health = !value.online() ? 0 : Math.max(0, 100 - (Double.isFinite(value.tps()) ? (int) Math.max(0, (18.0 - value.tps()) * 12) : 0) - (Double.isFinite(value.ramPercent()) ? (int) Math.max(0, value.ramPercent() - 80) : 0));
        String color = health >= 80 ? "§a" : health >= 55 ? "§e" : "§c";
        String crisis = value.crisisActive() ? "§cEtkin" : "§aBeklemede";
        return "§b[AeroMC] §fSağlık: " + color + health + "/100 §7• " + state + " §7• §fOyuncu: §b" + players
                + " §7• §fCPU: §b" + cpu + " §7• §fRAM: §b" + ram + " §7• §fTPS: §b" + tps
                + " §7• §fPing: §b" + latency + " §7• §fKriz: " + crisis;
    }
    private static String memory(Snapshot value) {
        if (value.memoryMb() >= 0) return value.memoryLimitMb() > 0 ? value.memoryMb() + "/" + value.memoryLimitMb() + " MB" : value.memoryMb() + " MB";
        if (Double.isFinite(value.ramPercent())) return String.format(Locale.ROOT, "%.0f%%", value.ramPercent());
        return value.memoryLimitMb() > 0 ? value.memoryLimitMb() + " MB ayrılmış" : "-";
    }
    private static String announcement(String value) {
        if (value == null) return null;
        String clean = value.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return clean.isEmpty() || clean.length() > 160 ? null : clean;
    }
    private static String tellraw(String player, String text) { return "tellraw " + player + " {\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"; }
    private static String tellrawAll(String text) { return "tellraw @a {\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"; }
    private static String root(Throwable error) { Throwable value = error; while (value.getCause() != null && value.getCause() != value) value = value.getCause(); return Objects.toString(value.getMessage(), value.getClass().getSimpleName()); }
    void shutdown() { worker.shutdownNow(); }
    private record OpsCache(Set<String> players, long loadedAt) { boolean expired() { return System.nanoTime() - loadedAt > OPS_CACHE_TTL.toNanos(); } }
}
