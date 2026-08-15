package com.aerogroup.mcpanel;

import javafx.concurrent.Task;
import javafx.application.HostServices;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Aternos için ToS uyumlu durum kontrolü ve resmi panele geçiş görünümü. */
public final class AternosPane {
    private final HostServices hostServices;
    private final TextField serverAddress = new TextField();
    private final Label state = new Label("Kontrol bekleniyor");
    private final Label players = new Label("-");
    private final Label version = new Label("-");
    private final Label latency = new Label("-");
    public AternosPane(HostServices hostServices) { this.hostServices = hostServices; }
    public Node buildView() {
        serverAddress.setPromptText("Sunucu adresi: örnek.aternos.me");
        Button check = button("Durumu Kontrol Et", "primary"); check.setOnAction(event -> check());
        HBox row = new HBox(8, serverAddress, check); HBox.setHgrow(serverAddress, Priority.ALWAYS);
        GridPane info = new GridPane(); info.setHgap(24); info.setVgap(12);
        add(info, 0, "Durum", state); add(info, 1, "Oyuncular", players); add(info, 2, "Sürüm", version); add(info, 3, "Gecikme", latency);
        Button panel = button("Aternos Resmî Panelini Aç ↗", "secondary"); panel.setOnAction(event -> openPanel());
        Label note = new Label("Aternos resmî bir yönetim API'si sunmadığı için bu sekme yalnızca herkese açık sunucu durumunu okur. Başlatma, durdurma ve yedekleme işlemleri resmî Aternos panelinde yapılır."); note.setWrapText(true); note.getStyleClass().add("muted");
        VBox card = card("ATERNOS SUNUCU DURUMU", row, info, panel, note); card.setMaxWidth(780);
        VBox page = new VBox(card); page.setAlignment(Pos.TOP_CENTER); page.setPadding(new Insets(28)); return page;
    }
    private void add(GridPane grid, int column, String title, Label value) { VBox box = new VBox(4, new Label(title), value); value.getStyleClass().add("metric"); grid.add(box, column, 0); }
    private void check() {
        String address = serverAddress.getText().trim(); if (address.isEmpty()) { state.setText("Adres gerekli"); return; }
        state.setText("Kontrol ediliyor...");
        Task<MinecraftPing.Result> task = new Task<>() { protected MinecraftPing.Result call() throws Exception { return MinecraftPing.ping(address); } };
        task.setOnSucceeded(event -> { if (!address.equals(serverAddress.getText().trim())) return; var result = task.getValue(); state.setText("Online"); players.setText(result.online() + " / " + result.max()); version.setText(result.version()); latency.setText(result.latencyMs() + " ms"); });
        task.setOnFailed(event -> { if (!address.equals(serverAddress.getText().trim())) return; state.setText("Offline / erişilemiyor"); players.setText("-"); version.setText("-"); latency.setText("-"); });
        Thread thread = new Thread(task, "aternos-ping"); thread.setDaemon(true); thread.start();
    }
    private void openPanel() {
        try { hostServices.showDocument("https://aternos.org/server/"); state.setText("Aternos paneli tarayıcıda açıldı"); }
        catch (Throwable error) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text("Tarayıcı açılamadı. Adresi elle açabilirsin:\nhttps://aternos.org/server/"), ButtonType.OK); alert.setHeaderText(LanguageManager.text("Aternos paneli açılamadı")); alert.showAndWait(); }
    }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(18); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
}
