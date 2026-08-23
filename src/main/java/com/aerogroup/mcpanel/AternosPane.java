package com.aerogroup.mcpanel;

import javafx.animation.*;
import javafx.concurrent.Task;
import javafx.application.HostServices;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Aternos için ToS uyumlu durum kontrolü ve resmi panele geçiş görünümü. */
public final class AternosPane {
    private static final Path ADDRESS_FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "aternos-address.txt");
    private final HostServices hostServices;
    private final TextField serverAddress = new TextField();
    private final Label state = new Label("Kontrol bekleniyor");
    private final Label players = new Label("-");
    private final Label version = new Label("-");
    private final Label latency = new Label("-");
    private final Label updated = new Label("-");
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(15), event -> refreshIfConfigured()));
    private boolean checking;
    public AternosPane(HostServices hostServices) {
        this.hostServices = hostServices; loadAddress(); refresh.setCycleCount(Animation.INDEFINITE);
    }
    public Node buildView() {
        serverAddress.setPromptText("Sunucu adresi: örnek.aternos.me veya adres:port");
        Button check = button("Durumu Kontrol Et", "primary"); check.setOnAction(event -> check());
        HBox row = new HBox(8, serverAddress, check); HBox.setHgrow(serverAddress, Priority.ALWAYS);
        GridPane info = new GridPane(); info.setHgap(24); info.setVgap(12);
        add(info, 0, "Durum", state); add(info, 1, "Oyuncular", players); add(info, 2, "Sürüm", version); add(info, 3, "Gecikme", latency); add(info, 4, "Son kontrol", updated);
        Button panel = button("Aternos Resmî Panelini Aç ↗", "secondary"); panel.setOnAction(event -> openPanel());
        Label note = new Label("Adres her 15 saniyede bir yenilenir. Aternos resmî bir yönetim API'si sunmadığı için panel, herkese açık Minecraft durum bilgisini okur; başlatma/durdurma gibi ayrıntılı Aternos durumları resmî panelden yönetilir."); note.setWrapText(true); note.getStyleClass().add("muted");
        VBox card = card("ATERNOS SUNUCU DURUMU", row, info, panel, note); card.setMaxWidth(780);
        VBox page = new VBox(card); page.setAlignment(Pos.TOP_CENTER); page.setPadding(new Insets(28)); refresh.play(); refreshIfConfigured(); return page;
    }
    private void add(GridPane grid, int column, String title, Label value) { VBox box = new VBox(4, new Label(title), value); value.getStyleClass().add("metric"); grid.add(box, column, 0); }
    private void check() {
        String address = MinecraftPing.normalizeInput(serverAddress.getText()); if (address.isEmpty()) { state.setText("Adres gerekli"); return; }
        if (checking) return; checking = true; serverAddress.setText(address); saveAddress(address);
        state.setText("Kontrol ediliyor...");
        Task<MinecraftPing.Result> task = new Task<>() { protected MinecraftPing.Result call() throws Exception { return MinecraftPing.ping(address); } };
        task.setOnSucceeded(event -> { checking = false; if (!address.equals(serverAddress.getText().trim())) return; var result = task.getValue(); state.setText("Online"); players.setText(result.online() + " / " + result.max()); version.setText(result.version()); latency.setText(result.latencyMs() + " ms"); updated.setText(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))); });
        task.setOnFailed(event -> { checking = false; if (!address.equals(serverAddress.getText().trim())) return; state.setText("Offline / erişilemiyor"); players.setText("-"); version.setText("-"); latency.setText("-"); updated.setText(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))); });
        Thread thread = new Thread(task, "aternos-ping"); thread.setDaemon(true); thread.start();
    }
    private void refreshIfConfigured() { if (!serverAddress.getText().trim().isEmpty() && !checking) check(); }
    private void loadAddress() { try { if (Files.exists(ADDRESS_FILE)) serverAddress.setText(Files.readString(ADDRESS_FILE, StandardCharsets.UTF_8).trim()); } catch (IOException ignored) { } }
    private void saveAddress(String address) { try { Files.createDirectories(ADDRESS_FILE.getParent()); Files.writeString(ADDRESS_FILE, address, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); } catch (IOException ignored) { } }
    public void shutdown() { refresh.stop(); }
    private void openPanel() {
        try { hostServices.showDocument("https://aternos.org/server/"); state.setText("Aternos paneli tarayıcıda açıldı"); }
        catch (Throwable error) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text("Tarayıcı açılamadı. Adresi elle açabilirsin:\nhttps://aternos.org/server/"), ButtonType.OK); alert.setHeaderText(LanguageManager.text("Aternos paneli açılamadı")); alert.showAndWait(); }
    }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(18); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
}
