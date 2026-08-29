package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.CrashLoopGuard;
import com.aerogroup.mcpanel.aeroguard.SafePathGuard;

import com.exaroton.api.server.config.ConfigOption;
import javafx.animation.*;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.*;

/** Sağlık, Spark, otomasyon ve ayrılmış Pro araç bileşenlerini koordine eder. */
public final class ProToolsPane {
    private static final Path DATA = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final Path PERFORMANCE_FILE = DATA.resolve("performance-history.log");
    private static final Path CRISIS_HISTORY_FILE = DATA.resolve("crisis-history.properties");
    private static final Path DIAGNOSTIC_HISTORY_FILE = DATA.resolve("diagnostic-history.log");

    private final ServerManager manager;
    private final ExarotonPane exaroton;
    private final PterodactylPane pterodactyl;
    private final PanelConfig config;
    private final HostServices hostServices;
    private final ModCenterPane modCenterPane;
    private final WeeklyReportPane weeklyReportPane;
    private final EventTimelinePane eventTimelinePane = new EventTimelinePane();
    private final PlayerInsightsPane playerInsightsPane = new PlayerInsightsPane();
    private final ServerStoragePane storagePane;
    private final DiscordNotificationsPane discordPane;
    private final SparkProfilerPane sparkPane;
    private final ScheduledTasksPane scheduledTasksPane;
    private final InGameAeroMCBridge inGameBridge;
    private final ComboBox<String> provider = new ComboBox<>(FXCollections.observableArrayList("Yerel JAR", "Exaroton", "Pterodactyl"));
    private final Label providerState = new Label();
    private final ObservableList<String> findings = FXCollections.observableArrayList();
    private final ObservableList<String> crashReports = FXCollections.observableArrayList();
    private final ObservableList<String> healthReasons = FXCollections.observableArrayList();
    private final ObservableList<String> crisisHistoryRows = FXCollections.observableArrayList();
    private final Deque<String> consoleHistory = new ArrayDeque<>();
    private final IncidentContext incidentContext = new IncidentContext();
    private final SmartThresholdAdvisor thresholdAdvisor = new SmartThresholdAdvisor(PERFORMANCE_FILE);
    private final CrisisHistory crisisHistory = new CrisisHistory(CRISIS_HISTORY_FILE);
    private final DiagnosticHistory diagnosticHistory = new DiagnosticHistory(DIAGNOSTIC_HISTORY_FILE);
    private final CrashLoopGuard crashLoopGuard = new CrashLoopGuard();
    private final Label cpuValue = metric("0%"), memoryValue = metric("0 MB"), uptimeValue = metric("Kapalı"), playerValue = metric("0"), latencyValue = metric("-");
    private final Label healthValue = metric("0 / 100"), healthState = new Label("Sunucu kapalı"), crisisState = new Label("Kriz Modu beklemede");
    private final Label thresholdAdvice = new Label("Öneri için sunucuyu bir süre normal kullan");
    private final ProgressBar healthProgress = new ProgressBar(0);
    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>(), memorySeries = new XYChart.Series<>();
    private final Timeline metrics = new Timeline(new KeyFrame(Duration.seconds(3), event -> samplePerformance()));
    private final CheckBox restartOnCrash = new CheckBox("Çökünce otomatik yeniden başlat");
    private final CheckBox notifyHighMemory = new CheckBox("Yüksek RAM kullanımında bildir");
    private final Spinner<Integer> memoryLimit = new Spinner<>(256, 65536, 4096, 256);
    private final Label automationScope = new Label("Yerel JAR görevleri"), maintenanceNote = new Label();
    private final Button openExarotonAutomationButton = button("Exaroton Otomasyon Merkezini Aç", "primary");
    private final CheckBox automaticCrisis = new CheckBox("Kriz Modunu otomatik tetikle");
    private final Spinner<Double> crisisTps;
    private final Spinner<Integer> crisisRam;
    private final Spinner<Integer> crisisTriggerSeconds;
    private final Spinner<Integer> crisisRecoverySeconds;
    private final Spinner<Integer> crisisCooldownSeconds;
    private long lastPid = -1, lastCpuNanos = -1, lastSampleNanos = -1, sampleIndex;
    private int currentMemoryMb = -1;
    private boolean memoryWarned, autoRestarting;
    private boolean serverOnline, crisisActive, crisisManual, crisisTransitioning, crisisNeedsMetricsForRecovery, latencyProbeRunning;
    private String crisisProvider;
    private int recentCrashes, overloadWarnings, overloadBurst;
    private Instant crisisDangerSince, crisisRecoverySince, crisisLastExit = Instant.EPOCH, lastOverloadWarningAt;
    private double currentTps = Double.NaN, currentRamPercent = Double.NaN, currentCpuPercent = Double.NaN, currentLatencyMs = Double.NaN;
    private final Map<String, Object> crisisOriginalRemote = new LinkedHashMap<>();
    private final Map<String, String> crisisOriginalLocal = new LinkedHashMap<>();
    private ExarotonPane.ProSnapshot remoteSnapshot;
    private PterodactylPane.ProSnapshot pterodactylSnapshot;
    private String lastRemoteStatus;
    private Boolean lastRemoteOnline;
    private boolean remoteMetricsReceived;
    private String activeCrisisHistoryId;
    private volatile int localOnlinePlayers;
    private final AtomicReference<CompletableFuture<Integer>> inGamePlayerRefresh = new AtomicReference<>();
    private VBox localAutomationCard;
    private final Runnable openExarotonAutomation;

    public ProToolsPane(ServerManager manager, ExarotonPane exaroton, PterodactylPane pterodactyl, PanelConfig config, HostServices hostServices, Runnable openExarotonAutomation) {
        this.manager = manager; this.exaroton = exaroton; this.pterodactyl = pterodactyl; this.config = config; this.hostServices = hostServices; this.openExarotonAutomation = openExarotonAutomation;
        this.modCenterPane = new ModCenterPane(manager, exaroton, config, hostServices);
        this.weeklyReportPane = new WeeklyReportPane(this::weeklyReportSnapshot);
        this.storagePane = new ServerStoragePane(manager, exaroton, pterodactyl, hostServices, provider::getValue, this::worldBackupCompleted);
        this.discordPane = new DiscordNotificationsPane(config, provider::getValue, this::discordServerName);
        this.sparkPane = new SparkProfilerPane(manager, exaroton, pterodactyl, hostServices, provider::getValue, () -> serverOnline,
                () -> currentTps, this::notificationSource, this::recordEvent,
                message -> findings.add(0, now() + "  LAG AVCISI: " + message));
        this.scheduledTasksPane = new ScheduledTasksPane(this::isRemote, new ScheduledTasksPane.Actions() {
            @Override public void backup() { storagePane.backupWorlds(); }
            @Override public void restart() { restartConfigured(); }
            @Override public void stop() { stopConfigured(); }
            @Override public void announce(String message) { announceConfigured(message); }
        }, this::recordEvent,
                (action, message) -> sendDiscord(DiscordNotificationEngine.Type.AUTOMATION, "Zamanlanmış görev tetiklendi", action + (message.isBlank() ? "" : " • " + message), false),
                message -> findings.add(0, now() + "  ZAMANLAYICI: " + message));
        this.inGameBridge = new InGameAeroMCBridge(config, this::loadInGameOps, this::inGameSnapshot, this::sendInGameCommand,
                message -> Platform.runLater(() -> { findings.add(0, now() + "  OYUN İÇİ KOMUT: " + message); while (findings.size() > 100) findings.remove(findings.size() - 1); }));
        crisisTps = new Spinner<>(10.0, 19.5, Math.max(10.0, Math.min(19.5, config.getCrisisTpsThreshold())), 0.5);
        crisisRam = new Spinner<>(70, 99, Math.max(70, Math.min(99, (int) config.getCrisisRamThreshold())), 1);
        crisisTriggerSeconds = new Spinner<>(4, 60, config.getCrisisTriggerSeconds(), 2);
        crisisRecoverySeconds = new Spinner<>(10, 180, config.getCrisisRecoverySeconds(), 5);
        crisisCooldownSeconds = new Spinner<>(0, 600, config.getCrisisCooldownSeconds(), 15);
        diagnosticHistory.importTimeline(EventTimelinePane.FILE); crisisHistory.closeInterrupted(Instant.now()); refreshCrisisHistory(); LanguageManager.englishProperty().addListener((obs, old, value) -> { refreshCrisisHistory(); sparkPane.languageChanged(); weeklyReportPane.refresh(); }); metrics.setCycleCount(Animation.INDEFINITE);
        exaroton.addProConsoleListener(this::onRemoteConsole);
        exaroton.addProSnapshotListener(snapshot -> onFx(() -> acceptRemoteSnapshot(snapshot)));
        exaroton.addProMetricsListener(metrics -> onFx(() -> acceptRemoteMetrics(metrics)));
        exaroton.activeServerNameProperty().addListener((observable, oldName, newName) -> {
            remoteMetricsReceived = false;
            if (!"Exaroton sunucusu seçilmedi".equals(newName)) provider.getSelectionModel().select("Exaroton");
            updateProviderState();
        });
        pterodactyl.addConsoleListener(this::onPterodactylConsole);
        pterodactyl.addSnapshotListener(snapshot -> onFx(() -> acceptPterodactylSnapshot(snapshot)));
        pterodactyl.addMetricsListener(value -> onFx(() -> acceptPterodactylMetrics(value)));
        pterodactyl.activeServerNameProperty().addListener((observable, oldName, newName) -> {
            remoteMetricsReceived = false;
            if (!"Pterodactyl sunucusu seçilmedi".equals(newName)) provider.getSelectionModel().select("Pterodactyl");
            updateProviderState();
        });
    }

