package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.DeviceCredentialStore;
import com.aerogroup.mcpanel.aeroguard.SecretFieldGuard;
import com.aerogroup.mcpanel.aeroguard.SecureTokenStore;

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
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Objects;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.Map;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import com.exaroton.api.server.config.ConfigOption;

/** Exaroton'un resmi API istemcisini kullanan barındırılmış sunucu görünümü. */
public final class ExarotonPane {
    private static final Path GUARD_FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton-credit-guard.properties");
    private static final Path AUTOMATION_FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton-automation.properties");
    private final PasswordField token = new PasswordField();
    private final PanelConfig config;
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
    private final Timeline creditRefresh = new Timeline(new KeyFrame(Duration.minutes(1), event -> refreshAccount()));
    private final Timeline fleetRefresh = new Timeline(new KeyFrame(Duration.seconds(20), event -> refreshFleet()));
    private final Timeline automationRefresh = new Timeline(new KeyFrame(Duration.seconds(15), event -> runAutomationCheck()));
    private final ExarotonCreditTracker creditTracker = new ExarotonCreditTracker();
    private final FleetHealthHistory fleetHealthHistory = new FleetHealthHistory();
    private final XYChart.Series<Number, Number> creditSeries = new XYChart.Series<>();
    private final Label creditCurrent = new Label("-"), creditToday = new Label("-"), creditRate = new Label("-"), creditRemaining = new Label("-"), creditObserved = new Label("Hesap tüketimi için veri bekleniyor"), creditState = new Label("Kredi bağlantısı bekleniyor");
    private final Spinner<Double> lowCreditThreshold = new Spinner<>(0.1, 1000.0, 1.0, 0.1);
    private final CheckBox lowCreditEnabled = new CheckBox("Düşük kredi eşiği açık");
    private final CheckBox stopAtLowCredit = new CheckBox("Eşik altında sunucuyu otomatik durdur");
    private final FlowPane fleetCards = new FlowPane(14, 14);
    private final Label fleetSummary = new Label("Exaroton bağlantısı bekleniyor");
    private final CheckBox automationEnabled = new CheckBox("Tüm Exaroton otomasyonları açık"), scheduleEnabled = new CheckBox("Saat programı açık"), weekdayEnabled = new CheckBox("Hafta içi"), weekendEnabled = new CheckBox("Hafta sonu"), crashRecovery = new CheckBox("Çökerse otomatik kurtar"), automationIdleStop = new CheckBox("Oyuncu gelmezse durdur"), dailyBudgetEnabled = new CheckBox("Günlük bütçe"), weeklyBudgetEnabled = new CheckBox("Haftalık bütçe");
    private final TextField weekdayStart = new TextField("18:00"), weekdayStop = new TextField("23:00"), weekendStart = new TextField("10:00"), weekendStop = new TextField("02:00");
    private final Spinner<Integer> recoveryAttempts = new Spinner<>(1, 5, 2), automationIdleMinutes = new Spinner<>(5, 240, 20, 5);
    private final Spinner<Double> dailyBudget = new Spinner<>(0.1, 10000.0, 10.0, 0.5), weeklyBudget = new Spinner<>(0.1, 100000.0, 50.0, 1.0);
    private final Label automationTarget = new Label("Hedef sunucu bağlanmadı"), automationState = new Label("Otomasyon beklemede"), readyState = new Label("Hazır olma takibi beklemede"), automationBudgetState = new Label("Bütçe verisi bekleniyor");
    private final ObservableList<String> automationEventRows = FXCollections.observableArrayList();
    private final ExarotonAutomationLog automationLog = new ExarotonAutomationLog();
    private final ReadOnlyStringWrapper activeServerName = new ReadOnlyStringWrapper("Exaroton sunucusu seçilmedi");
    private ExarotonClient client;
    private volatile Server active;
    private ServerStatus lastStatus;
    private final Set<String> knownPlayers = new HashSet<>();
    private final List<Consumer<String>> proConsoleListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProSnapshot>> proSnapshotListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ProMetrics>> proMetricsListeners = new CopyOnWriteArrayList<>();
    private volatile double latestMemoryPercent = Double.NaN, latestTps = Double.NaN, latestMspt = Double.NaN;
    private volatile double accountCredits = Double.NaN;
    private boolean accountRefreshRunning, lowCreditNotified, lowCreditStopTriggered, connecting, viewBuilt, readinessRunning;
    private boolean fleetRefreshRunning, automationRefreshRunning, automationActionRunning, waitingForReady;
    private Instant automationEmptySince, lastAutomationAction, readyDeadline;
    private int automationRecoveryAttempts;
    private String automationTargetId = "", automationTargetName = "", lastAutomationObservedStatus = "";
    private int activeRamGiB = -1;
    private boolean activeServerOnline;
    private List<FleetSnapshot> currentFleet = List.of();
    private TabPane exarotonTabs;

    public ExarotonPane() { this(PanelConfig.load()); }
    public ExarotonPane(PanelConfig config) { this.config = Objects.requireNonNull(config); refresh.setCycleCount(Animation.INDEFINITE); creditRefresh.setCycleCount(Animation.INDEFINITE); fleetRefresh.setCycleCount(Animation.INDEFINITE); automationRefresh.setCycleCount(Animation.INDEFINITE); loadGuardPreferences(); loadAutomationPreferences(); refreshAutomationLog(); }

