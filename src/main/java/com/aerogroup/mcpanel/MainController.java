package com.aerogroup.mcpanel;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.application.HostServices;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** JavaFX arayüzünü sunucu yönetim katmanına bağlar. */
public final class MainController {
    private final PanelConfig config = PanelConfig.load();
    private final ConsoleView console = new ConsoleView();
    private final TextField jarPath = new TextField();
    private final Spinner<Integer> memory = new Spinner<>(512, 32768, config.getMemoryMb(), 512);
    private final Button start = new Button("▶ Sunucuyu Başlat");
    private final Button stop = new Button("■ Durdur");
    private final Button backup = new Button("Yedek Al");
    private final Label status = new Label("Sunucu kapalı");
    private final Label statusDot = new Label("●");
    private final ObservableList<String> players = FXCollections.observableArrayList();
    private final ServerManager manager;
    private final ExarotonPane exarotonPane;
    private final AternosPane aternosPane;
    private final DashboardPane dashboardPane = new DashboardPane();
    private final ManagementPane managementPane;
    private ProToolsPane proToolsPane;
    private final NextGenPane nextGenPane;
    private final SettingsPane settingsPane;
    private PlayerMapPane playerMapPane;
    private final Timeline playerRefresh;
    private final Timeline scheduledBackup = new Timeline();
    private final Set<String> knownPlayers = new HashSet<>();
    private BorderPane root;
    private TabPane mainTabs;
    private Tab nextGenTab, liveMapTab, serversTab, exarotonProviderTab;