    public Node buildView() {
        Node health = scrollable(insightsView()), weekly = scrollable(weeklyReportPane.buildView()), modCenter = scrollable(modCenterPane.buildView()), files = scrollable(storagePane.buildFilesView()), content = scrollable(storagePane.buildContentView()), worlds = scrollable(storagePane.buildWorldsView()), automation = scrollable(automationView()), players = scrollable(playerInsightsPane.buildView()), timeline = scrollable(eventTimelinePane.buildView());
        StackPane center = new StackPane(health);
        ToggleGroup navigation = new ToggleGroup();
        VBox menu = new VBox(7);
        menu.getStyleClass().add("control-menu");
        for (Object[] item : new Object[][]{{"Sunucu Sağlığı", health}, {"Haftalık Rapor", weekly}, {"Olay Zaman Çizelgesi", timeline}, {"Tek Tık Mod Merkezi", modCenter}, {"Dosyalar", files}, {"Eklenti & Modlar", content}, {"Dünyalar", worlds}, {"Görevler & Bildirimler", automation}, {"Başarı Kartları", players}}) {
            ToggleButton button = new ToggleButton((String) item[0]); button.setToggleGroup(navigation); button.setMaxWidth(Double.MAX_VALUE); button.setWrapText(true); button.setTooltip(new Tooltip((String) item[0])); button.getStyleClass().add("control-menu-button");
            Node view = (Node) item[1]; button.setOnAction(event -> { center.getChildren().setAll(view); if (view == weekly) weeklyReportPane.refresh(); if (view == files) storagePane.refreshFile(); if (view == content) storagePane.refreshContent(); if (view == worlds) storagePane.refreshWorlds(); }); menu.getChildren().add(button);
        }
        ((ToggleButton) menu.getChildren().get(0)).setSelected(true);
        BorderPane workspace = new BorderPane(); workspace.setLeft(menu); workspace.setCenter(center); workspace.getStyleClass().add("control-workspace");
        provider.getSelectionModel().selectFirst(); provider.setOnAction(event -> { sparkPane.providerChanged(); resetProcessSample(); recentCrashes = overloadWarnings = overloadBurst = 0; lastOverloadWarningAt = null; lastRemoteOnline = null; synchronized (consoleHistory) { consoleHistory.clear(); } incidentContext.clear(); updateProviderState(); updateAutomationProviderUi(); scheduledTasksPane.updateProvider(); weeklyReportPane.refresh(); remoteMetricsReceived = false; cpuSeries.getData().clear(); memorySeries.getData().clear(); storagePane.refreshAll(); });
        providerState.getStyleClass().add("muted"); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label title = new Label("AKTİF SUNUCU"); title.getStyleClass().add("section-title");
        HBox providerBar = new HBox(9, title, provider, providerState, spacer, new Label("Kontrol Merkezi")); providerBar.setAlignment(Pos.CENTER_LEFT); providerBar.getStyleClass().add("pro-provider-bar"); updateProviderState(); updateAutomationProviderUi();
        VBox shell = new VBox(providerBar, workspace); VBox.setVgrow(workspace, Priority.ALWAYS);
        metrics.play(); return shell;
    }

    private Node insightsView() {
        healthProgress.setMaxWidth(Double.MAX_VALUE); healthProgress.getStyleClass().add("health-progress"); healthState.getStyleClass().add("health-state");
        ListView<String> reasons = new ListView<>(healthReasons); reasons.setPrefHeight(86); reasons.setMinHeight(86); reasons.setPlaceholder(new Label("Canlı ölçüm bekleniyor"));
        VBox score = card("SUNUCU SAĞLIK PUANI", healthValue, healthState, healthProgress, reasons); score.getStyleClass().add("health-card");

        automaticCrisis.setSelected(config.isCrisisModeEnabled()); crisisTps.setEditable(true); crisisRam.setEditable(true); crisisTriggerSeconds.setEditable(true); crisisRecoverySeconds.setEditable(true); crisisCooldownSeconds.setEditable(true); crisisState.getStyleClass().add("crisis-state");
        automaticCrisis.setOnAction(event -> saveCrisisPreferences()); crisisTps.valueProperty().addListener((obs, old, value) -> saveCrisisPreferences()); crisisRam.valueProperty().addListener((obs, old, value) -> saveCrisisPreferences()); crisisTriggerSeconds.valueProperty().addListener((obs, old, value) -> saveCrisisPreferences()); crisisRecoverySeconds.valueProperty().addListener((obs, old, value) -> saveCrisisPreferences()); crisisCooldownSeconds.valueProperty().addListener((obs, old, value) -> saveCrisisPreferences());
        Button startCrisis = button("Şimdi Etkinleştir", "danger"), stopCrisis = button("Krizden Çık", "secondary"), smartThreshold = button("Akıllı Eşik Öner", "primary");
        startCrisis.setOnAction(event -> enterCrisis("Yönetici tarafından etkinleştirildi", true)); stopCrisis.setOnAction(event -> exitCrisis(true)); smartThreshold.setOnAction(event -> suggestSmartThresholds()); thresholdAdvice.setWrapText(true); thresholdAdvice.getStyleClass().add("muted");
        FlowPane thresholds = new FlowPane(8, 8, new Label("TPS altı"), crisisTps, new Label("RAM %"), crisisRam, new Label("Tetikleme (sn)"), crisisTriggerSeconds);
        FlowPane recovery = new FlowPane(8, 8, new Label("Toparlanma (sn)"), crisisRecoverySeconds, new Label("Yeniden tetikleme beklemesi (sn)"), crisisCooldownSeconds);
        Label crisisNote = new Label("Kriz, eşiklerin belirlenen süre boyunca bozuk kalmasıyla açılır. Çıkış için TPS +1 ve RAM -%5 güvenli payla toparlanmalıdır. Manuel kriz yalnızca yönetici tarafından kapatılır; ayarlar geri yüklenmeden otomatik görevler başlamaz."); crisisNote.setWrapText(true); crisisNote.setMinHeight(42); crisisNote.getStyleClass().add("muted");
        VBox crisis = card("KRİZ MODU", automaticCrisis, thresholds, recovery, new FlowPane(8, 8, startCrisis, stopCrisis, smartThreshold), thresholdAdvice, crisisState, crisisNote); crisis.setMinHeight(315); crisis.getStyleClass().add("crisis-card"); score.setMinHeight(315);
        HBox healthRow = new HBox(14, score, crisis); HBox.setHgrow(score, Priority.ALWAYS); HBox.setHgrow(crisis, Priority.ALWAYS); score.setMaxWidth(Double.MAX_VALUE); crisis.setMaxWidth(Double.MAX_VALUE);

        ListView<String> crisisHistoryList = new ListView<>(crisisHistoryRows); crisisHistoryList.setPrefHeight(170); crisisHistoryList.setMinHeight(130); crisisHistoryList.setPlaceholder(new Label("Henüz Kriz Modu tetiklenmedi"));
        crisisHistoryList.setCellFactory(view -> new ListCell<>() { private final Label text = new Label(); { text.setWrapText(true); text.maxWidthProperty().bind(view.widthProperty().subtract(34)); } protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(null); if (empty || item == null) setGraphic(null); else { text.setText(item); setGraphic(text); } } });
        Label crisisHistoryNote = new Label("Son 10 tetiklenme; başlangıç zamanı, kullanılan TPS/RAM eşiği ve Kriz Modu süresiyle birlikte saklanır."); crisisHistoryNote.setWrapText(true); crisisHistoryNote.getStyleClass().add("muted");
        VBox crisisHistoryCard = card("KRİZ MODU GEÇMİŞİ", crisisHistoryNote, crisisHistoryList);

