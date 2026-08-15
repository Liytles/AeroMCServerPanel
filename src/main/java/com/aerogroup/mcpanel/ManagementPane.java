package com.aerogroup.mcpanel;

import com.exaroton.api.server.config.ConfigOption;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Yerel ve Exaroton sunucuları için ortak oyuncu/ayar yönetim ekranı. */
public final class ManagementPane {
    private final ServerManager local;
    private final ExarotonPane exaroton;
    private final ComboBox<String> provider = new ComboBox<>(FXCollections.observableArrayList("Yerel JAR", "Exaroton"));
    private final TextField player = new TextField(), reason = new TextField();
    private final TextField motd = new TextField();
    private final Spinner<Integer> maxPlayers = new Spinner<>(1, 500, 20);
    private final ComboBox<String> gamemode = new ComboBox<>(FXCollections.observableArrayList("survival", "creative", "adventure", "spectator"));
    private final ComboBox<String> difficulty = new ComboBox<>(FXCollections.observableArrayList("peaceful", "easy", "normal", "hard"));
    private final CheckBox pvp = new CheckBox("PvP açık"), whitelist = new CheckBox("Whitelist açık");
    private final ObservableList<String> audit = FXCollections.observableArrayList();
    private final Label providerStatus = new Label();

    public ManagementPane(ServerManager local, ExarotonPane exaroton) {
        this.local = local;
        this.exaroton = exaroton;
        exaroton.activeServerNameProperty().addListener((observable, oldName, newName) -> updateProviderStatus());
    }
    public Node buildView() {
        provider.getSelectionModel().selectFirst(); provider.setOnAction(event -> updateProviderStatus());
        Button refresh = button("Ayarları Yükle", "secondary"); refresh.setOnAction(event -> loadSettings());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox providerRow = new HBox(9, new Label("Sağlayıcı"), provider, providerStatus, spacer, refresh); providerRow.setAlignment(Pos.CENTER_LEFT); updateProviderStatus();

        player.setPromptText("Oyuncu adı"); reason.setPromptText("Sebep veya mesaj (isteğe bağlı)");
        HBox inputs = new HBox(8, player, reason); HBox.setHgrow(player, Priority.ALWAYS); HBox.setHgrow(reason, Priority.ALWAYS);
        FlowPane actions = new FlowPane(8, 8);
        actions.getChildren().addAll(action("Whitelist +", "whitelist-add", false), action("Whitelist −", "whitelist-remove", false), action("OP Ver", "op", true), action("OP Al", "deop", true), action("Kick", "kick", true), action("Ban", "ban", true), action("Unban", "unban", true), action("Özel Mesaj", "message", false));
        VBox playerCard = card("OYUNCU YÖNETİMİ", inputs, actions);

        motd.setPromptText("Sunucu açıklaması (MOTD)"); gamemode.getSelectionModel().select("survival"); difficulty.getSelectionModel().select("normal"); maxPlayers.setEditable(true);
        GridPane settings = new GridPane(); settings.setHgap(13); settings.setVgap(9);
        addField(settings, 0, 0, "MOTD", motd, 3); addField(settings, 0, 1, "Maksimum oyuncu", maxPlayers, 1); addField(settings, 1, 1, "Oyun modu", gamemode, 1); addField(settings, 2, 1, "Zorluk", difficulty, 1);
        HBox toggles = new HBox(18, pvp, whitelist); Button save = button("Ayarları Kaydet", "primary"); save.setOnAction(event -> saveSettings());
        Label restartNote = new Label("Bazı server.properties değişiklikleri sunucu yeniden başlatıldıktan sonra uygulanır."); restartNote.getStyleClass().add("muted");
        VBox settingsCard = card("SUNUCU AYARLARI", settings, toggles, save, restartNote);

        ListView<String> log = new ListView<>(audit); log.setPlaceholder(new Label("Henüz yönetim işlemi yapılmadı")); VBox.setVgrow(log, Priority.ALWAYS);
        VBox auditCard = card("YÖNETİM GÜNLÜĞÜ", log); VBox.setVgrow(auditCard, Priority.ALWAYS);
        VBox left = new VBox(14, playerCard, settingsCard); HBox.setHgrow(left, Priority.ALWAYS);
        VBox right = new VBox(auditCard); right.setPrefWidth(330); VBox.setVgrow(auditCard, Priority.ALWAYS);
        HBox body = new HBox(14, left, right); HBox.setHgrow(left, Priority.ALWAYS); VBox page = new VBox(14, providerRow, body); VBox.setVgrow(body, Priority.ALWAYS); page.setPadding(new Insets(18)); return page;
    }
    private Button action(String label, String operation, boolean confirm) {
        Button button = button(label, operation.equals("ban") || operation.equals("kick") ? "danger" : "secondary");
        button.setOnAction(event -> managePlayer(operation, confirm)); return button;
    }
    private void managePlayer(String operation, boolean needsConfirmation) {
        String username = player.getText().trim(); if (username.isEmpty()) { error("Oyuncu adını gir."); return; }
        if (needsConfirmation) { Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, username + " için “" + operation + "” işlemi uygulansın mı?", ButtonType.YES, ButtonType.NO); if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return; }
        String providerName = provider.getValue(), detail = reason.getText().trim();
        Task<Void> task = new Task<>() { protected Void call() throws Exception { if (providerName.equals("Yerel JAR")) localOperation(operation, username, detail); else remoteOperation(operation, username, detail); return null; } };
        task.setOnSucceeded(event -> log("BAŞARILI", providerName, operation, username)); task.setOnFailed(event -> { log("HATA", providerName, operation, username); error(rootMessage(task.getException())); }); run(task, "player-admin");
    }
    private void localOperation(String operation, String username, String detail) throws Exception {
        String command = switch (operation) { case "whitelist-add" -> "whitelist add " + username; case "whitelist-remove" -> "whitelist remove " + username; case "op" -> "op " + username; case "deop" -> "deop " + username; case "kick" -> "kick " + username + suffix(detail); case "ban" -> "ban " + username + suffix(detail); case "unban" -> "pardon " + username; case "message" -> "tell " + username + " " + (detail.isBlank() ? "Merhaba!" : detail); default -> throw new IllegalArgumentException("Bilinmeyen işlem"); };
        local.command(command);
    }
    private void remoteOperation(String operation, String username, String detail) throws Exception {
        switch (operation) {
            case "whitelist-add" -> exaroton.modifyPlayerList("whitelist", true, username).join();
            case "whitelist-remove" -> exaroton.modifyPlayerList("whitelist", false, username).join();
            case "op" -> exaroton.modifyPlayerList("ops", true, username).join();
            case "deop" -> exaroton.modifyPlayerList("ops", false, username).join();
            case "ban" -> exaroton.modifyPlayerList("banned-players", true, username).join();
            case "unban" -> exaroton.modifyPlayerList("banned-players", false, username).join();
            case "kick" -> exaroton.executeAdminCommand("kick " + username + suffix(detail)).join();
            case "message" -> exaroton.executeAdminCommand("tell " + username + " " + (detail.isBlank() ? "Merhaba!" : detail)).join();
            default -> throw new IllegalArgumentException("Bilinmeyen işlem");
        }
    }
    private String suffix(String value) { return value.isBlank() ? "" : " " + value; }
    private void loadSettings() {
        String providerName = provider.getValue();
        Task<Map<String, Object>> task = new Task<>() { protected Map<String, Object> call() throws Exception { return providerName.equals("Yerel JAR") ? loadLocalSettings() : loadRemoteSettings(); } };
        task.setOnSucceeded(event -> { applyToForm(task.getValue()); log("BAŞARILI", providerName, "ayarları-yükle", "-"); }); task.setOnFailed(event -> error(rootMessage(task.getException()))); run(task, "settings-load");
    }
    private void saveSettings() {
        String providerName = provider.getValue(); Map<String, Object> values = formValues();
        Task<Void> task = new Task<>() { protected Void call() throws Exception { if (providerName.equals("Yerel JAR")) saveLocalSettings(values); else exaroton.saveServerOptions(values).join(); return null; } };
        task.setOnSucceeded(event -> { log("BAŞARILI", providerName, "ayarları-kaydet", "-"); DesktopNotifier.show("AeroMC", providerName + " ayarları kaydedildi."); }); task.setOnFailed(event -> error(rootMessage(task.getException()))); run(task, "settings-save");
    }
    private Map<String, Object> formValues() { Map<String, Object> values = new LinkedHashMap<>(); values.put("motd", motd.getText()); values.put("max-players", maxPlayers.getValue()); values.put("gamemode", gamemode.getValue()); values.put("difficulty", difficulty.getValue()); values.put("pvp", pvp.isSelected()); values.put("white-list", whitelist.isSelected()); return values; }
    private Map<String, Object> loadLocalSettings() throws Exception {
        Path folder = local.getServerFolder(); if (folder == null) throw new IllegalStateException("Önce Yerel JAR sekmesinden server.jar seç.");
        Path file = folder.resolve("server.properties"); if (!Files.exists(file)) throw new IllegalStateException("server.properties bulunamadı. Sunucuyu en az bir kez başlat.");
        Properties properties = new Properties(); try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        Map<String, Object> values = new LinkedHashMap<>(); values.put("motd", properties.getProperty("motd", "A Minecraft Server")); values.put("max-players", Integer.parseInt(properties.getProperty("max-players", "20"))); values.put("gamemode", properties.getProperty("gamemode", "survival")); values.put("difficulty", properties.getProperty("difficulty", "easy")); values.put("pvp", Boolean.parseBoolean(properties.getProperty("pvp", "true"))); values.put("white-list", Boolean.parseBoolean(properties.getProperty("white-list", "false"))); return values;
    }
    private Map<String, Object> loadRemoteSettings() throws Exception {
        Map<String, ConfigOption<?>> options = exaroton.loadServerOptions().join(); Map<String, Object> values = new LinkedHashMap<>();
        for (String key : List.of("motd", "max-players", "gamemode", "difficulty", "pvp", "white-list")) if (options.containsKey(key)) values.put(key, options.get(key).getValue()); return values;
    }
    private void saveLocalSettings(Map<String, Object> values) throws Exception {
        Path folder = local.getServerFolder(); if (folder == null) throw new IllegalStateException("Önce Yerel JAR sekmesinden server.jar seç."); Path file = folder.resolve("server.properties");
        List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>(); Set<String> written = new HashSet<>(); List<String> output = new ArrayList<>();
        for (String line : lines) { int equals = line.indexOf('='); String key = equals > 0 && !line.stripLeading().startsWith("#") ? line.substring(0, equals).trim() : ""; if (values.containsKey(key)) { output.add(key + "=" + values.get(key)); written.add(key); } else output.add(line); }
        values.forEach((key, value) -> { if (!written.contains(key)) output.add(key + "=" + value); }); Files.createDirectories(folder); Path temporary = Files.createTempFile(folder, "server-properties-", ".tmp"); Files.write(temporary, output, StandardCharsets.UTF_8); try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    private void applyToForm(Map<String, Object> values) { motd.setText(String.valueOf(values.getOrDefault("motd", ""))); maxPlayers.getValueFactory().setValue(number(values.get("max-players"), 20)); gamemode.getSelectionModel().select(String.valueOf(values.getOrDefault("gamemode", "survival"))); difficulty.getSelectionModel().select(String.valueOf(values.getOrDefault("difficulty", "normal"))); pvp.setSelected(bool(values.get("pvp"), true)); whitelist.setSelected(bool(values.get("white-list"), false)); }
    private int number(Object value, int fallback) { try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; } }
    private boolean bool(Object value, boolean fallback) { return value == null ? fallback : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)); }
    public void updateProviderStatus() {
        String selectedProvider = provider.getValue();
        providerStatus.setText("Yerel JAR".equals(selectedProvider) ? (local.isRunning() ? "Yerel sunucu online" : "Yerel sunucu kapalı") : exaroton.getActiveServerName());
        if (!providerStatus.getStyleClass().contains("muted")) providerStatus.getStyleClass().add("muted");
    }
    private void addField(GridPane grid, int column, int row, String title, Node field, int span) { VBox box = new VBox(4, new Label(title), field); grid.add(box, column, row, span, 1); GridPane.setHgrow(box, Priority.ALWAYS); }
    private void log(String result, String providerName, String operation, String target) { audit.add(0, LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + result + "  " + providerName + "  " + operation + "  " + target); }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }
    private void error(String message) { Platform.runLater(() -> { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
}
