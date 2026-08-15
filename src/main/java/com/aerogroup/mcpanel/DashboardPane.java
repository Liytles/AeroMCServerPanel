package com.aerogroup.mcpanel;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Favori Minecraft sunucularını kartlar halinde izleyen ortak ana panel. */
public final class DashboardPane {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "favorites.properties");
    private final FlowPane cards = new FlowPane(14, 14);
    private final Map<String, ServerCard> favorites = new LinkedHashMap<>();
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(30), event -> refreshAll()));
    private final TextField name = new TextField(), address = new TextField();

    public DashboardPane() { refresh.setCycleCount(Animation.INDEFINITE); load(); }
    public Node buildView() {
        name.setPromptText("Görünen ad"); address.setPromptText("play.example.net");
        Button add = button("+ Favorilere Ekle", "primary"); add.setOnAction(event -> addFavorite());
        HBox addRow = new HBox(8, name, address, add); HBox.setHgrow(address, Priority.ALWAYS); name.setPrefWidth(180);
        Label note = new Label("Favori sunucular 30 saniyede bir yenilenir. Oyuncu sayısı artınca veya sunucu kapanınca bildirim gösterilir."); note.getStyleClass().add("muted");
        VBox top = card("SUNUCU MERKEZİ", addRow, note);
        cards.setPadding(new Insets(2)); ScrollPane scroll = new ScrollPane(cards); scroll.setFitToWidth(true); scroll.getStyleClass().add("dashboard-scroll"); VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox page = new VBox(14, top, scroll); page.setPadding(new Insets(18)); refreshAll(); refresh.play(); return page;
    }
    private void addFavorite() {
        String serverName = name.getText().trim(), serverAddress = address.getText().trim();
        if (serverAddress.isEmpty()) return;
        if (serverName.isEmpty()) serverName = serverAddress;
        if (!favorites.containsKey(serverAddress)) { ServerCard card = new ServerCard(serverName, serverAddress); favorites.put(serverAddress, card); cards.getChildren().add(card.view); save(); card.refresh(); }
        name.clear(); address.clear();
    }
    private void remove(String serverAddress) { ServerCard removed = favorites.remove(serverAddress); if (removed != null) cards.getChildren().remove(removed.view); save(); }
    private void refreshAll() { favorites.values().forEach(ServerCard::refresh); }
    private void load() {
        Properties values = new Properties(); try { if (Files.exists(FILE)) try (Reader reader = Files.newBufferedReader(FILE)) { values.load(reader); } } catch (IOException ignored) { }
        for (String key : values.stringPropertyNames()) { String serverAddress = key.substring("server.".length()); ServerCard card = new ServerCard(values.getProperty(key), serverAddress); favorites.put(serverAddress, card); cards.getChildren().add(card.view); }
    }
    private void save() {
        try { Files.createDirectories(FILE.getParent()); Properties values = new Properties(); favorites.forEach((serverAddress, card) -> values.setProperty("server." + serverAddress, card.serverName)); try (Writer writer = Files.newBufferedWriter(FILE)) { values.store(writer, "AeroMC favorites"); } } catch (IOException ignored) { }
    }
    public void shutdown() { refresh.stop(); }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }

    private final class ServerCard {
        final String serverName, serverAddress; final VBox view = new VBox(8); final Label state = new Label("Kontrol ediliyor..."), playerLabel = new Label("-"), version = new Label("-"), uptime = new Label("-"); final Canvas chart = new Canvas(245, 58); final Deque<Integer> history = new ArrayDeque<>();
        int lastPlayers = -1; boolean wasOnline = false; Instant onlineSince;
        ServerCard(String name, String address) {
            this.serverName = name; this.serverAddress = address;
            Label title = new Label(name); title.getStyleClass().add("server-card-title"); Label host = new Label(address); host.getStyleClass().add("muted"); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); Button remove = button("×", "danger"); remove.setOnAction(event -> remove(address));
            HBox heading = new HBox(7, title, spacer, remove); heading.setAlignment(Pos.CENTER_LEFT);
            GridPane metrics = new GridPane(); metrics.setHgap(18); metrics.add(metric("Durum", state), 0, 0); metrics.add(metric("Oyuncular", playerLabel), 1, 0); metrics.add(metric("Sürüm", version), 0, 1); metrics.add(metric("Çalışma", uptime), 1, 1);
            view.getChildren().addAll(heading, host, metrics, chart); view.getStyleClass().add("server-card"); view.setPrefWidth(285);
        }
        private VBox metric(String label, Label value) { Label heading = new Label(label); heading.getStyleClass().add("muted"); value.getStyleClass().add("metric-small"); return new VBox(2, heading, value); }
        void refresh() {
            Task<MinecraftPing.Result> task = new Task<>() { protected MinecraftPing.Result call() throws Exception { return MinecraftPing.ping(serverAddress); } };
            task.setOnSucceeded(event -> updateOnline(task.getValue())); task.setOnFailed(event -> updateOffline()); Thread thread = new Thread(task, "dashboard-ping"); thread.setDaemon(true); thread.start();
        }
        void updateOnline(MinecraftPing.Result result) {
            state.setText("Online"); playerLabel.setText(result.online() + " / " + result.max()); version.setText(result.version());
            if (!wasOnline) { onlineSince = Instant.now(); if (lastPlayers >= 0) DesktopNotifier.show(serverName, "Sunucu yeniden online."); }
            if (lastPlayers >= 0 && result.online() > lastPlayers) DesktopNotifier.show(serverName, (result.online() - lastPlayers) + " yeni oyuncu katıldı.");
            wasOnline = true; lastPlayers = result.online(); history.addLast(result.online()); while (history.size() > 24) history.removeFirst();
            uptime.setText(duration(java.time.Duration.between(onlineSince, Instant.now()))); drawChart();
        }
        void updateOffline() { if (wasOnline) DesktopNotifier.show(serverName, "Sunucu offline oldu veya erişilemiyor."); wasOnline = false; state.setText("Offline"); playerLabel.setText("-"); uptime.setText("-"); }
        String duration(java.time.Duration value) { long minutes = value.toMinutes(); return minutes >= 60 ? (minutes / 60) + "s " + (minutes % 60) + "dk" : minutes + " dk"; }
        void drawChart() {
            GraphicsContext g = chart.getGraphicsContext2D(); g.setFill(Color.web("#0f1921")); g.fillRoundRect(0, 0, chart.getWidth(), chart.getHeight(), 8, 8); if (history.size() < 2) return;
            int max = Math.max(1, history.stream().max(Integer::compareTo).orElse(1)); double step = chart.getWidth() / (history.size() - 1.0); g.setStroke(Color.web("#55b9ea")); g.setLineWidth(2); int i = 0; double previousX = 0, previousY = 0;
            for (int value : history) { double x = i * step, y = chart.getHeight() - 8 - (value / (double) max) * (chart.getHeight() - 16); if (i > 0) g.strokeLine(previousX, previousY, x, y); previousX = x; previousY = y; i++; }
        }
    }
}