    public MainController(HostServices hostServices) {
        exarotonPane = new ExarotonPane(config);
        aternosPane = new AternosPane(hostServices);
        manager = new ServerManager(new ServerManager.Listener() {
            public void onConsole(String line) { Platform.runLater(() -> console.append(line)); if (proToolsPane != null) proToolsPane.onConsole(line); if (playerMapPane != null) playerMapPane.onLocalConsole(line); }
            public void onState(boolean running, String text) { Platform.runLater(() -> { updateState(running, text); if (text.toLowerCase().contains("çöktü")) DesktopNotifier.show("Yerel sunucu", text); if (proToolsPane != null) proToolsPane.onState(running, text); }); }
            public void onPlayers(List<String> names) { Platform.runLater(() -> { for (String name : names) if (!knownPlayers.contains(name)) DesktopNotifier.show("Oyuncu katıldı", name + " sunucuya katıldı."); knownPlayers.clear(); knownPlayers.addAll(names); players.setAll(names); if (proToolsPane != null) proToolsPane.onPlayers(names); if (playerMapPane != null) playerMapPane.onLocalPlayers(names); }); }
        });
        managementPane = new ManagementPane(manager, exarotonPane);
        proToolsPane = new ProToolsPane(manager, exarotonPane, config, hostServices, this::openExarotonAutomation);
        if (config.isLiveMapEnabled()) playerMapPane = new PlayerMapPane(manager, exarotonPane);
        nextGenPane = new NextGenPane(manager, exarotonPane, config, hostServices, this::useInstalledJar);
        settingsPane = new SettingsPane(config, this::setLiveMapEnabled, this::setAutomaticCredentialVaultEnabled, hostServices);
        playerRefresh = new Timeline(new KeyFrame(Duration.seconds(15), event -> manager.requestPlayers()));
        playerRefresh.setCycleCount(Animation.INDEFINITE);
    }
    public BorderPane buildView() {
        root = new BorderPane(); root.getStyleClass().add("app-root");
        root.setTop(header()); root.setCenter(providerTabs());
        if (config.getServerJar() != null) { jarPath.setText(config.getServerJar().toString()); manager.configure(config.getServerJar()); }
        updateState(false, "Sunucu kapalı");
        return root;
    }
    private TabPane providerTabs() {
        Tab dashboard = new Tab("Ana Panel", dashboardPane.buildView());
        Tab local = new Tab("Yerel JAR", content()); local.setClosable(false);
        exarotonProviderTab = new Tab("Exaroton", exarotonPane.buildView()); exarotonProviderTab.setClosable(false);
        Tab aternos = new Tab("Aternos", aternosPane.buildView()); aternos.setClosable(false);
        TabPane serverProviders = new TabPane(local, exarotonProviderTab, aternos); serverProviders.getStyleClass().add("server-provider-tabs");
        serversTab = new Tab("Sunucular", serverProviders);
        Tab management = new Tab("Yönetim", managementPane.buildView());
        Tab proTools = new Tab("Kontrol Merkezi", proToolsPane.buildView());
        nextGenTab = new Tab("Araçlar", nextGenPane.buildView());
        Tab settings = new Tab("Ayarlar", settingsPane.buildView());
        if (playerMapPane != null) liveMapTab = new Tab("Canlı Harita", playerMapPane.buildView());
        dashboard.setClosable(false); serversTab.setClosable(false); management.setClosable(false); proTools.setClosable(false); nextGenTab.setClosable(false); settings.setClosable(false);
        mainTabs = new TabPane(); mainTabs.getTabs().addAll(dashboard, serversTab, management, proTools); if (liveMapTab != null) { liveMapTab.setClosable(false); mainTabs.getTabs().add(liveMapTab); } mainTabs.getTabs().addAll(nextGenTab, settings);
        mainTabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> { if (newTab == management) managementPane.updateProviderStatus(); });
        mainTabs.getStyleClass().add("provider-tabs"); return mainTabs;
    }
    private HBox header() {
        Label logo = new Label("AEROMC"); logo.getStyleClass().add("logo");
        Label title = new Label("Server Panel"); title.getStyleClass().add("header-title");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label mode = new Label("Çoklu Sunucu Yönetim Merkezi"); mode.getStyleClass().add("status-text");
        HBox header = new HBox(10, logo, title, spacer, mode); header.setAlignment(Pos.CENTER_LEFT); header.getStyleClass().add("header"); return header;
    }
    private SplitPane content() {
        VBox left = new VBox(14, serverCard(), consoleCard()); left.setPadding(new Insets(18));
        VBox right = new VBox(14, playersCard(), quickCommands()); right.setPadding(new Insets(18, 18, 18, 0)); right.setPrefWidth(285);
        SplitPane split = new SplitPane(left, right); split.setDividerPositions(.73); HBox.setHgrow(left, Priority.ALWAYS); return split;
    }
    private VBox serverCard() {
        Label heading = heading("SUNUCU AYARLARI");
        statusDot.getStyleClass().add("status-dot"); status.getStyleClass().add("status-text");
        Label localLabel = new Label("YEREL SUNUCU"); localLabel.getStyleClass().add("section-title");
        Region statusSpacer = new Region(); HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox localStatus = new HBox(7, localLabel, statusSpacer, statusDot, status); localStatus.setAlignment(Pos.CENTER_LEFT);
        jarPath.setPromptText("server.jar dosyasını seç"); jarPath.setEditable(false); HBox.setHgrow(jarPath, Priority.ALWAYS);
        Button choose = new Button("Dosya Seç"); choose.setOnAction(event -> chooseJar());
        HBox jarRow = new HBox(8, jarPath, choose);
        Label ramLabel = new Label("RAM (MB)"); memory.setEditable(true);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Button preflight = new Button("Başlatma Kontrolü"); preflight.getStyleClass().add("secondary"); preflight.setOnAction(event -> previewPreflight());
        HBox controls = new HBox(9, start, stop, backup, preflight, spacer, ramLabel, memory); controls.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> schedule = new ComboBox<>(FXCollections.observableArrayList("Planlı yedek: Kapalı", "Her 15 dakika", "Her 30 dakika", "Her 60 dakika")); schedule.getSelectionModel().selectFirst(); schedule.setOnAction(event -> configureBackupSchedule(schedule.getSelectionModel().getSelectedIndex()));
        HBox scheduleRow = new HBox(8, new Label("OTOMATİK YEDEK"), schedule); scheduleRow.setAlignment(Pos.CENTER_LEFT);
        start.getStyleClass().add("primary"); stop.getStyleClass().add("danger"); backup.getStyleClass().add("secondary");
        start.setOnAction(event -> startServer()); stop.setOnAction(event -> manager.stop()); backup.setOnAction(event -> createBackup());
        VBox card = new VBox(11, localStatus, heading, jarRow, controls, scheduleRow); card.getStyleClass().add("card"); return card;
    }
    private VBox consoleCard() {
        VBox.setVgrow(console, Priority.ALWAYS);
        TextField command = new TextField(); command.setPromptText("Konsol komutu yaz (örnek: say Merhaba)");
        Button send = new Button("Gönder"); send.getStyleClass().add("primary");
        Runnable sendAction = () -> { String value = command.getText().trim(); if (!value.isEmpty()) { try { manager.command(value); console.append("> " + value); command.clear(); } catch (IOException e) { alert("Komut gönderilemedi", e.getMessage()); } } };
        send.setOnAction(event -> sendAction.run()); command.setOnAction(event -> sendAction.run()); HBox.setHgrow(command, Priority.ALWAYS);
        HBox input = new HBox(8, command, send);
        VBox card = new VBox(10, heading("CANLI KONSOL"), console, input); card.getStyleClass().add("card"); VBox.setVgrow(card, Priority.ALWAYS); return card;
    }
    private VBox playersCard() {
        ListView<String> list = new ListView<>(players); list.setPlaceholder(new Label("Online oyuncu yok")); VBox.setVgrow(list, Priority.ALWAYS);
        Label count = new Label(); count.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> LanguageManager.text(players.size() + " oyuncu online"), javafx.beans.binding.Bindings.size(players), LanguageManager.englishProperty())); count.getStyleClass().add("muted");
        VBox card = new VBox(9, heading("ONLINE OYUNCULAR"), count, list); card.getStyleClass().add("card"); VBox.setVgrow(card, Priority.ALWAYS); return card;
    }
    private VBox quickCommands() {
        VBox buttons = new VBox(8);
        for (String[] item : new String[][]{{"Gündüz yap", "time set day"}, {"Havayı temizle", "weather clear"}, {"Dünyayı kaydet", "save-all flush"}, {"Oyuncuları yenile", "list"}}) {
            Button button = new Button(item[0]); button.setMaxWidth(Double.MAX_VALUE); button.getStyleClass().add("secondary"); button.setOnAction(event -> { try { manager.command(item[1]); } catch (IOException e) { alert("Komut gönderilemedi", e.getMessage()); } }); buttons.getChildren().add(button);
        }
        TextField broadcast = new TextField(); broadcast.setPromptText("Herkese mesaj"); Button announce = new Button("Yayınla"); announce.getStyleClass().add("primary");
        announce.setOnAction(event -> { String message = broadcast.getText().trim(); if (!message.isEmpty()) try { manager.command("say " + message); broadcast.clear(); } catch (IOException error) { alert("Mesaj gönderilemedi", error.getMessage()); } });
        VBox card = new VBox(10, heading("HIZLI KOMUTLAR"), buttons, broadcast, announce); card.getStyleClass().add("card"); return card;
    }
    private Label heading(String value) { Label label = new Label(value); label.getStyleClass().add("section-title"); return label; }
    private void chooseJar() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Minecraft sunucu JAR dosyasını seç"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JAR dosyaları", "*.jar"));
        File file = chooser.showOpenDialog(root.getScene().getWindow()); if (file != null) { jarPath.setText(file.getAbsolutePath()); manager.configure(file.toPath()); config.setServerJar(file.toPath()); try { config.save(); } catch (IOException ignored) { } }
    }
    private void useInstalledJar(Path jar, Integer ram) { jarPath.setText(jar.toAbsolutePath().toString()); manager.configure(jar); config.setServerJar(jar); config.setMemoryMb(ram); memory.getValueFactory().setValue(ram); try { config.save(); } catch (IOException ignored) { } }
    private void setLiveMapEnabled(Boolean enabled) {
        if (enabled && playerMapPane == null) {
            playerMapPane = new PlayerMapPane(manager, exarotonPane); liveMapTab = new Tab(LanguageManager.text("Canlı Harita"), playerMapPane.buildView()); liveMapTab.setClosable(false);
            int index = nextGenTab == null ? mainTabs.getTabs().size() : mainTabs.getTabs().indexOf(nextGenTab); mainTabs.getTabs().add(Math.max(0, index), liveMapTab);
            if ("en".equals(LanguageManager.load()) && liveMapTab.getContent() instanceof javafx.scene.Parent parent) LanguageManager.apply(parent, "en");
        } else if (!enabled && playerMapPane != null) {
            if (mainTabs != null && liveMapTab != null) mainTabs.getTabs().remove(liveMapTab); playerMapPane.shutdown(); playerMapPane = null; liveMapTab = null;
        }
    }
    private void setAutomaticCredentialVaultEnabled(Boolean enabled) {
        exarotonPane.setAutomaticCredentialVaultEnabled(enabled);
        if (proToolsPane != null) proToolsPane.setAutomaticCredentialVaultEnabled(enabled);
    }
    private void openExarotonAutomation() {
        if (mainTabs == null || serversTab == null || exarotonProviderTab == null) return; mainTabs.getSelectionModel().select(serversTab);
        if (serversTab.getContent() instanceof TabPane providers) providers.getSelectionModel().select(exarotonProviderTab); exarotonPane.openAutomationTab();
    }
    private void startServer() {
        try {
            Path jar = Path.of(jarPath.getText()); int ram = memory.getValue(); if (!reviewPreflight(jar, ram, true)) return; config.setServerJar(jar); config.setMemoryMb(ram); config.save(); console.clearConsole(); console.append("[Panel] Başlatma öncesi kontrol tamamlandı."); manager.start(jar, ram); playerRefresh.play();
        } catch (Exception error) { alert("Sunucu başlatılamadı", error.getMessage()); }
    }
    private void previewPreflight() {
        try { reviewPreflight(jarPath.getText().isBlank() ? null : Path.of(jarPath.getText()), memory.getValue(), false); }
        catch (Exception error) { alert("Başlatma kontrolü yapılamadı", error.getMessage()); }
    }
    private boolean reviewPreflight(Path jar, int ram, boolean starting) throws IOException {
        PreflightEngine.Report report = PreflightEngine.inspect(jar, ram);
        if (starting && !report.hasCritical() && !report.hasWarnings()) return true;
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle("AeroMC Başlatma Kontrolü");
        String summary = report.hasCritical() ? report.criticalCount() + " kritik sorun • " + report.warningCount() + " uyarı" : report.hasWarnings() ? report.warningCount() + " uyarı bulundu" : "Sunucu başlatmaya hazır";
        dialog.setHeaderText(summary);
        ListView<PreflightEngine.Issue> list = new ListView<>(FXCollections.observableArrayList(report.issues())); list.setPrefSize(680, Math.min(390, 70 + report.issues().size() * 46));
        Label note = new Label(report.hasCritical() ? "Kritik sorunlar çözülmeden sunucu başlatılmaz." : "Uyarıları inceleyip devam edebilirsin."); note.setWrapText(true); note.getStyleClass().add("muted");
        dialog.getDialogPane().setContent(new VBox(10, note, list)); ButtonType cancel = new ButtonType(starting ? "İptal" : "Kapat", ButtonBar.ButtonData.CANCEL_CLOSE); dialog.getDialogPane().getButtonTypes().add(cancel);
        ButtonType fix = null, launch = null;
        if (report.hasFixable()) { fix = new ButtonType("EULA'yı Kabul Et ve Düzelt", ButtonBar.ButtonData.APPLY); dialog.getDialogPane().getButtonTypes().add(0, fix); }
        if (starting && !report.hasCritical()) { launch = new ButtonType(report.hasWarnings() ? "Uyarılarla Başlat" : "Başlat", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().add(0, launch); }
        Optional<ButtonType> selected = dialog.showAndWait();
        if (fix != null && selected.orElse(null) == fix) {
            PreflightEngine.applySafeFixes(jar, report); PreflightEngine.Report after = PreflightEngine.inspect(jar, ram);
            if (after.hasCritical()) { alert("Kritik sorun devam ediyor", after.issues().stream().filter(issue -> issue.severity() == PreflightEngine.Severity.CRITICAL).map(PreflightEngine.Issue::toString).reduce((a, b) -> a + "\n" + b).orElse("Bilinmeyen sorun")); return false; }
            if (!starting) { new Alert(Alert.AlertType.INFORMATION, "Güvenli düzeltmeler uygulandı.", ButtonType.OK).showAndWait(); return false; }
            return true;
        }
        return launch != null && selected.orElse(null) == launch;
    }
    private void createBackup() {
        if (!jarPath.getText().isBlank()) manager.configure(Path.of(jarPath.getText()));
        backup.setDisable(true); status.setText("Yedek alınıyor...");
        Task<Path> task = new Task<>() { protected Path call() throws Exception { return manager.createBackup(); } };
        task.setOnSucceeded(event -> { backup.setDisable(false); status.setText("Yedek hazır"); console.append("[Panel] Yedek: " + task.getValue()); DesktopNotifier.show("AeroMC", "Sunucu yedeği hazırlandı."); });
        task.setOnFailed(event -> { backup.setDisable(false); alert("Yedek alınamadı", task.getException().getMessage()); });
        Thread thread = new Thread(task, "aeromc-backup"); thread.setDaemon(true); thread.start();
    }
    private void configureBackupSchedule(int selection) {
        scheduledBackup.stop(); scheduledBackup.getKeyFrames().clear();
        if (selection <= 0) return; int minutes = selection == 1 ? 15 : selection == 2 ? 30 : 60;
        scheduledBackup.getKeyFrames().add(new KeyFrame(Duration.minutes(minutes), event -> { if (!backup.isDisable()) createBackup(); })); scheduledBackup.setCycleCount(Animation.INDEFINITE); scheduledBackup.play();
    }
    private void updateState(boolean running, String text) {
        status.setText(text); statusDot.setStyle(running ? "-fx-text-fill:#45d483" : "-fx-text-fill:#ef6b72"); start.setDisable(running); stop.setDisable(!running);
        if (!running) { playerRefresh.stop(); players.clear(); knownPlayers.clear(); }
    }
    private void alert(String title, String message) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setTitle(LanguageManager.text(title)); alert.setHeaderText(LanguageManager.text(title)); alert.showAndWait(); }
    public void shutdown() { playerRefresh.stop(); scheduledBackup.stop(); nextGenPane.shutdown(); if (playerMapPane != null) playerMapPane.shutdown(); if (proToolsPane != null) proToolsPane.shutdown(); manager.shutdown(); exarotonPane.shutdown(); aternosPane.shutdown(); dashboardPane.shutdown(); }
}
