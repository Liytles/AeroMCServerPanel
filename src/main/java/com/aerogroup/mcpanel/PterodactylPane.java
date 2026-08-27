package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.CommandSecurity;
import com.aerogroup.mcpanel.aeroguard.DeviceCredentialStore;
import com.aerogroup.mcpanel.aeroguard.SecretFieldGuard;

import javafx.animation.*;
import javafx.application.HostServices;
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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Pterodactyl Client API sunucularını güvenli bir masaüstü sağlayıcı görünümünde yönetir. */
public final class PterodactylPane {
    private final PanelConfig config;
    private final HostServices hostServices;
    private final TextField panelUrl = new TextField();
    private final PasswordField apiKey = new PasswordField();
    private final ComboBox<PterodactylClient.ServerInfo> servers = new ComboBox<>();
    private final ConsoleView log = new ConsoleView();
    private final Label connectionState = new Label("Bağlantı bekleniyor");
    private final Label serverState = metric("-");
    private final Label allocation = metric("-");
    private final Label cpu = metric("-");
    private final Label memory = metric("-");
    private final Label disk = metric("-");
    private final Label uptime = metric("-");
    private final Button connect = button("Bağlan", "primary");
    private final Button connectSaved = button("Güvenli Kasayla Bağlan", "secondary");
    private final Button deleteSaved = button("Kasa Kaydını Sil", "danger");
    private final Button start = button("▶ Başlat", "primary");
    private final Button stop = button("■ Durdur", "danger");
    private final Button restart = button("↻ Yeniden Başlat", "secondary");
    private final Button kill = button("Zorla Kapat", "danger");
    private final Button refreshNow = button("Şimdi Yenile", "secondary");
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.seconds(10), event -> refreshSelected()));
    private final Timeline liveUiRefresh = new Timeline(new KeyFrame(Duration.seconds(1), event -> flushLiveUi()));
    private final AtomicReference<PterodactylClient.ConsoleStats> pendingStats = new AtomicReference<>();
    private final AtomicReference<PterodactylClient.PowerState> pendingState = new AtomicReference<>();
    private final ReadOnlyStringWrapper activeServerName = new ReadOnlyStringWrapper("Pterodactyl sunucusu seçilmedi");
    private final List<Consumer<String>> consoleListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProSnapshot>> snapshotListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProMetrics>> metricsListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService apiExecutor = Executors.newFixedThreadPool(3, runnable -> { Thread thread = new Thread(runnable, "aeromc-pterodactyl-api"); thread.setDaemon(true); return thread; });
    private PterodactylClient client;
    private PterodactylClient.ServerInfo active;
    private PterodactylClient.ConsoleSession consoleSession;
    private PterodactylClient.PowerState currentState = PterodactylClient.PowerState.UNKNOWN;
    private boolean currentSuspended;
    private boolean connecting, refreshing, consoleConnecting, viewBuilt;

    public PterodactylPane(PanelConfig config, HostServices hostServices) {
        this.config = Objects.requireNonNull(config); this.hostServices = Objects.requireNonNull(hostServices);
        refresh.setCycleCount(Animation.INDEFINITE);
        liveUiRefresh.setCycleCount(Animation.INDEFINITE); liveUiRefresh.play();
    }

    public Node buildView() {
        viewBuilt = true;
        panelUrl.setPromptText("https://panel.example.com"); panelUrl.setText(config.getPterodactylPanelUrl());
        apiKey.setPromptText("Pterodactyl Client API anahtarı (ptlc_...)"); SecretFieldGuard.protect(apiKey);
        HBox.setHgrow(panelUrl, Priority.SOMETIMES); HBox.setHgrow(apiKey, Priority.ALWAYS);
        connect.setOnAction(event -> connectEntered()); connectSaved.setOnAction(event -> connectSaved());
        deleteSaved.setOnAction(event -> deleteSavedCredential());
        FlowPane connectionButtons = new FlowPane(8, 8, connect, connectSaved, deleteSaved);
        HBox connection = new HBox(8, panelUrl, apiKey); connection.setAlignment(Pos.CENTER_LEFT);
        Label securityNote = note("Yalnız Client API anahtarı kullan. AeroMC yönlendirmeleri izlemez; uzak panellerde HTTPS zorunludur. Anahtar başarılı bağlantıdan sonra görünür alandan temizlenir ve otomatik kasa açıksa cihaz bağlı AES-256-GCM kasasına alınır.");
        VBox connectionCard = card("PTERODACTYL BAĞLANTISI", connection, connectionButtons, connectionState, securityNote);

        servers.setPromptText("Pterodactyl sunucusu seç"); servers.setMaxWidth(Double.MAX_VALUE);
        servers.valueProperty().addListener((observable, oldValue, newValue) -> select(newValue));
        start.setOnAction(event -> power(PterodactylClient.PowerSignal.START));
        stop.setOnAction(event -> power(PterodactylClient.PowerSignal.STOP));
        restart.setOnAction(event -> power(PterodactylClient.PowerSignal.RESTART));
        kill.setOnAction(event -> confirmKill()); refreshNow.setOnAction(event -> refreshSelected());
        Button openPanel = button("Panelde Aç", "secondary"); openPanel.setOnAction(event -> openPanel());
        FlowPane actions = new FlowPane(8, 8, start, stop, restart, kill, refreshNow, openPanel);
        updatePowerButtons();

        TilePane metrics = new TilePane(12, 12,
                metricCard("DURUM", serverState), metricCard("ADRES", allocation), metricCard("CPU", cpu),
                metricCard("RAM", memory), metricCard("DİSK", disk), metricCard("ÇALIŞMA", uptime));
        metrics.setPrefColumns(6); metrics.setPrefTileWidth(190);
        VBox serverCard = card("SUNUCU KONTROLÜ", servers, actions, metrics);

        VBox.setVgrow(log, Priority.ALWAYS);
        TextField command = new TextField(); command.setPromptText("Konsol komutu yaz");
        Button send = button("Gönder", "primary"); Runnable sendCommand = () -> sendCommand(command);
        send.setOnAction(event -> sendCommand.run()); command.setOnAction(event -> sendCommand.run()); HBox.setHgrow(command, Priority.ALWAYS);
        Label consoleNote = note("Komutlar Client API ile gönderilir; canlı çıktı ve performans verileri Pterodactyl'in kısa ömürlü WebSocket biletiyle alınır. API anahtarı WebSocket bağlantısına gönderilmez.");
        VBox logCard = card("CANLI PTERODACTYL KONSOLU", log, new HBox(8, command, send), consoleNote); VBox.setVgrow(logCard, Priority.ALWAYS);

        VBox page = new VBox(14, connectionCard, serverCard, logCard); page.setPadding(new Insets(18)); VBox.setVgrow(logCard, Priority.ALWAYS);
        updateVaultState();
        if (config.isAutomaticCredentialVaultEnabled() && DeviceCredentialStore.exists(DeviceCredentialStore.Kind.PTERODACTYL)
                && !panelUrl.getText().isBlank()) Platform.runLater(this::connectSaved);
        return page;
    }

    public void setAutomaticCredentialVaultEnabled(boolean enabled) {
        if (!enabled) {
            try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.PTERODACTYL); }
            catch (IOException error) { throw new IllegalStateException("Pterodactyl kasa kaydı silinemedi: " + error.getMessage(), error); }
        }
        updateVaultState();
    }

    public void shutdown() { refresh.stop(); liveUiRefresh.stop(); closeConsole(); pendingStats.set(null); pendingState.set(null); apiExecutor.shutdownNow(); client = null; active = null; }

    private void connectEntered() {
        String key = apiKey.getText(); if (key == null || key.isBlank()) { showError("Pterodactyl Client API anahtarı gerekli."); return; }
        connect(panelUrl.getText(), key, true);
    }

    private void connectSaved() {
        if (!config.isAutomaticCredentialVaultEnabled()) { showError("Otomatik kimlik kasası Ayarlar bölümünde kapalı."); return; }
        if (!DeviceCredentialStore.exists(DeviceCredentialStore.Kind.PTERODACTYL)) { showError("Kayıtlı Pterodactyl API anahtarı bulunamadı."); return; }
        if (panelUrl.getText().isBlank()) { showError("Önce Pterodactyl panel adresini gir."); return; }
        if (connecting) return; setConnecting(true, "Güvenli Pterodactyl kasası açılıyor...");
        Task<String> task = new Task<>() { @Override protected String call() throws Exception { return DeviceCredentialStore.load(DeviceCredentialStore.Kind.PTERODACTYL); } };
        task.setOnSucceeded(event -> { connecting = false; connect(panelUrl.getText(), task.getValue(), false); });
        task.setOnFailed(event -> { setConnecting(false, "Pterodactyl kasası açılamadı"); showError(rootMessage(task.getException())); });
        run(task, "aeromc-pterodactyl-vault-load");
    }

    private void connect(String url, String key, boolean allowSave) {
        if (connecting) return; setConnecting(true, "Pterodactyl paneline bağlanılıyor...");
        Task<Connection> task = new Task<>() {
            @Override protected Connection call() throws Exception {
                PterodactylClient candidate = new PterodactylClient(url, key);
                List<PterodactylClient.ServerInfo> found = candidate.listServers();
                if (allowSave && config.isAutomaticCredentialVaultEnabled()) DeviceCredentialStore.save(DeviceCredentialStore.Kind.PTERODACTYL, key);
                return new Connection(candidate, found);
            }
        };
        task.setOnSucceeded(event -> {
            Connection result = task.getValue(); closeConsole(); client = result.client(); active = null; activeServerName.set("Pterodactyl sunucusu seçilmedi"); apiKey.clear();
            panelUrl.setText(client.panelUri().toString().replaceAll("/$", "")); config.setPterodactylPanelUrl(panelUrl.getText());
            try { config.save(); } catch (IOException error) { log.append("[AeroMC] Panel adresi kaydedilemedi: " + error.getMessage()); }
            servers.getItems().setAll(result.servers()); setConnecting(false, result.servers().isEmpty() ? "Bağlandı • erişilebilir sunucu yok" : result.servers().size() + " sunucu bulundu");
            log.append("[AeroMC] Pterodactyl Client API bağlantısı kuruldu."); updateVaultState();
            if (!result.servers().isEmpty()) servers.getSelectionModel().selectFirst(); refresh.play();
        });
        task.setOnFailed(event -> { closeConsole(); client = null; active = null; activeServerName.set("Pterodactyl sunucusu seçilmedi"); servers.getItems().clear(); setConnecting(false, "Bağlantı başarısız"); showError(rootMessage(task.getException())); });
        run(task, "aeromc-pterodactyl-connect");
    }

    private void select(PterodactylClient.ServerInfo server) {
        closeConsole();
        active = server; currentState = PterodactylClient.PowerState.UNKNOWN; currentSuspended = server != null && server.suspended();
        activeServerName.set(server == null ? "Pterodactyl sunucusu seçilmedi" : server.name());
        if (server == null) { resetMetrics(); return; }
        allocation.setText(server.allocation()); memory.setText(server.memoryLimitMb() > 0 ? "Limit " + server.memoryLimitMb() + " MB" : "Limitsiz");
        disk.setText(server.diskLimitMb() > 0 ? "Limit " + server.diskLimitMb() + " MB" : "Limitsiz"); cpu.setText(server.cpuLimitPercent() > 0 ? "Limit %" + format(server.cpuLimitPercent()) : "Limitsiz");
        serverState.setText(server.suspended() ? "Askıya alınmış" : "Yenileniyor..."); uptime.setText("-"); updatePowerButtons(); refreshSelected(); connectConsole();
    }

    private void refreshSelected() {
        PterodactylClient currentClient = client; PterodactylClient.ServerInfo server = active;
        if (currentClient == null || server == null || refreshing) return; refreshing = true; refreshNow.setDisable(true);
        Task<PterodactylClient.Resources> task = new Task<>() { @Override protected PterodactylClient.Resources call() throws Exception { return currentClient.resources(server.identifier()); } };
        task.setOnSucceeded(event -> {
            refreshing = false; refreshNow.setDisable(false); PterodactylClient.Resources value = task.getValue(); currentState = value.state(); currentSuspended = value.suspended();
            serverState.setText(value.suspended() ? "Askıya alınmış" : stateText(value.state()));
            cpu.setText("%" + format(value.cpuPercent()) + limitSuffix(server.cpuLimitPercent(), "%"));
            memory.setText(bytes(value.memoryBytes()) + limitSuffix(server.memoryLimitMb(), " MB"));
            disk.setText(bytes(value.diskBytes()) + limitSuffix(server.diskLimitMb(), " MB")); uptime.setText(duration(value.uptimeMillis())); updatePowerButtons();
            publishMetrics(value); publishSnapshot(value);
            if (value.state() == PterodactylClient.PowerState.RUNNING && (consoleSession == null || !consoleSession.isOpen())) connectConsole();
            else if (value.state() == PterodactylClient.PowerState.OFFLINE) closeConsole();
        });
        task.setOnFailed(event -> { refreshing = false; refreshNow.setDisable(false); serverState.setText("Durum alınamadı"); updatePowerButtons(); log.append("[Hata] " + rootMessage(task.getException())); });
        run(task, "aeromc-pterodactyl-refresh");
    }

    private void power(PterodactylClient.PowerSignal signal) {
        PterodactylClient currentClient = client; PterodactylClient.ServerInfo server = active;
        if (currentClient == null || server == null) return; disablePowerButtons(true); connectionState.setText("Pterodactyl güç isteği gönderiliyor...");
        Task<Void> task = new Task<>() { @Override protected Void call() throws Exception { currentClient.power(server.identifier(), signal); return null; } };
        task.setOnSucceeded(event -> { log.append("[AeroMC] " + server.name() + " • " + signalText(signal)); connectionState.setText("Güç isteği kabul edildi"); currentState = PterodactylClient.PowerState.UNKNOWN; updatePowerButtons(); scheduleRefresh(); });
        task.setOnFailed(event -> { connectionState.setText("Güç isteği başarısız"); updatePowerButtons(); showError(rootMessage(task.getException())); });
        run(task, "aeromc-pterodactyl-power");
    }

    private void confirmKill() {
        if (active == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Zorla kapatma kaydedilmemiş dünya verisini bozabilir. Yalnız normal durdurma çalışmadığında kullan.\n\n" + active.name() + " zorla kapatılsın mı?", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Pterodactyl zorla kapatma"); if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) power(PterodactylClient.PowerSignal.KILL);
    }

    private void sendCommand(TextField field) {
        PterodactylClient currentClient = client; PterodactylClient.ServerInfo server = active; if (currentClient == null || server == null) return;
        try {
            CommandSecurity.Assessment assessment = CommandSecurity.assess(field.getText());
            if (assessment.needsConfirmation()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Komut: " + assessment.command() + "\n\n" + assessment.reason(), ButtonType.YES, ButtonType.NO);
                confirm.setHeaderText("Pterodactyl konsol komutunu doğrula"); if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
            }
            Task<Void> task = new Task<>() { @Override protected Void call() throws Exception { currentClient.command(server.identifier(), assessment.command()); return null; } };
            task.setOnSucceeded(event -> { log.append("> " + assessment.command()); field.clear(); });
            task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "aeromc-pterodactyl-command");
        } catch (IllegalArgumentException error) { showError(error.getMessage()); }
    }

    private void connectConsole() {
        PterodactylClient currentClient = client; PterodactylClient.ServerInfo server = active;
        if (currentClient == null || server == null || currentState == PterodactylClient.PowerState.OFFLINE || consoleConnecting || consoleSession != null && consoleSession.isOpen()) return;
        consoleConnecting = true;
        Task<PterodactylClient.ConsoleSession> task = new Task<>() { @Override protected PterodactylClient.ConsoleSession call() throws Exception {
            return currentClient.openConsole(server.identifier(), new PterodactylClient.ConsoleListener() {
                @Override public void onConsole(String line) { consoleListeners.forEach(listener -> listener.accept(line)); log.append(line); }
                @Override public void onStats(PterodactylClient.ConsoleStats stats) { if (active == server) pendingStats.set(stats); }
                @Override public void onStatus(PterodactylClient.PowerState state) { if (active == server) pendingState.set(state); }
                @Override public void onClosed(String reason) { Platform.runLater(() -> { if (active == server) { log.append("[AeroMC] Pterodactyl canlı bağlantısı kapandı: " + reason); consoleSession = null; } }); }
                @Override public void onError(Throwable error) { log.append("[Pterodactyl WebSocket] " + rootMessage(error)); }
            });
        } };
        task.setOnSucceeded(event -> { consoleConnecting = false; if (active == server) { closeConsole(); consoleSession = task.getValue(); log.append("[AeroMC] Pterodactyl canlı konsolu bağlandı."); } else task.getValue().close(); });
        task.setOnFailed(event -> { consoleConnecting = false; log.append("[AeroMC] Canlı konsol bağlanamadı: " + rootMessage(task.getException())); }); run(task, "aeromc-pterodactyl-websocket");
    }

    private void acceptConsoleStats(PterodactylClient.ServerInfo server, PterodactylClient.ConsoleStats value) {
        if (active != server) return; currentState = value.state();
        serverState.setText(stateText(value.state())); cpu.setText("%" + format(value.cpuPercent()) + limitSuffix(server.cpuLimitPercent(), "%"));
        memory.setText(bytes(value.memoryBytes()) + limitSuffix(server.memoryLimitMb(), " MB")); disk.setText(bytes(value.diskBytes()) + limitSuffix(server.diskLimitMb(), " MB")); uptime.setText(duration(value.uptimeMillis())); updatePowerButtons();
        publishMetrics(new PterodactylClient.Resources(value.state(), false, value.memoryBytes(), value.cpuPercent(), value.diskBytes(), value.networkRxBytes(), value.networkTxBytes(), value.uptimeMillis()));
    }

    private void flushLiveUi() {
        PterodactylClient.ServerInfo server = active; if (server == null) { pendingStats.set(null); pendingState.set(null); return; }
        PterodactylClient.ConsoleStats stats = pendingStats.getAndSet(null); if (stats != null) acceptConsoleStats(server, stats);
        PterodactylClient.PowerState state = pendingState.getAndSet(null); if (state != null && (stats == null || state != stats.state())) { currentState = state; serverState.setText(stateText(state)); updatePowerButtons(); }
    }

    private void publishMetrics(PterodactylClient.Resources value) {
        PterodactylClient.ServerInfo server = active; if (server == null) return;
        double memoryPercent = server.memoryLimitMb() > 0 ? value.memoryBytes() * 100.0 / (server.memoryLimitMb() * 1_048_576.0) : Double.NaN;
        ProMetrics metrics = new ProMetrics(value.cpuPercent(), memoryPercent, value.memoryBytes() / 1_048_576L, value.uptimeMillis() / 1000L);
        metricsListeners.forEach(listener -> listener.accept(metrics));
    }

    private void publishSnapshot(PterodactylClient.Resources resources) {
        PterodactylClient.ServerInfo server = active; if (server == null) return;
        CompletableFuture.runAsync(() -> {
            int online = 0, max = 0; try { if (resources.state() == PterodactylClient.PowerState.RUNNING && !"-".equals(server.allocation())) { MinecraftPing.Result ping = MinecraftPing.ping(server.allocation()); online = ping.online(); max = ping.max(); } } catch (Exception ignored) { }
            if (active != server) return;
            ProSnapshot snapshot = new ProSnapshot(server.name(), stateText(resources.state()), resources.state() == PterodactylClient.PowerState.RUNNING, online, max, List.of(), server.memoryLimitMb(), server.allocation());
            snapshotListeners.forEach(listener -> listener.accept(snapshot));
        }, apiExecutor);
    }

    private void closeConsole() { PterodactylClient.ConsoleSession value = consoleSession; consoleSession = null; if (value != null) value.close(); }

    public boolean hasActiveServer() { return client != null && active != null; }
    public String getActiveServerName() { return activeServerName.get(); }
    public ReadOnlyStringProperty activeServerNameProperty() { return activeServerName.getReadOnlyProperty(); }
    public void addConsoleListener(Consumer<String> listener) { consoleListeners.add(listener); }
    public void removeConsoleListener(Consumer<String> listener) { consoleListeners.remove(listener); }
    public void addSnapshotListener(Consumer<ProSnapshot> listener) { snapshotListeners.add(listener); }
    public void removeSnapshotListener(Consumer<ProSnapshot> listener) { snapshotListeners.remove(listener); }
    public void addMetricsListener(Consumer<ProMetrics> listener) { metricsListeners.add(listener); }
    public void removeMetricsListener(Consumer<ProMetrics> listener) { metricsListeners.remove(listener); }

    public CompletableFuture<Void> executeAdminCommand(String command) { return withActive((value, server) -> { value.command(server.identifier(), command); return null; }); }
    public CompletableFuture<Void> startActiveServer() { return powerAsync(PterodactylClient.PowerSignal.START); }
    public CompletableFuture<Void> stopActiveServer() { return powerAsync(PterodactylClient.PowerSignal.STOP); }
    public CompletableFuture<Void> restartActiveServer() { return powerAsync(PterodactylClient.PowerSignal.RESTART); }
    public CompletableFuture<ProSnapshot> fetchProSnapshot() { return withActive((value, server) -> { PterodactylClient.Resources resources = value.resources(server.identifier()); int online = 0, max = 0; try { if (resources.state() == PterodactylClient.PowerState.RUNNING) { MinecraftPing.Result ping = MinecraftPing.ping(server.allocation()); online = ping.online(); max = ping.max(); } } catch (Exception ignored) { } return new ProSnapshot(server.name(), stateText(resources.state()), resources.state() == PterodactylClient.PowerState.RUNNING, online, max, List.of(), server.memoryLimitMb(), server.allocation()); }); }
    public CompletableFuture<Map<String, Object>> loadServerOptions() { return withActive((value, server) -> propertiesMap(value.readFile(server.identifier(), "/server.properties"))); }
    public CompletableFuture<Void> saveServerOptions(Map<String, Object> changes) { return withActive((value, server) -> { String source = value.readFile(server.identifier(), "/server.properties"); Properties properties = new Properties(); properties.load(new StringReader(source)); changes.forEach((key, item) -> properties.setProperty(key, Objects.toString(item, ""))); StringWriter output = new StringWriter(); properties.store(output, "AeroMC Pterodactyl settings"); value.writeFile(server.identifier(), "/server.properties", output.toString()); return null; }); }
    public CompletableFuture<String> readRemoteFile(String path) { return withActive((value, server) -> value.readFile(server.identifier(), path)); }
    public CompletableFuture<Void> writeRemoteFile(String path, String content) { return withActive((value, server) -> { value.writeFile(server.identifier(), path, content); return null; }); }

    private CompletableFuture<Void> powerAsync(PterodactylClient.PowerSignal signal) { return withActive((value, server) -> { value.power(server.identifier(), signal); return null; }); }
    private <T> CompletableFuture<T> withActive(RemoteCall<T> call) {
        PterodactylClient currentClient = client; PterodactylClient.ServerInfo server = active;
        if (currentClient == null || server == null) return CompletableFuture.failedFuture(new IllegalStateException("Önce Pterodactyl sekmesinden bir sunucu seç."));
        return CompletableFuture.supplyAsync(() -> { try { return call.run(currentClient, server); } catch (Exception error) { throw new CompletionException(error); } }, apiExecutor);
    }
    private static Map<String, Object> propertiesMap(String text) throws IOException { Properties properties = new Properties(); properties.load(new StringReader(text)); Map<String, Object> result = new LinkedHashMap<>(); properties.forEach((key, value) -> result.put(String.valueOf(key), value)); return result; }

    private void openPanel() {
        if (client == null) { showError("Önce Pterodactyl paneline bağlan."); return; }
        try { hostServices.showDocument(client.panelUri().toString()); } catch (RuntimeException error) { showError("Pterodactyl paneli açılamadı: " + error.getMessage()); }
    }

    private void deleteSavedCredential() {
        try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.PTERODACTYL); updateVaultState(); connectionState.setText("Pterodactyl kasa kaydı silindi"); }
        catch (IOException error) { showError(error.getMessage()); }
    }

    private void setConnecting(boolean value, String text) { connecting = value; connect.setDisable(value); connectSaved.setDisable(value || !DeviceCredentialStore.exists(DeviceCredentialStore.Kind.PTERODACTYL)); panelUrl.setDisable(value); apiKey.setDisable(value); connectionState.setText(text); }
    private void updateVaultState() {
        if (!viewBuilt) return; boolean exists = DeviceCredentialStore.exists(DeviceCredentialStore.Kind.PTERODACTYL);
        connectSaved.setDisable(connecting || !config.isAutomaticCredentialVaultEnabled() || !exists); deleteSaved.setDisable(!exists);
        apiKey.setPromptText(config.isAutomaticCredentialVaultEnabled() && exists ? "Bu cihazın güvenli kasasından otomatik kullanılıyor" : config.isAutomaticCredentialVaultEnabled() ? "Client API anahtarını bir kez gir; güvenli kasaya alınır" : "Pterodactyl Client API anahtarı (ptlc_...)");
    }

    private void resetMetrics() { for (Label label : List.of(serverState, allocation, cpu, memory, disk, uptime)) label.setText("-"); updatePowerButtons(); }
    private void updatePowerButtons() {
        disablePowerButtons(active == null); if (active == null) return;
        boolean unavailable = currentSuspended || currentState == PterodactylClient.PowerState.UNKNOWN;
        start.setDisable(unavailable || currentState != PterodactylClient.PowerState.OFFLINE);
        stop.setDisable(unavailable || (currentState != PterodactylClient.PowerState.RUNNING && currentState != PterodactylClient.PowerState.STARTING));
        restart.setDisable(unavailable || currentState != PterodactylClient.PowerState.RUNNING);
        kill.setDisable(unavailable || currentState == PterodactylClient.PowerState.OFFLINE);
    }
    private void disablePowerButtons(boolean value) { start.setDisable(value); stop.setDisable(value); restart.setDisable(value); kill.setDisable(value); }
    private void scheduleRefresh() { Timeline later = new Timeline(new KeyFrame(Duration.seconds(2), event -> refreshSelected())); later.play(); }
    private static void run(Task<?> task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }
    private static String rootMessage(Throwable error) { Throwable value = error; while (value != null && value.getCause() != null) value = value.getCause(); return value == null || value.getMessage() == null ? "Bilinmeyen Pterodactyl hatası" : value.getMessage(); }
    private void showError(String text) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(Objects.toString(text, "Bilinmeyen hata")), ButtonType.OK); alert.setHeaderText(LanguageManager.text("Pterodactyl işlemi tamamlanamadı")); alert.showAndWait(); }
    private static Label metric(String text) { Label label = new Label(text); label.getStyleClass().add("metric"); return label; }
    private static Label note(String text) { Label label = new Label(text); label.setWrapText(true); label.getStyleClass().add("muted"); return label; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private static VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static VBox metricCard(String title, Label value) { return card(title, value); }
    private static String stateText(PterodactylClient.PowerState state) { return switch (state) { case OFFLINE -> "Kapalı"; case STARTING -> "Başlatılıyor"; case RUNNING -> "Çalışıyor"; case STOPPING -> "Durduruluyor"; case UNKNOWN -> "Bilinmiyor"; }; }
    private static String signalText(PterodactylClient.PowerSignal signal) { return switch (signal) { case START -> "başlatma isteği"; case STOP -> "güvenli durdurma isteği"; case RESTART -> "yeniden başlatma isteği"; case KILL -> "zorla kapatma isteği"; }; }
    private static String bytes(long value) { if (value < 1_048_576L) return String.format(Locale.ROOT, "%.1f KiB", value / 1024.0); if (value < 1_073_741_824L) return String.format(Locale.ROOT, "%.1f MiB", value / 1_048_576.0); return String.format(Locale.ROOT, "%.2f GiB", value / 1_073_741_824.0); }
    private static String duration(long millis) { if (millis <= 0) return "0 dk"; long minutes = millis / 60_000L, days = minutes / 1440L, hours = minutes % 1440L / 60L; return days > 0 ? days + " gün " + hours + " sa" : hours > 0 ? hours + " sa " + minutes % 60 + " dk" : minutes + " dk"; }
    private static String format(double value) { return String.format(Locale.ROOT, value >= 100 ? "%.0f" : "%.1f", value); }
    private static String limitSuffix(double value, String unit) { return value > 0 ? " / " + format(value) + unit : ""; }
    private record Connection(PterodactylClient client, List<PterodactylClient.ServerInfo> servers) { }
    public record ProSnapshot(String name, String status, boolean online, int players, int maxPlayers, List<String> playerNames, int memoryLimitMb, String address) { }
    public record ProMetrics(double cpuPercent, double memoryPercent, long memoryMb, long uptimeSeconds) { }
    @FunctionalInterface private interface RemoteCall<T> { T run(PterodactylClient client, PterodactylClient.ServerInfo server) throws Exception; }
}