        HBox cards = new HBox(12, metricCard("CPU / TPS", cpuValue), metricCard("SUNUCU RAM", memoryValue), metricCard("GECİKME", latencyValue), metricCard("ÇALIŞMA / DURUM", uptimeValue), metricCard("OYUNCULAR", playerValue));
        for (Node node : cards.getChildren()) HBox.setHgrow(node, Priority.ALWAYS);
        NumberAxis x = new NumberAxis(); NumberAxis y = new NumberAxis(); x.setForceZeroInRange(false); x.setTickLabelsVisible(false); x.setTickMarkVisible(false); y.setLabel("Canlı değer");
        LineChart<Number, Number> chart = new LineChart<>(x, y); chart.setAnimated(false); chart.setCreateSymbols(false); chart.setLegendVisible(true); cpuSeries.setName("CPU % / TPS"); memorySeries.setName("RAM MB / %"); chart.getData().addAll(cpuSeries, memorySeries);
        ListView<String> list = new ListView<>(findings); list.setPlaceholder(new Label("Henüz önemli bir hata yakalanmadı")); VBox.setVgrow(list, Priority.ALWAYS);
        ListView<String> doctor = new ListView<>(crashReports); doctor.setPlaceholder(new Label("Henüz çökme analizi yok")); VBox.setVgrow(doctor, Priority.ALWAYS);
        Button clear = button("Analizi Temizle", "secondary"); clear.setOnAction(event -> { findings.clear(); crashReports.clear(); });
        Tab warnings = tab("Canlı Uyarılar", list), crashes = tab("Çökme Doktoru", doctor); TabPane analysisTabs = new TabPane(warnings, crashes); analysisTabs.getStyleClass().add("diagnostic-tabs"); VBox.setVgrow(analysisTabs, Priority.ALWAYS);
        VBox analyzer = card("AKILLI ÇÖKME DOKTORU", new Label("Konsoldaki belirtileri, olası şüpheliyi ve çözüm sırasını açıklar."), analysisTabs, clear); VBox.setVgrow(analyzer, Priority.ALWAYS);
        VBox chartCard = card("CANLI PERFORMANS", chart); chart.setMinHeight(320); chart.setPrefHeight(430); chart.setMaxHeight(Double.MAX_VALUE); VBox.setVgrow(chart, Priority.ALWAYS); VBox.setVgrow(chartCard, Priority.ALWAYS); HBox body = new HBox(14, chartCard, analyzer); HBox.setHgrow(chartCard, Priority.ALWAYS); HBox.setHgrow(analyzer, Priority.ALWAYS);
        VBox profiler = sparkPane.buildView();
        VBox page = page(healthRow, crisisHistoryCard, cards, profiler, body); VBox.setVgrow(healthRow, Priority.NEVER); VBox.setVgrow(crisisHistoryCard, Priority.NEVER); VBox.setVgrow(cards, Priority.NEVER); VBox.setVgrow(profiler, Priority.NEVER); VBox.setVgrow(body, Priority.ALWAYS); return page;
    }

    private WeeklyReportPane.Snapshot weeklyReportSnapshot() {
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(7));
        return new WeeklyReportPane.Snapshot(isRemote(), config.getMemoryMb(), thresholdAdvisor.samplesSince(cutoff), new FleetHealthHistory().since(cutoff), crisisHistory.entries(), diagnosticHistory.since(cutoff), playerInsightsPane.weeklyInputs());
    }

    private Node automationView() {
        VBox jobsCard = scheduledTasksPane.buildView();

        automationScope.getStyleClass().add("metric"); automationScope.setWrapText(true); openExarotonAutomationButton.setOnAction(event -> openExarotonAutomation.run());
        VBox scope = card("OTOMASYON KAPSAMI", automationScope, openExarotonAutomationButton);
        memoryLimit.setEditable(true); restartOnCrash.setText("Yerel sunucu çökerse otomatik yeniden başlat"); FlowPane rules = new FlowPane(14, 9, restartOnCrash, notifyHighMemory, new Label("RAM sınırı (MB)"), memoryLimit); rules.setAlignment(Pos.CENTER_LEFT);
        Label localNote = new Label("Bu korumalar yalnızca bilgisayarındaki Yerel JAR sunucusuna uygulanır; Exaroton kurallarıyla çakışmaz."); localNote.setWrapText(true); localNote.getStyleClass().add("muted");
        localAutomationCard = card("YEREL SUNUCU KORUMASI", rules, localNote);
        Node notifications = discordPane.buildView();
        Button maintenance = button("BAKIM MODUNU BAŞLAT", "danger"); maintenance.setOnAction(event -> maintenanceMode());
        maintenanceNote.setWrapText(true); maintenanceNote.getStyleClass().add("muted"); VBox maintenanceCard = card("BAKIM MODU", maintenance, maintenanceNote);
        VBox page = new VBox(14, scope, jobsCard, localAutomationCard, notifications, maintenanceCard); page.setPadding(new Insets(18)); updateAutomationProviderUi(); return page;
    }

    private void updateAutomationProviderUi() {
        if (localAutomationCard == null) return; boolean remote = isRemote();
        automationScope.setText(isExaroton() ? "Exaroton seçili • Sürekli kurallar Sunucular → Exaroton → Otomasyon Merkezi'nde tek yerden yönetilir." : isPterodactyl() ? "Pterodactyl seçili • Zamanlanmış görevler, güç işlemleri, komutlar ve bildirimler Client API üzerinden yürütülür." : "Yerel JAR seçili • Yerel görevler ve korumalar bu sayfadan yönetilir.");
        openExarotonAutomationButton.setManaged(isExaroton()); openExarotonAutomationButton.setVisible(isExaroton()); localAutomationCard.setManaged(!remote); localAutomationCard.setVisible(!remote);
        maintenanceNote.setText(remote ? provider.getValue() + " sunucusunda whitelist açılır, oyuncular bilgilendirilir ve sunucu API üzerinden güvenle kapatılır. Uzak ZIP yedeği bu işlemde oluşturulmaz." : "Yerel sunucuda whitelist açılır, oyuncular bilgilendirilir, dünya kaydedilir, yedek alınır ve sunucu güvenli şekilde kapatılır.");
    }

    public void onConsole(String line) {
        inGameBridge.accept(InGameAeroMCBridge.Provider.LOCAL, line);
        if (isRemote()) return;
        analyzeConsole(line);
    }
    private void onRemoteConsole(String line) {
        inGameBridge.accept(InGameAeroMCBridge.Provider.EXAROTON, line);
        if (!isExaroton()) return;
        analyzeConsole(line);
    }
    private void onPterodactylConsole(String line) { inGameBridge.accept(InGameAeroMCBridge.Provider.PTERODACTYL, line); if (isPterodactyl()) analyzeConsole(line); }
    private void analyzeConsole(String line) {
        synchronized (consoleHistory) { consoleHistory.addLast(line); while (consoleHistory.size() > 300) consoleHistory.removeFirst(); }
        incidentContext.recordConsole(line);
        sparkPane.acceptConsole(line);
        playerInsightsPane.acceptConsole(line);
        String lower = line.toLowerCase(Locale.ROOT), explanation = null;
        if (lower.contains("outofmemoryerror") || lower.contains("java heap space")) explanation = "RAM YETERSİZ: Sunucu belleği tükendi. RAM sınırını yükselt veya ağır mod/eklenti sayısını azalt.";
        else if (lower.contains("failed to bind to port") || lower.contains("address already in use")) explanation = "PORT ÇAKIŞMASI: Aynı portu başka bir uygulama kullanıyor. Diğer sunucuyu kapat veya server-port değerini değiştir.";
        else if (lower.contains("could not load") && lower.contains("plugin")) explanation = "EKLENTİ YÜKLENEMEDİ: Eklenti sürümünü ve gerekli bağımlılıklarını kontrol et.";
        else if (lower.contains("modresolutionexception") || lower.contains("requires") && lower.contains("fabric")) explanation = "MOD BAĞIMLILIĞI: Bir mod eksik ya da uyumsuz bağımlılık istiyor. Hata satırındaki mod sürümlerini eşleştir.";
        else if (lower.contains("can't keep up") || lower.contains("server is overloaded") || lower.contains("a single server tick took")) { explanation = "PERFORMANS UYARISI: Sunucu tick'leri geride kalıyor. Görüş mesafesini azalt, RAM/CPU kullanımını ve ağır eklentileri kontrol et."; Platform.runLater(() -> { overloadWarnings++; overloadBurst++; lastOverloadWarningAt = Instant.now(); updateHealthAndCrisis(); }); }
        else if (lower.contains("exception") && !lower.contains("connection")) explanation = "JAVA HATASI: Ayrıntı için bu satırın hemen altındaki 'Caused by' bölümünü kontrol et: " + trim(line, 170);
        else if (lower.contains("crash report")) explanation = "ÇÖKME RAPORU: Sunucu bir crash-report oluşturdu. En son eklenen mod/eklenti ilk şüphelidir.";
        if (explanation != null) { String alertText = explanation, source = notificationSource(), value = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + alertText; diagnosticHistory.record(Instant.now(), source, alertText); Platform.runLater(() -> { if (findings.isEmpty() || !findings.get(0).endsWith(value.substring(10))) { findings.add(0, value); recordEvent("Uyarı", alertText); } while (findings.size() > 100) findings.remove(findings.size() - 1); }); }
    }

    public void onState(boolean running, String text) {
        if (isRemote()) return;
        serverOnline = running;
        if (!running) sparkPane.serverStopped("Sunucu kapandı • Lag analizi beklemesi durduruldu");
        if (running) autoRestarting = false;
        if (!running) playerInsightsPane.disconnectAll();
        if (!running && text.toLowerCase(Locale.ROOT).contains("çöktü")) {
            recentCrashes++; createIncident(text, true); updateHealthAndCrisis();
            sendDiscord(DiscordNotificationEngine.Type.CRASH, "Yerel sunucu çöktü", text, true);
            CrashLoopGuard.Decision decision = crashLoopGuard.record(Instant.now());
            if (!decision.restartAllowed()) {
                findings.add(0, now() + "  GÜVENLİK: " + decision.message()); recordEvent("Çökme Döngüsü Kalkanı", decision.message()); DesktopNotifier.show(notificationSource(), "Çökme döngüsü engellendi", decision.message()); sendDiscord(DiscordNotificationEngine.Type.CRASH, "Çökme döngüsü engellendi", decision.message(), true);
            } else if (restartOnCrash.isSelected() && !autoRestarting) { autoRestarting = true; Platform.runLater(() -> findings.add(0, now() + "  OTOMASYON: Çökme sonrası yeniden başlatma tetiklendi.")); restartConfigured(); }
        } else if (running && text.toLowerCase(Locale.ROOT).contains("online")) { recordEvent("Sunucu", text); sendDiscord(DiscordNotificationEngine.Type.STATUS, "Sunucu online", "Yerel Minecraft sunucusu oyuncu kabul etmeye hazır.", false); updateHealthAndCrisis(); }
        else if (!running) { createIncident(text, false); sendDiscord(DiscordNotificationEngine.Type.STATUS, "Sunucu kapandı", text, false); }
        else recordEvent("Sunucu", text);
    }

    public void onPlayers(List<String> names) {
        localOnlinePlayers = names == null ? 0 : names.size();
        CompletableFuture<Integer> waiting = inGamePlayerRefresh.getAndSet(null);
        if (waiting != null) waiting.complete(localOnlinePlayers);
        if (!isRemote()) acceptPlayers(names);
    }

    private CompletableFuture<String> loadInGameOps(InGameAeroMCBridge.Provider source) {
        try {
            return switch (source) {
                case LOCAL -> CompletableFuture.supplyAsync(() -> {
                    Path jar = config.getServerJar(); if (jar == null || jar.getParent() == null) return "[]";
                    try {
                        Path root = jar.getParent().toAbsolutePath().normalize(); Path file = SafePathGuard.resolve(root, "ops.json", false);
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 131_072) return "[]";
                        return Files.readString(file, StandardCharsets.UTF_8);
                    } catch (IOException error) { return "[]"; }
                });
                case EXAROTON -> exaroton.readRemoteFile("/ops.json");
                case PTERODACTYL -> pterodactyl.readRemoteFile("/ops.json");
            };
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    private CompletableFuture<InGameAeroMCBridge.Snapshot> inGameSnapshot(InGameAeroMCBridge.Provider source) {
        try {
            double tps = inGameMetricAvailable(source) ? currentTps : Double.NaN;
            double ramPercent = inGameMetricAvailable(source) ? currentRamPercent : Double.NaN;
            return switch (source) {
                case LOCAL -> refreshLocalInGamePlayers().thenApply(ignored -> freshLocalInGameSnapshot());
                case EXAROTON -> exaroton.fetchProSnapshot().thenApply(value -> new InGameAeroMCBridge.Snapshot(value.online(), value.players(), value.maxPlayers(), tps, ramPercent, currentLatencyMs, crisisActive, Double.NaN, -1, Math.max(0, value.ramGiB()) * 1024L));
                case PTERODACTYL -> pterodactyl.fetchProSnapshot().thenApply(value -> new InGameAeroMCBridge.Snapshot(value.online(), value.players(), value.maxPlayers(), tps, ramPercent, currentLatencyMs, crisisActive, currentCpuPercent, currentMemoryMb, value.memoryLimitMb()));
            };
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    /** Konsolun periyodik yenilemesini beklemeden, oyun içi komut için güncel /list yanıtını ister. */
    private CompletableFuture<Integer> refreshLocalInGamePlayers() {
        if (!manager.isRunning()) return CompletableFuture.completedFuture(0);
        CompletableFuture<Integer> existing = inGamePlayerRefresh.get();
        if (existing != null && !existing.isDone()) return existing;
        CompletableFuture<Integer> request = new CompletableFuture<>();
        if (!inGamePlayerRefresh.compareAndSet(existing, request)) return refreshLocalInGamePlayers();
        manager.requestPlayers();
        request.completeOnTimeout(localOnlinePlayers, 1200, TimeUnit.MILLISECONDS)
                .whenComplete((value, error) -> inGamePlayerRefresh.compareAndSet(request, null));
        return request;
    }

    /** Komut anında ölçüm alır; Kontrol Merkezi açık olmasa bile Yerel JAR verisi güncel kalır. */
    private InGameAeroMCBridge.Snapshot freshLocalInGameSnapshot() {
        long pid = manager.getProcessId();
        if (pid <= 0) return new InGameAeroMCBridge.Snapshot(false, localOnlinePlayers, 0, Double.NaN, Double.NaN, Double.NaN, crisisActive, Double.NaN, -1, config.getMemoryMb());
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) return new InGameAeroMCBridge.Snapshot(false, localOnlinePlayers, 0, Double.NaN, Double.NaN, currentLatencyMs, crisisActive, Double.NaN, -1, config.getMemoryMb());
        long startedAt = System.nanoTime();
        long cpuBefore = handle.get().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();
        try { Thread.sleep(300); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        long elapsed = Math.max(1, System.nanoTime() - startedAt);
        long cpuAfter = handle.get().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();
        double cpu = Math.max(0, Math.min(999, (cpuAfter - cpuBefore) * 100.0 / elapsed));
        int memory = readMemoryMb(pid);
        double ramPercent = memory < 0 ? Double.NaN : memory * 100.0 / Math.max(1, config.getMemoryMb());
        return new InGameAeroMCBridge.Snapshot(manager.isRunning(), localOnlinePlayers, 0, Double.NaN, ramPercent, currentLatencyMs, crisisActive, cpu, memory, config.getMemoryMb());
    }

    private boolean inGameMetricAvailable(InGameAeroMCBridge.Provider source) {
        return switch (source) {
            case LOCAL -> !isRemote();
            case EXAROTON -> isExaroton();
            case PTERODACTYL -> isPterodactyl();
        };
    }

    private void sendInGameCommand(InGameAeroMCBridge.Provider source, String command) {
        try {
            switch (source) {
                case LOCAL -> { if (manager.isRunning()) manager.command(command); }
                case EXAROTON -> exaroton.executeAdminCommand(command).exceptionally(error -> { logInGameError(error); return null; });
                case PTERODACTYL -> pterodactyl.executeAdminCommand(command).exceptionally(error -> { logInGameError(error); return null; });
            }
        } catch (Exception error) { logInGameError(error); }
    }
    private void logInGameError(Throwable error) { Platform.runLater(() -> findings.add(0, now() + "  OYUN İÇİ KOMUT: Yanıt gönderilemedi • " + rootMessage(error))); }
    private void acceptPlayers(List<String> names) {
        PlayerInsightsPane.Changes changes = playerInsightsPane.acceptPlayers(names);
        for (String name : changes.joined()) { recordEvent("Oyuncu", name + " katıldı"); sendDiscord(DiscordNotificationEngine.Type.PLAYER, "Oyuncu katıldı", name + " sunucuya katıldı.", false); }
        for (String name : changes.left()) recordEvent("Oyuncu", name + " ayrıldı");
        playerValue.setText(Integer.toString(names.size()));
    }

    private boolean isRemote() { return !"Yerel JAR".equals(provider.getValue()); }
    private boolean isExaroton() { return "Exaroton".equals(provider.getValue()); }
    private boolean isPterodactyl() { return "Pterodactyl".equals(provider.getValue()); }
    private void updateProviderState() {
        if (provider.getValue() == null) return;
        serverOnline = isExaroton() ? remoteSnapshot != null && remoteSnapshot.online() : isPterodactyl() ? pterodactylSnapshot != null && pterodactylSnapshot.online() : manager.isRunning();
        providerState.setText(isExaroton() ? exaroton.getActiveServerName() : isPterodactyl() ? pterodactyl.getActiveServerName() : (serverOnline ? "Yerel sunucu online" : "Yerel sunucu kapalı")); updateHealthAndCrisis();
    }
    private void acceptRemoteSnapshot(ExarotonPane.ProSnapshot snapshot) {
        remoteSnapshot = snapshot; if (!isExaroton()) return;
        serverOnline = snapshot.online();
        if (!snapshot.online()) sparkPane.serverStopped("Exaroton sunucusu kapandı • Lag analizi beklemesi durduruldu");
        providerState.setText(snapshot.name() + " • " + snapshot.status()); playerValue.setText(snapshot.players() + " / " + snapshot.maxPlayers());
        if (!snapshot.online()) { cpuValue.setText("-"); memoryValue.setText(snapshot.ramGiB() < 0 ? "-" : snapshot.ramGiB() + " GiB ayrılmış"); remoteMetricsReceived = false; }
        else if (!remoteMetricsReceived) { cpuValue.setText("Canlı veri bekleniyor"); memoryValue.setText(snapshot.ramGiB() < 0 ? "Canlı veri bekleniyor" : snapshot.ramGiB() + " GiB ayrılmış"); }
        uptimeValue.setText(snapshot.status());
        acceptPlayers(snapshot.playerNames());
        String lower = snapshot.status().toLowerCase(Locale.ROOT);
        if (lastRemoteStatus != null && !lastRemoteStatus.equals(snapshot.status()) && snapshot.online()) sendDiscord(DiscordNotificationEngine.Type.STATUS, "Exaroton sunucusu online", snapshot.name() + " oyuncu kabul etmeye hazır.", false);
        else if (lastRemoteStatus != null && !lastRemoteStatus.equals(snapshot.status()) && !snapshot.online() && !snapshot.status().toLowerCase(Locale.ROOT).contains("crash")) sendDiscord(DiscordNotificationEngine.Type.STATUS, "Exaroton durum değişikliği", snapshot.name() + " • " + snapshot.status(), false);
        boolean crashedNow = lower.contains("crash") && !lower.equals(String.valueOf(lastRemoteStatus).toLowerCase(Locale.ROOT));
        if (crashedNow) {
            recentCrashes++; createIncident(snapshot.name() + " çökmüş duruma geçti", true);
            sendDiscord(DiscordNotificationEngine.Type.CRASH, "Exaroton sunucusu çöktü", snapshot.name() + " çökmüş duruma geçti.", true);
        } else if (Boolean.TRUE.equals(lastRemoteOnline) && !snapshot.online()) createIncident(snapshot.name() + " • " + snapshot.status(), false);
        if (snapshot.online()) { autoRestarting = false; if (sampleIndex % 5 == 0) probeLatency(snapshot.address()); } lastRemoteStatus = snapshot.status(); lastRemoteOnline = snapshot.online(); updateHealthAndCrisis();
    }
    private void acceptRemoteMetrics(ExarotonPane.ProMetrics metrics) {
        if (!isExaroton()) return;
        boolean received = false;
        if (Double.isFinite(metrics.tps())) { currentTps = metrics.tps(); cpuValue.setText(String.format(Locale.US, "TPS %.1f • %.1f ms", metrics.tps(), metrics.averageTickTime())); addPoint(cpuSeries, sampleIndex, metrics.tps()); received = true; }
        if (Double.isFinite(metrics.memoryPercent())) { double percent = metrics.memoryPercent() <= 1.0 ? metrics.memoryPercent() * 100.0 : metrics.memoryPercent(); currentRamPercent = percent; memoryValue.setText(String.format(Locale.US, "%.1f%% kullanım", percent)); addPoint(memorySeries, sampleIndex, percent); received = true; }
        if (received) { remoteMetricsReceived = true; sampleIndex++; recordPerformanceSample(); updateHealthAndCrisis(); }
    }

    private void acceptPterodactylSnapshot(PterodactylPane.ProSnapshot snapshot) {
        pterodactylSnapshot = snapshot; if (!isPterodactyl()) return; serverOnline = snapshot.online();
        providerState.setText(snapshot.name() + " • " + snapshot.status()); playerValue.setText(snapshot.players() + " / " + snapshot.maxPlayers()); uptimeValue.setText(snapshot.status());
        if (!snapshot.online()) { cpuValue.setText("-"); memoryValue.setText(snapshot.memoryLimitMb() > 0 ? snapshot.memoryLimitMb() + " MB ayrılmış" : "-"); sparkPane.serverStopped("Pterodactyl sunucusu kapandı • Lag analizi beklemesi durduruldu"); }
        if (!snapshot.playerNames().isEmpty()) acceptPlayers(snapshot.playerNames());
        if (snapshot.online() && sampleIndex % 5 == 0) probeLatency(snapshot.address()); updateHealthAndCrisis();
    }

    private void acceptPterodactylMetrics(PterodactylPane.ProMetrics value) {
        if (!isPterodactyl()) return; currentCpuPercent = value.cpuPercent(); currentRamPercent = value.memoryPercent(); currentMemoryMb = (int) Math.min(Integer.MAX_VALUE, value.memoryMb());
        cpuValue.setText(String.format(Locale.US, "%.1f%% CPU", value.cpuPercent())); memoryValue.setText(Double.isFinite(value.memoryPercent()) ? String.format(Locale.US, "%d MB • %.1f%%", value.memoryMb(), value.memoryPercent()) : value.memoryMb() + " MB"); uptimeValue.setText(durationText(value.uptimeSeconds()));
        addPoint(cpuSeries, sampleIndex, value.cpuPercent()); if (Double.isFinite(value.memoryPercent())) addPoint(memorySeries, sampleIndex, value.memoryPercent()); sampleIndex++; remoteMetricsReceived = true; recordPerformanceSample(); updateHealthAndCrisis();
    }

    private void samplePerformance() {
        if (isRemote()) { sampleRemotePerformance(); return; }
        long pid = manager.getProcessId();
        if (pid <= 0) { serverOnline = false; cpuValue.setText("0%"); memoryValue.setText("0 MB"); uptimeValue.setText("Kapalı"); resetProcessSample(); updateHealthAndCrisis(); return; }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid); if (handle.isEmpty()) return;
        long now = System.nanoTime(), cpu = handle.get().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos(); double percent = 0;
        if (pid == lastPid && lastCpuNanos >= 0 && lastSampleNanos > 0) percent = Math.max(0, Math.min(999, (cpu - lastCpuNanos) * 100.0 / (now - lastSampleNanos)));
        lastPid = pid; lastCpuNanos = cpu; lastSampleNanos = now; currentMemoryMb = readMemoryMb(pid);
        serverOnline = true; currentCpuPercent = percent; currentRamPercent = currentMemoryMb < 0 ? Double.NaN : currentMemoryMb * 100.0 / Math.max(1, config.getMemoryMb());
        cpuValue.setText(String.format(Locale.US, "%.1f%%", percent)); memoryValue.setText(currentMemoryMb < 0 ? "Bilinmiyor" : currentMemoryMb + " MB");
        Instant start = handle.get().info().startInstant().orElse(Instant.now()); uptimeValue.setText(durationText(java.time.Duration.between(start, Instant.now()).toSeconds()));
        addPoint(cpuSeries, sampleIndex, percent); if (currentMemoryMb >= 0) addPoint(memorySeries, sampleIndex, currentMemoryMb); sampleIndex++;
        recordPerformanceSample();
        if (sampleIndex % 5 == 0) probeLocalLatency();
        if (notifyHighMemory.isSelected() && currentMemoryMb >= memoryLimit.getValue() && !memoryWarned) { memoryWarned = true; DesktopNotifier.show(notificationSource(), "AeroMC RAM uyarısı", "Sunucu " + currentMemoryMb + " MB RAM kullanıyor."); sendDiscord(DiscordNotificationEngine.Type.PERFORMANCE, "Yüksek RAM kullanımı", "Sunucu " + currentMemoryMb + " MB RAM kullanıyor.", true); }
        if (currentMemoryMb < memoryLimit.getValue() * .85) memoryWarned = false;
        updateHealthAndCrisis();
    }
    private void sampleRemotePerformance() {
        sampleIndex++;
        if (isPterodactyl()) {
            if (!pterodactyl.hasActiveServer()) { providerState.setText("Pterodactyl sunucusu seçilmedi"); cpuValue.setText("-"); memoryValue.setText("-"); uptimeValue.setText("-"); playerValue.setText("0"); return; }
            return; // PterodactylPane WebSocket metrikleri ve 10 sn'lik tek durum yenilemesi ortak dinleyicilerle buraya gelir.
        }
        if (!exaroton.hasActiveServer()) { providerState.setText("Exaroton sunucusu seçilmedi"); cpuValue.setText("-"); memoryValue.setText("-"); uptimeValue.setText("-"); playerValue.setText("0"); return; }
        if (sampleIndex % 5 != 0 && remoteSnapshot != null) return;
        Task<ExarotonPane.ProSnapshot> task = new Task<>() { protected ExarotonPane.ProSnapshot call() throws Exception { return exaroton.fetchProSnapshot().join(); } };
        task.setOnSucceeded(event -> acceptRemoteSnapshot(task.getValue())); task.setOnFailed(event -> providerState.setText("Exaroton durumu alınamadı")); run(task, "exaroton-pro-snapshot");
    }

    private void updateHealthAndCrisis() {
        ServerHealthEngine.Snapshot health = ServerHealthEngine.calculate(serverOnline, currentTps, currentRamPercent, currentCpuPercent, currentLatencyMs, recentCrashes, overloadWarnings);
        healthValue.setText(health.score() + " / 100"); healthState.setText(health.state()); healthProgress.setProgress(health.score() / 100.0); healthReasons.setAll(health.reasons());
        healthProgress.getStyleClass().removeAll("health-good", "health-warning", "health-critical"); healthProgress.getStyleClass().add("health-" + health.tone());
        Instant now = Instant.now();
        if (lastOverloadWarningAt != null && elapsedSeconds(lastOverloadWarningAt, now) > 30) { overloadBurst = 0; lastOverloadWarningAt = null; }
        ServerHealthEngine.CrisisSignal signal = ServerHealthEngine.crisisSignal(currentTps, currentRamPercent, overloadBurst, crisisTps.getValue(), crisisRam.getValue());
        if (!serverOnline) {
            crisisDangerSince = crisisRecoverySince = null;
            if (crisisActive && !crisisTransitioning) exitCrisis(false, "Sunucu kapandığı için koruma sonlandırıldı");
            return;
        }
        if (signal.danger()) {
            if (crisisDangerSince == null) crisisDangerSince = now;
            crisisRecoverySince = null;
            if (!crisisActive && automaticCrisis.isSelected() && !crisisTransitioning) {
                long triggerLeft = Math.max(0, crisisTriggerSeconds.getValue() - elapsedSeconds(crisisDangerSince, now));
                long cooldownLeft = Math.max(0, crisisCooldownSeconds.getValue() - elapsedSeconds(crisisLastExit, now));
                if (cooldownLeft > 0) crisisState.setText("Bekleme koruması • " + cooldownLeft + " sn • " + signal.reason());
                else if (triggerLeft > 0) crisisState.setText("Kriz doğrulanıyor • " + triggerLeft + " sn • " + signal.reason());
                else enterCrisis(signal.reason(), false);
            } else if (crisisActive && !crisisTransitioning) crisisState.setText("ETKİN • " + signal.reason() + (crisisManual ? " • Manuel kontrol" : ""));
        } else {
            crisisDangerSince = null;
            if (crisisActive && !crisisTransitioning && !crisisManual) {
                boolean recovered = signal.recovered() || !crisisNeedsMetricsForRecovery && overloadBurst == 0;
                if (recovered) {
                    if (crisisRecoverySince == null) crisisRecoverySince = now;
                    long recoveryLeft = Math.max(0, crisisRecoverySeconds.getValue() - elapsedSeconds(crisisRecoverySince, now));
                    if (recoveryLeft > 0) crisisState.setText("ETKİN • Toparlanma doğrulanıyor: " + recoveryLeft + " sn");
                    else exitCrisis(false, "Sunucu değerleri kararlı biçimde toparlandı");
                } else crisisRecoverySince = null;
            } else if (!crisisActive && !crisisTransitioning) crisisState.setText("Kriz Modu beklemede");
        }
    }

    private void saveCrisisPreferences() {
        config.setCrisisModeEnabled(automaticCrisis.isSelected()); config.setCrisisTpsThreshold(crisisTps.getValue()); config.setCrisisRamThreshold(crisisRam.getValue()); config.setCrisisTriggerSeconds(crisisTriggerSeconds.getValue()); config.setCrisisRecoverySeconds(crisisRecoverySeconds.getValue()); config.setCrisisCooldownSeconds(crisisCooldownSeconds.getValue());
        try { config.save(); } catch (IOException error) { showError(error.getMessage()); }
    }

    private void refreshCrisisHistory() { crisisHistoryRows.setAll(crisisHistory.entries().stream().limit(10).map(CrisisHistory::display).toList()); }

    private void enterCrisis(String reason, boolean manual) {
        if (crisisActive || crisisTransitioning) return;
        if (!serverOnline) { showError("Kriz Modu için seçili sunucu online olmalı."); return; }
        crisisActive = true; crisisManual = manual; crisisTransitioning = true; crisisNeedsMetricsForRecovery = Double.isFinite(currentTps) || Double.isFinite(currentRamPercent); crisisProvider = provider.getValue(); provider.setDisable(true); crisisDangerSince = crisisRecoverySince = null; scheduledTasksPane.pause(); crisisState.setText("Kriz önlemleri uygulanıyor • " + reason); crisisState.getStyleClass().add("crisis-active");
        findings.add(0, now() + "  KRİZ MODU: " + reason + ". Otomatik ağır görevler durduruldu."); recordEvent("Kriz Modu", "Etkinleştirildi: " + reason);
        Task<Void> task = new Task<>() { protected Void call() throws Exception { try { applyCrisisSettings(); return null; } catch (Exception error) { try { restoreCrisisSettings(); } catch (Exception restoreError) { error.addSuppressed(restoreError); } throw error; } } };
        task.setOnSucceeded(event -> { crisisTransitioning = false; activeCrisisHistoryId = crisisHistory.start(Instant.now(), notificationSource(), reason, crisisTps.getValue(), crisisRam.getValue(), manual); refreshCrisisHistory(); crisisState.setText("ETKİN • " + reason + (manual ? " • Manuel kontrol" : "")); DesktopNotifier.show(notificationSource(), "AeroMC Kriz Modu", "Sunucuyu rahatlatan güvenli önlemler uygulandı."); sendDiscord(DiscordNotificationEngine.Type.PERFORMANCE, "Kriz Modu etkinleştirildi", reason, true); });
        task.setOnFailed(event -> { crisisActive = crisisManual = crisisTransitioning = crisisNeedsMetricsForRecovery = false; crisisLastExit = Instant.now(); provider.setDisable(false); scheduledTasksPane.resume(); crisisState.setText("Kriz Modu etkinleştirilemedi"); crisisState.getStyleClass().remove("crisis-active"); String error = rootMessage(task.getException()); findings.add(0, now() + "  KRİZ UYARISI: Önlemler geri alındı: " + error); recordEvent("Kriz Modu", "Etkinleştirme başarısız: " + error); }); run(task, "crisis-enter");
    }

    private void applyCrisisSettings() throws Exception {
        if (isCrisisPterodactyl()) {
            Map<String, Object> options = pterodactyl.loadServerOptions().join(); crisisOriginalRemote.clear();
            for (String key : List.of("view-distance", "simulation-distance")) if (options.containsKey(key)) crisisOriginalRemote.put(key, options.get(key));
            pterodactyl.saveServerOptions(Map.of("view-distance", 6, "simulation-distance", 4)).join();
            pterodactyl.executeAdminCommand("say [AeroMC] Kriz Modu etkin: ağır görevler geçici olarak azaltılıyor.").join();
            pterodactyl.executeAdminCommand("gamerule randomTickSpeed 1").join(); return;
        }
        if (isCrisisExaroton()) {
            Map<String, ConfigOption<?>> options = exaroton.loadServerOptions().join(); crisisOriginalRemote.clear();
            for (String key : List.of("view-distance", "simulation-distance")) if (options.containsKey(key)) crisisOriginalRemote.put(key, options.get(key).getValue());
            exaroton.saveServerOptions(Map.of("view-distance", 6, "simulation-distance", 4)).join();
            exaroton.executeAdminCommand("say [AeroMC] Kriz Modu etkin: ağır görevler geçici olarak azaltılıyor.").join();
            exaroton.executeAdminCommand("gamerule randomTickSpeed 1").join(); return;
        }
        Path folder = manager.getServerFolder(); if (folder == null) throw new IOException("Sunucu klasörü seçilmedi."); Path properties = folder.resolve("server.properties");
        Properties values = loadProperties(properties); crisisOriginalLocal.clear(); crisisOriginalLocal.put("view-distance", values.getProperty("view-distance", "10")); crisisOriginalLocal.put("simulation-distance", values.getProperty("simulation-distance", "10"));
        if (Files.exists(properties)) Files.copy(properties, properties.resolveSibling("server.properties.crisis-backup"), StandardCopyOption.REPLACE_EXISTING);
        updateProperty(properties, "view-distance", "6"); updateProperty(properties, "simulation-distance", "4");
        manager.command("say [AeroMC] Kriz Modu etkin: ağır görevler geçici olarak azaltılıyor."); manager.command("gamerule randomTickSpeed 1"); manager.command("save-all");
    }

    private void exitCrisis(boolean manual) { exitCrisis(manual, manual ? "Yönetici tarafından kapatıldı" : "Sunucu değerleri kararlı biçimde toparlandı"); }

    private void exitCrisis(boolean manual, String reason) {
        if (!crisisActive || crisisTransitioning) return;
        crisisTransitioning = true; crisisState.setText("Normal ayarlar geri yükleniyor...");
        Task<Void> task = new Task<>() { protected Void call() throws Exception { restoreCrisisSettings(); return null; } };
        task.setOnSucceeded(event -> { crisisActive = crisisManual = crisisTransitioning = crisisNeedsMetricsForRecovery = false; crisisLastExit = Instant.now(); crisisDangerSince = crisisRecoverySince = null; if (activeCrisisHistoryId != null) crisisHistory.finish(activeCrisisHistoryId, crisisLastExit, reason); activeCrisisHistoryId = null; refreshCrisisHistory(); provider.setDisable(false); scheduledTasksPane.resume(); crisisState.setText(reason + " • Kriz Modu kapatıldı"); crisisState.getStyleClass().remove("crisis-active"); recordEvent("Kriz Modu", reason); findings.add(0, now() + "  KRİZ MODU: Normal ayarlar geri yüklendi, otomatik görevler devam ediyor."); sendDiscord(DiscordNotificationEngine.Type.PERFORMANCE, "Kriz Modu kapandı", reason + ". Normal ayarlar geri yüklendi.", false); });
        task.setOnFailed(event -> { crisisTransitioning = false; String error = rootMessage(task.getException()); crisisState.setText("ETKİN • Ayarlar geri yüklenemedi; Krizden Çık ile yeniden dene"); findings.add(0, now() + "  KRİZ UYARISI: Ayarlar geri yüklenemedi: " + error); recordEvent("Kriz Modu", "Çıkış başarısız: " + error); }); run(task, "crisis-exit");
    }

    private void restoreCrisisSettings() throws Exception {
        if (isCrisisPterodactyl()) {
            if (!crisisOriginalRemote.isEmpty()) pterodactyl.saveServerOptions(new LinkedHashMap<>(crisisOriginalRemote)).join();
            if (pterodactyl.hasActiveServer()) pterodactyl.executeAdminCommand("gamerule randomTickSpeed 3").join(); crisisOriginalRemote.clear(); crisisProvider = null; return;
        }
        if (isCrisisExaroton()) {
            if (!crisisOriginalRemote.isEmpty()) exaroton.saveServerOptions(new LinkedHashMap<>(crisisOriginalRemote)).join();
            if (exaroton.hasActiveServer()) exaroton.executeAdminCommand("gamerule randomTickSpeed 3").join(); crisisOriginalRemote.clear(); crisisProvider = null; return;
        }
        Path folder = manager.getServerFolder(); if (folder != null && !crisisOriginalLocal.isEmpty()) { Path properties = folder.resolve("server.properties"); for (var entry : crisisOriginalLocal.entrySet()) updateProperty(properties, entry.getKey(), entry.getValue()); }
        if (manager.isRunning()) manager.command("gamerule randomTickSpeed 3"); crisisOriginalLocal.clear(); crisisProvider = null;
    }

    private boolean isCrisisExaroton() { return "Exaroton".equals(crisisProvider); }
    private boolean isCrisisPterodactyl() { return "Pterodactyl".equals(crisisProvider); }
    private long elapsedSeconds(Instant start, Instant end) { return start == null ? 0 : Math.max(0, java.time.Duration.between(start, end).toSeconds()); }

    private void createIncident(String state, boolean crashed) {
        List<String> history; synchronized (consoleHistory) { history = new ArrayList<>(consoleHistory); }
        CrashDoctor.Diagnosis diagnosis = CrashDoctor.diagnose(history); String actions = String.join(" → ", diagnosis.actions());
        IncidentContext.Report context = incidentContext.report(Instant.now());
        String report = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + "  " + (crashed ? "ÇÖKME" : "KAPANMA") + " • " + context.detail() + "  |  Tanı: " + diagnosis.summary() + "  |  Kanıt: " + diagnosis.evidence() + "  |  Çözüm: " + actions;
        crashReports.add(0, report);
        while (crashReports.size() > 30) crashReports.remove(crashReports.size() - 1);
        diagnosticHistory.record(Instant.now(), notificationSource(), diagnosis.summary() + " • " + diagnosis.evidence());
        recordEvent(crashed ? "Çökme Vakası" : "Kapanma Vakası", state + " • " + context.detail() + " • Tanı: " + diagnosis.summary());
    }

    private void recordPerformanceSample() {
        Instant time = Instant.now(); incidentContext.recordMetric(time, currentTps, currentRamPercent, currentCpuPercent); thresholdAdvisor.record(time, notificationSource(), currentTps, currentRamPercent, currentCpuPercent);
    }

    private void suggestSmartThresholds() {
        Optional<SmartThresholdAdvisor.Recommendation> value = thresholdAdvisor.recommend(notificationSource(), Instant.now());
        if (value.isEmpty()) { info("Akıllı Eşik henüz hazır değil", "AeroMC'nin en az 20 seyrekleştirilmiş ölçüme ihtiyacı var. Sunucuyu yaklaşık 10 dakika normal kullanıp tekrar dene."); return; }
        SmartThresholdAdvisor.Recommendation advice = value.get();
        String detail = String.format(Locale.US, "TPS: %.1f → %.1f%nRAM: %d%% → %d%%%nTetikleme: %d sn → %d sn%n%nGüven: %s • %d örnek%n%s", crisisTps.getValue(), advice.tps(), crisisRam.getValue(), advice.ramPercent(), crisisTriggerSeconds.getValue(), advice.triggerSeconds(), advice.confidence(), advice.samples(), advice.explanation());
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, detail, ButtonType.YES, ButtonType.NO); alert.setHeaderText("Önerilen kriz eşikleri uygulansın mı?");
        if (alert.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        crisisTps.getValueFactory().setValue(advice.tps()); crisisRam.getValueFactory().setValue(advice.ramPercent()); crisisTriggerSeconds.getValueFactory().setValue(advice.triggerSeconds()); saveCrisisPreferences();
        thresholdAdvice.setText(String.format(Locale.US, "Uygulandı • TPS %.1f altı • RAM %%%d • %d sn • Güven: %s", advice.tps(), advice.ramPercent(), advice.triggerSeconds(), advice.confidence()));
        recordEvent("Akıllı Eşik", thresholdAdvice.getText());
    }

    private void addPoint(XYChart.Series<Number, Number> series, long x, double y) { series.getData().add(new XYChart.Data<>(x, y)); while (series.getData().size() > 60) series.getData().remove(0); }
    private static void onFx(Runnable action) { if (Platform.isFxApplicationThread()) action.run(); else Platform.runLater(action); }
    private void probeLocalLatency() {
        Path folder = manager.getServerFolder(); if (folder == null) return; Properties values = loadProperties(folder.resolve("server.properties")); String port = values.getProperty("server-port", "25565"); probeLatency("127.0.0.1:" + port);
    }
    private void probeLatency(String address) {
        if (latencyProbeRunning || address == null || address.isBlank()) return; latencyProbeRunning = true;
        Task<Long> task = new Task<>() { protected Long call() throws Exception { return MinecraftPing.ping(address).latencyMs(); } };
        task.setOnSucceeded(event -> { latencyProbeRunning = false; currentLatencyMs = task.getValue(); latencyValue.setText(task.getValue() + " ms"); updateHealthAndCrisis(); });
        task.setOnFailed(event -> { latencyProbeRunning = false; currentLatencyMs = Double.NaN; latencyValue.setText("-"); updateHealthAndCrisis(); }); run(task, "health-latency");
    }
    private void resetProcessSample() { lastPid = -1; lastCpuNanos = -1; lastSampleNanos = -1; currentMemoryMb = -1; currentTps = currentRamPercent = currentCpuPercent = currentLatencyMs = Double.NaN; latencyValue.setText("-"); }
    private int readMemoryMb(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status"); if (!Files.isReadable(status)) return -1;
        try { for (String line : Files.readAllLines(status)) if (line.startsWith("VmRSS:")) return Integer.parseInt(line.replaceAll("[^0-9]", "")) / 1024; } catch (Exception ignored) { } return -1;
    }

    private void restartConfigured() { if (isPterodactyl()) { runRemoteAction("Pterodactyl yeniden başlatma", pterodactyl::restartActiveServer); return; } if (isExaroton()) { runRemoteAction("Exaroton yeniden başlatma", exaroton::restartActiveServer); return; } Path jar = config.getServerJar(); if (jar == null || !Files.isRegularFile(jar)) { Platform.runLater(() -> showError("Yeniden başlatmak için Yerel JAR seçimi bulunamadı.")); return; } manager.restart(jar, config.getMemoryMb()); }
    private void stopConfigured() { if (isPterodactyl()) runRemoteAction("Pterodactyl durdurma", pterodactyl::stopActiveServer); else if (isExaroton()) runRemoteAction("Exaroton durdurma", exaroton::stopActiveServer); else manager.stop(); }
    private void announceConfigured(String message) { if (isPterodactyl()) runRemoteAction("Pterodactyl duyuru", () -> pterodactyl.executeAdminCommand("say " + message)); else if (isExaroton()) runRemoteAction("Exaroton duyuru", () -> exaroton.executeAdminCommand("say " + message)); else try { manager.command("say " + message); } catch (IOException error) { showError(error.getMessage()); } }
    private void worldBackupCompleted(Path path) { recordEvent("Yedek", "Dünya yedeği hazırlandı: " + path.getFileName()); DesktopNotifier.show(notificationSource(), "AeroMC", "Dünya yedeği hazır."); sendDiscord(DiscordNotificationEngine.Type.BACKUP, "Yedek hazır", path.getFileName() + " başarıyla oluşturuldu.", false); info("Yedek hazır", path.toString()); }
    private void maintenanceMode() {
        if (isRemote()) {
            boolean ptero = isPterodactyl(); String source = provider.getValue(); if (ptero ? !pterodactyl.hasActiveServer() : !exaroton.hasActiveServer()) { showError("Önce " + source + " sekmesinden bir sunucu seç."); return; } if (!confirm(source + " sunucusunda whitelist açılıp duyuru gönderildikten sonra sunucu kapatılsın mı? Uzak yedek bu işlemde oluşturulmaz.")) return;
            Task<Void> remote = new Task<>() { protected Void call() throws Exception { if (ptero) { pterodactyl.executeAdminCommand("whitelist on").join(); pterodactyl.executeAdminCommand("say Sunucu bakım moduna giriyor. Lütfen güvenli şekilde çıkış yapın.").join(); pterodactyl.stopActiveServer().join(); } else { exaroton.executeAdminCommand("whitelist on").join(); exaroton.executeAdminCommand("say Sunucu bakım moduna giriyor. Lütfen güvenli şekilde çıkış yapın.").join(); exaroton.stopActiveServer().join(); } return null; } };
            remote.setOnSucceeded(event -> { findings.add(0, now() + "  BAKIM: " + source + " whitelist açıldı ve sunucu kapatıldı."); sendDiscord(DiscordNotificationEngine.Type.MAINTENANCE, source + " bakım modu", "Whitelist açıldı, oyuncular bilgilendirildi ve sunucu kapatıldı.", false); }); remote.setOnFailed(event -> showError(rootMessage(remote.getException()))); run(remote, source.toLowerCase(Locale.ROOT) + "-maintenance"); return;
        }
        if (!manager.isRunning()) { showError("Bakım modu için yerel sunucu çalışıyor olmalı."); return; } if (!confirm("Bakım modu sunucuyu yedekleyip kapatacak. Devam edilsin mi?")) return;
        Task<Path> task = new Task<>() { protected Path call() throws Exception { manager.command("whitelist on"); manager.command("say Sunucu bakım moduna giriyor. Lütfen güvenli şekilde çıkış yapın."); manager.command("save-all flush"); Thread.sleep(1500); Path backup = manager.createBackup(); manager.stop(); return backup; } };
        task.setOnSucceeded(event -> { findings.add(0, now() + "  BAKIM: Whitelist açıldı, yedek alındı ve sunucu kapatıldı."); DesktopNotifier.show(notificationSource(), "AeroMC", "Bakım modu tamamlandı."); sendDiscord(DiscordNotificationEngine.Type.MAINTENANCE, "Bakım modu tamamlandı", "Whitelist açıldı, yedek alındı ve yerel sunucu güvenli şekilde kapatıldı.", false); }); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "maintenance-mode");
    }

    private void recordEvent(String category, String detail) { eventTimelinePane.record(category, detail); }

    public void setAutomaticCredentialVaultEnabled(boolean enabled) { discordPane.setAutomaticCredentialVaultEnabled(enabled); }
    private void sendDiscord(DiscordNotificationEngine.Type type, String title, String message, boolean critical) { discordPane.send(type, title, message, critical); }
    private String discordServerName() { if (isPterodactyl()) return pterodactylSnapshot != null ? pterodactylSnapshot.name() : pterodactyl.getActiveServerName(); if (isExaroton() && remoteSnapshot != null) return remoteSnapshot.name(); Path folder = manager.getServerFolder(); return folder == null || folder.getFileName() == null ? (isExaroton() ? exaroton.getActiveServerName() : "Yerel sunucu") : folder.getFileName().toString(); }
    private String notificationSource() { return isPterodactyl() ? NotificationCenter.serverSource("Pterodactyl", pterodactyl.getActiveServerName()) : isExaroton() ? NotificationCenter.serverSource("Exaroton", exaroton.getActiveServerName()) : NotificationCenter.serverSource("Yerel JAR", ""); }

    private void runRemoteAction(String name, Callable<CompletableFuture<Void>> action) { Task<Void> task = new Task<>() { protected Void call() throws Exception { action.call().join(); return null; } }; task.setOnFailed(event -> showError(name + " başarısız: " + rootMessage(task.getException()))); run(task, name.toLowerCase(Locale.ROOT).replace(' ', '-')); }

    public void shutdown() { metrics.stop(); inGameBridge.shutdown(); scheduledTasksPane.shutdown(); sparkPane.shutdown(); discordPane.shutdown(); if (crisisActive) { try { restoreCrisisSettings(); if (activeCrisisHistoryId != null) crisisHistory.finish(activeCrisisHistoryId, Instant.now(), "AeroMC kapanırken güvenle sonlandırıldı"); } catch (Exception ignored) { } } playerInsightsPane.disconnectAll(); }
    private void atomicWrite(Path file, String text) throws IOException { Path temp = Files.createTempFile(file.getParent(), ".aeromc-", ".tmp"); Files.writeString(temp, text, StandardCharsets.UTF_8); try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); } }
    private void updateProperty(Path file, String key, String value) throws IOException { List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>(); boolean found = false; List<String> out = new ArrayList<>(); for (String line : lines) { if (!line.stripLeading().startsWith("#") && line.startsWith(key + "=")) { out.add(key + "=" + value); found = true; } else out.add(line); } if (!found) out.add(key + "=" + value); Files.createDirectories(file.getParent()); atomicWrite(file, String.join(System.lineSeparator(), out) + System.lineSeparator()); }
    private Properties loadProperties(Path file) { Properties values = new Properties(); try { if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file)) { values.load(reader); } } catch (IOException ignored) { } return values; }
    private void saveProperties(Path file, Properties values, String title) { try { Files.createDirectories(file.getParent()); try (Writer writer = Files.newBufferedWriter(file)) { values.store(writer, title); } } catch (IOException ignored) { } }
    private <T> void runTask(String name, Callable<T> work, java.util.function.Consumer<T> success) { Task<T> task = new Task<>() { protected T call() throws Exception { return work.call(); } }; task.setOnSucceeded(event -> success.accept(task.getValue())); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, name); }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, "aeromc-" + name); thread.setDaemon(true); thread.start(); }
    private Tab tab(String title, Node content) { Tab tab = new Tab(title, content); tab.setClosable(false); return tab; }
    private VBox page(Node... children) { VBox page = new VBox(14, children); page.setPadding(new Insets(18)); for (Node child : children) VBox.setVgrow(child, Priority.ALWAYS); return page; }
    private ScrollPane scrollable(Node content) { ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); scroll.setFitToHeight(false); scroll.getStyleClass().add("control-scroll"); return scroll; }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11); box.getChildren().add(label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private VBox metricCard(String title, Label value) { VBox box = card(title, value); box.setMaxWidth(Double.MAX_VALUE); return box; }
    private static Label metric(String text) { Label label = new Label(text); label.getStyleClass().add("metric"); return label; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private String durationText(long seconds) { long hours = seconds / 3600, minutes = seconds % 3600 / 60; return hours > 0 ? hours + " sa " + minutes + " dk" : minutes + " dk"; }
    private String now() { return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")); }
    private String trim(String value, int max) { String clean = value.strip(); return clean.length() <= max ? clean : clean.substring(0, max) + "…"; }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private void showError(String message) { if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(message)); return; } Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private void info(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, LanguageManager.text(message), ButtonType.OK); alert.setHeaderText(LanguageManager.text(title)); alert.showAndWait(); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }

}
