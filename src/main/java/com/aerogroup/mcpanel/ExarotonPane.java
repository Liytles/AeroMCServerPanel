package com.aerogroup.mcpanel;

import com.exaroton.api.ExarotonClient;
import com.exaroton.api.server.Server;
import com.exaroton.api.server.ServerStatus;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.Map;
import com.exaroton.api.server.config.ConfigOption;

/** Exaroton'un resmi API istemcisini kullanan barındırılmış sunucu görünümü. */
public final class ExarotonPane {
    private final PasswordField token = new PasswordField();
    private final ComboBox<ServerChoice> servers = new ComboBox<>();
    private final ObservableList<String> players = FXCollections.observableArrayList();
    private final ConsoleView console = new ConsoleView();
    private final Label status = new Label("Bağlantı bekleniyor");
    private final Label address = new Label("-");
    private final Label playerCount = new Label("0 / 0");
    private final Label account = new Label("-");
    private final Label credits = new Label("-");
    private final Label ram = new Label("-");
    private final Button start = button("▶ Başlat", "primary");
    private final Button stop = button("■ Durdur", "danger");
    private final Button restart = button("↻ Yeniden Başlat", "secondary");
    private final CheckBox saveEncrypted = new CheckBox("API anahtarını şifreli sakla");
    private final Button loadSaved = button("Kayıtlı Anahtarla Bağlan", "secondary");
    private final Button deleteSaved = button("Kaydı Sil", "danger");
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(10), event -> refreshServer()));
    private final ReadOnlyStringWrapper activeServerName = new ReadOnlyStringWrapper("Exaroton sunucusu seçilmedi");
    private ExarotonClient client;
    private volatile Server active;
    private ServerStatus lastStatus;
    private final Set<String> knownPlayers = new HashSet<>();
    private final List<Consumer<String>> proConsoleListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProSnapshot>> proSnapshotListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProMetrics>> proMetricsListeners = new CopyOnWriteArrayList<>();
    private volatile double latestMemoryPercent = Double.NaN, latestTps = Double.NaN, latestMspt = Double.NaN;

    public ExarotonPane() { refresh.setCycleCount(Animation.INDEFINITE); }

    public Node buildView() {
        token.setPromptText("Exaroton API anahtarı (dosyaya kaydedilmez)");
        Button connect = button("Bağlan", "primary"); connect.setOnAction(event -> connect());
        HBox tokenRow = new HBox(8, token, connect); HBox.setHgrow(token, Priority.ALWAYS);
        loadSaved.setDisable(!SecureTokenStore.exists()); loadSaved.setOnAction(event -> loadSavedToken());
        deleteSaved.setDisable(!SecureTokenStore.exists()); deleteSaved.setOnAction(event -> { try { SecureTokenStore.delete(); loadSaved.setDisable(true); deleteSaved.setDisable(true); } catch (Exception error) { showError(error.getMessage()); } });
        HBox secureRow = new HBox(9, saveEncrypted, loadSaved, deleteSaved); secureRow.setAlignment(Pos.CENTER_LEFT);
        servers.setPromptText("Sunucu seç"); servers.setMaxWidth(Double.MAX_VALUE);
        servers.valueProperty().addListener((observable, oldChoice, newChoice) -> selectServer(newChoice));
        ListView<String> playerList = new ListView<>(players); playerList.setPlaceholder(new Label("Online oyuncu yok")); VBox.setVgrow(playerList, Priority.ALWAYS);
        start.setOnAction(event -> action("Sunucu başlatılıyor...", () -> { active.start().join(); return null; }));
        stop.setOnAction(event -> action("Sunucu durduruluyor...", () -> { active.stop().join(); return null; }));
        restart.setOnAction(event -> action("Sunucu yeniden başlatılıyor...", () -> { active.restart().join(); return null; }));
        HBox actions = new HBox(8, start, stop, restart);
        GridPane info = new GridPane(); info.setHgap(18); info.setVgap(8);
        info.add(new Label("Durum"), 0, 0); info.add(status, 1, 0); info.add(new Label("Adres"), 0, 1); info.add(address, 1, 1); info.add(new Label("Oyuncular"), 0, 2); info.add(playerCount, 1, 2);
        info.add(new Label("Hesap"), 2, 0); info.add(account, 3, 0); info.add(new Label("Kredi"), 2, 1); info.add(credits, 3, 1); info.add(new Label("RAM"), 2, 2); info.add(ram, 3, 2);
        VBox accountCard = card("EXAROTON BAĞLANTISI", tokenRow, secureRow, servers, info, actions);

        VBox.setVgrow(console, Priority.ALWAYS);
        TextField command = new TextField(); command.setPromptText("Konsol komutu yaz"); Button send = button("Gönder", "primary");
        Runnable sendCommand = () -> { String value = command.getText().trim(); if (active != null && !value.isEmpty()) { action("Komut gönderiliyor...", () -> { active.executeCommand(value).join(); return null; }); command.clear(); } };
        send.setOnAction(event -> sendCommand.run()); command.setOnAction(event -> sendCommand.run()); HBox.setHgrow(command, Priority.ALWAYS);
        VBox consoleCard = card("CANLI EXAROTON KONSOLU", console, new HBox(8, command, send)); VBox.setVgrow(consoleCard, Priority.ALWAYS);
        VBox playerCard = card("ONLINE OYUNCULAR", playerList); playerCard.setPrefWidth(260); VBox.setVgrow(playerCard, Priority.ALWAYS);
        VBox left = new VBox(14, accountCard, consoleCard); VBox.setVgrow(consoleCard, Priority.ALWAYS); HBox.setHgrow(left, Priority.ALWAYS);
        HBox content = new HBox(14, left, playerCard); content.setPadding(new Insets(18)); HBox.setHgrow(left, Priority.ALWAYS); return content;
    }
    private void connect() {
        String apiToken = token.getText().trim();
        if (apiToken.isEmpty()) { showError("API anahtarını gir."); return; }
        char[] password = null;
        if (saveEncrypted.isSelected()) {
            Optional<char[]> entered = passwordDialog("Ana Parola Oluştur", true);
            if (entered.isEmpty()) return;
            password = entered.get();
        }
        connectWithToken(apiToken, password);
    }
    private void connectWithToken(String apiToken, char[] encryptionPassword) {
        status.setText("Bağlanıyor...");
        Task<List<Server>> task = new Task<>() { protected List<Server> call() throws Exception { client = new ExarotonClient(apiToken).setUserAgent("AeroMC-Server-Panel/1.0"); return client.getServers().get(); } };
        task.setOnSucceeded(event -> {
            servers.getItems().setAll(task.getValue().stream().map(ServerChoice::new).toList());
            status.setText(task.getValue().isEmpty() ? "Sunucu bulunamadı" : "Bağlandı");
            token.clear();
            if (encryptionPassword != null) {
                try { SecureTokenStore.save(apiToken, encryptionPassword); loadSaved.setDisable(false); deleteSaved.setDisable(false); } catch (Exception error) { showError("Anahtar bağlandı fakat saklanamadı: " + error.getMessage()); }
                finally { Arrays.fill(encryptionPassword, '\0'); }
            }
            try { client.getAccount().thenAccept(value -> Platform.runLater(() -> { account.setText(value.getName()); credits.setText(String.format("%.2f", value.getCredits())); })); } catch (Exception ignored) { }
            if (!servers.getItems().isEmpty()) servers.getSelectionModel().selectFirst();
        });
        task.setOnFailed(event -> { if (encryptionPassword != null) Arrays.fill(encryptionPassword, '\0'); status.setText("Bağlantı başarısız"); showError(message(task.getException())); }); run(task, "exaroton-connect");
    }
    private void loadSavedToken() {
        Optional<char[]> entered = passwordDialog("Ana Parolayı Gir", false); if (entered.isEmpty()) return;
        char[] password = entered.get();
        try { connectWithToken(SecureTokenStore.load(password), null); }
        catch (Exception error) { showError("Anahtar açılamadı. Parola yanlış veya kayıt bozuk olabilir."); }
        finally { Arrays.fill(password, '\0'); }
    }
    private Optional<char[]> passwordDialog(String title, boolean confirm) {
        Dialog<char[]> dialog = new Dialog<>(); dialog.setTitle(LanguageManager.text(title)); dialog.setHeaderText(LanguageManager.text(confirm ? "En az 8 karakterlik bir ana parola belirle. Bu parola kaydedilmez." : "Şifreli API anahtarını açmak için ana parolanı gir."));
        PasswordField first = new PasswordField(); first.setPromptText(LanguageManager.text("Ana parola")); VBox fields = new VBox(8, first);
        PasswordField second = new PasswordField(); if (confirm) { second.setPromptText(LanguageManager.text("Ana parolayı tekrar yaz")); fields.getChildren().add(second); }
        dialog.getDialogPane().setContent(fields); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> { if (button != ButtonType.OK) return null; if (first.getText().length() < 8) { showError("Ana parola en az 8 karakter olmalı."); return null; } if (confirm && !first.getText().equals(second.getText())) { showError("Ana parolalar eşleşmiyor."); return null; } return first.getText().toCharArray(); });
        return dialog.showAndWait();
    }
    private void selectServer(ServerChoice choice) {
        if (choice == null) { activeServerName.set("Exaroton sunucusu seçilmedi"); return; }
        if (active != null) active.unsubscribe();
        active = choice.server();
        activeServerName.set(active.getName());
        latestMemoryPercent = Double.NaN; latestTps = Double.NaN; latestMspt = Double.NaN;
        status.setText("Durum alınıyor..."); address.setText(active.getAddress()); playerCount.setText("-"); ram.setText("-");
        start.setDisable(true); stop.setDisable(true); restart.setDisable(true);
        active.addConsoleSubscriber(line -> { Platform.runLater(() -> console.append(line)); proConsoleListeners.forEach(listener -> listener.accept(line)); });
        active.addStatusSubscriber((oldServer, newServer) -> Platform.runLater(() -> update(newServer)));
        active.addStatsSubscriber(data -> { if (data != null && data.getMemory() != null) latestMemoryPercent = data.getMemory().getPercent(); publishProMetrics(); });
        active.addTickSubscriber(data -> { if (data != null) { latestTps = data.calculateTPS(); latestMspt = data.getAverageTickTime(); publishProMetrics(); } });
        console.clearConsole(); knownPlayers.clear(); lastStatus = null; refreshServer(); refresh.play();
    }
    private void refreshServer() {
        if (active == null) return;
        Server requestedServer = active;
        Task<Server> task = new Task<>() { protected Server call() throws Exception { return requestedServer.fetch().get(); } };
        task.setOnSucceeded(event -> { if (active != null && active.getId().equals(requestedServer.getId())) update(task.getValue()); });
        task.setOnFailed(event -> { if (active != null && active.getId().equals(requestedServer.getId())) status.setText("Durum alınamadı"); }); run(task, "exaroton-refresh");
    }
    private void update(Server server) {
        if (active == null || !active.getId().equals(server.getId())) return;
        status.setText(server.getStatus().getName()); address.setText(server.getAddress());
        var info = server.getPlayerInfo();
        for (String name : info.getList()) if (!knownPlayers.contains(name)) DesktopNotifier.show(server.getName(), name + " sunucuya katıldı.");
        knownPlayers.clear(); knownPlayers.addAll(info.getList()); players.setAll(info.getList()); playerCount.setText(info.getCount() + " / " + info.getMax());
        if (lastStatus != null && lastStatus != server.getStatus() && server.hasStatus(ServerStatus.OFFLINE, ServerStatus.CRASHED)) DesktopNotifier.show(server.getName(), "Sunucu " + server.getStatus().getName() + " durumuna geçti.");
        lastStatus = server.getStatus();
        try { server.getRAM().thenAccept(value -> Platform.runLater(() -> ram.setText(value.getRam() + " GiB"))); } catch (Exception ignored) { }
        boolean online = server.hasStatus(ServerStatus.ONLINE); boolean offline = server.hasStatus(ServerStatus.OFFLINE, ServerStatus.CRASHED);
        start.setDisable(!offline); stop.setDisable(!online); restart.setDisable(!online);
        ProSnapshot snapshot = snapshot(server, -1); proSnapshotListeners.forEach(listener -> listener.accept(snapshot));
    }
    private void action(String text, Callable<Void> operation) {
        if (active == null) { showError("Önce bir sunucu seç."); return; }
        status.setText(text); Task<Void> task = new Task<>() { protected Void call() throws Exception { return operation.call(); } };
        task.setOnSucceeded(event -> refreshServer()); task.setOnFailed(event -> showError(message(task.getException()))); run(task, "exaroton-action");
    }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }
    private String message(Throwable error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? cause.toString() : cause.getMessage(); }
    private void showError(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    public void shutdown() { refresh.stop(); if (active != null) active.unsubscribe(); }
    public boolean hasActiveServer() { return active != null; }
    public String getActiveServerName() { return activeServerName.get(); }
    public ReadOnlyStringProperty activeServerNameProperty() { return activeServerName.getReadOnlyProperty(); }
    public void addProConsoleListener(Consumer<String> listener) { proConsoleListeners.add(listener); }
    public void addProSnapshotListener(Consumer<ProSnapshot> listener) { proSnapshotListeners.add(listener); }
    public void addProMetricsListener(Consumer<ProMetrics> listener) { proMetricsListeners.add(listener); }
    public void removeProConsoleListener(Consumer<String> listener) { proConsoleListeners.remove(listener); }
    public void removeProSnapshotListener(Consumer<ProSnapshot> listener) { proSnapshotListeners.remove(listener); }
    public void removeProMetricsListener(Consumer<ProMetrics> listener) { proMetricsListeners.remove(listener); }
    public CompletableFuture<ProSnapshot> fetchProSnapshot() throws Exception {
        Server selected = requireActive();
        return selected.fetch().thenCompose(server -> {
            try { return selected.getRAM().thenApply(value -> snapshot(server, value.getRam())); }
            catch (Exception error) { return CompletableFuture.completedFuture(snapshot(server, -1)); }
        });
    }
    public CompletableFuture<String> readRemoteFile(String path) throws Exception { return requireActive().getFile(path).getContent(); }
    public CompletableFuture<Void> writeRemoteFile(String path, String content) throws Exception { return requireActive().getFile(path).putContent(content); }
    public CompletableFuture<List<String>> listRemoteDirectory(String path) throws Exception {
        return requireActive().getFile(path).fetch().thenApply(file -> file.getChildren().stream().map(child -> child.getName() + (child.isDirectory() ? "/" : "")).sorted().toList());
    }
    public CompletableFuture<Void> downloadRemoteFile(String path, Path target) throws Exception { return requireActive().getFile(path).download(target); }
    public CompletableFuture<Void> uploadRemoteFile(String directory, Path source) throws Exception { return requireActive().getFile(directory + "/" + source.getFileName()).upload(source); }
    public CompletableFuture<Void> deleteRemoteFile(String path) throws Exception { return requireActive().getFile(path).delete(); }
    public CompletableFuture<Void> startActiveServer() throws Exception { return requireActive().start(); }
    public CompletableFuture<Void> stopActiveServer() throws Exception { return requireActive().stop(); }
    public CompletableFuture<Void> restartActiveServer() throws Exception { return requireActive().restart(); }
    private Server requireActive() { Server selected = active; if (selected == null) throw new IllegalStateException("Önce Exaroton sekmesinden bir sunucu seç."); return selected; }
    private ProSnapshot snapshot(Server server, int ramGiB) { var info = server.getPlayerInfo(); var software = server.getSoftware(); return new ProSnapshot(server.getName(), server.getStatus().getName(), server.hasStatus(ServerStatus.ONLINE), info.getCount(), info.getMax(), new ArrayList<>(info.getList()), ramGiB, server.getAddress(), software == null ? "" : software.getName(), software == null ? "" : software.getVersion()); }
    private void publishProMetrics() { ProMetrics metrics = new ProMetrics(latestMemoryPercent, latestTps, latestMspt); proMetricsListeners.forEach(listener -> listener.accept(metrics)); }
    public CompletableFuture<Void> executeAdminCommand(String command) throws Exception {
        if (active == null) throw new IllegalStateException("Önce Exaroton sekmesinden bir sunucu seç.");
        return active.executeCommand(command);
    }
    public CompletableFuture<List<String>> modifyPlayerList(String list, boolean add, String player) throws Exception {
        if (active == null) throw new IllegalStateException("Önce Exaroton sekmesinden bir sunucu seç.");
        return add ? active.getPlayerList(list).add(player) : active.getPlayerList(list).remove(player);
    }
    public CompletableFuture<Map<String, ConfigOption<?>>> loadServerOptions() throws Exception {
        if (active == null) throw new IllegalStateException("Önce Exaroton sekmesinden bir sunucu seç.");
        return active.getFile("/server.properties").getConfig().getOptions(true);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    public CompletableFuture<Void> saveServerOptions(Map<String, Object> values) throws Exception {
        if (active == null) throw new IllegalStateException("Önce Exaroton sekmesinden bir sunucu seç.");
        var config = active.getFile("/server.properties").getConfig();
        return config.getOptions(true).thenCompose(options -> {
            values.forEach((key, value) -> { ConfigOption option = options.get(key); if (option != null) option.setValue(value); });
            config.setOptions(options); try { return config.save(); } catch (Exception error) { return CompletableFuture.failedFuture(error); }
        });
    }
    private record ServerChoice(Server server) { @Override public String toString() { return server.getName() + " — " + server.getAddress(); } }
    public record ProSnapshot(String name, String status, boolean online, int players, int maxPlayers, List<String> playerNames, int ramGiB, String address, String softwareName, String softwareVersion) { }
    public record ProMetrics(double memoryPercent, double tps, double averageTickTime) { }
}
