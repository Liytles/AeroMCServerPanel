package com.aerogroup.mcpanel;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

/** Modsuz, oyun penceresinin üzerinde çalışabilen küçük AeroMC yardımcı penceresi. */
final class GameOverlayWindow {
    private final ServerManager manager;
    private final ExarotonPane exaroton;
    private final PterodactylPane pterodactyl;
    private final PanelConfig config;
    private final IntSupplier localPlayerCount;
    private final ComboBox<String> provider = new ComboBox<>(FXCollections.observableArrayList("Yerel JAR", "Exaroton", "Pterodactyl"));
    private final Label state = metric("Bağlantı bekleniyor"), players = metric("-"), resources = metric("-"), note = new Label();
    private final TextField announcement = new TextField();
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(5), event -> refresh()));
    private Stage stage;

    GameOverlayWindow(ServerManager manager, ExarotonPane exaroton, PterodactylPane pterodactyl, PanelConfig config, IntSupplier localPlayerCount) {
        this.manager = manager; this.exaroton = exaroton; this.pterodactyl = pterodactyl; this.config = config; this.localPlayerCount = localPlayerCount;
        refresh.setCycleCount(Timeline.INDEFINITE);
    }

    void show() {
        if (stage != null && stage.isShowing()) { stage.toFront(); refresh(); return; }
        provider.getSelectionModel().select("Yerel JAR");
        provider.setOnAction(event -> refresh());
        Button refreshNow = button("Yenile", "secondary"); refreshNow.setOnAction(event -> refresh());
        Button send = button("Duyur", "primary"); send.setOnAction(event -> announce());
        Button hide = button("Gizle", "secondary"); hide.setOnAction(event -> stage.hide());
        announcement.setPromptText(LanguageManager.text("Tüm oyunculara duyuru yaz")); HBox.setHgrow(announcement, Priority.ALWAYS);
        note.setWrapText(true); note.getStyleClass().add("muted");
        VBox root = new VBox(11,
                title(),
                new HBox(8, provider, refreshNow),
                metrics(),
                new HBox(8, announcement, send),
                note,
                hide);
        root.setPadding(new Insets(14)); root.getStyleClass().addAll("app-root", "game-overlay");
        Scene scene = new Scene(root, 510, 250);
        var stylesheet = GameOverlayWindow.class.getResource("/style.css"); if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage = new Stage(StageStyle.UTILITY); stage.setTitle("AeroMC Game Helper"); stage.setAlwaysOnTop(true); stage.setResizable(false); stage.setScene(scene);
        stage.setOnHidden(event -> refresh.stop()); stage.show(); refresh.play(); refresh();
    }

    void close() { refresh.stop(); if (stage != null) stage.close(); }

    private VBox title() {
        Label heading = new Label(LanguageManager.text("AEROMC OYUN YARDIMCISI")); heading.getStyleClass().add("overlay-title");
        Label detail = new Label(LanguageManager.text("Modsuz çalışır; yalnız AeroMC'nin yetkili olduğu sunuculara bağlanır.")); detail.getStyleClass().add("muted"); detail.setWrapText(true);
        return new VBox(3, heading, detail);
    }
    private GridPane metrics() {
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(8);
        add(grid, 0, LanguageManager.text("DURUM"), state); add(grid, 1, LanguageManager.text("OYUNCULAR"), players); add(grid, 2, LanguageManager.text("KAYNAKLAR"), resources);
        return grid;
    }
    private void add(GridPane grid, int column, String label, Label value) {
        Label title = new Label(label); title.getStyleClass().add("section-title"); VBox card = new VBox(4, title, value); card.getStyleClass().add("overlay-metric"); GridPane.setHgrow(card, Priority.ALWAYS); grid.add(card, column, 0);
    }
    private void refresh() {
        String selected = provider.getValue(); if (selected == null) return;
        note.setText(LanguageManager.text("Güncelleniyor..."));
        switch (selected) {
            case "Exaroton" -> refreshExaroton();
            case "Pterodactyl" -> refreshPterodactyl();
            default -> refreshLocal();
        }
    }
    private void refreshLocal() {
        boolean online = manager.isRunning(); int memory = localMemory(manager.getProcessId());
        if (online) manager.requestPlayers();
        state.setText(online ? LanguageManager.text("Sunucu online") : LanguageManager.text("Sunucu kapalı")); players.setText(online ? Integer.toString(Math.max(0, localPlayerCount.getAsInt())) : "0");
        resources.setText(memory >= 0 ? memory + " / " + config.getMemoryMb() + " MB" : config.getMemoryMb() + " MB ayrılmış");
        note.setText(online ? LanguageManager.text("Yerel Java işlemi ve oyuncu listesi AeroMC tarafından izleniyor.") : LanguageManager.text("Yerel sunucu kapalı."));
    }
    private void refreshExaroton() {
        if (!exaroton.hasActiveServer()) { offline(LanguageManager.text("Önce Exaroton sekmesinden bir sunucu seç.")); return; }
        try {
            exaroton.fetchProSnapshot().whenComplete((value, error) -> Platform.runLater(() -> {
                if (!"Exaroton".equals(provider.getValue())) return;
                if (error != null) { offline(LanguageManager.text("Exaroton verisi alınamadı.")); return; }
                state.setText(value.online() ? LanguageManager.text("Sunucu online") : value.status()); players.setText(value.players() + " / " + value.maxPlayers()); resources.setText(Math.max(0, value.ramGiB()) + " GiB ayrılmış"); note.setText(value.name() + " • " + value.address());
            }));
        } catch (Exception error) { offline(LanguageManager.text("Exaroton verisi alınamadı.")); }
    }
    private void refreshPterodactyl() {
        if (!pterodactyl.hasActiveServer()) { offline(LanguageManager.text("Önce Pterodactyl sekmesinden bir sunucu seç.")); return; }
        pterodactyl.fetchProSnapshot().whenComplete((value, error) -> Platform.runLater(() -> {
            if (!"Pterodactyl".equals(provider.getValue())) return;
            if (error != null) { offline(LanguageManager.text("Pterodactyl verisi alınamadı.")); return; }
            state.setText(value.online() ? LanguageManager.text("Sunucu online") : value.status()); players.setText(value.players() + " / " + value.maxPlayers()); resources.setText(Math.max(0, value.memoryLimitMb()) + " MB ayrılmış"); note.setText(value.name() + " • " + value.address());
        }));
    }
    private void announce() {
        String text = clean(announcement.getText()); if (text == null) { note.setText(LanguageManager.text("Duyuru 1-160 karakter olmalıdır.")); return; }
        String command = tellrawAll("§6[AeroMC Duyuru] §f" + text); String selected = provider.getValue();
        try {
            CompletableFuture<?> result = switch (selected) {
                case "Exaroton" -> exaroton.executeAdminCommand(command);
                case "Pterodactyl" -> pterodactyl.executeAdminCommand(command);
                default -> { manager.command(command); yield CompletableFuture.completedFuture(null); }
            };
            result.whenComplete((ignored, error) -> Platform.runLater(() -> note.setText(error == null ? LanguageManager.text("Duyuru gönderildi.") : LanguageManager.text("Duyuru gönderilemedi."))));
            announcement.clear();
        } catch (Exception error) { note.setText(LanguageManager.text("Duyuru gönderilemedi.")); }
    }
    private void offline(String message) { state.setText("-"); players.setText("-"); resources.setText("-"); note.setText(message); }
    private static int localMemory(long pid) { if (pid <= 0) return -1; Path status = Path.of("/proc", Long.toString(pid), "status"); try { for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) if (line.startsWith("VmRSS:")) return Integer.parseInt(line.replaceAll("[^0-9]", "")) / 1024; } catch (Exception ignored) { } return -1; }
    private static String clean(String value) { if (value == null) return null; String clean = value.replace('§', ' ').replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim(); return clean.isEmpty() || clean.length() > 160 ? null : clean; }
    private static String tellrawAll(String text) { return "tellraw @a {\"text\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"; }
    private static Label metric(String text) { Label value = new Label(text); value.getStyleClass().add("metric"); return value; }
    private static Button button(String text, String style) { Button value = new Button(LanguageManager.text(text)); value.getStyleClass().add(style); return value; }
}
