package com.aerogroup.mcpanel;

import com.exaroton.api.server.config.ConfigOption;
import javafx.animation.*;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.zip.*;

/** Yerel sunucu için performans, içerik, dosya, dünya ve otomasyon merkezi. */
public final class ProToolsPane {
    private static final Path DATA = Path.of(System.getProperty("user.home"), ".aeromc-panel");
    private static final Path PLAYER_FILE = DATA.resolve("player-history.properties");
    private static final Path JOB_FILE = DATA.resolve("scheduled-jobs.properties");
    private static final Path EVENT_FILE = DATA.resolve("event-timeline.log");
    private static final Path PERFORMANCE_FILE = DATA.resolve("performance-history.log");
    private static final Path DISCORD_SETTINGS_FILE = DATA.resolve("discord-notifications.properties");
    private static final Set<String> SAFE_FILES = Set.of("server.properties", "eula.txt", "whitelist.json", "ops.json", "banned-players.json", "banned-ips.json");
    private static final Pattern JOIN = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) joined the game");
    private static final Pattern LEAVE = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) left the game");
    private static final Pattern ADVANCEMENT = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) has (?:made the advancement|completed the challenge|reached the goal)");
    private static final Pattern DEATH = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) (?:died|was |fell |drowned|blew up|burned|hit the ground|starved|suffocated|withered|experienced kinetic energy|went up in flames|tried to swim in lava)", Pattern.CASE_INSENSITIVE);

    private final ServerManager manager;
    private final ExarotonPane exaroton;
    private final PanelConfig config;
    private final HostServices hostServices;
    private final ModCenterPane modCenterPane;
    private final ComboBox<String> provider = new ComboBox<>(FXCollections.observableArrayList("Yerel JAR", "Exaroton"));
    private final Label providerState = new Label();
    private final ObservableList<String> findings = FXCollections.observableArrayList();
    private final ObservableList<String> crashReports = FXCollections.observableArrayList();
    private final ObservableList<String> healthReasons = FXCollections.observableArrayList();
    private final ObservableList<String> events = FXCollections.observableArrayList();
    private final Deque<String> consoleHistory = new ArrayDeque<>();
    private final IncidentContext incidentContext = new IncidentContext();
    private final SmartThresholdAdvisor thresholdAdvisor = new SmartThresholdAdvisor(PERFORMANCE_FILE);
    private final CrashLoopGuard crashLoopGuard = new CrashLoopGuard();
    private final ObservableList<PlayerProfile> playerRows = FXCollections.observableArrayList();
    private final Map<String, PlayerProfile> profiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> onlinePlayers = new HashSet<>();
    private final ObservableList<Job> jobs = FXCollections.observableArrayList();
    private final ComboBox<String> scheduledAction = new ComboBox<>();
    private final Label cpuValue = metric("0%"), memoryValue = metric("0 MB"), uptimeValue = metric("Kapalı"), playerValue = metric("0"), latencyValue = metric("-");
    private final Label healthValue = metric("0 / 100"), healthState = new Label("Sunucu kapalı"), crisisState = new Label("Kriz Modu beklemede");
    private final Label thresholdAdvice = new Label("Öneri için sunucuyu bir süre normal kullan");
    private final ProgressBar healthProgress = new ProgressBar(0);
    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>(), memorySeries = new XYChart.Series<>();
    private final Timeline metrics = new Timeline(new KeyFrame(Duration.seconds(2), event -> samplePerformance()));
    private final Timeline scheduler = new Timeline(new KeyFrame(Duration.seconds(5), event -> runDueJobs()));
    private final ComboBox<String> fileChoice = new ComboBox<>(FXCollections.observableArrayList(SAFE_FILES.stream().sorted().toList()));
    private final TextArea fileEditor = new TextArea();
    private final Label fileState = new Label("Dosya seç");
    private final ComboBox<String> contentType = new ComboBox<>(FXCollections.observableArrayList("Eklentiler (plugins)", "Modlar (mods)"));
    private final ListView<Path> contentList = new ListView<>();
    private final ListView<Path> worldList = new ListView<>();
    private final CheckBox restartOnCrash = new CheckBox("Çökünce otomatik yeniden başlat");
    private final CheckBox notifyHighMemory = new CheckBox("Yüksek RAM kullanımında bildir");
    private final Spinner<Integer> memoryLimit = new Spinner<>(256, 65536, 4096, 256);
    private final PasswordField webhook = new PasswordField();
    private final CheckBox discordEnabled = new CheckBox("Discord bildirimleri açık");
    private final CheckBox discordStatus = new CheckBox("Açılma / kapanma"), discordCrash = new CheckBox("Çökme ve kritik hata"), discordPlayers = new CheckBox("Oyuncu girişleri"), discordPerformance = new CheckBox("RAM ve Kriz Modu"), discordMaintenance = new CheckBox("Bakım ve yedek"), discordAutomation = new CheckBox("Zamanlanmış görevler"), discordMentionCritical = new CheckBox("Kritik olayda rolü etiketle");
    private final TextField discordName = new TextField("AeroMC"), discordRoleId = new TextField();
    private final Label discordState = new Label("Discord webhook bağlantısı bekleniyor");
    private volatile String automaticDiscordWebhook;
    private boolean discordViewBuilt, discordVaultLoading;
    private final DiscordWebhookClient discordClient = new DiscordWebhookClient();
    private final Button discordLoadSaved = button("Kayıtlı Webhook'u Aç", "secondary"), discordDeleteSaved = button("Kaydı Sil", "danger");
    private final Label automationScope = new Label("Yerel JAR görevleri"), maintenanceNote = new Label();
    private final Button openExarotonAutomationButton = button("Exaroton Otomasyon Merkezini Aç", "primary");
    private final CheckBox automaticCrisis = new CheckBox("Kriz Modunu otomatik tetikle");
    private final Spinner<Double> crisisTps;
    private final Spinner<Integer> crisisRam;
    private final Spinner<Integer> crisisTriggerSeconds;
    private final Spinner<Integer> crisisRecoverySeconds;
    private final Spinner<Integer> crisisCooldownSeconds;
    private final ComboBox<String> sparkDuration = new ComboBox<>(FXCollections.observableArrayList("Hızlı • 60 saniye", "Normal • 3 dakika", "Detaylı • 5 dakika"));
    private final Label sparkState = new Label("1. Sunucuyu aç  •  2. Süreyi seç  •  3. Analizi başlat");
    private Button sparkStartButton, sparkOpenButton;
    private final Timeline sparkCountdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateSparkCountdown()));
    private final VBox achievementPreview = new VBox(10);
    private long lastPid = -1, lastCpuNanos = -1, lastSampleNanos = -1, sampleIndex;
    private int currentMemoryMb;
    private boolean memoryWarned, autoRestarting;
    private boolean serverOnline, crisisActive, crisisManual, crisisTransitioning, crisisNeedsMetricsForRecovery, latencyProbeRunning;
    private String crisisProvider;
    private int recentCrashes, overloadWarnings, overloadBurst;
    private Instant crisisDangerSince, crisisRecoverySince, crisisLastExit = Instant.EPOCH, lastOverloadWarningAt;
    private double currentTps = Double.NaN, currentRamPercent = Double.NaN, currentCpuPercent = Double.NaN, currentLatencyMs = Double.NaN;
    private final Map<String, Object> crisisOriginalRemote = new LinkedHashMap<>();
    private final Map<String, String> crisisOriginalLocal = new LinkedHashMap<>();
    private ExarotonPane.ProSnapshot remoteSnapshot;
    private String lastRemoteStatus;
    private Boolean lastRemoteOnline;
    private boolean remoteMetricsReceived;
    private volatile String latestSparkReport;
    private boolean sparkProfileRunning;
    private Instant sparkDeadline;
    private VBox localAutomationCard;
    private final Runnable openExarotonAutomation;

    public ProToolsPane(ServerManager manager, ExarotonPane exaroton, PanelConfig config, HostServices hostServices, Runnable openExarotonAutomation) {
        this.manager = manager; this.exaroton = exaroton; this.config = config; this.hostServices = hostServices; this.openExarotonAutomation = openExarotonAutomation;
        this.modCenterPane = new ModCenterPane(manager, exaroton, config, hostServices);
        crisisTps = new Spinner<>(10.0, 19.5, Math.max(10.0, Math.min(19.5, config.getCrisisTpsThreshold())), 0.5);
        crisisRam = new Spinner<>(70, 99, Math.max(70, Math.min(99, (int) config.getCrisisRamThreshold())), 1);
        crisisTriggerSeconds = new Spinner<>(4, 60, config.getCrisisTriggerSeconds(), 2);
        crisisRecoverySeconds = new Spinner<>(10, 180, config.getCrisisRecoverySeconds(), 5);
        crisisCooldownSeconds = new Spinner<>(0, 600, config.getCrisisCooldownSeconds(), 15);
        loadPlayers(); loadJobs(); loadEvents(); loadDiscordPreferences(); metrics.setCycleCount(Animation.INDEFINITE); scheduler.setCycleCount(Animation.INDEFINITE); sparkCountdown.setCycleCount(Animation.INDEFINITE);
        exaroton.addProConsoleListener(this::onRemoteConsole);
        exaroton.addProSnapshotListener(snapshot -> Platform.runLater(() -> acceptRemoteSnapshot(snapshot)));
        exaroton.addProMetricsListener(metrics -> Platform.runLater(() -> acceptRemoteMetrics(metrics)));
        exaroton.activeServerNameProperty().addListener((observable, oldName, newName) -> {
            remoteMetricsReceived = false;
            if (!"Exaroton sunucusu seçilmedi".equals(newName)) provider.getSelectionModel().select("Exaroton");
            updateProviderState();
        });
    }

    public Node buildView() {
        Node health = scrollable(insightsView()), modCenter = scrollable(modCenterPane.buildView()), files = scrollable(filesView()), content = scrollable(contentView()), worlds = scrollable(worldsView()), automation = scrollable(automationView()), players = scrollable(playersView()), timeline = scrollable(timelineView());
        StackPane center = new StackPane(health);
        ToggleGroup navigation = new ToggleGroup();
        VBox menu = new VBox(7);
        menu.getStyleClass().add("control-menu");
        for (Object[] item : new Object[][]{{"Sunucu Sağlığı", health}, {"Olay Zaman Çizelgesi", timeline}, {"Tek Tık Mod Merkezi", modCenter}, {"Dosyalar", files}, {"Eklenti & Modlar", content}, {"Dünyalar", worlds}, {"Görevler & Bildirimler", automation}, {"Başarı Kartları", players}}) {
            ToggleButton button = new ToggleButton((String) item[0]); button.setToggleGroup(navigation); button.setMaxWidth(Double.MAX_VALUE); button.setWrapText(true); button.setTooltip(new Tooltip((String) item[0])); button.getStyleClass().add("control-menu-button");
            Node view = (Node) item[1]; button.setOnAction(event -> { center.getChildren().setAll(view); if (view == files) loadSelectedFile(); if (view == content) refreshContent(); if (view == worlds) refreshWorlds(); }); menu.getChildren().add(button);
        }
        ((ToggleButton) menu.getChildren().get(0)).setSelected(true);
        BorderPane workspace = new BorderPane(); workspace.setLeft(menu); workspace.setCenter(center); workspace.getStyleClass().add("control-workspace");
        provider.getSelectionModel().selectFirst(); provider.setOnAction(event -> { cancelSparkWait("Aktif sunucu değişti • Yeni analiz başlatabilirsin"); resetProcessSample(); recentCrashes = overloadWarnings = overloadBurst = 0; lastOverloadWarningAt = null; lastRemoteOnline = null; synchronized (consoleHistory) { consoleHistory.clear(); } incidentContext.clear(); updateProviderState(); updateAutomationProviderUi(); remoteMetricsReceived = false; cpuSeries.getData().clear(); memorySeries.getData().clear(); loadSelectedFile(); refreshContent(); refreshWorlds(); });
        providerState.getStyleClass().add("muted"); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label title = new Label("AKTİF SUNUCU"); title.getStyleClass().add("section-title");
        HBox providerBar = new HBox(9, title, provider, providerState, spacer, new Label("Kontrol Merkezi")); providerBar.setAlignment(Pos.CENTER_LEFT); providerBar.getStyleClass().add("pro-provider-bar"); updateProviderState(); updateAutomationProviderUi();
        VBox shell = new VBox(providerBar, workspace); VBox.setVgrow(workspace, Priority.ALWAYS);
        metrics.play(); scheduler.play(); return shell;
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
        VBox profiler = profilerView();
        VBox page = page(healthRow, cards, profiler, body); VBox.setVgrow(healthRow, Priority.NEVER); VBox.setVgrow(cards, Priority.NEVER); VBox.setVgrow(profiler, Priority.NEVER); VBox.setVgrow(body, Priority.ALWAYS); return page;
    }

    private VBox profilerView() {
        sparkDuration.getSelectionModel().selectFirst(); sparkDuration.setPrefWidth(170);
        sparkStartButton = button("LAG ANALİZİNİ BAŞLAT", "primary"); sparkOpenButton = button("HAZIR RAPORU AÇ", "secondary"); Button help = button("Spark Yoksa?", "secondary");
        sparkStartButton.setOnAction(event -> startSparkAnalysis()); sparkOpenButton.setOnAction(event -> openSparkReport()); help.setOnAction(event -> showSparkHelp()); sparkOpenButton.setDisable(latestSparkReport == null);
        sparkState.setWrapText(true); sparkState.getStyleClass().add("muted");
        FlowPane controls = new FlowPane(8, 8, new Label("Analiz süresi"), sparkDuration, sparkStartButton, sparkOpenButton, help); controls.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Sunucu kasarken başlat ve hiçbir şey yapmadan geri sayımın bitmesini bekle. AeroMC rapor bağlantısını konsoldan kendisi yakalar; hazır olduğunda yalnızca 'Hazır Raporu Aç'a basarsın."); note.setWrapText(true); note.getStyleClass().add("muted");
        return card("TEK TIK LAG ANALİZİ", controls, sparkState, note);
    }

    private Node timelineView() {
        ListView<String> list = new ListView<>(events); list.setPlaceholder(new Label("Henüz kaydedilmiş olay yok")); VBox.setVgrow(list, Priority.ALWAYS);
        Button copy = button("Son Olayları Kopyala", "secondary"), clear = button("Zaman Çizelgesini Temizle", "danger");
        copy.setOnAction(event -> { javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent(); content.putString(String.join(System.lineSeparator(), events)); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); });
        clear.setOnAction(event -> { if (confirm("Tüm olay zaman çizelgesi silinsin mi?")) { events.clear(); saveEvents(); } });
        Label note = new Label("Başlatma, kapanma/çökme, oyuncu, yedek, kriz modu ve Spark profil olayları bu bilgisayarda saklanır."); note.getStyleClass().add("muted");
        return page(card("SUNUCU OLAY ZAMAN ÇİZELGESİ", note, list, new HBox(8, copy, clear)));
    }

    private Node filesView() {
        fileChoice.getSelectionModel().select("server.properties"); fileChoice.setOnAction(event -> loadSelectedFile());
        Button load = button("Yükle", "secondary"), save = button("Güvenli Kaydet", "primary"), folder = button("Klasörü Aç", "secondary");
        load.setOnAction(event -> loadSelectedFile()); save.setOnAction(event -> saveSelectedFile()); folder.setOnAction(event -> openServerFolder());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); fileState.getStyleClass().add("muted");
        HBox row = new HBox(8, fileChoice, load, save, spacer, fileState, folder); row.setAlignment(Pos.CENTER_LEFT);
        fileEditor.setStyle("-fx-font-family:'JetBrains Mono','Monospace';"); VBox.setVgrow(fileEditor, Priority.ALWAYS);
        Label note = new Label("Güvenlik için yalnızca temel sunucu yapılandırma dosyaları düzenlenebilir. Kaydetmeden önce otomatik .bak kopyası alınır."); note.getStyleClass().add("muted");
        return page(card("GÜVENLİ DOSYA YÖNETİCİSİ", row, note, fileEditor));
    }

    private Node contentView() {
        contentType.getSelectionModel().selectFirst(); contentType.setOnAction(event -> refreshContent());
        contentList.setCellFactory(list -> new ListCell<>() { protected void updateItem(Path value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : (value.getFileName() + (ProToolsPane.this.isDisabled(value) ? "  [DEVRE DIŞI]" : "  [AKTİF]"))); } });
        Button refresh = button("Yenile", "secondary"), install = button("Dosya Ekle", "primary"), toggle = button("Etkinleştir / Devre Dışı", "secondary");
        refresh.setOnAction(event -> refreshContent()); install.setOnAction(event -> importContent()); toggle.setOnAction(event -> toggleContent());
        HBox top = new HBox(8, contentType, refresh, install, toggle); VBox.setVgrow(contentList, Priority.ALWAYS);
        Label note = new Label("JAR dosyaları sunucu klasörüne kopyalanır. Değişikliklerin uygulanması için sunucuyu yeniden başlat."); note.getStyleClass().add("muted");
        return page(card("EKLENTİ VE MOD YÖNETİCİSİ", top, note, contentList));
    }

    private Node worldsView() {
        worldList.setCellFactory(list -> new ListCell<>() { protected void updateItem(Path value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : value.getFileName().toString()); } });
        Button refresh = button("Yenile", "secondary"), backup = button("Dünyaları Yedekle", "primary"), restore = button("Seçileni Geri Yükle", "danger");
        refresh.setOnAction(event -> refreshWorlds()); backup.setOnAction(event -> backupWorlds()); restore.setOnAction(event -> restoreWorld());
        TextField newName = new TextField(); newName.setPromptText("Yeni dünya adı"); Button create = button("Yeni Dünyayı Ayarla", "secondary"); create.setOnAction(event -> createWorld(newName.getText().trim()));
        HBox top = new HBox(8, refresh, backup, restore); HBox createRow = new HBox(8, newName, create); HBox.setHgrow(newName, Priority.ALWAYS); VBox.setVgrow(worldList, Priority.ALWAYS);
        Label note = new Label("Geri yükleme sadece sunucu kapalıyken yapılır; mevcut dünya silinmez, tarihli bir kurtarma klasörüne taşınır."); note.getStyleClass().add("muted");
        return page(card("DÜNYA YÖNETİMİ", top, note, worldList, createRow));
    }

    private Node automationView() {
        scheduledAction.getItems().setAll("Yedek al", "Yeniden başlat", "Sunucuyu durdur", "Duyuru gönder"); scheduledAction.getSelectionModel().selectFirst();
        Spinner<Integer> minutes = new Spinner<>(1, 10080, 30); TextField message = new TextField(); message.setPromptText("Duyuru metni");
        Button add = button("Görevi Planla", "primary"); add.setOnAction(event -> addJob(scheduledAction.getValue(), minutes.getValue(), message.getText().trim()));
        ListView<Job> jobList = new ListView<>(jobs); Button remove = button("Seçili Görevi Sil", "danger"); remove.setOnAction(event -> { Job selected = jobList.getSelectionModel().getSelectedItem(); if (selected != null) { jobs.remove(selected); saveJobs(); } });
        message.setPrefWidth(360); FlowPane jobRow = new FlowPane(8, 8, scheduledAction, new Label("dakika sonra"), minutes, message, add); jobRow.setAlignment(Pos.CENTER_LEFT); jobList.setPrefHeight(220); jobList.setMinHeight(130); jobList.setMaxHeight(280);
        Label taskNote = new Label("Buradaki görevler tek seferliktir. Sürekli Exaroton saat, kredi, çökme ve oyuncusuz-durdurma kuralları Exaroton Otomasyon Merkezi'nde yönetilir."); taskNote.setWrapText(true); taskNote.getStyleClass().add("muted");
        VBox jobsCard = card("TEK SEFERLİK GÖREV ZAMANLAYICISI", jobRow, taskNote, jobList, remove);

        automationScope.getStyleClass().add("metric"); automationScope.setWrapText(true); openExarotonAutomationButton.setOnAction(event -> openExarotonAutomation.run());
        VBox scope = card("OTOMASYON KAPSAMI", automationScope, openExarotonAutomationButton);
        memoryLimit.setEditable(true); restartOnCrash.setText("Yerel sunucu çökerse otomatik yeniden başlat"); FlowPane rules = new FlowPane(14, 9, restartOnCrash, notifyHighMemory, new Label("RAM sınırı (MB)"), memoryLimit); rules.setAlignment(Pos.CENTER_LEFT);
        Label localNote = new Label("Bu korumalar yalnızca bilgisayarındaki Yerel JAR sunucusuna uygulanır; Exaroton kurallarıyla çakışmaz."); localNote.setWrapText(true); localNote.getStyleClass().add("muted");
        localAutomationCard = card("YEREL SUNUCU KORUMASI", rules, localNote);
        webhook.setPromptText("https://discord.com/api/webhooks/..."); webhook.setPrefWidth(560); SecretFieldGuard.protect(webhook); discordName.setPromptText("Webhook görünen adı"); discordName.setPrefWidth(180); discordRoleId.setPromptText("Discord rol kimliği"); discordRoleId.setPrefWidth(210); discordState.getStyleClass().add("muted");
        discordRoleId.setDisable(!discordMentionCritical.isSelected()); discordMentionCritical.setOnAction(event -> discordRoleId.setDisable(!discordMentionCritical.isSelected()));
        Button test = button("Test Mesajı", "secondary"), saveDiscord = button("Ayarları Kaydet", "primary"), storeWebhook = button("Şifreli Sakla", "secondary");
        test.setOnAction(event -> sendDiscordTest()); saveDiscord.setOnAction(event -> saveDiscordPreferences(true)); storeWebhook.setOnAction(event -> storeDiscordWebhook()); discordLoadSaved.setOnAction(event -> loadDiscordWebhook()); discordDeleteSaved.setOnAction(event -> deleteDiscordWebhook()); discordLoadSaved.setDisable(!DiscordWebhookStore.exists()); discordDeleteSaved.setDisable(!DiscordWebhookStore.exists());
        FlowPane connection = new FlowPane(8, 8, discordEnabled, webhook, test, storeWebhook, discordLoadSaved, discordDeleteSaved); connection.setAlignment(Pos.CENTER_LEFT);
        FlowPane eventFilters = new FlowPane(14, 9, discordStatus, discordCrash, discordPlayers, discordPerformance, discordMaintenance, discordAutomation);
        FlowPane identity = new FlowPane(9, 9, new Label("Görünen ad"), discordName, discordMentionCritical, discordRoleId, saveDiscord); identity.setAlignment(Pos.CENTER_LEFT);
        Label discordNote = new Label("Bildirimler renkli embed olarak gönderilir. Oyuncu ve konsol metinlerinin rol veya @everyone etiketi oluşturması engellenir. Webhook yalnızca ana parolayla şifrelenerek kalıcı saklanabilir."); discordNote.setWrapText(true); discordNote.getStyleClass().add("muted");
        VBox notifications = card("DISCORD BİLDİRİM MERKEZİ", connection, eventFilters, identity, discordState, discordNote); discordViewBuilt = true; updateDiscordVaultPrompt(); if (config.isAutomaticCredentialVaultEnabled()) Platform.runLater(this::loadAutomaticDiscordWebhook);
        Button maintenance = button("BAKIM MODUNU BAŞLAT", "danger"); maintenance.setOnAction(event -> maintenanceMode());
        maintenanceNote.setWrapText(true); maintenanceNote.getStyleClass().add("muted"); VBox maintenanceCard = card("BAKIM MODU", maintenance, maintenanceNote);
        VBox page = new VBox(14, scope, jobsCard, localAutomationCard, notifications, maintenanceCard); page.setPadding(new Insets(18)); updateAutomationProviderUi(); return page;
    }

    private void updateAutomationProviderUi() {
        if (localAutomationCard == null) return; boolean remote = isRemote();
        automationScope.setText(remote ? "Exaroton seçili • Sürekli kurallar Sunucular → Exaroton → Otomasyon Merkezi'nde tek yerden yönetilir." : "Yerel JAR seçili • Yerel görevler ve korumalar bu sayfadan yönetilir.");
        openExarotonAutomationButton.setManaged(remote); openExarotonAutomationButton.setVisible(remote); localAutomationCard.setManaged(!remote); localAutomationCard.setVisible(!remote);
        String selected = scheduledAction.getValue(); if (remote) scheduledAction.getItems().setAll("Yeniden başlat", "Sunucuyu durdur", "Duyuru gönder"); else scheduledAction.getItems().setAll("Yedek al", "Yeniden başlat", "Sunucuyu durdur", "Duyuru gönder");
        if (selected != null && scheduledAction.getItems().contains(selected)) scheduledAction.setValue(selected); else scheduledAction.getSelectionModel().selectFirst();
        maintenanceNote.setText(remote ? "Exaroton sunucusunda whitelist açılır, oyuncular bilgilendirilir ve sunucu kapatılır. Exaroton API yedek oluşturmayı desteklemez." : "Yerel sunucuda whitelist açılır, oyuncular bilgilendirilir, dünya kaydedilir, yedek alınır ve sunucu güvenli şekilde kapatılır.");
    }

    private Node playersView() {
        TableView<PlayerProfile> table = new TableView<>(playerRows);
        table.getColumns().add(column("Oyuncu", p -> p.name)); table.getColumns().add(column("Son Görülme", p -> formatTime(p.lastSeen))); table.getColumns().add(column("Giriş", p -> Integer.toString(p.joins))); table.getColumns().add(column("Oyun Süresi", p -> durationText(p.totalSeconds + activeSeconds(p)))); table.getColumns().add(column("Ölüm", p -> Integer.toString(p.deaths))); table.getColumns().add(column("İlerleme", p -> Integer.toString(p.advancements)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN); VBox.setVgrow(table, Priority.ALWAYS);
        achievementPreview.getStyleClass().addAll("achievement-preview", "card"); achievementPreview.setPrefWidth(310); achievementPreview.setMinWidth(260);
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> updateAchievementCard(selected));
        if (!playerRows.isEmpty()) { table.getSelectionModel().selectFirst(); updateAchievementCard(playerRows.get(0)); } else updateAchievementCard(null);
        HBox body = new HBox(14, table, achievementPreview); HBox.setHgrow(table, Priority.ALWAYS); VBox.setVgrow(body, Priority.ALWAYS);
        Button clear = button("Geçmişi Temizle", "danger"); clear.setOnAction(event -> { if (confirm("Tüm oyuncu geçmişi temizlensin mi?")) { profiles.clear(); playerRows.clear(); savePlayers(); } });
        Label note = new Label("Veriler panel açıkken görülen giriş/çıkışlardan ve çevrimiçi oyuncu listesinden oluşturulur."); note.getStyleClass().add("muted");
        return page(card("OYUNCU BAŞARI KARTLARI", note, body, clear));
    }

    public void onConsole(String line) {
        if (isRemote()) return;
        analyzeConsole(line);
    }
    private void onRemoteConsole(String line) {
        if (!isRemote()) return;
        analyzeConsole(line);
    }
    private void analyzeConsole(String line) {
        synchronized (consoleHistory) { consoleHistory.addLast(line); while (consoleHistory.size() > 300) consoleHistory.removeFirst(); }
        incidentContext.recordConsole(line);
        captureSparkReport(line);
        if (sparkProfileRunning && SparkAnalysisEngine.commandRejected(line)) Platform.runLater(() -> failSparkAnalysis("Spark komutu tanınmadı. 'Spark Yoksa?' düğmesindeki kısa kurulumu uygula."));
        Matcher joined = JOIN.matcher(line), left = LEAVE.matcher(line), advancement = ADVANCEMENT.matcher(line), death = DEATH.matcher(line);
        if (joined.find()) Platform.runLater(() -> markJoined(joined.group(1)));
        if (left.find()) Platform.runLater(() -> markLeft(left.group(1)));
        if (advancement.find()) Platform.runLater(() -> markAdvancement(advancement.group(1)));
        if (death.find()) Platform.runLater(() -> markDeath(death.group(1)));
        String lower = line.toLowerCase(Locale.ROOT), explanation = null;
        if (lower.contains("outofmemoryerror") || lower.contains("java heap space")) explanation = "RAM YETERSİZ: Sunucu belleği tükendi. RAM sınırını yükselt veya ağır mod/eklenti sayısını azalt.";
        else if (lower.contains("failed to bind to port") || lower.contains("address already in use")) explanation = "PORT ÇAKIŞMASI: Aynı portu başka bir uygulama kullanıyor. Diğer sunucuyu kapat veya server-port değerini değiştir.";
        else if (lower.contains("could not load") && lower.contains("plugin")) explanation = "EKLENTİ YÜKLENEMEDİ: Eklenti sürümünü ve gerekli bağımlılıklarını kontrol et.";
        else if (lower.contains("modresolutionexception") || lower.contains("requires") && lower.contains("fabric")) explanation = "MOD BAĞIMLILIĞI: Bir mod eksik ya da uyumsuz bağımlılık istiyor. Hata satırındaki mod sürümlerini eşleştir.";
        else if (lower.contains("can't keep up") || lower.contains("server is overloaded") || lower.contains("a single server tick took")) { explanation = "PERFORMANS UYARISI: Sunucu tick'leri geride kalıyor. Görüş mesafesini azalt, RAM/CPU kullanımını ve ağır eklentileri kontrol et."; Platform.runLater(() -> { overloadWarnings++; overloadBurst++; lastOverloadWarningAt = Instant.now(); updateHealthAndCrisis(); }); }
        else if (lower.contains("exception") && !lower.contains("connection")) explanation = "JAVA HATASI: Ayrıntı için bu satırın hemen altındaki 'Caused by' bölümünü kontrol et: " + trim(line, 170);
        else if (lower.contains("crash report")) explanation = "ÇÖKME RAPORU: Sunucu bir crash-report oluşturdu. En son eklenen mod/eklenti ilk şüphelidir.";
        if (explanation != null) { String alertText = explanation, value = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + alertText; Platform.runLater(() -> { if (findings.isEmpty() || !findings.get(0).endsWith(value.substring(10))) { findings.add(0, value); recordEvent("Uyarı", alertText); } while (findings.size() > 100) findings.remove(findings.size() - 1); }); }
    }

    private void startSparkAnalysis() {
        if (!serverOnline) { showError("Lag Avcısı için seçili sunucu online olmalı."); return; }
        if (sparkProfileRunning) return;
        int seconds = SparkAnalysisEngine.durationSeconds(sparkDuration.getValue()); String command = SparkAnalysisEngine.profilerCommand(seconds);
        sparkStartButton.setDisable(true); sparkDuration.setDisable(true); sparkState.setText("Spark komutu sunucuya gönderiliyor...");
        if (isRemote()) {
            Task<Void> task = new Task<>() { protected Void call() throws Exception { exaroton.executeAdminCommand(command).join(); return null; } };
            task.setOnSucceeded(event -> beginSparkCountdown(seconds)); task.setOnFailed(event -> { resetSparkControls(); showError("Spark analizi başlatılamadı: " + rootMessage(task.getException())); }); run(task, "spark-analysis-start");
            return;
        }
        try { manager.command(command); beginSparkCountdown(seconds); }
        catch (Exception error) { resetSparkControls(); showError("Spark analizi başlatılamadı: " + rootMessage(error)); }
    }

    private void beginSparkCountdown(int seconds) {
        sparkProfileRunning = true; sparkDeadline = Instant.now().plusSeconds(seconds); sparkState.setText("Veri toplanıyor • " + seconds + " saniye kaldı"); sparkCountdown.playFromStart();
        recordEvent("Lag Avcısı", seconds + " saniyelik tek tık lag analizi başlatıldı.");
        findings.add(0, now() + "  LAG AVCISI: Veri toplama başladı; rapor otomatik yakalanacak.");
    }

    private void updateSparkCountdown() {
        if (!sparkProfileRunning || sparkDeadline == null) { sparkCountdown.stop(); return; }
        long remaining = java.time.Duration.between(Instant.now(), sparkDeadline).toSeconds();
        if (remaining > 0) sparkState.setText("Veri toplanıyor • " + remaining + " saniye kaldı • Sunucuyu normal kullanmaya devam et");
        else if (remaining >= -20) sparkState.setText("Analiz tamamlandı • Spark rapor bağlantısı bekleniyor...");
        else failSparkAnalysis("Rapor bağlantısı gelmedi. Spark kurulu değilse 'Spark Yoksa?' düğmesine bas.");
    }

    private void captureSparkReport(String line) {
        Optional<String> report = SparkAnalysisEngine.trustedReportUrl(line); if (report.isEmpty()) return;
        String url = report.get(); if (url.equals(latestSparkReport) && !sparkProfileRunning) return; latestSparkReport = url;
        recordEvent("Lag Avcısı", "Spark raporu hazırlandı: " + latestSparkReport);
        Platform.runLater(() -> { sparkProfileRunning = false; sparkDeadline = null; sparkCountdown.stop(); resetSparkControls(); if (sparkOpenButton != null) sparkOpenButton.setDisable(false); sparkState.setText("RAPOR HAZIR ✓ • Şimdi 'Hazır Raporu Aç'a bas"); findings.add(0, now() + "  LAG AVCISI: Spark raporu hazır ve güvenilir bağlantı doğrulandı."); DesktopNotifier.show(notificationSource(), "AeroMC Lag Analizi", "Spark raporu hazır. Kontrol Merkezi'nden açabilirsin."); });
    }

    private void openSparkReport() {
        if (latestSparkReport == null || latestSparkReport.isBlank()) { showError("Henüz yakalanmış bir Spark raporu yok."); return; }
        try { hostServices.showDocument(latestSparkReport); } catch (Exception error) { showError("Rapor açılamadı: " + rootMessage(error)); }
    }

    private void failSparkAnalysis(String message) { sparkProfileRunning = false; sparkDeadline = null; sparkCountdown.stop(); resetSparkControls(); sparkState.setText(message); recordEvent("Lag Avcısı", message); }
    private void cancelSparkWait(String message) { if (!sparkProfileRunning) return; failSparkAnalysis(message); }
    private void resetSparkControls() { if (sparkStartButton != null) sparkStartButton.setDisable(false); sparkDuration.setDisable(false); if (sparkOpenButton != null) sparkOpenButton.setDisable(latestSparkReport == null); }
    private void showSparkHelp() { info("Spark'ı hazırlamanın en kolay yolu", "Paper 1.21 ve üzerindeysen Spark genellikle sunucuyla birlikte gelir. Komut tanınmazsa Kontrol Merkezi → Tek Tık Mod Merkezi'ne gir, 'spark' ara, sunucuna yükle ve sunucuyu bir kez yeniden başlat. Sonra buraya dönüp yalnızca 'Lag Analizini Başlat'a bas."); }

    public void onState(boolean running, String text) {
        if (isRemote()) return;
        serverOnline = running;
        if (!running) cancelSparkWait("Sunucu kapandı • Lag analizi beklemesi durduruldu");
        if (running) autoRestarting = false;
        if (!running) for (String name : new HashSet<>(onlinePlayers)) markLeft(name);
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
        if (isRemote()) return;
        acceptPlayers(names);
    }
    private void acceptPlayers(List<String> names) {
        Set<String> incoming = new HashSet<>(names);
        for (String name : incoming) if (!onlinePlayers.contains(name)) { markJoined(name); recordEvent("Oyuncu", name + " katıldı"); sendDiscord(DiscordNotificationEngine.Type.PLAYER, "Oyuncu katıldı", name + " sunucuya katıldı.", false); }
        for (String name : new HashSet<>(onlinePlayers)) if (!incoming.contains(name)) { markLeft(name); recordEvent("Oyuncu", name + " ayrıldı"); }
        onlinePlayers.clear(); onlinePlayers.addAll(incoming); playerValue.setText(Integer.toString(incoming.size())); refreshPlayerRows();
    }

    private boolean isRemote() { return "Exaroton".equals(provider.getValue()); }
    private void updateProviderState() {
        if (provider.getValue() == null) return;
        serverOnline = isRemote() ? remoteSnapshot != null && remoteSnapshot.online() : manager.isRunning();
        providerState.setText(isRemote() ? exaroton.getActiveServerName() : (serverOnline ? "Yerel sunucu online" : "Yerel sunucu kapalı")); updateHealthAndCrisis();
    }
    private void acceptRemoteSnapshot(ExarotonPane.ProSnapshot snapshot) {
        remoteSnapshot = snapshot; if (!isRemote()) return;
        serverOnline = snapshot.online();
        if (!snapshot.online()) cancelSparkWait("Exaroton sunucusu kapandı • Lag analizi beklemesi durduruldu");
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
        if (!isRemote()) return;
        boolean received = false;
        if (Double.isFinite(metrics.tps())) { currentTps = metrics.tps(); cpuValue.setText(String.format(Locale.US, "TPS %.1f • %.1f ms", metrics.tps(), metrics.averageTickTime())); addPoint(cpuSeries, sampleIndex, metrics.tps()); received = true; }
        if (Double.isFinite(metrics.memoryPercent())) { double percent = metrics.memoryPercent() <= 1.0 ? metrics.memoryPercent() * 100.0 : metrics.memoryPercent(); currentRamPercent = percent; memoryValue.setText(String.format(Locale.US, "%.1f%% kullanım", percent)); addPoint(memorySeries, sampleIndex, percent); received = true; }
        if (received) { remoteMetricsReceived = true; sampleIndex++; recordPerformanceSample(); updateHealthAndCrisis(); }
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

    private void enterCrisis(String reason, boolean manual) {
        if (crisisActive || crisisTransitioning) return;
        if (!serverOnline) { showError("Kriz Modu için seçili sunucu online olmalı."); return; }
        crisisActive = true; crisisManual = manual; crisisTransitioning = true; crisisNeedsMetricsForRecovery = Double.isFinite(currentTps) || Double.isFinite(currentRamPercent); crisisProvider = provider.getValue(); provider.setDisable(true); crisisDangerSince = crisisRecoverySince = null; scheduler.pause(); crisisState.setText("Kriz önlemleri uygulanıyor • " + reason); crisisState.getStyleClass().add("crisis-active");
        findings.add(0, now() + "  KRİZ MODU: " + reason + ". Otomatik ağır görevler durduruldu."); recordEvent("Kriz Modu", "Etkinleştirildi: " + reason);
        Task<Void> task = new Task<>() { protected Void call() throws Exception { try { applyCrisisSettings(); return null; } catch (Exception error) { try { restoreCrisisSettings(); } catch (Exception restoreError) { error.addSuppressed(restoreError); } throw error; } } };
        task.setOnSucceeded(event -> { crisisTransitioning = false; crisisState.setText("ETKİN • " + reason + (manual ? " • Manuel kontrol" : "")); DesktopNotifier.show(notificationSource(), "AeroMC Kriz Modu", "Sunucuyu rahatlatan güvenli önlemler uygulandı."); sendDiscord(DiscordNotificationEngine.Type.PERFORMANCE, "Kriz Modu etkinleştirildi", reason, true); });
        task.setOnFailed(event -> { crisisActive = crisisManual = crisisTransitioning = crisisNeedsMetricsForRecovery = false; crisisLastExit = Instant.now(); provider.setDisable(false); scheduler.play(); crisisState.setText("Kriz Modu etkinleştirilemedi"); crisisState.getStyleClass().remove("crisis-active"); String error = rootMessage(task.getException()); findings.add(0, now() + "  KRİZ UYARISI: Önlemler geri alındı: " + error); recordEvent("Kriz Modu", "Etkinleştirme başarısız: " + error); }); run(task, "crisis-enter");
    }

    private void applyCrisisSettings() throws Exception {
        if (isCrisisRemote()) {
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
        task.setOnSucceeded(event -> { crisisActive = crisisManual = crisisTransitioning = crisisNeedsMetricsForRecovery = false; crisisLastExit = Instant.now(); crisisDangerSince = crisisRecoverySince = null; provider.setDisable(false); scheduler.play(); crisisState.setText(reason + " • Kriz Modu kapatıldı"); crisisState.getStyleClass().remove("crisis-active"); recordEvent("Kriz Modu", reason); findings.add(0, now() + "  KRİZ MODU: Normal ayarlar geri yüklendi, otomatik görevler devam ediyor."); sendDiscord(DiscordNotificationEngine.Type.PERFORMANCE, "Kriz Modu kapandı", reason + ". Normal ayarlar geri yüklendi.", false); });
        task.setOnFailed(event -> { crisisTransitioning = false; String error = rootMessage(task.getException()); crisisState.setText("ETKİN • Ayarlar geri yüklenemedi; Krizden Çık ile yeniden dene"); findings.add(0, now() + "  KRİZ UYARISI: Ayarlar geri yüklenemedi: " + error); recordEvent("Kriz Modu", "Çıkış başarısız: " + error); }); run(task, "crisis-exit");
    }

    private void restoreCrisisSettings() throws Exception {
        if (isCrisisRemote()) {
            if (!crisisOriginalRemote.isEmpty()) exaroton.saveServerOptions(new LinkedHashMap<>(crisisOriginalRemote)).join();
            if (exaroton.hasActiveServer()) exaroton.executeAdminCommand("gamerule randomTickSpeed 3").join(); crisisOriginalRemote.clear(); crisisProvider = null; return;
        }
        Path folder = manager.getServerFolder(); if (folder != null && !crisisOriginalLocal.isEmpty()) { Path properties = folder.resolve("server.properties"); for (var entry : crisisOriginalLocal.entrySet()) updateProperty(properties, entry.getKey(), entry.getValue()); }
        if (manager.isRunning()) manager.command("gamerule randomTickSpeed 3"); crisisOriginalLocal.clear(); crisisProvider = null;
    }

    private boolean isCrisisRemote() { return "Exaroton".equals(crisisProvider); }
    private long elapsedSeconds(Instant start, Instant end) { return start == null ? 0 : Math.max(0, java.time.Duration.between(start, end).toSeconds()); }

    private void createIncident(String state, boolean crashed) {
        List<String> history; synchronized (consoleHistory) { history = new ArrayList<>(consoleHistory); }
        CrashDoctor.Diagnosis diagnosis = CrashDoctor.diagnose(history); String actions = String.join(" → ", diagnosis.actions());
        IncidentContext.Report context = incidentContext.report(Instant.now());
        String report = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) + "  " + (crashed ? "ÇÖKME" : "KAPANMA") + " • " + context.detail() + "  |  Tanı: " + diagnosis.summary() + "  |  Kanıt: " + diagnosis.evidence() + "  |  Çözüm: " + actions;
        crashReports.add(0, report);
        while (crashReports.size() > 30) crashReports.remove(crashReports.size() - 1);
        recordEvent(crashed ? "Çökme Vakası" : "Kapanma Vakası", state + " • " + context.detail() + " • Tanı: " + diagnosis.summary());
    }

    private void recordPerformanceSample() {
        Instant time = Instant.now(); incidentContext.recordMetric(time, currentTps, currentRamPercent, currentCpuPercent); thresholdAdvisor.record(time, notificationSource(), currentTps, currentRamPercent);
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
    private void probeLocalLatency() {
        Path folder = manager.getServerFolder(); if (folder == null) return; Properties values = loadProperties(folder.resolve("server.properties")); String port = values.getProperty("server-port", "25565"); probeLatency("127.0.0.1:" + port);
    }
    private void probeLatency(String address) {
        if (latencyProbeRunning || address == null || address.isBlank()) return; latencyProbeRunning = true;
        Task<Long> task = new Task<>() { protected Long call() throws Exception { return MinecraftPing.ping(address).latencyMs(); } };
        task.setOnSucceeded(event -> { latencyProbeRunning = false; currentLatencyMs = task.getValue(); latencyValue.setText(task.getValue() + " ms"); updateHealthAndCrisis(); });
        task.setOnFailed(event -> { latencyProbeRunning = false; currentLatencyMs = Double.NaN; latencyValue.setText("-"); updateHealthAndCrisis(); }); run(task, "health-latency");
    }
    private void resetProcessSample() { lastPid = -1; lastCpuNanos = -1; lastSampleNanos = -1; currentMemoryMb = 0; currentTps = currentRamPercent = currentCpuPercent = currentLatencyMs = Double.NaN; latencyValue.setText("-"); }
    private int readMemoryMb(long pid) {
        Path status = Path.of("/proc", Long.toString(pid), "status"); if (!Files.isReadable(status)) return -1;
        try { for (String line : Files.readAllLines(status)) if (line.startsWith("VmRSS:")) return Integer.parseInt(line.replaceAll("[^0-9]", "")) / 1024; } catch (Exception ignored) { } return -1;
    }

    private void loadSelectedFile() {
        if (isRemote()) {
            String name = fileChoice.getValue(); if (!SAFE_FILES.contains(name)) return;
            fileState.setText("Exaroton'dan yükleniyor..."); Task<String> task = new Task<>() { protected String call() throws Exception { return exaroton.readRemoteFile("/" + name).join(); } };
            task.setOnSucceeded(event -> { fileEditor.setText(task.getValue()); fileState.setText("Exaroton'dan yüklendi"); }); task.setOnFailed(event -> { fileState.setText("Yüklenemedi"); showError(rootMessage(task.getException())); }); run(task, "exaroton-file-load"); return;
        }
        Path file = safeSelectedFile(); if (file == null) return;
        try { fileEditor.setText(Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : ""); fileState.setText(Files.exists(file) ? "Yüklendi" : "Yeni dosya"); } catch (IOException error) { showError(error.getMessage()); }
    }
    private void saveSelectedFile() {
        if (isRemote()) {
            String name = fileChoice.getValue(); if (!SAFE_FILES.contains(name) || !confirm(name + " Exaroton sunucusunda güncellensin mi?")) return;
            fileState.setText("Exaroton'a kaydediliyor..."); Task<Void> task = new Task<>() { protected Void call() throws Exception { exaroton.writeRemoteFile("/" + name, fileEditor.getText()).join(); return null; } };
            task.setOnSucceeded(event -> fileState.setText("Exaroton'a kaydedildi")); task.setOnFailed(event -> { fileState.setText("Kaydedilemedi"); showError(rootMessage(task.getException())); }); run(task, "exaroton-file-save"); return;
        }
        Path file = safeSelectedFile(); if (file == null) return;
        try { Files.createDirectories(file.getParent()); if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING); atomicWrite(file, fileEditor.getText()); fileState.setText("Kaydedildi: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))); } catch (IOException error) { showError(error.getMessage()); }
    }
    private Path safeSelectedFile() {
        Path folder = manager.getServerFolder(); String name = fileChoice.getValue(); if (folder == null) { showError("Önce Yerel JAR sekmesinden server.jar seç."); return null; } if (!SAFE_FILES.contains(name)) { showError("Bu dosyanın düzenlenmesine izin verilmiyor."); return null; } try { return SafePathGuard.resolve(folder, name, true); } catch (IOException error) { showError(error.getMessage()); return null; }
    }
    private void openServerFolder() { if (isRemote()) { showError("Exaroton dosyaları uzakta olduğu için yerel klasör açılamaz; dosya editörünü kullanabilirsin."); return; } Path folder = manager.getServerFolder(); if (folder == null) showError("Önce bir server.jar seç."); else try { hostServices.showDocument(folder.toUri().toString()); } catch (Exception error) { showError("Klasör açılamadı: " + error.getMessage()); } }

    private Path contentFolder() { Path root = manager.getServerFolder(); if (root == null) return null; try { return SafePathGuard.resolve(root, contentType.getSelectionModel().getSelectedIndex() == 1 ? "mods" : "plugins", true); } catch (IOException error) { showError(error.getMessage()); return null; } }
    private void refreshContent() {
        if (isRemote()) { String directory = contentType.getSelectionModel().getSelectedIndex() == 1 ? "/mods" : "/plugins"; Task<List<String>> task = new Task<>() { protected List<String> call() throws Exception { return exaroton.listRemoteDirectory(directory).join(); } }; task.setOnSucceeded(event -> contentList.getItems().setAll(task.getValue().stream().filter(name -> name.endsWith(".jar") || name.endsWith(".jar.disabled")).map(Path::of).toList())); task.setOnFailed(event -> contentList.getItems().clear()); run(task, "exaroton-content-list"); return; }
        Path folder = contentFolder(); if (folder == null) { contentList.getItems().clear(); return; } try { Files.createDirectories(folder); try (var files = Files.list(folder)) { contentList.getItems().setAll(files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && (path.getFileName().toString().endsWith(".jar") || path.getFileName().toString().endsWith(".jar.disabled"))).sorted().toList()); } } catch (IOException error) { showError(error.getMessage()); }
    }
    private void importContent() {
        Path folder = contentFolder(); if (!isRemote() && folder == null) { showError("Önce Yerel JAR sekmesinden server.jar seç."); return; } if (isRemote() && !exaroton.hasActiveServer()) { showError("Önce Exaroton sekmesinden bir sunucu seç."); return; }
        FileChooser chooser = new FileChooser(); chooser.setTitle("Eklenti veya mod JAR dosyası seç"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java arşivi", "*.jar")); File selected = chooser.showOpenDialog(contentList.getScene().getWindow()); if (selected == null) return;
        if (isRemote()) { String directory = contentType.getSelectionModel().getSelectedIndex() == 1 ? "/mods" : "/plugins"; Task<Void> task = new Task<>() { protected Void call() throws Exception { exaroton.uploadRemoteFile(directory, selected.toPath()).join(); return null; } }; task.setOnSucceeded(event -> { refreshContent(); info("Yükleme tamamlandı", selected.getName() + " Exaroton'a gönderildi."); }); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "exaroton-content-upload"); return; }
        try { Files.createDirectories(folder); Path target = SafePathGuard.requireWithin(manager.getServerFolder(), folder.resolve(selected.getName()), true); Files.copy(selected.toPath(), target, StandardCopyOption.REPLACE_EXISTING); refreshContent(); } catch (IOException error) { showError(error.getMessage()); }
    }
    private void toggleContent() { if (isRemote()) { showError("Exaroton API dosya yeniden adlandırmayı desteklemiyor. Dosya yükleme çalışır; devre dışı bırakma işlemini Exaroton panelinden yapabilirsin."); return; } Path selected = contentList.getSelectionModel().getSelectedItem(); if (selected == null) return; String name = selected.getFileName().toString(); Path target = selected.resolveSibling(isDisabled(selected) ? name.substring(0, name.length() - ".disabled".length()) : name + ".disabled"); try { Path root = manager.getServerFolder(); Files.move(SafePathGuard.requireWithin(root, selected, false), SafePathGuard.requireWithin(root, target, true)); refreshContent(); } catch (IOException error) { showError(error.getMessage()); } }
    private boolean isDisabled(Path path) { return path.getFileName().toString().endsWith(".disabled"); }

    private void refreshWorlds() { if (isRemote()) { Task<List<String>> task = new Task<>() { protected List<String> call() throws Exception { return exaroton.listRemoteDirectory("/").join(); } }; task.setOnSucceeded(event -> worldList.getItems().setAll(task.getValue().stream().filter(name -> name.endsWith("/")).map(name -> name.substring(0, name.length() - 1)).filter(name -> name.equals("world") || name.endsWith("_nether") || name.endsWith("_the_end")).map(Path::of).toList())); task.setOnFailed(event -> worldList.getItems().clear()); run(task, "exaroton-world-list"); return; } Path folder = manager.getServerFolder(); if (folder == null) { worldList.getItems().clear(); return; } try (var dirs = Files.list(folder)) { worldList.getItems().setAll(dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)).filter(path -> { String n = path.getFileName().toString(); return n.equals("world") || n.endsWith("_nether") || n.endsWith("_the_end") || Files.exists(path.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS); }).sorted().toList()); } catch (IOException error) { showError(error.getMessage()); } }
    private void backupWorlds() { if (isRemote()) { showError("Exaroton resmî API'si yedek oluşturma uç noktası sunmuyor. Diğer Pro işlemleri bağlı; yedeği Exaroton panelinden başlatmalısın."); return; } runTask("world-backup", manager::createBackup, path -> { recordEvent("Yedek", "Dünya yedeği hazırlandı: " + path.getFileName()); DesktopNotifier.show(notificationSource(), "AeroMC", "Dünya yedeği hazır."); sendDiscord(DiscordNotificationEngine.Type.BACKUP, "Yedek hazır", path.getFileName() + " başarıyla oluşturuldu.", false); info("Yedek hazır", path.toString()); }); }
    private void createWorld(String name) {
        if (isRemote()) { showError("Exaroton'da yeni dünya yükleme resmî API tarafından sunulmuyor; server.properties içindeki level-name alanını Pro Dosyalar bölümünden değiştirebilirsin."); return; }
        if (!name.matches("[A-Za-z0-9_-]{1,40}")) { showError("Dünya adı yalnızca harf, rakam, _ ve - içerebilir."); return; }
        if (manager.isRunning()) { showError("Yeni dünya seçmeden önce sunucuyu durdur."); return; }
        Path folder = manager.getServerFolder(); if (folder == null) { showError("Önce server.jar seç."); return; }
        try { Path properties = SafePathGuard.resolve(folder, "server.properties", true); updateProperty(properties, "level-name", name); info("Dünya ayarlandı", name + " sunucu sonraki açılışta oluşturulacak."); refreshWorlds(); } catch (IOException error) { showError(error.getMessage()); }
    }
    private void restoreWorld() {
        if (isRemote()) { showError("Exaroton API dünya ZIP geri yüklemesine izin vermiyor. Bu işlem Exaroton web panelinden yapılmalı."); return; }
        Path selectedWorld = worldList.getSelectionModel().getSelectedItem(); if (selectedWorld == null) { showError("Geri yüklenecek dünyayı seç."); return; } if (manager.isRunning()) { showError("Geri yüklemeden önce sunucuyu durdur."); return; }
        FileChooser chooser = new FileChooser(); chooser.setTitle("AeroMC ZIP yedeğini seç"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP yedeği", "*.zip")); File zip = chooser.showOpenDialog(worldList.getScene().getWindow()); if (zip == null || !confirm(selectedWorld.getFileName() + " seçilen yedekten geri yüklensin mi?")) return;
        Task<Void> task = new Task<>() { protected Void call() throws Exception { restoreWorldZip(zip.toPath(), selectedWorld); return null; } }; task.setOnSucceeded(event -> { refreshWorlds(); info("Geri yükleme tamamlandı", "Eski dünya kurtarma klasörüne taşındı."); }); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "world-restore");
    }
    private void restoreWorldZip(Path zipFile, Path world) throws IOException {
        Path root = manager.getServerFolder(); if (root == null) throw new IOException("Sunucu klasörü bulunamadı."); root = root.toRealPath(LinkOption.NOFOLLOW_LINKS); world = SafePathGuard.requireWithin(root, world, false);
        if (!world.getParent().equals(root)) throw new IOException("Yalnızca sunucu kökündeki dünyalar geri yüklenebilir.");
        String worldName = world.getFileName().toString(), prefix = worldName + "/"; Path staging = SafePathGuard.resolve(root, ".aeromc-restore-" + UUID.randomUUID(), true), recovery = SafePathGuard.resolve(root, worldName + ".pre-restore-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), true); Files.createDirectory(staging);
        boolean extracted = false, movedOriginal = false; long total = 0; int entries = 0;
        try {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > 100_000) throw new IOException("ZIP çok fazla dosya içeriyor.");
                    String name = entry.getName().replace('\\', '/'); if (!name.startsWith(prefix)) continue;
                    Path target = SafePathGuard.requireWithin(staging, staging.resolve(name).normalize(), true);
                    if (entry.isDirectory()) Files.createDirectories(target);
                    else { Files.createDirectories(target.getParent()); total += copyZipLimited(zip, target, 20L * 1024 * 1024 * 1024 - total); }
                    extracted = true;
                }
            }
            Path stagedWorld = SafePathGuard.requireWithin(staging, staging.resolve(worldName), false); if (!extracted || !Files.isDirectory(stagedWorld, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Yedekte " + worldName + " bulunamadı.");
            Files.move(world, recovery); movedOriginal = true; Files.move(stagedWorld, world); movedOriginal = false;
        } catch (IOException error) {
            if (movedOriginal && !Files.exists(world, LinkOption.NOFOLLOW_LINKS) && Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) Files.move(recovery, world);
            throw error;
        } finally { deleteStaging(staging); }
    }
    private long copyZipLimited(InputStream input, Path target, long remaining) throws IOException { if (remaining <= 0) throw new IOException("ZIP açılmış boyutu 20 GiB sınırını aşıyor."); long total = 0; byte[] buffer = new byte[64 * 1024]; try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) { int count; while ((count = input.read(buffer)) >= 0) { total += count; if (total > remaining) throw new IOException("ZIP açılmış boyutu 20 GiB sınırını aşıyor."); output.write(buffer, 0, count); } } return total; }
    private void deleteStaging(Path staging) { try { if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return; try (var paths = Files.walk(staging)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } } catch (IOException ignored) { } }

    private void addJob(String action, int minutes, String message) { if (action.equals("Duyuru gönder") && message.isBlank()) { showError("Duyuru metnini gir."); return; } jobs.add(new Job(UUID.randomUUID().toString(), LocalDateTime.now().plusMinutes(minutes), action, message)); jobs.sort(Comparator.comparing(job -> job.due)); saveJobs(); recordEvent("Otomasyon", action + " görevi " + minutes + " dakika sonrası için planlandı."); }
    private void runDueJobs() { List<Job> due = jobs.stream().filter(job -> !job.due.isAfter(LocalDateTime.now())).toList(); for (Job job : due) { executeJob(job); jobs.remove(job); } if (!due.isEmpty()) saveJobs(); }
    private void executeJob(Job job) {
        recordEvent("Otomasyon", "Planlanan görev çalıştırıldı: " + job.action);
        switch (job.action) { case "Yedek al" -> backupWorlds(); case "Yeniden başlat" -> restartConfigured(); case "Sunucuyu durdur" -> { if (isRemote()) runRemoteAction("Exaroton durdurma", exaroton::stopActiveServer); else manager.stop(); } case "Duyuru gönder" -> { if (isRemote()) runRemoteAction("Exaroton duyuru", () -> exaroton.executeAdminCommand("say " + job.message)); else try { manager.command("say " + job.message); } catch (IOException error) { showError(error.getMessage()); } } default -> { } }
        sendDiscord(DiscordNotificationEngine.Type.AUTOMATION, "Zamanlanmış görev tetiklendi", job.action + (job.message.isBlank() ? "" : " • " + job.message), false);
        findings.add(0, now() + "  ZAMANLAYICI: " + job.action + " görevi çalıştırıldı.");
    }
    private void restartConfigured() { if (isRemote()) { runRemoteAction("Exaroton yeniden başlatma", exaroton::restartActiveServer); return; } Path jar = config.getServerJar(); if (jar == null || !Files.isRegularFile(jar)) { Platform.runLater(() -> showError("Yeniden başlatmak için Yerel JAR seçimi bulunamadı.")); return; } manager.restart(jar, config.getMemoryMb()); }
    private void maintenanceMode() {
        if (isRemote()) {
            if (!exaroton.hasActiveServer()) { showError("Önce Exaroton sekmesinden bir sunucu seç."); return; } if (!confirm("Exaroton sunucusunda whitelist açılıp duyuru gönderildikten sonra sunucu kapatılsın mı? Yedekleme API tarafından desteklenmiyor.")) return;
            Task<Void> remote = new Task<>() { protected Void call() throws Exception { exaroton.executeAdminCommand("whitelist on").join(); exaroton.executeAdminCommand("say Sunucu bakım moduna giriyor. Lütfen güvenli şekilde çıkış yapın.").join(); exaroton.stopActiveServer().join(); return null; } };
            remote.setOnSucceeded(event -> { findings.add(0, now() + "  BAKIM: Exaroton whitelist açıldı ve sunucu kapatıldı."); sendDiscord(DiscordNotificationEngine.Type.MAINTENANCE, "Exaroton bakım modu", "Whitelist açıldı, oyuncular bilgilendirildi ve sunucu kapatıldı.", false); }); remote.setOnFailed(event -> showError(rootMessage(remote.getException()))); run(remote, "exaroton-maintenance"); return;
        }
        if (!manager.isRunning()) { showError("Bakım modu için yerel sunucu çalışıyor olmalı."); return; } if (!confirm("Bakım modu sunucuyu yedekleyip kapatacak. Devam edilsin mi?")) return;
        Task<Path> task = new Task<>() { protected Path call() throws Exception { manager.command("whitelist on"); manager.command("say Sunucu bakım moduna giriyor. Lütfen güvenli şekilde çıkış yapın."); manager.command("save-all flush"); Thread.sleep(1500); Path backup = manager.createBackup(); manager.stop(); return backup; } };
        task.setOnSucceeded(event -> { findings.add(0, now() + "  BAKIM: Whitelist açıldı, yedek alındı ve sunucu kapatıldı."); DesktopNotifier.show(notificationSource(), "AeroMC", "Bakım modu tamamlandı."); sendDiscord(DiscordNotificationEngine.Type.MAINTENANCE, "Bakım modu tamamlandı", "Whitelist açıldı, yedek alındı ve yerel sunucu güvenli şekilde kapatıldı.", false); }); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "maintenance-mode");
    }

    private void markJoined(String name) { if (onlinePlayers.contains(name)) return; PlayerProfile profile = profiles.computeIfAbsent(name, PlayerProfile::new); long now = Instant.now().getEpochSecond(); if (profile.firstSeen == 0) profile.firstSeen = now; profile.lastSeen = now; profile.joins++; profile.activeSince = now; onlinePlayers.add(name); refreshPlayerRows(); savePlayers(); }
    private void markLeft(String name) { PlayerProfile profile = profiles.get(name); if (profile == null) return; long now = Instant.now().getEpochSecond(); if (profile.activeSince > 0) profile.totalSeconds += Math.max(0, now - profile.activeSince); profile.activeSince = 0; profile.lastSeen = now; onlinePlayers.remove(name); refreshPlayerRows(); savePlayers(); }
    private void markDeath(String name) { PlayerProfile profile = profiles.computeIfAbsent(name, PlayerProfile::new); profile.deaths++; profile.lastSeen = Instant.now().getEpochSecond(); refreshPlayerRows(); updateAchievementCard(profile); savePlayers(); }
    private void markAdvancement(String name) { PlayerProfile profile = profiles.computeIfAbsent(name, PlayerProfile::new); profile.advancements++; profile.lastSeen = Instant.now().getEpochSecond(); refreshPlayerRows(); updateAchievementCard(profile); savePlayers(); }
    private long activeSeconds(PlayerProfile profile) { return profile.activeSince <= 0 ? 0 : Math.max(0, Instant.now().getEpochSecond() - profile.activeSince); }
    private void refreshPlayerRows() { playerRows.setAll(profiles.values()); }
    private void loadPlayers() { Properties data = loadProperties(PLAYER_FILE); for (String name : data.stringPropertyNames()) { try { String[] parts = data.getProperty(name).split(","); PlayerProfile p = new PlayerProfile(name); p.firstSeen = Long.parseLong(parts[0]); p.lastSeen = Long.parseLong(parts[1]); p.joins = Integer.parseInt(parts[2]); p.totalSeconds = Long.parseLong(parts[3]); p.deaths = parts.length > 4 ? Integer.parseInt(parts[4]) : 0; p.advancements = parts.length > 5 ? Integer.parseInt(parts[5]) : 0; profiles.put(name, p); } catch (Exception ignored) { } } refreshPlayerRows(); }
    private void savePlayers() { Properties data = new Properties(); profiles.forEach((name, p) -> data.setProperty(name, p.firstSeen + "," + p.lastSeen + "," + p.joins + "," + (p.totalSeconds + activeSeconds(p)) + "," + p.deaths + "," + p.advancements)); saveProperties(PLAYER_FILE, data, "AeroMC player history"); }

    private void updateAchievementCard(PlayerProfile profile) {
        achievementPreview.getChildren().clear();
        Label heading = new Label("OYUNCU BAŞARI KARTI"); heading.getStyleClass().add("section-title"); achievementPreview.getChildren().add(heading);
        if (profile == null) { Label empty = new Label("Kartını görmek için tablodan bir oyuncu seç."); empty.setWrapText(true); empty.getStyleClass().add("muted"); achievementPreview.getChildren().add(empty); return; }
        long seconds = profile.totalSeconds + activeSeconds(profile), hours = seconds / 3600;
        int score = Math.min(9999, profile.joins * 10 + (int) hours * 5 + profile.advancements * 20 + Math.min(250, profile.deaths * 2));
        String rank = hours >= 40 || score >= 1000 ? "SUNUCU EFSANESİ" : hours >= 15 || score >= 500 ? "USTA OYUNCU" : hours >= 5 || score >= 180 ? "MACERACI" : "YENİ KAHRAMAN";
        Label name = new Label(profile.name); name.getStyleClass().add("achievement-name"); Label title = new Label(rank); title.getStyleClass().add("achievement-rank");
        Label stats = new Label("★ " + score + " puan\n" + profile.joins + " giriş  •  " + durationText(seconds) + "\n" + profile.advancements + " ilerleme  •  " + profile.deaths + " ölüm"); stats.getStyleClass().add("achievement-stats");
        FlowPane badges = new FlowPane(7, 7); for (String badge : achievements(profile, hours)) { Label label = new Label(badge); label.getStyleClass().add("achievement-badge"); badges.getChildren().add(label); }
        Button copy = button("Kart Metnini Kopyala", "secondary"); copy.setOnAction(event -> { javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent(); content.putString("AeroMC • " + profile.name + "\n" + rank + " • " + score + " puan\n" + durationText(seconds) + " • " + profile.joins + " giriş • " + profile.advancements + " ilerleme\n" + String.join(" • ", achievements(profile, hours))); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); });
        achievementPreview.getChildren().addAll(name, title, stats, badges, copy);
    }

    private List<String> achievements(PlayerProfile profile, long hours) {
        List<String> result = new ArrayList<>(); result.add("İlk Adım"); if (profile.joins >= 5) result.add("Sadık Oyuncu"); if (profile.joins >= 25) result.add("Müdavim"); if (hours >= 5) result.add("Uzun Soluklu"); if (hours >= 25) result.add("Maratoncu"); if (profile.advancements >= 5) result.add("Kaşif"); if (profile.advancements >= 20) result.add("Başarım Avcısı"); if (profile.deaths == 0 && hours >= 2) result.add("Hayatta Kalan"); return result;
    }
    private void loadJobs() { Properties data = loadProperties(JOB_FILE); for (String id : data.stringPropertyNames()) { try { String[] p = data.getProperty(id).split("\\|", 3); LocalDateTime due = LocalDateTime.parse(p[0]); if (due.isAfter(LocalDateTime.now())) jobs.add(new Job(id, due, p[1], new String(Base64.getDecoder().decode(p.length > 2 ? p[2] : ""), StandardCharsets.UTF_8))); } catch (Exception ignored) { } } jobs.sort(Comparator.comparing(job -> job.due)); }
    private void saveJobs() { Properties data = new Properties(); for (Job job : jobs) data.setProperty(job.id, job.due + "|" + job.action + "|" + Base64.getEncoder().encodeToString(job.message.getBytes(StandardCharsets.UTF_8))); saveProperties(JOB_FILE, data, "AeroMC scheduled jobs"); }

    private void recordEvent(String category, String detail) {
        Runnable add = () -> {
            String value = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + "  •  " + category + "  •  " + trim(detail == null ? "Bilinmeyen olay" : detail, 220);
            if (!events.isEmpty() && events.get(0).endsWith("  •  " + category + "  •  " + trim(detail == null ? "Bilinmeyen olay" : detail, 220))) return;
            events.add(0, value); while (events.size() > 300) events.remove(events.size() - 1); saveEvents();
        };
        if (Platform.isFxApplicationThread()) add.run(); else Platform.runLater(add);
    }

    private void loadEvents() {
        try { if (Files.exists(EVENT_FILE)) { List<String> saved = Files.readAllLines(EVENT_FILE, StandardCharsets.UTF_8); events.setAll(saved.stream().filter(value -> !value.isBlank()).limit(300).toList()); } }
        catch (IOException ignored) { }
    }

    private void saveEvents() {
        try { Files.createDirectories(EVENT_FILE.getParent()); atomicWrite(EVENT_FILE, String.join(System.lineSeparator(), events) + (events.isEmpty() ? "" : System.lineSeparator())); }
        catch (IOException ignored) { }
    }

    private void loadDiscordPreferences() {
        Properties values = loadProperties(DISCORD_SETTINGS_FILE); discordEnabled.setSelected(Boolean.parseBoolean(values.getProperty("enabled", "false"))); discordStatus.setSelected(Boolean.parseBoolean(values.getProperty("status", "true"))); discordCrash.setSelected(Boolean.parseBoolean(values.getProperty("crash", "true"))); discordPlayers.setSelected(Boolean.parseBoolean(values.getProperty("players", "false"))); discordPerformance.setSelected(Boolean.parseBoolean(values.getProperty("performance", "true"))); discordMaintenance.setSelected(Boolean.parseBoolean(values.getProperty("maintenance", "true"))); discordAutomation.setSelected(Boolean.parseBoolean(values.getProperty("automation", "true"))); discordMentionCritical.setSelected(Boolean.parseBoolean(values.getProperty("mentionCritical", "false"))); discordName.setText(values.getProperty("username", "AeroMC")); discordRoleId.setText(values.getProperty("roleId", ""));
    }
    private void saveDiscordPreferences(boolean notify) {
        if (discordMentionCritical.isSelected() && !DiscordNotificationEngine.validRole(discordRoleId.getText())) { showError("Kritik etiketleme için 17–20 haneli Discord rol kimliği gir."); return; }
        Properties values = new Properties(); values.setProperty("enabled", Boolean.toString(discordEnabled.isSelected())); values.setProperty("status", Boolean.toString(discordStatus.isSelected())); values.setProperty("crash", Boolean.toString(discordCrash.isSelected())); values.setProperty("players", Boolean.toString(discordPlayers.isSelected())); values.setProperty("performance", Boolean.toString(discordPerformance.isSelected())); values.setProperty("maintenance", Boolean.toString(discordMaintenance.isSelected())); values.setProperty("automation", Boolean.toString(discordAutomation.isSelected())); values.setProperty("mentionCritical", Boolean.toString(discordMentionCritical.isSelected())); values.setProperty("username", discordName.getText().isBlank() ? "AeroMC" : discordName.getText().trim()); values.setProperty("roleId", discordRoleId.getText().trim()); saveProperties(DISCORD_SETTINGS_FILE, values, "AeroMC Discord notification preferences");
        String enteredUrl = webhook.getText().trim();
        if (config.isAutomaticCredentialVaultEnabled() && !enteredUrl.isBlank()) {
            try { DiscordNotificationEngine.validateWebhook(enteredUrl); storeAutomaticDiscordWebhook(enteredUrl); }
            catch (IllegalArgumentException error) { showError(error.getMessage()); return; }
        }
        if (notify) discordState.setText("Discord bildirim ayarları kaydedildi" + (config.isAutomaticCredentialVaultEnabled() && !enteredUrl.isBlank() ? " • webhook kasaya ekleniyor" : ""));
    }
    private DiscordNotificationEngine.Settings discordSettings() {
        EnumSet<DiscordNotificationEngine.Type> types = EnumSet.noneOf(DiscordNotificationEngine.Type.class); if (discordStatus.isSelected()) types.add(DiscordNotificationEngine.Type.STATUS); if (discordCrash.isSelected()) types.add(DiscordNotificationEngine.Type.CRASH); if (discordPlayers.isSelected()) types.add(DiscordNotificationEngine.Type.PLAYER); if (discordPerformance.isSelected()) types.add(DiscordNotificationEngine.Type.PERFORMANCE); if (discordMaintenance.isSelected()) { types.add(DiscordNotificationEngine.Type.MAINTENANCE); types.add(DiscordNotificationEngine.Type.BACKUP); } if (discordAutomation.isSelected()) types.add(DiscordNotificationEngine.Type.AUTOMATION); return new DiscordNotificationEngine.Settings(discordEnabled.isSelected(), types, discordName.getText(), discordMentionCritical.isSelected(), discordRoleId.getText());
    }
    public void setAutomaticCredentialVaultEnabled(boolean enabled) {
        if (!enabled) {
            automaticDiscordWebhook = null; webhook.clear();
            try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.DISCORD); }
            catch (IOException error) { throw new IllegalStateException("Discord otomatik kasası silinemedi: " + error.getMessage(), error); }
            updateDiscordVaultPrompt(); if (discordViewBuilt) discordState.setText("Otomatik Discord kasası kapatıldı"); return;
        }
        updateDiscordVaultPrompt();
        String entered = webhook.getText().trim();
        if (!entered.isBlank()) {
            try { DiscordNotificationEngine.validateWebhook(entered); storeAutomaticDiscordWebhook(entered); return; }
            catch (IllegalArgumentException ignored) { }
        }
        if (discordViewBuilt) loadAutomaticDiscordWebhook();
    }
    private void loadAutomaticDiscordWebhook() {
        if (!config.isAutomaticCredentialVaultEnabled() || automaticDiscordWebhook != null || discordVaultLoading || !DeviceCredentialStore.exists(DeviceCredentialStore.Kind.DISCORD)) { updateDiscordVaultPrompt(); return; }
        discordVaultLoading = true; discordState.setText("Otomatik Discord kasası açılıyor...");
        Task<String> task = new Task<>() { protected String call() throws Exception { String value = DeviceCredentialStore.load(DeviceCredentialStore.Kind.DISCORD); DiscordNotificationEngine.validateWebhook(value); return value; } };
        task.setOnSucceeded(event -> { discordVaultLoading = false; if (!config.isAutomaticCredentialVaultEnabled()) { automaticDiscordWebhook = null; updateDiscordVaultPrompt(); return; } automaticDiscordWebhook = task.getValue(); webhook.clear(); updateDiscordVaultPrompt(); discordState.setText("Webhook otomatik cihaz kasasından hazır"); });
        task.setOnFailed(event -> { discordVaultLoading = false; automaticDiscordWebhook = null; updateDiscordVaultPrompt(); discordState.setText("Otomatik webhook kasası açılamadı; webhook'u yeniden gir"); });
        run(task, "discord-auto-vault-load");
    }
    private void storeAutomaticDiscordWebhook(String url) {
        if (!config.isAutomaticCredentialVaultEnabled()) return;
        discordVaultLoading = true;
        Task<Void> task = new Task<>() { protected Void call() throws Exception { DeviceCredentialStore.save(DeviceCredentialStore.Kind.DISCORD, url); return null; } };
        task.setOnSucceeded(event -> { discordVaultLoading = false; if (!config.isAutomaticCredentialVaultEnabled()) { automaticDiscordWebhook = null; try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.DISCORD); } catch (IOException ignored) { } updateDiscordVaultPrompt(); return; } automaticDiscordWebhook = url; webhook.clear(); updateDiscordVaultPrompt(); discordState.setText("Webhook otomatik cihaz kasasında hazır"); });
        task.setOnFailed(event -> { discordVaultLoading = false; discordState.setText("Webhook otomatik kasaya kaydedilemedi"); showError("Webhook otomatik kasaya kaydedilemedi: " + rootMessage(task.getException())); });
        run(task, "discord-auto-vault-save");
    }
    private void updateDiscordVaultPrompt() {
        if (config.isAutomaticCredentialVaultEnabled() && (automaticDiscordWebhook != null || DeviceCredentialStore.exists(DeviceCredentialStore.Kind.DISCORD))) webhook.setPromptText("Bu cihazın güvenli kasasından otomatik kullanılıyor");
        else if (config.isAutomaticCredentialVaultEnabled()) webhook.setPromptText("Webhook'u bir kez gir; otomatik kasaya kaydedilir");
        else webhook.setPromptText("https://discord.com/api/webhooks/...");
    }
    private void storeDiscordWebhook() {
        String url = webhook.getText().trim(); try { DiscordNotificationEngine.validateWebhook(url); } catch (IllegalArgumentException error) { showError(error.getMessage()); return; }
        Optional<char[]> password = discordPasswordDialog("Discord Webhook'unu Şifrele", true); if (password.isEmpty()) return; char[] value = password.get();
        try { DiscordWebhookStore.save(url, value); discordLoadSaved.setDisable(false); discordDeleteSaved.setDisable(false); discordState.setText("Webhook şifreli olarak saklandı"); if (config.isAutomaticCredentialVaultEnabled()) storeAutomaticDiscordWebhook(url); }
        catch (Exception error) { showError("Webhook saklanamadı: " + rootMessage(error)); } finally { Arrays.fill(value, '\0'); }
    }
    private void loadDiscordWebhook() {
        Optional<char[]> password = discordPasswordDialog("Discord Webhook Kasasını Aç", false); if (password.isEmpty()) return; char[] value = password.get();
        try { String url = DiscordWebhookStore.load(value); if (config.isAutomaticCredentialVaultEnabled()) { automaticDiscordWebhook = url; webhook.clear(); storeAutomaticDiscordWebhook(url); } else webhook.setText(url); discordState.setText("Şifreli webhook bu oturum için açıldı"); updateDiscordVaultPrompt(); }
        catch (Exception error) { showError("Webhook açılamadı. Parola yanlış veya kayıt bozuk olabilir."); } finally { Arrays.fill(value, '\0'); }
    }
    private void deleteDiscordWebhook() {
        if (!confirm("Şifreli Discord webhook kaydı silinsin mi?")) return; try { DiscordWebhookStore.delete(); webhook.clear(); discordLoadSaved.setDisable(true); discordDeleteSaved.setDisable(true); discordState.setText(automaticDiscordWebhook == null ? "Şifreli webhook kaydı silindi" : "Parolalı kayıt silindi • otomatik cihaz kasası hazır"); } catch (Exception error) { showError(rootMessage(error)); }
    }
    private Optional<char[]> discordPasswordDialog(String title, boolean confirmPassword) {
        Dialog<char[]> dialog = new Dialog<>(); dialog.setTitle(title); dialog.setHeaderText(confirmPassword ? "En az 8 karakterlik ana parola belirle. Parola kaydedilmez." : "Şifreli webhook'u açmak için ana parolayı gir."); PasswordField first = new PasswordField(); SecretFieldGuard.protect(first); first.setPromptText("Ana parola"); VBox fields = new VBox(8, first); PasswordField second = new PasswordField(); SecretFieldGuard.protect(second); if (confirmPassword) { second.setPromptText("Ana parolayı tekrar yaz"); fields.getChildren().add(second); } dialog.getDialogPane().setContent(fields); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); dialog.setResultConverter(button -> { if (button != ButtonType.OK) return null; if (first.getText().length() < 8) { showError("Ana parola en az 8 karakter olmalı."); return null; } if (confirmPassword && !first.getText().equals(second.getText())) { showError("Ana parolalar eşleşmiyor."); return null; } return first.getText().toCharArray(); }); return dialog.showAndWait();
    }
    private void sendDiscordTest() { sendDiscord(DiscordNotificationEngine.Type.TEST, "AeroMC bağlantı testi", "Webhook bağlantısı başarılı. Bildirim merkezi kullanıma hazır 🎮", false, true); }
    private void sendDiscord(DiscordNotificationEngine.Type type, String title, String message, boolean critical) { sendDiscord(type, title, message, critical, false); }
    private void sendDiscord(DiscordNotificationEngine.Type type, String title, String message, boolean critical, boolean force) {
        DiscordNotificationEngine.Settings settings = discordSettings(); DiscordNotificationEngine.Event event = new DiscordNotificationEngine.Event(type, title, message, isRemote() ? "Exaroton" : "Yerel JAR", discordServerName(), critical); if (!force && !DiscordNotificationEngine.shouldSend(settings, event)) return; URI uri;
        String enteredUrl = webhook.getText().trim(), selectedUrl = enteredUrl.isBlank() ? automaticDiscordWebhook : enteredUrl;
        try { uri = DiscordNotificationEngine.validateWebhook(selectedUrl); } catch (IllegalArgumentException error) { discordState.setText(error.getMessage()); if (force) showError(error.getMessage()); return; }
        discordState.setText("Discord bildirimi gönderiliyor..."); String payload = DiscordNotificationEngine.payload(settings, event); discordClient.send(uri, payload).thenAccept(result -> Platform.runLater(() -> { discordState.setText(result.message()); if (result.success() && config.isAutomaticCredentialVaultEnabled() && !enteredUrl.isBlank()) { automaticDiscordWebhook = enteredUrl; webhook.clear(); updateDiscordVaultPrompt(); storeAutomaticDiscordWebhook(enteredUrl); } if (force && result.success()) info("Discord bağlantısı başarılı", "Test embed mesajı gönderildi."); else if (force && !result.success()) showError(result.message()); }));
    }
    private String discordServerName() { if (isRemote() && remoteSnapshot != null) return remoteSnapshot.name(); Path folder = manager.getServerFolder(); return folder == null || folder.getFileName() == null ? (isRemote() ? exaroton.getActiveServerName() : "Yerel sunucu") : folder.getFileName().toString(); }
    private String notificationSource() { return isRemote() ? NotificationCenter.serverSource("Exaroton", exaroton.getActiveServerName()) : NotificationCenter.serverSource("Yerel JAR", ""); }

    private void runRemoteAction(String name, Callable<CompletableFuture<Void>> action) { Task<Void> task = new Task<>() { protected Void call() throws Exception { action.call().join(); return null; } }; task.setOnFailed(event -> showError(name + " başarısız: " + rootMessage(task.getException()))); run(task, name.toLowerCase(Locale.ROOT).replace(' ', '-')); }

    public void shutdown() { metrics.stop(); scheduler.stop(); sparkCountdown.stop(); discordClient.close(); webhook.clear(); automaticDiscordWebhook = null; if (crisisActive) { try { restoreCrisisSettings(); } catch (Exception ignored) { } } for (String name : new HashSet<>(onlinePlayers)) markLeft(name); }
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
    private TableColumn<PlayerProfile, String> column(String name, java.util.function.Function<PlayerProfile, String> value) { TableColumn<PlayerProfile, String> column = new TableColumn<>(name); column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data.getValue()))); return column; }
    private String formatTime(long epoch) { return epoch <= 0 ? "-" : Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")); }
    private String durationText(long seconds) { long hours = seconds / 3600, minutes = seconds % 3600 / 60; return hours > 0 ? hours + " sa " + minutes + " dk" : minutes + " dk"; }
    private String now() { return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")); }
    private String trim(String value, int max) { String clean = value.strip(); return clean.length() <= max ? clean : clean.substring(0, max) + "…"; }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private void showError(String message) { if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(message)); return; } Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private void info(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, LanguageManager.text(message), ButtonType.OK); alert.setHeaderText(LanguageManager.text(title)); alert.showAndWait(); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }

    private static final class PlayerProfile { final String name; long firstSeen, lastSeen, totalSeconds, activeSince; int joins, deaths, advancements; PlayerProfile(String name) { this.name = name; } }
    private record Job(String id, LocalDateTime due, String action, String message) { @Override public String toString() { return due.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "  •  " + action + (message.isBlank() ? "" : "  •  " + message); } }
}