    public Node buildView() {
        token.setPromptText("Exaroton API anahtarı (dosyaya kaydedilmez)");
        SecretFieldGuard.protect(token);
        Button connect = button("Bağlan", "primary"); connect.setOnAction(event -> connect());
        HBox tokenRow = new HBox(8, token, connect); HBox.setHgrow(token, Priority.ALWAYS);
        loadSaved.setDisable(!SecureTokenStore.exists()); loadSaved.setOnAction(event -> loadSavedToken());
        deleteSaved.setDisable(!SecureTokenStore.exists()); deleteSaved.setOnAction(event -> { try { SecureTokenStore.delete(); loadSaved.setDisable(true); deleteSaved.setDisable(true); } catch (Exception error) { showError(error.getMessage()); } });
        HBox secureRow = new HBox(9, saveEncrypted, loadSaved, deleteSaved); secureRow.setAlignment(Pos.CENTER_LEFT);
        servers.setPromptText("Sunucu seç"); servers.setMaxWidth(Double.MAX_VALUE);
        servers.valueProperty().addListener((observable, oldChoice, newChoice) -> selectServer(newChoice));
        ListView<String> playerList = new ListView<>(players); playerList.setPlaceholder(new Label("Online oyuncu yok")); VBox.setVgrow(playerList, Priority.ALWAYS);
        Button readiness = button("Hazırlık Denetimi", "secondary"); readiness.setOnAction(event -> runReadiness(false));
        start.setOnAction(event -> requestStart());
        stop.setOnAction(event -> action("Sunucu durduruluyor...", "KULLANICI", "Durdurma isteği gönderildi", () -> { active.stop().join(); return null; }));
        restart.setOnAction(event -> restartSafely());
        HBox actions = new HBox(8, start, stop, restart, readiness);
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
        HBox content = new HBox(14, left, playerCard); content.setPadding(new Insets(18)); HBox.setHgrow(left, Priority.ALWAYS);
        Tab controlTab = new Tab("Sunucu Kontrolü", content), fleetTab = new Tab("Filo Paneli", fleetView()), guardTab = new Tab("Kredi Koruması", creditGuardView()), automationTab = new Tab("Otomasyon Merkezi", automationView()); controlTab.setClosable(false); fleetTab.setClosable(false); guardTab.setClosable(false); automationTab.setClosable(false);
        exarotonTabs = new TabPane(controlTab, fleetTab, guardTab, automationTab); exarotonTabs.getStyleClass().add("inner-tabs"); viewBuilt = true; updateAutomaticTokenPrompt(); if (config.isAutomaticCredentialVaultEnabled()) Platform.runLater(this::tryAutomaticConnect); return exarotonTabs;
    }
    private Node fleetView() {
        fleetSummary.getStyleClass().add("metric"); Button refreshNow = button("Tümünü Yenile", "primary"), stopEmpty = button("Oyuncusuz Online Sunucuları Durdur", "danger");
        refreshNow.setOnAction(event -> refreshFleet()); stopEmpty.setOnAction(event -> stopEmptyFleetServers());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox top = new HBox(10, fleetSummary, spacer, refreshNow, stopEmpty); top.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Toplam saatlik maliyet, online sunucuların ayrılmış RAM miktarından hesaplanır. Toplu durdurma yalnızca oyuncu sayısı sıfır görünen online sunuculara uygulanır ve önce onay ister."); note.setWrapText(true); note.getStyleClass().add("muted");
        fleetCards.setPadding(new Insets(2)); ScrollPane scroll = new ScrollPane(fleetCards); scroll.setFitToWidth(true); scroll.getStyleClass().add("dashboard-scroll"); VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox page = new VBox(14, card("EXAROTON FİLO ÖZETİ", top, note), scroll); page.setPadding(new Insets(18)); return page;
    }
    private Node automationView() {
        for (TextField field : List.of(weekdayStart, weekdayStop, weekendStart, weekendStop)) field.setPrefColumnCount(5);
        recoveryAttempts.setEditable(true); automationIdleMinutes.setEditable(true); dailyBudget.setEditable(true); weeklyBudget.setEditable(true);
        automationTarget.getStyleClass().add("metric"); for (Label label : List.of(automationState, readyState, automationBudgetState)) label.getStyleClass().add("muted");
        Button bindTarget = button("Seçili Sunucuyu Hedefle", "secondary"), save = button("Ayarları Kaydet", "primary"), checkNow = button("Şimdi Denetle", "secondary"), disableAll = button("Tümünü Kapat", "danger");
        automationEnabled.setOnAction(event -> updateAutomationControls()); scheduleEnabled.setOnAction(event -> updateAutomationControls()); weekdayEnabled.setOnAction(event -> updateAutomationControls()); weekendEnabled.setOnAction(event -> updateAutomationControls()); crashRecovery.setOnAction(event -> updateAutomationControls()); automationIdleStop.setOnAction(event -> updateAutomationControls()); dailyBudgetEnabled.setOnAction(event -> updateAutomationControls()); weeklyBudgetEnabled.setOnAction(event -> updateAutomationControls());
        bindTarget.setOnAction(event -> bindAutomationTarget()); save.setOnAction(event -> saveAutomationPreferences(true)); checkNow.setOnAction(event -> runAutomationCheck()); disableAll.setOnAction(event -> { automationEnabled.setSelected(false); saveAutomationPreferences(false); automationState.setText("Tüm otomasyonlar kapatıldı"); addAutomationEvent(automationTargetName, "KULLANICI", "Tüm otomasyonlar kapatıldı"); });
        FlowPane masterActions = new FlowPane(9, 9, automationEnabled, bindTarget, save, checkNow, disableAll);
        FlowPane weekdays = new FlowPane(10, 9, weekdayEnabled, new Label("Başlat"), weekdayStart, new Label("Durdur"), weekdayStop);
        FlowPane weekends = new FlowPane(10, 9, weekendEnabled, new Label("Başlat"), weekendStart, new Label("Durdur"), weekendStop);
        FlowPane schedule = new FlowPane(10, 9, scheduleEnabled); schedule.getChildren().addAll(weekdays, weekends);
        FlowPane resilience = new FlowPane(10, 9, crashRecovery, new Label("En fazla deneme"), recoveryAttempts, automationIdleStop, new Label("Oyuncusuz dakika"), automationIdleMinutes);
        FlowPane budgets = new FlowPane(10, 9, dailyBudgetEnabled, dailyBudget, new Label("kredi"), weeklyBudgetEnabled, weeklyBudget, new Label("kredi"));
        ListView<String> events = new ListView<>(automationEventRows); events.setPlaceholder(new Label("Henüz Exaroton otomasyon olayı yok")); events.setPrefHeight(280);
        Button refreshLog = button("Günlüğü Yenile", "secondary"), clearLog = button("Günlüğü Temizle", "danger"); refreshLog.setOnAction(event -> refreshAutomationLog()); clearLog.setOnAction(event -> clearAutomationLog());
        Label scheduleNote = new Label("Gece yarısını aşan programlar desteklenir. Örnek: 20:00 → 02:00. Zamanlama ve otomatik eylemler AeroMC açıkken çalışır."); scheduleNote.setWrapText(true); scheduleNote.getStyleClass().add("muted");
        Label safetyNote = new Label("Bütçe sınırı en yüksek önceliktedir; sınır dolunca sunucu oyuncu olsa da duyuru gönderilerek durdurulur ve yeniden başlatılmaz. Her otomatik işlemden hemen önce sunucu durumu tekrar doğrulanır."); safetyNote.setWrapText(true); safetyNote.getStyleClass().add("muted");
        VBox page = new VBox(14,
                card("OTOMASYON ANA KONTROLÜ", automationTarget, masterActions, automationState, readyState),
                card("HAFTA İÇİ & HAFTA SONU PROGRAMI", schedule, scheduleNote),
                card("ÇÖKME KURTARMA & OYUNCU BEKLEME", resilience),
                card("GÜNLÜK & HAFTALIK KREDİ BÜTÇESİ", budgets, automationBudgetState, safetyNote),
                card("EXAROTON OLAY GÜNLÜĞÜ", new HBox(9, refreshLog, clearLog), events));
        page.setPadding(new Insets(18)); ScrollPane scroll = new ScrollPane(page); scroll.setFitToWidth(true); scroll.getStyleClass().add("dashboard-scroll"); updateAutomationControls(); updateAutomationTargetLabel(); return scroll;
    }
    private Node creditGuardView() {
        for (Label label : List.of(creditCurrent, creditToday, creditRate, creditRemaining)) label.getStyleClass().add("metric");
        TilePane metrics = new TilePane(12, 12, metricCard("MEVCUT KREDİ", creditCurrent), metricCard("BUGÜN HESAPTAN HARCANAN", creditToday), metricCard("SEÇİLİ SUNUCU / SAAT", creditRate), metricCard("TAHMİNİ KALAN", creditRemaining)); metrics.setPrefColumns(4); metrics.setPrefTileWidth(260);
        NumberAxis x = new NumberAxis(); x.setForceZeroInRange(false); x.setTickLabelsVisible(false); x.setTickMarkVisible(false); NumberAxis y = new NumberAxis(); y.setForceZeroInRange(false); y.setLabel("Kredi");
        LineChart<Number, Number> chart = new LineChart<>(x, y); chart.setAnimated(false); chart.setCreateSymbols(false); chart.setLegendVisible(false); chart.getData().add(creditSeries); chart.setPrefHeight(330);
        lowCreditThreshold.setEditable(true); creditObserved.getStyleClass().add("muted"); creditState.getStyleClass().add("muted");
        lowCreditEnabled.setOnAction(event -> { lowCreditNotified = false; lowCreditStopTriggered = false; updateThresholdControls(); saveGuardPreferences(); checkLowCredit(); });
        stopAtLowCredit.setOnAction(event -> { lowCreditStopTriggered = false; if (stopAtLowCredit.isSelected()) { Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Kredi eşiğin altına düştüğünde seçili Exaroton sunucusu, oyuncu olsa bile duyuru gönderilerek durdurulacak. Etkinleştirilsin mi?", ButtonType.YES, ButtonType.NO); confirm.setHeaderText("Düşük kredi otomatik durdurma"); if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) stopAtLowCredit.setSelected(false); } saveGuardPreferences(); checkLowCredit(); });
        lowCreditThreshold.valueProperty().addListener((obs, old, value) -> { lowCreditNotified = false; lowCreditStopTriggered = false; saveGuardPreferences(); checkLowCredit(); });
        Button refreshNow = button("Krediyi Şimdi Yenile", "primary"); refreshNow.setOnAction(event -> refreshAccount());
        FlowPane thresholdControls = new FlowPane(10, 9, lowCreditEnabled, new Label("Eşik"), lowCreditThreshold, stopAtLowCredit, refreshNow); thresholdControls.setAlignment(Pos.CENTER_LEFT); updateThresholdControls();
        Label note = new Label("Bu bölüm yalnızca kredi bakiyesini korur. Oyuncusuz kalınca durdurma, saat programı ve çökme kurtarma ayarları Otomasyon Merkezi'nde tek yerden yönetilir. Seçili sunucu maliyeti Exaroton'un resmî 1 kredi / GiB RAM / saat tarifesine göre hesaplanır."); note.setWrapText(true); note.getStyleClass().add("muted");
        VBox page = new VBox(14, metrics, card("HESAP KREDİ GEÇMİŞİ", chart), card("DÜŞÜK KREDİ KORUMASI", thresholdControls, creditObserved, creditState, note)); page.setPadding(new Insets(18)); VBox.setVgrow(page.getChildren().get(1), Priority.ALWAYS); updateCreditUi(); return page;
    }
    private VBox metricCard(String title, Label value) { VBox box = card(title, value); box.setMaxWidth(Double.MAX_VALUE); return box; }
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
        if (connecting) { if (encryptionPassword != null) Arrays.fill(encryptionPassword, '\0'); return; }
        connecting = true;
        status.setText("Bağlanıyor...");
        Task<List<Server>> task = new Task<>() { protected List<Server> call() throws Exception { client = new ExarotonClient(apiToken).setUserAgent("AeroMC-Server-Panel/1.0"); return client.getServers().get(); } };
        task.setOnSucceeded(event -> {
            connecting = false;
            servers.getItems().setAll(task.getValue().stream().map(ServerChoice::new).toList());
            status.setText(task.getValue().isEmpty() ? "Sunucu bulunamadı" : "Bağlandı");
            token.clear();
            if (config.isAutomaticCredentialVaultEnabled()) {
                try { DeviceCredentialStore.save(DeviceCredentialStore.Kind.EXAROTON, apiToken); updateAutomaticTokenPrompt(); }
                catch (Exception error) { status.setText("Bağlandı; otomatik kasa kaydedilemedi"); showError("API anahtarı otomatik kasaya kaydedilemedi: " + message(error)); }
            }
            if (encryptionPassword != null) {
                try { SecureTokenStore.save(apiToken, encryptionPassword); loadSaved.setDisable(false); deleteSaved.setDisable(false); } catch (Exception error) { showError("Anahtar bağlandı fakat saklanamadı: " + error.getMessage()); }
                finally { Arrays.fill(encryptionPassword, '\0'); }
            }
            refreshAccount(); creditRefresh.play(); refreshFleet(); fleetRefresh.play(); updateAutomationTargetLabel(); automationRefresh.play();
            if (!servers.getItems().isEmpty()) servers.getSelectionModel().selectFirst();
        });
        task.setOnFailed(event -> { connecting = false; client = null; if (encryptionPassword != null) Arrays.fill(encryptionPassword, '\0'); status.setText("Bağlantı başarısız"); showError(message(task.getException())); }); run(task, "exaroton-connect");
    }
    private void loadSavedToken() {
        Optional<char[]> entered = passwordDialog("Ana Parolayı Gir", false); if (entered.isEmpty()) return;
        char[] password = entered.get();
        try { connectWithToken(SecureTokenStore.load(password), null); }
        catch (Exception error) { showError("Anahtar açılamadı. Parola yanlış veya kayıt bozuk olabilir."); }
        finally { Arrays.fill(password, '\0'); }
    }
    private Optional<char[]> passwordDialog(String title, boolean confirm) {
        Dialog<char[]> dialog = new Dialog<>(); dialog.setTitle(LanguageManager.text(title)); dialog.setHeaderText(LanguageManager.text(confirm ? "En az 12 karakterlik bir ana parola belirle. Bu parola kaydedilmez." : "Şifreli API anahtarını açmak için ana parolanı gir."));
        PasswordField first = new PasswordField(); first.setPromptText(LanguageManager.text("Ana parola")); VBox fields = new VBox(8, first);
        SecretFieldGuard.protect(first); PasswordField second = new PasswordField(); SecretFieldGuard.protect(second); if (confirm) { second.setPromptText(LanguageManager.text("Ana parolayı tekrar yaz")); fields.getChildren().add(second); }
        dialog.getDialogPane().setContent(fields); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> { if (button != ButtonType.OK) return null; if (first.getText().length() < (confirm ? 12 : 8)) { showError(confirm ? "Ana parola en az 12 karakter olmalı." : "Ana parola geçersiz."); return null; } if (confirm && !first.getText().equals(second.getText())) { showError("Ana parolalar eşleşmiyor."); return null; } return first.getText().toCharArray(); });
        return dialog.showAndWait();
    }
    private void selectServer(ServerChoice choice) {
        if (choice == null) { activeServerName.set("Exaroton sunucusu seçilmedi"); return; }
        if (active != null) active.unsubscribe();
        active = choice.server();
        NotificationCenter.shared().registerServerSource(notificationSource(active));
        activeServerName.set(active.getName());
        latestMemoryPercent = Double.NaN; latestTps = Double.NaN; latestMspt = Double.NaN; activeRamGiB = -1; activeServerOnline = false; updateCreditUi();
        status.setText("Durum alınıyor..."); address.setText(active.getAddress()); playerCount.setText("-"); ram.setText("-");
        start.setDisable(true); stop.setDisable(true); restart.setDisable(true);
        active.addConsoleSubscriber(line -> { console.append(line); proConsoleListeners.forEach(listener -> listener.accept(line)); });
        active.addStatusSubscriber((oldServer, newServer) -> Platform.runLater(() -> update(newServer)));
        active.addStatsSubscriber(data -> { if (data != null && data.getMemory() != null) latestMemoryPercent = data.getMemory().getPercent(); publishProMetrics(); });
        active.addTickSubscriber(data -> { if (data != null) { latestTps = data.calculateTPS(); latestMspt = data.getAverageTickTime(); publishProMetrics(); } });
        console.clearConsole(); knownPlayers.clear(); lastStatus = null; refreshServer(); refresh.play();
    }
    private void refreshServer() {
        if (active == null) return;
        Server requestedServer = active;
        Task<Server> task = new Task<>() { protected Server call() throws Exception { return requestedServer.fetch().get(8, TimeUnit.SECONDS); } };
        task.setOnSucceeded(event -> { if (active != null && active.getId().equals(requestedServer.getId())) update(task.getValue()); });
        task.setOnFailed(event -> { if (active != null && active.getId().equals(requestedServer.getId())) status.setText("Durum alınamadı"); }); run(task, "exaroton-refresh");
    }
    private void update(Server server) {
        if (active == null || !active.getId().equals(server.getId())) return;
        status.setText(server.getStatus().getName()); address.setText(server.getAddress());
        var info = server.getPlayerInfo();
        for (String name : info.getList()) if (!knownPlayers.contains(name)) DesktopNotifier.show(notificationSource(server), server.getName(), name + " sunucuya katıldı.");
        knownPlayers.clear(); knownPlayers.addAll(info.getList()); players.setAll(info.getList()); playerCount.setText(info.getCount() + " / " + info.getMax());
        if (lastStatus != null && lastStatus != server.getStatus()) { addAutomationEvent(server.getName(), "DURUM", lastStatus.getName() + " → " + server.getStatus().getName()); if (server.hasStatus(ServerStatus.OFFLINE, ServerStatus.CRASHED)) DesktopNotifier.show(notificationSource(server), server.getName(), "Sunucu " + server.getStatus().getName() + " durumuna geçti."); }
        lastStatus = server.getStatus();
        try { server.getRAM().thenAccept(value -> Platform.runLater(() -> { activeRamGiB = value.getRam(); ram.setText(value.getRam() + " GiB"); updateCreditUi(); })); } catch (Exception ignored) { }
        boolean online = server.hasStatus(ServerStatus.ONLINE); boolean offline = server.hasStatus(ServerStatus.OFFLINE, ServerStatus.CRASHED), becameOnline = online && !activeServerOnline;
        activeServerOnline = online; if (becameOnline) lowCreditStopTriggered = false; updateCreditUi(); checkLowCredit();
        start.setDisable(!offline); stop.setDisable(!online); restart.setDisable(!online);
        ProSnapshot snapshot = snapshot(server, -1); proSnapshotListeners.forEach(listener -> listener.accept(snapshot));
    }
    private void action(String text, Callable<Void> operation) {
        action(text, null, null, operation);
    }
    private void action(String text, String logSource, String logDetail, Callable<Void> operation) {
        if (active == null) { showError("Önce bir sunucu seç."); return; }
        Server operated = active; status.setText(text); Task<Void> task = new Task<>() { protected Void call() throws Exception { return operation.call(); } };
        task.setOnSucceeded(event -> { if (logSource != null) addAutomationEvent(operated.getName(), logSource, logDetail); refreshServer(); }); task.setOnFailed(event -> { status.setText("İşlem başarısız"); if (logSource != null) addAutomationEvent(operated.getName(), "HATA", logDetail + ": " + message(task.getException())); showError(message(task.getException())); }); run(task, "exaroton-action");
    }
    private void refreshAccount() {
        ExarotonClient selected = client; if (selected == null || accountRefreshRunning) return; accountRefreshRunning = true; creditState.setText("Kredi yenileniyor..."); creditState.getStyleClass().remove("crisis-active");
        try { selected.getAccount().whenComplete((value, error) -> Platform.runLater(() -> { accountRefreshRunning = false; if (selected != client) return; if (error != null || value == null) { creditState.setText("Kredi bilgisi alınamadı: bağlantıyı kontrol et"); return; } accountCredits = value.getCredits(); account.setText(value.getName()); credits.setText(String.format(Locale.US, "%.2f", value.getCredits())); creditTracker.record(accountCredits); updateCreditUi(); checkLowCredit(); })); }
        catch (Exception ignored) { accountRefreshRunning = false; creditState.setText("Kredi bilgisi alınamadı"); }
    }
    private void refreshFleet() {
        if (client == null || fleetRefreshRunning) return; List<ServerChoice> choices = List.copyOf(servers.getItems()); if (choices.isEmpty()) { currentFleet = List.of(); renderFleet(); return; }
        fleetRefreshRunning = true; fleetSummary.setText("Filo durumu yenileniyor...");
        Task<List<FleetSnapshot>> task = new Task<>() { protected List<FleetSnapshot> call() {
            List<FleetSnapshot> result = new ArrayList<>();
            for (ServerChoice choice : choices) {
                Server source = choice.server();
                try { Server fresh = source.fetch().get(8, TimeUnit.SECONDS); int allocated = -1; try { allocated = fresh.getRAM().get(8, TimeUnit.SECONDS).getRam(); } catch (Exception ignored) { } result.add(fleetSnapshot(fresh, allocated)); }
                catch (Exception error) { result.add(fleetSnapshot(source, -1)); }
            }
            return result;
        } };
        task.setOnSucceeded(event -> { fleetRefreshRunning = false; currentFleet = task.getValue(); fleetHealthHistory.record(Instant.now(), currentFleet.stream().map(FleetSnapshot::state).toList()); renderFleet(); }); task.setOnFailed(event -> { fleetRefreshRunning = false; fleetSummary.setText("Filo durumu alınamadı"); }); run(task, "exaroton-fleet-refresh");
    }
    private FleetSnapshot fleetSnapshot(Server server, int allocatedRam) {
        var info = server.getPlayerInfo(); boolean online = server.hasStatus(ServerStatus.ONLINE), crashed = server.hasStatus(ServerStatus.CRASHED);
        return new FleetSnapshot(server, new ExarotonFleetEngine.ServerState(server.getName(), server.getStatus().getName(), online, crashed, info.getCount(), info.getMax(), allocatedRam), server.getAddress());
    }
    private void renderFleet() {
        List<ExarotonFleetEngine.ServerState> states = currentFleet.stream().map(FleetSnapshot::state).toList(); ExarotonFleetEngine.Summary summary = ExarotonFleetEngine.summarize(states);
        fleetSummary.setText(summary.onlineServers() + " / " + summary.totalServers() + " online  •  " + summary.totalPlayers() + " oyuncu  •  " + summary.activeRamGiB() + " GiB aktif  •  " + String.format(Locale.US, "%.2f kredi/saat", summary.creditsPerHour()) + (summary.crashedServers() > 0 ? "  •  " + summary.crashedServers() + " çöktü" : ""));
        fleetCards.getChildren().clear(); if (currentFleet.isEmpty()) { fleetCards.getChildren().add(new Label("Hesapta Exaroton sunucusu bulunamadı.")); return; }
        for (FleetSnapshot snapshot : currentFleet) fleetCards.getChildren().add(fleetCard(snapshot));
    }
    private VBox fleetCard(FleetSnapshot snapshot) {
        ExarotonFleetEngine.ServerState state = snapshot.state(); Label title = new Label(state.name()); title.getStyleClass().add("server-card-title"); Label stateLabel = new Label(state.status()); stateLabel.getStyleClass().add(state.crashed() ? "crisis-active" : "metric-small");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox heading = new HBox(8, title, spacer, stateLabel); heading.setAlignment(Pos.CENTER_LEFT);
        Label addressLabel = new Label(snapshot.address()); addressLabel.getStyleClass().add("muted"); Label details = new Label(state.players() + " / " + state.maxPlayers() + " oyuncu  •  " + (state.ramGiB() > 0 ? state.ramGiB() + " GiB" : "RAM ?") + (state.online() && state.ramGiB() > 0 ? "  •  " + state.ramGiB() + " kredi/saat" : ""));
        Button manage = button("Yönet", "secondary"), startServer = button("Başlat", "primary"), stopServer = button("Durdur", "danger"), restartServer = button("Yeniden Başlat", "secondary");
        String statusText = state.status().toLowerCase(Locale.ROOT); boolean startable = statusText.contains("offline") || statusText.contains("crash"); startServer.setDisable(!startable); stopServer.setDisable(!state.online()); restartServer.setDisable(!state.online());
        manage.setOnAction(event -> manageFleetServer(snapshot.server())); startServer.setOnAction(event -> fleetAction("Sunucu başlatılıyor", snapshot.server(), () -> snapshot.server().start().join())); stopServer.setOnAction(event -> fleetAction("Sunucu durduruluyor", snapshot.server(), () -> snapshot.server().stop().join())); restartServer.setOnAction(event -> fleetAction("Sunucu yeniden başlatılıyor", snapshot.server(), () -> snapshot.server().restart().join()));
        FlowPane actions = new FlowPane(8, 8, manage, startServer, stopServer, restartServer); VBox card = new VBox(9, heading, addressLabel, details, actions); card.getStyleClass().add("server-card"); card.setPrefWidth(360); return card;
    }
    private void manageFleetServer(Server server) { servers.getItems().stream().filter(choice -> choice.server().getId().equals(server.getId())).findFirst().ifPresent(choice -> servers.getSelectionModel().select(choice)); if (exarotonTabs != null) exarotonTabs.getSelectionModel().selectFirst(); }
    private void fleetAction(String text, Server server, CheckedOperation operation) { fleetSummary.setText(text + ": " + server.getName()); Task<Void> task = new Task<>() { protected Void call() throws Exception { operation.run(); return null; } }; task.setOnSucceeded(event -> { addAutomationEvent(server.getName(), "KULLANICI", text); refreshFleet(); if (active != null && active.getId().equals(server.getId())) refreshServer(); }); task.setOnFailed(event -> { addAutomationEvent(server.getName(), "HATA", text + ": " + message(task.getException())); showError(text + " başarısız: " + message(task.getException())); refreshFleet(); }); run(task, "exaroton-fleet-action"); }
    private void stopEmptyFleetServers() {
        List<FleetSnapshot> targets = currentFleet.stream().filter(snapshot -> snapshot.state().online() && snapshot.state().players() == 0).toList(); if (targets.isEmpty()) { showError("Durdurulabilecek oyuncusuz online sunucu yok."); return; }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, targets.size() + " oyuncusuz online Exaroton sunucusu durdurulsun mu?", ButtonType.YES, ButtonType.NO); confirm.setHeaderText("Toplu Exaroton durdurma"); if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        fleetSummary.setText("Oyuncusuz sunucular tekrar doğrulanıp durduruluyor..."); Task<Integer> task = new Task<>() { protected Integer call() { int stopped = 0; for (FleetSnapshot target : targets) try { Server fresh = target.server().fetch().get(8, TimeUnit.SECONDS); if (fresh.hasStatus(ServerStatus.ONLINE) && fresh.getPlayerInfo().getCount() == 0) { fresh.stop().join(); stopped++; } } catch (Exception ignored) { } return stopped; } };
        task.setOnSucceeded(event -> { fleetSummary.setText(task.getValue() + " sunucu durdurma isteği gönderildi"); refreshFleet(); }); task.setOnFailed(event -> { showError("Toplu durdurma tamamlanamadı."); refreshFleet(); }); run(task, "exaroton-fleet-stop-empty");
    }
    private void updateCreditUi() {
        ExarotonCreditTracker.Stats stats = creditTracker.stats(); creditSeries.getData().clear(); int index = 0;
        for (ExarotonCreditTracker.Sample sample : creditTracker.recent(120)) creditSeries.getData().add(new XYChart.Data<>(index++, sample.credits()));
        creditCurrent.setText(Double.isFinite(stats.current()) ? String.format(Locale.US, "%.2f", stats.current()) : "Veri bekleniyor"); creditToday.setText(String.format(Locale.US, "%.2f", stats.spentToday()));
        double selectedRate = ExarotonCreditTracker.officialServerRate(activeServerOnline, activeRamGiB);
        if (activeServerOnline && activeRamGiB > 0) { creditRate.setText(String.format(Locale.US, "%.2f kredi • %d GiB", selectedRate, activeRamGiB)); creditRemaining.setText(Double.isFinite(stats.current()) ? remainingText(stats.current() / selectedRate) : "Kredi verisi yok"); }
        else if (activeServerOnline) { creditRate.setText("RAM bilgisi bekleniyor"); creditRemaining.setText("Hesaplanıyor"); }
        else { creditRate.setText(active == null ? "Sunucu seçilmedi" : "Offline • 0 kredi"); creditRemaining.setText(Double.isFinite(stats.current()) ? "Sunucu kapalı • kredi korunuyor" : "Kredi verisi yok"); }
        if (!activeServerOnline && active != null) creditObserved.setText("Seçili sunucu offline • hesap geçmişindeki düşüş bu sunucunun anlık tüketimi değildir");
        else creditObserved.setText(stats.perHour() > 0 ? String.format(Locale.US, "Hesap bakiyesinde gözlenen: %.3f kredi/saat • %d dk örnek (diğer hesap hareketlerini içerebilir)", stats.perHour(), stats.observedMinutes()) : "Hesap bakiyesi tüketimi için en az 1 dakika değişim verisi gerekli");
    }
    private void checkLowCredit() {
        creditState.getStyleClass().remove("crisis-active");
        if (!lowCreditEnabled.isSelected()) { creditState.setText("Düşük kredi eşiği kapalı"); lowCreditNotified = false; lowCreditStopTriggered = false; return; }
        if (!Double.isFinite(accountCredits)) { creditState.setText("Kredi bağlantısı bekleniyor"); return; } double threshold = lowCreditThreshold.getValue();
        if (accountCredits <= threshold) { creditState.setText(String.format(Locale.US, "DÜŞÜK KREDİ • %.2f kredi kaldı • eşik %.2f", accountCredits, threshold)); creditState.getStyleClass().add("crisis-active"); if (!lowCreditNotified) { lowCreditNotified = true; DesktopNotifier.show("Exaroton düşük kredi", String.format(Locale.US, "%.2f kredi kaldı. Eşik: %.2f", accountCredits, threshold)); } if (ExarotonCreditTracker.shouldStopAtThreshold(true, stopAtLowCredit.isSelected(), activeServerOnline, accountCredits, threshold) && !lowCreditStopTriggered) stopForLowCredit(); }
        else { creditState.setText("Son kredi kontrolü " + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) + String.format(Locale.US, " • eşik %.2f", threshold)); lowCreditStopTriggered = false; if (accountCredits > threshold * 1.15) lowCreditNotified = false; }
    }
    private void stopForLowCredit() {
        Server protectedServer = active; if (protectedServer == null) return; lowCreditStopTriggered = true; creditState.setText(String.format(Locale.US, "DÜŞÜK KREDİ • %.2f kaldı • sunucu durduruluyor", accountCredits)); creditState.getStyleClass().add("crisis-active");
        action("Düşük kredi koruması sunucuyu durduruyor...", "KREDİ KORUMASI", "Düşük kredi eşiği nedeniyle durduruldu", () -> { try { protectedServer.executeCommand("say [AeroMC] Kredi güvenlik eşiğine ulaşıldı; sunucu durduruluyor.").join(); } catch (Exception ignored) { } protectedServer.stop().join(); return null; });
    }
    private void updateThresholdControls() { boolean enabled = lowCreditEnabled.isSelected(); lowCreditThreshold.setDisable(!enabled); stopAtLowCredit.setDisable(!enabled); }
    private String remainingText(double hours) { if (hours < 1) return Math.max(1, Math.round(hours * 60)) + " dk"; if (hours < 48) return String.format(Locale.US, "%.1f saat", hours); return String.format(Locale.US, "%.1f gün", hours / 24.0); }
    private void loadGuardPreferences() { Properties values = new Properties(); try { if (Files.exists(GUARD_FILE)) try (Reader reader = Files.newBufferedReader(GUARD_FILE, StandardCharsets.UTF_8)) { values.load(reader); } lowCreditEnabled.setSelected(Boolean.parseBoolean(values.getProperty("lowCreditEnabled", "true"))); stopAtLowCredit.setSelected(Boolean.parseBoolean(values.getProperty("stopAtLowCredit", "false"))); lowCreditThreshold.getValueFactory().setValue(Double.parseDouble(values.getProperty("lowCreditThreshold", "1.0"))); } catch (Exception ignored) { } }
    private void saveGuardPreferences() { try { Files.createDirectories(GUARD_FILE.getParent()); Properties values = new Properties(); values.setProperty("lowCreditEnabled", Boolean.toString(lowCreditEnabled.isSelected())); values.setProperty("stopAtLowCredit", Boolean.toString(stopAtLowCredit.isSelected())); values.setProperty("lowCreditThreshold", lowCreditThreshold.getValue().toString()); try (Writer writer = Files.newBufferedWriter(GUARD_FILE, StandardCharsets.UTF_8)) { values.store(writer, "AeroMC Exaroton credit guard"); } } catch (IOException ignored) { } }
    private void loadAutomationPreferences() {
        Properties values = new Properties(), legacyGuard = new Properties(); try {
            if (Files.exists(AUTOMATION_FILE)) try (Reader reader = Files.newBufferedReader(AUTOMATION_FILE, StandardCharsets.UTF_8)) { values.load(reader); }
            if (Files.exists(GUARD_FILE)) try (Reader reader = Files.newBufferedReader(GUARD_FILE, StandardCharsets.UTF_8)) { legacyGuard.load(reader); }
            automationEnabled.setSelected(Boolean.parseBoolean(values.getProperty("enabled", "false"))); scheduleEnabled.setSelected(Boolean.parseBoolean(values.getProperty("scheduleEnabled", "false")));
            weekdayEnabled.setSelected(Boolean.parseBoolean(values.getProperty("weekdayEnabled", "true"))); weekdayStart.setText(values.getProperty("weekdayStart", "18:00")); weekdayStop.setText(values.getProperty("weekdayStop", "23:00"));
            weekendEnabled.setSelected(Boolean.parseBoolean(values.getProperty("weekendEnabled", "true"))); weekendStart.setText(values.getProperty("weekendStart", "10:00")); weekendStop.setText(values.getProperty("weekendStop", "02:00"));
            crashRecovery.setSelected(Boolean.parseBoolean(values.getProperty("crashRecovery", "true"))); recoveryAttempts.getValueFactory().setValue(Integer.parseInt(values.getProperty("recoveryAttempts", "2")));
            automationIdleStop.setSelected(Boolean.parseBoolean(values.getProperty("idleStop", legacyGuard.getProperty("autoStopEmpty", "false")))); automationIdleMinutes.getValueFactory().setValue(Integer.parseInt(values.getProperty("idleMinutes", legacyGuard.getProperty("emptyMinutes", "20"))));
            dailyBudgetEnabled.setSelected(Boolean.parseBoolean(values.getProperty("dailyBudgetEnabled", "false"))); dailyBudget.getValueFactory().setValue(Double.parseDouble(values.getProperty("dailyBudget", "10.0")));
            weeklyBudgetEnabled.setSelected(Boolean.parseBoolean(values.getProperty("weeklyBudgetEnabled", "false"))); weeklyBudget.getValueFactory().setValue(Double.parseDouble(values.getProperty("weeklyBudget", "50.0")));
            automationTargetId = values.getProperty("targetId", ""); automationTargetName = values.getProperty("targetName", "");
        } catch (Exception ignored) { automationEnabled.setSelected(false); }
    }
    private void saveAutomationPreferences(boolean notifySaved) {
        try {
            if (automationEnabled.isSelected()) automationConfig();
            if (automationEnabled.isSelected() && automationTargetId.isBlank()) { if (active == null) { showError("Otomasyonu açmadan önce bir Exaroton sunucusunu hedefle."); automationEnabled.setSelected(false); return; } automationTargetId = active.getId(); automationTargetName = active.getName(); }
            Files.createDirectories(AUTOMATION_FILE.getParent()); Properties values = new Properties();
            values.setProperty("enabled", Boolean.toString(automationEnabled.isSelected())); values.setProperty("scheduleEnabled", Boolean.toString(scheduleEnabled.isSelected()));
            values.setProperty("weekdayEnabled", Boolean.toString(weekdayEnabled.isSelected())); values.setProperty("weekdayStart", weekdayStart.getText().trim()); values.setProperty("weekdayStop", weekdayStop.getText().trim());
            values.setProperty("weekendEnabled", Boolean.toString(weekendEnabled.isSelected())); values.setProperty("weekendStart", weekendStart.getText().trim()); values.setProperty("weekendStop", weekendStop.getText().trim());
            values.setProperty("crashRecovery", Boolean.toString(crashRecovery.isSelected())); values.setProperty("recoveryAttempts", recoveryAttempts.getValue().toString()); values.setProperty("idleStop", Boolean.toString(automationIdleStop.isSelected())); values.setProperty("idleMinutes", automationIdleMinutes.getValue().toString());
            values.setProperty("dailyBudgetEnabled", Boolean.toString(dailyBudgetEnabled.isSelected())); values.setProperty("dailyBudget", dailyBudget.getValue().toString()); values.setProperty("weeklyBudgetEnabled", Boolean.toString(weeklyBudgetEnabled.isSelected())); values.setProperty("weeklyBudget", weeklyBudget.getValue().toString());
            values.setProperty("targetId", automationTargetId); values.setProperty("targetName", automationTargetName); try (Writer writer = Files.newBufferedWriter(AUTOMATION_FILE, StandardCharsets.UTF_8)) { values.store(writer, "AeroMC Exaroton automation"); }
            updateAutomationControls(); updateAutomationTargetLabel(); if (notifySaved) { automationState.setText(automationEnabled.isSelected() ? "Ayarlar kaydedildi • otomasyon aktif" : "Ayarlar kaydedildi • otomasyon kapalı"); addAutomationEvent(automationTargetName, "KULLANICI", "Otomasyon ayarları kaydedildi: " + (automationEnabled.isSelected() ? "açık" : "kapalı")); }
        } catch (IllegalArgumentException error) { showError(error.getMessage()); } catch (IOException error) { showError("Otomasyon ayarları kaydedilemedi: " + error.getMessage()); }
    }
    private ExarotonAutomationEngine.Config automationConfig() {
        var weekday = new ExarotonAutomationEngine.Window(weekdayEnabled.isSelected(), ExarotonAutomationEngine.parseTime(weekdayStart.getText().trim()), ExarotonAutomationEngine.parseTime(weekdayStop.getText().trim()));
        var weekend = new ExarotonAutomationEngine.Window(weekendEnabled.isSelected(), ExarotonAutomationEngine.parseTime(weekendStart.getText().trim()), ExarotonAutomationEngine.parseTime(weekendStop.getText().trim()));
        if (scheduleEnabled.isSelected() && !weekday.enabled() && !weekend.enabled()) throw new IllegalArgumentException("Saat programı için hafta içi veya hafta sonundan en az birini aç.");
        return new ExarotonAutomationEngine.Config(automationEnabled.isSelected(), scheduleEnabled.isSelected(), weekday, weekend, crashRecovery.isSelected(), recoveryAttempts.getValue(), automationIdleStop.isSelected(), automationIdleMinutes.getValue(), dailyBudgetEnabled.isSelected(), dailyBudget.getValue(), weeklyBudgetEnabled.isSelected(), weeklyBudget.getValue());
    }
    private void updateAutomationControls() {
        boolean schedule = scheduleEnabled.isSelected(); weekdayEnabled.setDisable(!schedule); weekendEnabled.setDisable(!schedule);
        weekdayStart.setDisable(!schedule || !weekdayEnabled.isSelected()); weekdayStop.setDisable(!schedule || !weekdayEnabled.isSelected()); weekendStart.setDisable(!schedule || !weekendEnabled.isSelected()); weekendStop.setDisable(!schedule || !weekendEnabled.isSelected());
        recoveryAttempts.setDisable(!crashRecovery.isSelected()); automationIdleMinutes.setDisable(!automationIdleStop.isSelected()); dailyBudget.setDisable(!dailyBudgetEnabled.isSelected()); weeklyBudget.setDisable(!weeklyBudgetEnabled.isSelected());
    }
    private void bindAutomationTarget() {
        if (active == null) { showError("Önce Sunucu Kontrolü veya Filo Paneli üzerinden bir sunucu seç."); return; }
        automationTargetId = active.getId(); automationTargetName = active.getName(); automationRecoveryAttempts = 0; automationEmptySince = null; waitingForReady = false; lastAutomationObservedStatus = ""; saveAutomationPreferences(false); updateAutomationTargetLabel(); addAutomationEvent(automationTargetName, "KULLANICI", "Otomasyon hedefi olarak bağlandı"); automationState.setText("Hedef bağlandı • ayarlar izleniyor");
    }
    private void updateAutomationTargetLabel() {
        if (!automationTargetId.isBlank()) servers.getItems().stream().filter(choice -> choice.server().getId().equals(automationTargetId)).findFirst().ifPresent(choice -> automationTargetName = choice.server().getName());
        automationTarget.setText(automationTargetId.isBlank() ? "Hedef sunucu bağlanmadı" : "Hedef: " + (automationTargetName.isBlank() ? automationTargetId : automationTargetName));
    }
    private void runAutomationCheck() {
        if (!automationEnabled.isSelected()) { automationState.setText("Tüm otomasyonlar kapalı"); return; }
        if (client == null) { automationState.setText("Exaroton bağlantısı bekleniyor"); return; }
        if (automationTargetId.isBlank()) { automationState.setText("Hedef sunucu bağlanmadı"); return; }
        if (automationRefreshRunning || automationActionRunning) return;
        ExarotonAutomationEngine.Config config; try { config = automationConfig(); } catch (IllegalArgumentException error) { automationState.setText(error.getMessage()); return; }
        Server target = servers.getItems().stream().map(ServerChoice::server).filter(server -> server.getId().equals(automationTargetId)).findFirst().orElse(null); if (target == null) { automationState.setText("Hedef sunucu hesapta bulunamadı"); return; }
        automationRefreshRunning = true; Task<AutomationObservation> task = new Task<>() { protected AutomationObservation call() throws Exception { Server fresh = target.fetch().get(8, TimeUnit.SECONDS); return automationObservation(fresh); } };
        task.setOnSucceeded(event -> { automationRefreshRunning = false; evaluateAutomation(config, task.getValue()); }); task.setOnFailed(event -> { automationRefreshRunning = false; automationState.setText("Otomasyon denetimi başarısız: " + message(task.getException())); }); run(task, "exaroton-automation-check");
    }
    private AutomationObservation automationObservation(Server server) {
        boolean online = server.hasStatus(ServerStatus.ONLINE), offline = server.hasStatus(ServerStatus.OFFLINE), crashed = server.hasStatus(ServerStatus.CRASHED);
        boolean transitional = server.hasStatus(ServerStatus.STARTING, ServerStatus.STOPPING, ServerStatus.RESTARTING, ServerStatus.SAVING, ServerStatus.LOADING, ServerStatus.PENDING, ServerStatus.TRANSFERRING, ServerStatus.PREPARING);
        return new AutomationObservation(server, server.getStatus().getName(), online, offline, crashed, transitional, server.getPlayerInfo().getCount());
    }
    private void evaluateAutomation(ExarotonAutomationEngine.Config config, AutomationObservation observation) {
        Instant now = Instant.now(); if (!observation.status().equals(lastAutomationObservedStatus)) { if (!lastAutomationObservedStatus.isBlank()) addAutomationEvent(observation.server().getName(), "DURUM", lastAutomationObservedStatus + " → " + observation.status()); lastAutomationObservedStatus = observation.status(); }
        if (observation.online()) { automationRecoveryAttempts = 0; if (waitingForReady) { waitingForReady = false; readyDeadline = null; readyState.setText("Sunucu hazır • " + LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))); addAutomationEvent(observation.server().getName(), "HAZIR", "Sunucu online ve oyuncu kabul etmeye hazır"); DesktopNotifier.show(notificationSource(observation.server()), observation.server().getName(), "Otomatik başlatma tamamlandı; sunucu hazır."); } }
        else if (waitingForReady && readyDeadline != null && now.isAfter(readyDeadline)) { waitingForReady = false; readyState.setText("Hazır olma süresi aşıldı"); addAutomationEvent(observation.server().getName(), "UYARI", "Sunucu 10 dakika içinde online olmadı"); }
        if (observation.online() && observation.players() == 0) { if (automationEmptySince == null) automationEmptySince = now; } else automationEmptySince = null;
        ExarotonCreditTracker.Stats creditStats = creditTracker.stats(); double weeklySpent = creditTracker.spentThisWeek(); automationBudgetState.setText(String.format(Locale.US, "Bugün %.2f / %.2f kredi  •  Bu hafta %.2f / %.2f kredi", creditStats.spentToday(), dailyBudget.getValue(), weeklySpent, weeklyBudget.getValue()));
        var state = new ExarotonAutomationEngine.State(observation.online(), observation.offline(), observation.crashed(), observation.transitional(), observation.players(), automationEmptySince, automationRecoveryAttempts, creditStats.spentToday(), weeklySpent);
        ExarotonAutomationEngine.Decision decision = ExarotonAutomationEngine.evaluate(config, ZonedDateTime.now(), state); automationState.setText(decision.reason());
        if (decision.action() == ExarotonAutomationEngine.Action.NONE || decision.action() == ExarotonAutomationEngine.Action.BLOCKED) return;
        if (lastAutomationAction != null && java.time.Duration.between(lastAutomationAction, now).toSeconds() < 60) { automationState.setText(decision.reason() + " • tekrar deneme bekleniyor"); return; }
        runAutomationAction(config, observation.server(), decision, creditStats.spentToday(), weeklySpent);
    }
    private void runAutomationAction(ExarotonAutomationEngine.Config config, Server target, ExarotonAutomationEngine.Decision expected, double spentToday, double spentWeek) {
        automationActionRunning = true; lastAutomationAction = Instant.now(); automationState.setText(expected.reason() + " • durum tekrar doğrulanıyor");
        Instant emptySnapshot = automationEmptySince; int attemptsSnapshot = automationRecoveryAttempts;
        Task<String> task = new Task<>() { protected String call() throws Exception {
            Server fresh = target.fetch().get(8, TimeUnit.SECONDS); AutomationObservation live = automationObservation(fresh);
            var state = new ExarotonAutomationEngine.State(live.online(), live.offline(), live.crashed(), live.transitional(), live.players(), emptySnapshot, attemptsSnapshot, spentToday, spentWeek);
            ExarotonAutomationEngine.Decision confirmed = ExarotonAutomationEngine.evaluate(config, ZonedDateTime.now(), state);
            if (confirmed.action() != expected.action()) return "ATLANDI\tCanlı doğrulamada koşul değişti: " + confirmed.reason();
            switch (confirmed.action()) {
                case START -> fresh.start().join();
                case RECOVER -> fresh.start().join();
                case STOP -> { try { fresh.executeCommand("say [AeroMC] " + confirmed.reason() + "; sunucu güvenli şekilde durduruluyor.").join(); } catch (Exception ignored) { } fresh.stop().join(); }
                default -> { return "ATLANDI\tEylem gerekmiyor"; }
            }
            return confirmed.action().name() + "\t" + confirmed.reason();
        } };
        task.setOnSucceeded(event -> { automationActionRunning = false; String[] result = task.getValue().split("\\t", 2); String source = result[0], detail = result.length > 1 ? result[1] : expected.reason(); automationState.setText(detail); addAutomationEvent(target.getName(), source.equals("ATLANDI") ? "DOĞRULAMA" : "OTOMASYON", source + " • " + detail); if (source.equals("START") || source.equals("RECOVER")) { waitingForReady = true; readyDeadline = Instant.now().plus(java.time.Duration.ofMinutes(10)); readyState.setText("Sunucunun hazır olması bekleniyor..."); if (source.equals("RECOVER")) automationRecoveryAttempts++; } else if (source.equals("STOP")) { waitingForReady = false; readyState.setText("Sunucu otomasyon tarafından durduruldu"); } if (!source.equals("ATLANDI")) DesktopNotifier.show(notificationSource(target), target.getName(), detail); refreshFleet(); if (active != null && active.getId().equals(target.getId())) refreshServer(); });
        task.setOnFailed(event -> { automationActionRunning = false; String detail = "Otomatik işlem başarısız: " + message(task.getException()); automationState.setText(detail); addAutomationEvent(target.getName(), "HATA", detail); DesktopNotifier.show(notificationSource(target), target.getName(), detail); }); run(task, "exaroton-automation-action");
    }
    private void addAutomationEvent(String server, String source, String detail) { automationLog.add(server == null || server.isBlank() ? "-" : server, source, detail); refreshAutomationLog(); }
    private void refreshAutomationLog() {
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"); List<ExarotonAutomationLog.Entry> entries = automationLog.recent(300); List<String> rows = new ArrayList<>();
        for (int index = entries.size() - 1; index >= 0; index--) { ExarotonAutomationLog.Entry entry = entries.get(index); rows.add(format.format(entry.time().atZone(ZoneId.systemDefault())) + "  •  " + entry.server() + "  •  " + entry.source() + "  •  " + entry.detail()); }
        automationEventRows.setAll(rows);
    }
    private void clearAutomationLog() { Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Exaroton otomasyon olay günlüğü temizlensin mi?", ButtonType.YES, ButtonType.NO); confirm.setHeaderText("Olay günlüğünü temizle"); if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) { automationLog.clear(); refreshAutomationLog(); } }
    private void requestStart() {
        Server selected = active;
        if (selected == null) { showError("Önce bir sunucu seç."); return; }
        if (!config.isExarotonReadinessCheckEnabled()) { startServer(selected, "Hazırlık denetimi ayarlardan kapalıyken başlatıldı"); return; }
        runReadiness(true);
    }
    private void runReadiness(boolean startAfter) {
        Server selected = active;
        if (selected == null) { showError("Önce bir sunucu seç."); return; }
        if (readinessRunning) { status.setText("Hazırlık denetimi zaten çalışıyor..."); return; }
        readinessRunning = true; status.setText("Exaroton hazırlık denetimi yapılıyor... (en fazla 12 sn)");
        Task<ExarotonReadinessEngine.Report> task = new Task<>() { protected ExarotonReadinessEngine.Report call() throws Exception { return ExarotonReadinessEngine.inspect(fetchProSnapshot(selected).get(12, TimeUnit.SECONDS), accountCredits); } };
        task.setOnSucceeded(event -> {
            readinessRunning = false;
            if (active == null || !active.getId().equals(selected.getId())) { status.setText("Sunucu seçimi değişti; denetim iptal edildi"); return; }
            refreshServer(); if (showReadiness(task.getValue(), startAfter)) startServer(selected, "Hazırlık denetiminden sonra başlatıldı");
        });
        task.setOnFailed(event -> {
            readinessRunning = false; status.setText("Hazırlık denetimi tamamlanamadı"); refreshServer();
            String detail = "Exaroton zamanında yanıt vermedi veya sunucu bilgileri alınamadı: " + message(task.getException());
            if (startAfter) offerReadinessBypass(selected, detail); else showError("Hazırlık denetimi başarısız: " + detail);
        });
        run(task, "exaroton-readiness");
    }
    private void offerReadinessBypass(Server selected, String detail) {
        Alert alert = new Alert(Alert.AlertType.WARNING); alert.setTitle("Exaroton Hazırlık Denetimi"); alert.setHeaderText("Denetim zaman aşımına uğradı"); alert.setContentText(detail + "\n\nİstersen denetimi atlayıp başlatma isteğini doğrudan Exaroton'a gönderebilirsin.");
        ButtonType bypass = new ButtonType("Denetimsiz Başlat", ButtonBar.ButtonData.OK_DONE), cancel = new ButtonType("İptal", ButtonBar.ButtonData.CANCEL_CLOSE); alert.getButtonTypes().setAll(bypass, cancel);
        if (alert.showAndWait().orElse(cancel) == bypass) startServer(selected, "Hazırlık denetimi zaman aşımından sonra kullanıcı onayıyla başlatıldı");
    }
    private void startServer(Server selected, String logDetail) {
        if (active == null || !active.getId().equals(selected.getId())) { showError("Sunucu seçimi değişti. Başlatmayı yeniden dene."); return; }
        action("Sunucu başlatılıyor...", "KULLANICI", logDetail, () -> { selected.start().get(30, TimeUnit.SECONDS); return null; });
    }
    private boolean showReadiness(ExarotonReadinessEngine.Report report, boolean startAfter) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("Exaroton Hazırlık Denetimi");
        dialog.setHeaderText(report.hasCritical() ? report.criticalCount() + " kritik sorun • " + report.warningCount() + " uyarı" : report.hasWarnings() ? report.warningCount() + " uyarı bulundu" : "Exaroton sunucusu hazır");
        ListView<ExarotonReadinessEngine.Check> list = new ListView<>(FXCollections.observableArrayList(report.checks())); list.setPrefSize(650, Math.min(390, 70 + report.checks().size() * 46));
        Label note = new Label(report.hasCritical() ? "Kritik sorun çözülmeden başlatma yapılmaz." : startAfter ? "Denetim tamamlandı. İstersen sunucuyu şimdi başlatabilirsin." : "Bağlantı ve sunucu bilgileri resmî Exaroton API'sinden alındı."); note.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(10, note, list)); ButtonType close = new ButtonType(startAfter ? "İptal" : "Kapat", ButtonBar.ButtonData.CANCEL_CLOSE); dialog.getDialogPane().getButtonTypes().add(close);
        ButtonType launch = null; if (startAfter && !report.hasCritical()) { launch = new ButtonType(report.hasWarnings() ? "Uyarılarla Başlat" : "Şimdi Başlat", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().add(0, launch); }
        Optional<ButtonType> selected = dialog.showAndWait(); return launch != null && selected.orElse(null) == launch;
    }
    private void restartSafely() {
        if (active == null) { showError("Önce bir sunucu seç."); return; }
        if (!players.isEmpty()) { Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, players.size() + " oyuncu çevrimiçi. Yeniden başlatmadan önce oyunculara duyuru gönderilsin mi?", ButtonType.YES, ButtonType.NO, ButtonType.CANCEL); confirm.setHeaderText("Oyuncular çevrimiçi"); ButtonType choice = confirm.showAndWait().orElse(ButtonType.CANCEL); if (choice == ButtonType.CANCEL) return; if (choice == ButtonType.YES) { action("Oyuncular bilgilendiriliyor...", "KULLANICI", "Duyuruyla yeniden başlatıldı", () -> { active.executeCommand("say [AeroMC] Sunucu kısa süre içinde yeniden başlatılıyor.").join(); active.restart().join(); return null; }); return; } }
        action("Sunucu yeniden başlatılıyor...", "KULLANICI", "Yeniden başlatma isteği gönderildi", () -> { active.restart().join(); return null; });
    }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }
    private String message(Throwable error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? cause.toString() : cause.getMessage(); }
    private void showError(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    public void setAutomaticCredentialVaultEnabled(boolean enabled) {
        if (!enabled) {
            try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.EXAROTON); }
            catch (IOException error) { throw new IllegalStateException("Exaroton otomatik kasası silinemedi: " + error.getMessage(), error); }
            updateAutomaticTokenPrompt();
            if (viewBuilt) status.setText(client == null ? "Otomatik kasa kapatıldı" : "Bağlı • otomatik kasa kapatıldı");
            return;
        }
        updateAutomaticTokenPrompt();
        if (viewBuilt) tryAutomaticConnect();
    }
    private void tryAutomaticConnect() {
        if (!config.isAutomaticCredentialVaultEnabled() || client != null || connecting || !DeviceCredentialStore.exists(DeviceCredentialStore.Kind.EXAROTON)) { updateAutomaticTokenPrompt(); return; }
        status.setText("Otomatik kasa açılıyor..."); connecting = true;
        Task<String> task = new Task<>() { protected String call() throws Exception { return DeviceCredentialStore.load(DeviceCredentialStore.Kind.EXAROTON); } };
        task.setOnSucceeded(event -> { connecting = false; if (!config.isAutomaticCredentialVaultEnabled()) { updateAutomaticTokenPrompt(); return; } String value = task.getValue(); if (value == null || value.isBlank()) { status.setText("Otomatik kasa boş"); return; } connectWithToken(value, null); });
        task.setOnFailed(event -> { connecting = false; status.setText("Otomatik kasa açılamadı"); updateAutomaticTokenPrompt(); showError("Otomatik Exaroton kasası açılamadı. API anahtarını yeniden girerek kasayı yenile."); });
        run(task, "exaroton-auto-vault");
    }
    private void updateAutomaticTokenPrompt() {
        if (config.isAutomaticCredentialVaultEnabled() && DeviceCredentialStore.exists(DeviceCredentialStore.Kind.EXAROTON)) token.setPromptText("Bu cihazın güvenli kasasından otomatik kullanılıyor");
        else if (config.isAutomaticCredentialVaultEnabled()) token.setPromptText("API anahtarını bir kez gir; başarılı bağlantıda otomatik saklanır");
        else token.setPromptText("Exaroton API anahtarı (dosyaya kaydedilmez)");
    }
    public void shutdown() { refresh.stop(); creditRefresh.stop(); fleetRefresh.stop(); automationRefresh.stop(); token.clear(); if (active != null) active.unsubscribe(); }
    public boolean hasActiveServer() { return active != null; }
    public void openAutomationTab() { if (exarotonTabs != null && exarotonTabs.getTabs().size() > 3) exarotonTabs.getSelectionModel().select(3); }
    /** Applies only non-destructive preferences. It never enables automation or stops a server. */
    public void applyServerProfile(String profileId) {
        if ("economy".equals(profileId)) { lowCreditEnabled.setSelected(true); lowCreditThreshold.getValueFactory().setValue(3.0); stopAtLowCredit.setSelected(false); }
        else if ("friends".equals(profileId)) { automationIdleStop.setSelected(true); automationIdleMinutes.getValueFactory().setValue(10); }
        else if ("performance".equals(profileId)) { crashRecovery.setSelected(true); recoveryAttempts.getValueFactory().setValue(2); }
        updateThresholdControls(); updateAutomationControls(); saveGuardPreferences(); saveAutomationPreferences(false); checkLowCredit();
    }
    public String getActiveServerName() { return activeServerName.get(); }
    private String notificationSource(Server server) { return NotificationCenter.serverSource("Exaroton", server == null ? "" : server.getName()); }
    public ReadOnlyStringProperty activeServerNameProperty() { return activeServerName.getReadOnlyProperty(); }
    public void addProConsoleListener(Consumer<String> listener) { proConsoleListeners.add(listener); }
    public void addProSnapshotListener(Consumer<ProSnapshot> listener) { proSnapshotListeners.add(listener); }
    public void addProMetricsListener(Consumer<ProMetrics> listener) { proMetricsListeners.add(listener); }
    public void removeProConsoleListener(Consumer<String> listener) { proConsoleListeners.remove(listener); }
    public void removeProSnapshotListener(Consumer<ProSnapshot> listener) { proSnapshotListeners.remove(listener); }
    public void removeProMetricsListener(Consumer<ProMetrics> listener) { proMetricsListeners.remove(listener); }
    public CompletableFuture<ProSnapshot> fetchProSnapshot() throws Exception { return fetchProSnapshot(requireActive()); }
    private CompletableFuture<ProSnapshot> fetchProSnapshot(Server selected) {
        final CompletableFuture<Server> serverFuture;
        try { serverFuture = selected.fetch(); }
        catch (Exception error) { return CompletableFuture.failedFuture(error); }
        return serverFuture.orTimeout(8, TimeUnit.SECONDS).thenCompose(server -> {
            try { return selected.getRAM().orTimeout(4, TimeUnit.SECONDS).handle((value, error) -> snapshot(server, error == null && value != null ? value.getRam() : -1)); }
            catch (Exception error) { return CompletableFuture.completedFuture(snapshot(server, -1)); }
        }).orTimeout(12, TimeUnit.SECONDS);
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
    @FunctionalInterface private interface CheckedOperation { void run() throws Exception; }
    private record ServerChoice(Server server) { @Override public String toString() { return server.getName() + " — " + server.getAddress(); } }
    private record FleetSnapshot(Server server, ExarotonFleetEngine.ServerState state, String address) { }
    private record AutomationObservation(Server server, String status, boolean online, boolean offline, boolean crashed, boolean transitional, int players) { }
    public record ProSnapshot(String name, String status, boolean online, int players, int maxPlayers, List<String> playerNames, int ramGiB, String address, String softwareName, String softwareVersion) { }
    public record ProMetrics(double memoryPercent, double tps, double averageTickTime) { }
}
