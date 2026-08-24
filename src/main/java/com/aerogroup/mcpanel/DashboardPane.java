package com.aerogroup.mcpanel;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
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
    private final NotificationCenter notifications = NotificationCenter.shared();

    public DashboardPane() { refresh.setCycleCount(Animation.INDEFINITE); load(); }
    public Node buildView() {
        name.setPromptText("Görünen ad"); address.setPromptText("play.example.net");
        Button add = button("+ Favorilere Ekle", "primary"); add.setOnAction(event -> addFavorite());
        HBox addRow = new HBox(8, name, address, add); HBox.setHgrow(address, Priority.ALWAYS); name.setPrefWidth(180);
        Label note = new Label("Favori sunucular 30 saniyede bir yenilenir. Oyuncu sayısı artınca veya sunucu kapanınca bildirim gösterilir."); note.getStyleClass().add("muted");
        VBox top = card("SUNUCU MERKEZİ", addRow, note);
        VBox notificationCenter = notificationCenterView();
        cards.setPadding(new Insets(2)); ScrollPane scroll = new ScrollPane(cards); scroll.setFitToWidth(true); scroll.setMinWidth(0); scroll.getStyleClass().add("dashboard-scroll");
        HBox.setHgrow(scroll, Priority.ALWAYS); notificationCenter.setMinWidth(360); notificationCenter.setPrefWidth(455); notificationCenter.setMaxWidth(520); notificationCenter.setMaxHeight(Double.MAX_VALUE);
        HBox body = new HBox(14, scroll, notificationCenter); VBox.setVgrow(body, Priority.ALWAYS);
        VBox page = new VBox(14, top, body); page.setPadding(new Insets(18)); refreshAll(); refresh.play(); return page;
    }

    private VBox notificationCenterView() {
        FilteredList<NotificationCenter.Entry> visible = new FilteredList<>(notifications.entries(), entry -> true);
        ComboBox<String> filter = new ComboBox<>(FXCollections.observableArrayList("Tümü", "Okunmamış", "Kritik", "Uyarı", "Başarılı")); filter.getSelectionModel().selectFirst(); filter.setPrefWidth(135);
        filter.setOnAction(event -> visible.setPredicate(entry -> switch (filter.getSelectionModel().getSelectedIndex()) {
            case 1 -> !entry.read(); case 2 -> entry.severity() == NotificationCenter.Severity.CRITICAL;
            case 3 -> entry.severity() == NotificationCenter.Severity.WARNING; case 4 -> entry.severity() == NotificationCenter.Severity.SUCCESS; default -> true;
        }));
        ListView<NotificationCenter.Entry> list = new ListView<>(visible); list.setMinHeight(240); list.setPlaceholder(new Label("Henüz önemli bir bildirim yok")); VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(NotificationCenter.Entry entry, boolean empty) {
                super.updateItem(entry, empty); if (empty || entry == null) { setText(null); setGraphic(null); return; }
                Label title = new Label((entry.read() ? "" : "●  ") + entry.title()); title.getStyleClass().add(entry.read() ? "notification-read" : "notification-unread");
                Label meta = new Label(entry.timeText() + "  •  " + entry.category() + "  •  " + severityText(entry.severity())); meta.getStyleClass().add("notification-meta");
                Label message = new Label(entry.message()); message.setWrapText(true); message.getStyleClass().add("notification-message");
                VBox box = new VBox(3, title, meta, message); box.prefWidthProperty().bind(view.widthProperty().subtract(38)); box.getStyleClass().add("notification-item"); setText(null); setGraphic(box);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> notifications.markRead(selected));
        Label unread = new Label(); unread.textProperty().bind(Bindings.createStringBinding(() -> notifications.unreadCount() + LanguageManager.text(" okunmamış"), notifications.unreadCountProperty(), LanguageManager.englishProperty())); unread.getStyleClass().add("metric-small");
        Button readAll = button("Tümünü Okundu Yap", "secondary"), clear = button("Geçmişi Temizle", "danger");
        readAll.setOnAction(event -> notifications.markAllRead());
        clear.setOnAction(event -> { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Bildirim geçmişinin tamamı silinsin mi?", ButtonType.YES, ButtonType.NO); alert.setHeaderText("Bildirim geçmişini temizle"); if (alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) notifications.clear(); });
        CheckBox enabled = new CheckBox("Bildirimler açık"); enabled.setSelected(notifications.isEnabled()); enabled.setOnAction(event -> notifications.setEnabled(enabled.isSelected()));
        Label digest = new Label(); digest.setWrapText(true); digest.getStyleClass().add("notification-digest");
        Runnable updateDigest = () -> digest.setText(NotificationSummary.since(List.copyOf(notifications.entries()), Instant.now().minus(java.time.Duration.ofHours(1))).text());
        updateDigest.run(); notifications.entries().addListener((ListChangeListener<NotificationCenter.Entry>) change -> updateDigest.run()); LanguageManager.englishProperty().addListener((obs, old, value) -> updateDigest.run());
        MenuButton sources = new MenuButton("Bildirim Sunucuları"); sources.getStyleClass().add("secondary");
        Runnable rebuildSources = () -> { sources.getItems().clear(); for (String source : notifications.serverSources()) { CheckMenuItem item = new CheckMenuItem(sourceDisplay(source)); item.setSelected(notifications.isServerSourceEnabled(source)); item.setOnAction(event -> notifications.setServerSourceEnabled(source, item.isSelected())); sources.getItems().add(item); } if (sources.getItems().isEmpty()) { MenuItem empty = new MenuItem(LanguageManager.text("Henüz sunucu kaynağı yok")); empty.setDisable(true); sources.getItems().add(empty); } };
        rebuildSources.run(); notifications.serverSources().addListener((ListChangeListener<String>) change -> rebuildSources.run()); LanguageManager.englishProperty().addListener((obs, old, value) -> rebuildSources.run());
        Button collapse = button("Paneli Daralt", "secondary");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox statusRow = new HBox(8, enabled, spacer, unread); statusRow.setAlignment(Pos.CENTER_LEFT);
        Button rules = button("Kurallar & Sessiz Saatler", "secondary"), test = button("Deneme Bildirimi", "secondary");
        rules.setOnAction(event -> NotificationRulesDialog.show(rules.getScene().getWindow(), notifications)); test.setOnAction(event -> DesktopNotifier.showTest("AeroMC deneme bildirimi", "Bildirim sistemi çalışıyor. Bu mesaj gerçek bir sunucu olayı değildir."));
        FlowPane options = new FlowPane(8, 8, sources, filter, rules, test); options.setAlignment(Pos.CENTER_LEFT); VBox controls = new VBox(8, statusRow, digest, options);
        FlowPane actions = new FlowPane(8, 8, readAll, clear); actions.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(10, list, actions); VBox.setVgrow(list, Priority.ALWAYS);
        VBox result = card("BİLDİRİM MERKEZİ", controls, content); result.getStyleClass().add("notification-center");
        Runnable updateCollapse = () -> { boolean value = notifications.isCollapsed(); content.setVisible(!value); content.setManaged(!value); result.setMaxHeight(value ? Region.USE_PREF_SIZE : Double.MAX_VALUE); collapse.setText(LanguageManager.text(value ? "Paneli Genişlet" : "Paneli Daralt")); };
        collapse.setOnAction(event -> { notifications.setCollapsed(!notifications.isCollapsed()); updateCollapse.run(); }); options.getChildren().add(collapse); updateCollapse.run();
        return result;
    }

    private String severityText(NotificationCenter.Severity severity) { return LanguageManager.text(switch (severity) { case CRITICAL -> "Kritik"; case WARNING -> "Uyarı"; case SUCCESS -> "Başarılı"; default -> "Bilgi"; }); }
    private String sourceDisplay(String source) { String value = source.substring("Sunucu • ".length()); if (value.startsWith("Yerel JAR")) return LanguageManager.text("Yerel JAR"); if (value.startsWith("Favori • ")) return LanguageManager.text("Favori") + " • " + value.substring("Favori • ".length()); return value; }
    private void addFavorite() {
        String serverName = name.getText().trim(), serverAddress = address.getText().trim();
        if (serverAddress.isEmpty()) return;
        if (serverName.isEmpty()) serverName = serverAddress;
        if (!favorites.containsKey(serverAddress)) { ServerCard card = new ServerCard(serverName, serverAddress); favorites.put(serverAddress, card); cards.getChildren().add(card.view); save(); card.refresh(); }
        name.clear(); address.clear();
    }
    private void remove(String serverAddress) { ServerCard removed = favorites.remove(serverAddress); if (removed != null) { cards.getChildren().remove(removed.view); notifications.unregisterServerSource(removed.notificationSource()); } save(); }
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
        int lastPlayers = -1; boolean wasOnline = false; Instant onlineSince; final ServerAvailabilityTracker availability = new ServerAvailabilityTracker();
        ServerCard(String name, String address) {
            this.serverName = name; this.serverAddress = address;
            notifications.registerServerSource(notificationSource());
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
            ServerAvailabilityTracker.Change change = availability.success();
            if (change == ServerAvailabilityTracker.Change.VERIFYING_ONLINE) { state.setText("Online durumu doğrulanıyor..."); return; }
            state.setText("Online"); playerLabel.setText(result.online() + " / " + result.max()); version.setText(result.version());
            boolean transitioned = change == ServerAvailabilityTracker.Change.ONLINE;
            if (!wasOnline) { onlineSince = Instant.now(); if (transitioned) DesktopNotifier.show(notificationSource(), serverName, "Sunucu yeniden online."); }
            if (!transitioned && lastPlayers >= 0 && result.online() > lastPlayers) DesktopNotifier.show(notificationSource(), serverName, (result.online() - lastPlayers) + " yeni oyuncu katıldı.");
            wasOnline = true; lastPlayers = result.online(); history.addLast(result.online()); while (history.size() > 24) history.removeFirst();
            uptime.setText(duration(java.time.Duration.between(onlineSince, Instant.now()))); drawChart();
        }
        void updateOffline() {
            ServerAvailabilityTracker.Change change = availability.failure();
            if (change == ServerAvailabilityTracker.Change.VERIFYING_OFFLINE) { state.setText("Bağlantı yeniden doğrulanıyor..."); return; }
            if (change == ServerAvailabilityTracker.Change.OFFLINE && wasOnline) DesktopNotifier.show(notificationSource(), serverName, "Sunucu iki kontrolde de erişilemedi ve offline kabul edildi.");
            wasOnline = false; state.setText("Offline"); playerLabel.setText("-"); uptime.setText("-");
        }
        String notificationSource() { return NotificationCenter.serverSource("Favori", serverName + " • " + serverAddress); }
        String duration(java.time.Duration value) { long minutes = value.toMinutes(); return minutes >= 60 ? (minutes / 60) + "s " + (minutes % 60) + "dk" : minutes + " dk"; }
        void drawChart() {
            GraphicsContext g = chart.getGraphicsContext2D(); g.setFill(Color.web("#0f1921")); g.fillRoundRect(0, 0, chart.getWidth(), chart.getHeight(), 8, 8); if (history.size() < 2) return;
            int max = Math.max(1, history.stream().max(Integer::compareTo).orElse(1)); double step = chart.getWidth() / (history.size() - 1.0); g.setStroke(Color.web("#55b9ea")); g.setLineWidth(2); int i = 0; double previousX = 0, previousY = 0;
            for (int value : history) { double x = i * step, y = chart.getHeight() - 8 - (value / (double) max) * (chart.getHeight() - 16); if (i > 0) g.strokeLine(previousX, previousY, x, y); previousX = x; previousY = y; i++; }
        }
    }
}
