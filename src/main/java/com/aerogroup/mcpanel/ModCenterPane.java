package com.aerogroup.mcpanel;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Modrinth araması ile yerel ve Exaroton sunucularına tek tık içerik kurar. */
public final class ModCenterPane {
    private final ServerManager manager;
    private final ExarotonPane exaroton;
    private final PanelConfig config;
    private final HostServices hostServices;
    private final ModrinthService modrinth = new ModrinthService();
    private final ModInstallManager installer = new ModInstallManager(modrinth);
    private final ModUpdateService updateService = new ModUpdateService(modrinth);
    private final ComboBox<String> provider = new ComboBox<>(FXCollections.observableArrayList("Yerel JAR", "Exaroton", "Aternos"));
    private final ObservableList<ServerTarget> targets = FXCollections.observableArrayList();
    private final ComboBox<ServerTarget> target = new ComboBox<>(targets);
    private final TextField gameVersion = new TextField(), query = new TextField();
    private final ComboBox<String> loader = new ComboBox<>(FXCollections.observableArrayList("fabric", "forge", "neoforge", "quilt", "paper", "purpur", "spigot", "bukkit", "vanilla"));
    private final ObservableList<ModrinthService.Project> projects = FXCollections.observableArrayList();
    private final ListView<ModrinthService.Project> results = new ListView<>(projects);
    private final Label detection = new Label("Sunucu ve yazılım bilgisi bekleniyor"), detail = new Label("Arama sonuçlarından bir proje seç."), state = new Label("Hazır");
    private final ProgressBar progress = new ProgressBar(0);
    private final ObservableList<ModUpdateService.UpdateItem> updateItems = FXCollections.observableArrayList();
    private final ObservableList<ModUpdateService.Conflict> conflictItems = FXCollections.observableArrayList();
    private final ListView<ModUpdateService.UpdateItem> updateList = new ListView<>(updateItems);
    private final ListView<ModUpdateService.Conflict> conflictList = new ListView<>(conflictItems);
    private final Label updateState = new Label("Güncelleme taraması bekleniyor");
    private final ProgressBar updateProgress = new ProgressBar(0);
    private ModUpdateService.ScanReport lastScan;
    private boolean syncingTargets;

    public ModCenterPane(ServerManager manager, ExarotonPane exaroton, PanelConfig config, HostServices hostServices) { this.manager = manager; this.exaroton = exaroton; this.config = config; this.hostServices = hostServices; }

    public Node buildView() {
        provider.getSelectionModel().selectFirst(); target.setMaxWidth(Double.MAX_VALUE); target.setPromptText("Yerel sunucuyu seç"); refreshTargets(); if (!targets.isEmpty()) target.getSelectionModel().selectFirst();
        gameVersion.setPromptText("Örn. 1.20.4"); loader.getSelectionModel().select("fabric"); query.setPromptText("Mod veya eklenti ara (örn. voice chat)"); HBox.setHgrow(query, Priority.ALWAYS); HBox.setHgrow(target, Priority.ALWAYS);
        Button detect = button("Otomatik Algıla", "secondary"), search = button("Modrinth'te Ara", "primary"), install = button("Tek Tıkla Yükle", "primary"), open = button("Proje Sayfasını Aç", "secondary"), aternos = button("Aternos Mod Sayfasını Aç", "secondary");
        detect.setOnAction(event -> detectServer()); search.setOnAction(event -> search()); query.setOnAction(event -> search()); install.setOnAction(event -> installSelected()); open.setOnAction(event -> openSelectedProject()); aternos.setOnAction(event -> openExternal("https://aternos.org/addons/"));
        provider.setOnAction(event -> { invalidateUpdates(); refreshProviderUi(); detectServer(); }); target.setOnAction(event -> { if (!syncingTargets) { invalidateUpdates(); if ("Yerel JAR".equals(provider.getValue())) detectServer(); } }); gameVersion.textProperty().addListener((obs, old, value) -> invalidateUpdates()); loader.setOnAction(event -> invalidateUpdates());
        GridPane selection = new GridPane(); selection.setHgap(10); selection.setVgap(8); addField(selection, 0, "Sağlayıcı", provider); addField(selection, 1, "Hedef sunucu", target); addField(selection, 2, "Minecraft sürümü", gameVersion); addField(selection, 3, "Loader", loader); for (int i = 0; i < 4; i++) GridPane.setHgrow(selection.getChildren().get(i), Priority.ALWAYS);
        detection.setWrapText(true); detection.getStyleClass().add("muted"); VBox targetCard = card("1 • HEDEF SUNUCU VE UYUMLULUK", selection, new HBox(8, detect, aternos), detection);

        results.setCellFactory(list -> new ListCell<>() { @Override protected void updateItem(ModrinthService.Project project, boolean empty) { super.updateItem(project, empty); if (empty || project == null) { setText(null); return; } setText(LanguageManager.text(project.title() + "  •  " + project.author() + "  •  " + compact(project.downloads()) + " indirme\n" + project.description())); setWrapText(true); } });
        results.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> showDetails(selected)); LanguageManager.englishProperty().addListener((obs, old, selected) -> { results.refresh(); updateList.refresh(); conflictList.refresh(); }); results.setPrefHeight(245); VBox.setVgrow(results, Priority.ALWAYS);
        HBox searchRow = new HBox(8, query, search); detail.setWrapText(true); detail.getStyleClass().add("mod-detail"); VBox searchCard = card("2 • MODRINTH'TE ARA", searchRow, results, detail); VBox.setVgrow(searchCard, Priority.ALWAYS);

        progress.setMaxWidth(Double.MAX_VALUE); state.setWrapText(true); state.getStyleClass().add("muted"); Label safety = new Label("Yalnız sunucu uyumlu sonuçlar gösterilir. Zorunlu bağımlılıklar otomatik çözülür ve her dosya SHA-512 ile doğrulanır. Yerel kurulumdan önce mods/plugins yedeği alınır."); safety.setWrapText(true); safety.getStyleClass().add("muted");
        HBox actions = new HBox(8, install, open); VBox installCard = card("3 • GÜVENLİ KURULUM", safety, progress, state, actions);
        VBox installPage = new VBox(14, searchCard, installCard); installPage.setPadding(new Insets(12)); VBox.setVgrow(searchCard, Priority.ALWAYS);

        Button scanUpdates = button("Güncellemeleri ve Çakışmaları Tara", "primary"), selectUpdates = button("Tüm Güncellemeleri Seç", "secondary"), applyUpdates = button("Seçilenleri Güvenle Güncelle", "primary");
        scanUpdates.setOnAction(event -> scanUpdates()); selectUpdates.setOnAction(event -> selectAllUpdates()); applyUpdates.setOnAction(event -> applySelectedUpdates());
        updateList.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); updateList.setCellFactory(list -> new ListCell<>() { @Override protected void updateItem(ModUpdateService.UpdateItem item, boolean empty) { super.updateItem(item, empty); if (empty || item == null) { setText(null); return; } String status = switch (item.status()) { case UPDATE -> "GÜNCELLEME"; case CURRENT -> "GÜNCEL"; case UNKNOWN -> "TANINMADI"; case INCOMPATIBLE -> "UYUMSUZ"; }; setText(LanguageManager.text(status + "  •  " + item.filename() + "\n" + item.currentVersion() + "  →  " + item.latestVersion())); setWrapText(true); } }); updateList.setPrefHeight(220);
        conflictList.setCellFactory(list -> new ListCell<>() { @Override protected void updateItem(ModUpdateService.Conflict item, boolean empty) { super.updateItem(item, empty); if (empty || item == null) { setText(null); return; } String icon = switch (item.severity()) { case ERROR -> "⛔"; case WARNING -> "⚠"; case INFO -> "ℹ"; }; setText(LanguageManager.text(icon + "  " + item.source() + "  •  " + item.message())); setWrapText(true); } }); conflictList.setPrefHeight(180);
        updateProgress.setMaxWidth(Double.MAX_VALUE); updateState.setWrapText(true); updateState.getStyleClass().add("muted"); Label updateHelp = new Label("Kurulu JAR'lar SHA-512 ile tanınır. Yalnızca seçili Minecraft sürümü ve loader ile uyumlu güncellemeler sunulur; uygulamadan önce yedek ve kesin bağımlılık çözümü yapılır."); updateHelp.setWrapText(true); updateHelp.getStyleClass().add("muted");
        VBox updatesCard = card("AKILLI MOD GÜNCELLEME MERKEZİ", updateHelp, new HBox(8, scanUpdates, selectUpdates, applyUpdates), updateProgress, updateState, updateList); VBox conflictsCard = card("ÇAKIŞMA DEDEKTÖRÜ", conflictList); VBox updatePage = new VBox(14, updatesCard, conflictsCard); updatePage.setPadding(new Insets(12)); VBox.setVgrow(updatesCard, Priority.ALWAYS); VBox.setVgrow(updateList, Priority.ALWAYS); VBox.setVgrow(conflictsCard, Priority.ALWAYS); VBox.setVgrow(conflictList, Priority.ALWAYS);

        Tab installTab = tab("Mod Ara & Yükle", installPage), updateTab = tab("Güncelleme & Çakışma", updatePage); TabPane tabs = new TabPane(installTab, updateTab); VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox page = new VBox(14, targetCard, tabs); page.setPadding(new Insets(18)); refreshProviderUi(); Platform.runLater(this::detectServer); return page;
    }

    private void refreshTargets() {
        Path selected = target.getValue() == null ? null : target.getValue().jar();
        LinkedHashSet<Path> known = new LinkedHashSet<>(config.getKnownServerJars());
        if (config.getServerJar() != null) known.add(config.getServerJar());
        syncingTargets = true;
        try {
            targets.setAll(known.stream().map(path -> path.toAbsolutePath().normalize()).filter(Files::isRegularFile).map(ServerTarget::new).toList());
            if (selected != null) targets.stream().filter(item -> item.jar().equals(selected)).findFirst().ifPresent(item -> target.getSelectionModel().select(item));
            if (target.getValue() == null && !targets.isEmpty()) target.getSelectionModel().selectFirst();
        } finally { syncingTargets = false; }
    }
    private void refreshProviderUi() { boolean local = "Yerel JAR".equals(provider.getValue()), hosted = "Aternos".equals(provider.getValue()); target.setDisable(!local); if (hosted) detection.setText("Aternos otomatik dosya yükleme API'si sunmuyor. Sürüm ve loader'ı seç; proje bulunduktan sonra Aternos mod sayfasında aynı adı arat."); }

    private void detectServer() {
        String selectedProvider = provider.getValue(); if (selectedProvider == null) return;
        if ("Aternos".equals(selectedProvider)) { if (gameVersion.getText().isBlank()) detection.setText("Aternos için Minecraft sürümünü ve loader'ı elle seç."); return; }
        if ("Yerel JAR".equals(selectedProvider)) { refreshTargets(); ServerTarget selected = target.getValue(); if (selected == null) { detection.setText("Önce Sunucular bölümünden bir JAR seç veya Araçlar → Kurulum ile sunucu kur."); return; } Detected value = detectLocal(selected); applyDetected(value); return; }
        if (!exaroton.hasActiveServer()) { detection.setText("Önce Sunucular → Exaroton bölümünden bir sunucu seç."); return; }
        detection.setText("Exaroton yazılım bilgisi alınıyor..."); Task<Detected> task = new Task<>() { protected Detected call() throws Exception { ExarotonPane.ProSnapshot snapshot = exaroton.fetchProSnapshot().join(); return new Detected(normalizeVersion(snapshot.softwareVersion()), normalizeLoader(snapshot.softwareName()), snapshot.name() + " • " + snapshot.softwareName() + " " + snapshot.softwareVersion()); } }; task.setOnSucceeded(event -> applyDetected(task.getValue())); task.setOnFailed(event -> detection.setText("Exaroton yazılımı algılanamadı: " + rootMessage(task.getException()))); run(task, "mod-detect-exaroton");
    }

    private Detected detectLocal(ServerTarget selected) {
        Path root = selected.folder(); Properties metadata = load(root.resolve(".aeromc-server.properties")); String version = metadata.getProperty("minecraftVersion", ""), detectedLoader = normalizeLoader(metadata.getProperty("loader", ""));
        if (version.isBlank()) version = detectVersionFromLog(root.resolve("logs/latest.log")); if (detectedLoader.isBlank()) detectedLoader = detectLoader(selected.jar(), root); String description = selected.name() + " • " + (version.isBlank() ? "sürüm bulunamadı" : version) + " • " + (detectedLoader.isBlank() ? "loader bulunamadı" : detectedLoader); return new Detected(version, detectedLoader, description);
    }
    private void applyDetected(Detected value) { if (value == null) return; if (!value.version().isBlank()) gameVersion.setText(value.version()); if (!value.loader().isBlank() && loader.getItems().contains(value.loader())) loader.getSelectionModel().select(value.loader()); detection.setText("Algılandı: " + value.description() + (value.version().isBlank() || value.loader().isBlank() ? " • Eksik alanı elle girebilirsin." : "")); }

    private void search() {
        String text = query.getText().trim(), version = gameVersion.getText().trim(), selectedLoader = loader.getValue(); if (text.length() < 2) { error("Arama için en az 2 karakter yaz."); return; } if (version.isBlank() || selectedLoader == null) { error("Minecraft sürümü ve loader seçimi gerekli."); return; } if ("vanilla".equals(selectedLoader)) { error("Vanilla sunucu mod/eklenti yükleyemez. Fabric, Forge, NeoForge veya eklenti destekli bir yazılım seç."); return; }
        state.setText("Modrinth aranıyor..."); progress.setProgress(-1); projects.clear(); Task<List<ModrinthService.Project>> task = new Task<>() { protected List<ModrinthService.Project> call() throws Exception { return modrinth.search(text, version, apiLoader(selectedLoader), projectType(selectedLoader)); } };
        task.setOnSucceeded(event -> { projects.setAll(task.getValue()); progress.setProgress(0); state.setText(task.getValue().isEmpty() ? "Bu sürüm ve loader için sunucu uyumlu sonuç bulunamadı." : task.getValue().size() + " uyumlu proje bulundu."); if (!projects.isEmpty()) results.getSelectionModel().selectFirst(); }); task.setOnFailed(event -> { progress.setProgress(0); state.setText("Arama başarısız"); error(rootMessage(task.getException())); }); run(task, "modrinth-search");
    }

    private void installSelected() {
        ModrinthService.Project project = results.getSelectionModel().getSelectedItem(); if (project == null) { error("Önce bir proje seç."); return; } String version = gameVersion.getText().trim(), selectedLoader = loader.getValue(), selectedProvider = provider.getValue(); ServerTarget selectedTarget = target.getValue();
        if ("Aternos".equals(selectedProvider)) { openExternal("https://aternos.org/addons/"); state.setText("Aternos sayfasında “" + project.title() + "” adını arat. Otomatik yükleme Aternos tarafından desteklenmiyor."); return; }
        if ("Yerel JAR".equals(selectedProvider) && selectedTarget == null) { error("Yüklenecek yerel sunucuyu seç."); return; } if ("Exaroton".equals(selectedProvider) && !exaroton.hasActiveServer()) { error("Önce Exaroton sunucusunu seç."); return; }
        if ("Exaroton".equals(selectedProvider) && !confirm("Exaroton resmî API'si dosya yüklemeye izin verir fakat uzaktaki mevcut mods/plugins klasörünü panel içine yedekleyemez. Devam edilsin mi?")) return;
        progress.setProgress(-1); state.setText(project.title() + " ve zorunlu bağımlılıkları hazırlanıyor..."); Task<InstallOutcome> task = new Task<>() { protected InstallOutcome call() throws Exception { ModrinthService.Resolution resolution = modrinth.resolve(project.id(), version, apiLoader(selectedLoader)); return "Yerel JAR".equals(selectedProvider) ? installLocal(selectedTarget, resolution, selectedLoader) : installExaroton(resolution, selectedLoader); } };
        task.setOnSucceeded(event -> { progress.setProgress(1); InstallOutcome outcome = task.getValue(); state.setText(outcome.message()); info("Kurulum tamamlandı", outcome.message() + " Sunucuyu yeniden başlatınca etkinleşecek."); }); task.setOnFailed(event -> { progress.setProgress(0); state.setText("Kurulum başarısız • Değişiklikler mümkün olduğunca geri alındı."); error(rootMessage(task.getException())); }); run(task, "modrinth-install");
    }

    private InstallOutcome installLocal(ServerTarget selected, ModrinthService.Resolution resolution, String selectedLoader) throws Exception { String directory = contentDirectory(selectedLoader); ModInstallManager.InstallReport report = installer.installLocal(selected.folder(), directory, resolution); return new InstallOutcome(report.installedFiles().size() + " dosya kuruldu (" + report.dependencies() + " bağımlılık). Güvenlik yedeği: " + report.backup()); }
    private InstallOutcome installExaroton(ModrinthService.Resolution resolution, String selectedLoader) throws Exception { Path temporary = Files.createTempDirectory("aeromc-exaroton-mod-"); try { List<Path> files = installer.downloadToTemporary(resolution, temporary); String directory = "/" + contentDirectory(selectedLoader); for (Path file : files) exaroton.uploadRemoteFile(directory, file).join(); return new InstallOutcome(files.size() + " doğrulanmış dosya Exaroton " + directory + " klasörüne yüklendi (" + resolution.dependencyCount() + " bağımlılık)."); } finally { ModInstallManager.deleteTree(temporary); } }

    private void invalidateUpdates() { lastScan = null; updateItems.clear(); conflictItems.clear(); updateProgress.setProgress(0); updateState.setText("Ayar değişti • yeniden tarama gerekli"); }

    private void scanUpdates() {
        String selectedProvider = provider.getValue(), version = gameVersion.getText().trim(), selectedLoader = loader.getValue(); ServerTarget selectedTarget = target.getValue();
        if (version.isBlank() || selectedLoader == null || "vanilla".equals(selectedLoader)) { error("Güncelleme taraması için geçerli Minecraft sürümü ve loader gerekli."); return; }
        if ("Aternos".equals(selectedProvider)) { openExternal("https://aternos.org/addons/"); updateState.setText("Aternos otomatik dosya inceleme API'si sunmadığı için güncellemeler resmî panelden yönetilir."); return; }
        if ("Yerel JAR".equals(selectedProvider) && selectedTarget == null) { error("Taranacak yerel sunucuyu seç."); return; }
        if ("Exaroton".equals(selectedProvider) && !exaroton.hasActiveServer()) { error("Önce Exaroton sunucusunu seç."); return; }
        lastScan = null; updateItems.clear(); conflictItems.clear(); updateProgress.setProgress(-1); updateState.setText("Kurulu JAR'lar tanınıyor ve uyumluluk inceleniyor..."); String directory = contentDirectory(selectedLoader), apiLoader = apiLoader(selectedLoader);
        Task<ModUpdateService.ScanReport> task = new Task<>() { protected ModUpdateService.ScanReport call() throws Exception { return "Yerel JAR".equals(selectedProvider) ? updateService.scan(selectedTarget.folder(), directory, version, apiLoader) : scanRemoteUpdates(directory, version, apiLoader); } };
        task.setOnSucceeded(event -> { lastScan = task.getValue(); updateItems.setAll(lastScan.items()); conflictItems.setAll(lastScan.conflicts()); updateProgress.setProgress(1); selectAllUpdates(); long errors = lastScan.conflicts().stream().filter(value -> value.severity() == ModUpdateService.Severity.ERROR).count(), warnings = lastScan.conflicts().stream().filter(value -> value.severity() == ModUpdateService.Severity.WARNING).count(); updateState.setText(lastScan.items().size() + " JAR incelendi • " + lastScan.updates().size() + " güncelleme • " + errors + " kritik • " + warnings + " uyarı"); });
        task.setOnFailed(event -> { updateProgress.setProgress(0); updateState.setText("Güncelleme taraması başarısız"); error(rootMessage(task.getException())); }); run(task, "mod-update-scan");
    }

    private ModUpdateService.ScanReport scanRemoteUpdates(String directory, String version, String selectedLoader) throws Exception {
        Path temporary = Files.createTempDirectory("aeromc-exaroton-scan-"); try { downloadRemoteJars(directory, temporary.resolve(directory)); return updateService.scan(temporary, directory, version, selectedLoader); } finally { ModInstallManager.deleteTree(temporary); }
    }

    private List<Path> downloadRemoteJars(String directory, Path destination) throws Exception {
        Files.createDirectories(destination); List<Path> result = new ArrayList<>(); for (String name : exaroton.listRemoteDirectory("/" + directory).join()) { if (!safeRemoteJarName(name)) continue; Path targetFile = destination.resolve(name); exaroton.downloadRemoteFile("/" + directory + "/" + name, targetFile).join(); result.add(targetFile); } return result;
    }

    private boolean safeRemoteJarName(String name) { return name != null && name.equals(Path.of(name).getFileName().toString()) && !name.contains("\\") && name.toLowerCase(Locale.ROOT).endsWith(".jar"); }

    private void selectAllUpdates() { updateList.getSelectionModel().clearSelection(); for (int index = 0; index < updateItems.size(); index++) if (updateItems.get(index).updateAvailable()) updateList.getSelectionModel().select(index); }

    private void applySelectedUpdates() {
        if (lastScan == null) { error("Önce güncelleme ve çakışma taraması yap."); return; }
        List<ModUpdateService.UpdateItem> selected = updateList.getSelectionModel().getSelectedItems().stream().filter(ModUpdateService.UpdateItem::updateAvailable).toList(); if (selected.isEmpty()) { error("Güncellenecek en az bir proje seç."); return; }
        if (lastScan.hasErrors()) { error("Kritik çakışmalar çözülmeden otomatik güncelleme yapılmadı. Çakışma Dedektörü'ndeki kırmızı maddeleri kontrol et."); return; }
        String selectedProvider = provider.getValue(), selectedLoader = loader.getValue(), version = gameVersion.getText().trim(), directory = contentDirectory(selectedLoader), apiLoader = apiLoader(selectedLoader); ServerTarget selectedTarget = target.getValue(); ModUpdateService.ScanReport report = lastScan;
        if ("Yerel JAR".equals(selectedProvider) && manager.isRunning() && selectedTarget != null && sameFolder(manager.getServerFolder(), selectedTarget.folder())) { error("Modları güncellemeden önce yerel sunucuyu durdur."); return; }
        if (!confirm(selected.size() + " proje ve gereken bağımlılıklar yedek alınarak güncellensin mi?")) return;
        List<String> versionIds = selected.stream().map(item -> item.latest().versionId()).toList(); updateProgress.setProgress(-1); updateState.setText("Kesin bağımlılıklar çözülüyor ve güvenli güncelleme hazırlanıyor...");
        Task<InstallOutcome> task = new Task<>() { protected InstallOutcome call() throws Exception { ModrinthService.Resolution resolution = modrinth.resolveVersions(versionIds, version, apiLoader); ensureNoFilenameCollision(resolution, report); if ("Yerel JAR".equals(selectedProvider)) { ModInstallManager.InstallReport installed = installer.installLocal(selectedTarget.folder(), directory, resolution, report.installedFilesByProject()); return new InstallOutcome(installed.installedFiles().size() + " dosya güncellendi. Güvenlik yedeği: " + installed.backup()); } return updateExaroton(directory, resolution, report); } };
        task.setOnSucceeded(event -> { updateProgress.setProgress(1); updateState.setText(task.getValue().message()); info("Güncelleme tamamlandı", task.getValue().message() + " Sunucuyu yeniden başlatınca etkinleşecek."); scanUpdates(); }); task.setOnFailed(event -> { updateProgress.setProgress(0); updateState.setText("Güncelleme başarısız • geri alma koruması çalıştı"); error(rootMessage(task.getException())); }); run(task, "mod-update-apply");
    }

    private void ensureNoFilenameCollision(ModrinthService.Resolution resolution, ModUpdateService.ScanReport report) throws IOException {
        Map<String, String> owners = new HashMap<>(); for (ModUpdateService.UpdateItem item : report.items()) owners.put(item.filename().toLowerCase(Locale.ROOT), item.projectId());
        for (ModrinthService.ResolvedFile file : resolution.files()) { String owner = owners.get(file.filename().toLowerCase(Locale.ROOT)); if (owner != null && !owner.isBlank() && !owner.equals(file.projectId())) throw new IOException("Hedef dosya başka bir kurulu projeye ait: " + file.filename()); if (owner != null && owner.isBlank()) throw new IOException("Hedef dosya tanımlanamayan mevcut bir JAR ile çakışıyor: " + file.filename()); }
    }

    private InstallOutcome updateExaroton(String directory, ModrinthService.Resolution resolution, ModUpdateService.ScanReport report) throws Exception {
        ExarotonPane.ProSnapshot snapshot = exaroton.fetchProSnapshot().join(); if (snapshot.online()) throw new IllegalStateException("Exaroton modlarını güncellemeden önce sunucuyu durdur.");
        Path temporary = Files.createTempDirectory("aeromc-exaroton-update-"); Path originals = temporary.resolve("originals").resolve(directory), downloads = temporary.resolve("downloads"); List<Path> uploaded = new ArrayList<>();
        try {
            List<Path> originalFiles = downloadRemoteJars(directory, originals); Path backup = createRemoteBackup(snapshot.name(), directory, originalFiles); List<Path> newFiles = installer.downloadToTemporary(resolution, downloads); Set<String> originalNames = new HashSet<>(); originalFiles.forEach(path -> originalNames.add(path.getFileName().toString()));
            try {
                for (Path file : newFiles) { exaroton.uploadRemoteFile("/" + directory, file).join(); uploaded.add(file); }
                Map<String, String> installed = report.installedFilesByProject(); for (ModrinthService.ResolvedFile file : resolution.files()) { String previous = installed.get(file.projectId()); if (previous == null) continue; String oldName = previous.split("\\|", 2)[0]; if (!oldName.equals(file.filename()) && originalNames.contains(oldName)) exaroton.deleteRemoteFile("/" + directory + "/" + oldName).join(); }
            } catch (Exception failure) {
                Exception rollbackFailure = null; for (Path original : originalFiles) try { exaroton.uploadRemoteFile("/" + directory, original).join(); } catch (Exception rollback) { rollbackFailure = rollback; }
                for (Path file : uploaded) if (!originalNames.contains(file.getFileName().toString())) try { exaroton.deleteRemoteFile("/" + directory + "/" + file.getFileName()).join(); } catch (Exception rollback) { rollbackFailure = rollback; }
                if (rollbackFailure != null) failure.addSuppressed(rollbackFailure); throw failure;
            }
            return new InstallOutcome(newFiles.size() + " dosya Exaroton'da güncellendi. Yerel güvenlik yedeği: " + backup);
        } finally { ModInstallManager.deleteTree(temporary); }
    }

    private Path createRemoteBackup(String serverName, String directory, List<Path> files) throws IOException {
        String safeServer = serverName == null ? "server" : serverName.replaceAll("[^A-Za-z0-9._-]", "_"); Path backups = Path.of(System.getProperty("user.home"), ".aeromc-panel", "exaroton-content-backups", safeServer); Files.createDirectories(backups); Path output = backups.resolve(directory + "-before-update-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) { for (Path file : files) { zip.putNextEntry(new ZipEntry(file.getFileName().toString())); Files.copy(file, zip); zip.closeEntry(); } } return output;
    }

    private boolean sameFolder(Path first, Path second) { return first != null && second != null && first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize()); }

    private void showDetails(ModrinthService.Project project) { if (project == null) { detail.setText("Arama sonuçlarından bir proje seç."); return; } detail.setText(project.title() + "\nGeliştirici: " + project.author() + "  •  Sunucu desteği: " + project.serverSide() + "  •  " + compact(project.downloads()) + " indirme\n" + project.description()); }
    private void openSelectedProject() { ModrinthService.Project project = results.getSelectionModel().getSelectedItem(); if (project != null) openExternal("https://modrinth.com/" + ("plugin".equals(project.projectType()) ? "plugin/" : "mod/") + project.slug()); }
    private void openExternal(String address) { try { hostServices.showDocument(address); } catch (Throwable failure) { error("Tarayıcı açılamadı. Adresi elle açabilirsin:\n" + address); } }
    private String detectVersionFromLog(Path log) { if (!Files.isReadable(log)) return ""; try { String text = Files.readString(log, StandardCharsets.UTF_8); for (Pattern pattern : List.of(Pattern.compile("Starting minecraft server version ([0-9][0-9A-Za-z.\\-]+)", Pattern.CASE_INSENSITIVE), Pattern.compile("\\(MC: ([0-9][^) ]+)\\)", Pattern.CASE_INSENSITIVE), Pattern.compile("Minecraft Version:? ([0-9][0-9A-Za-z.\\-]+)", Pattern.CASE_INSENSITIVE))) { Matcher matcher = pattern.matcher(text); String last = ""; while (matcher.find()) last = matcher.group(1); if (!last.isBlank()) return last; } } catch (IOException ignored) { } return ""; }
    private String detectLoader(Path jar, Path root) { String name = jar.getFileName().toString().toLowerCase(Locale.ROOT); if (name.contains("neoforge")) return "neoforge"; if (name.contains("forge")) return "forge"; if (name.contains("fabric")) return "fabric"; if (name.contains("quilt")) return "quilt"; if (name.contains("paper") || name.contains("purpur")) return name.contains("purpur") ? "purpur" : "paper"; try (ZipFile zip = new ZipFile(jar.toFile())) { if (zip.getEntry("fabric-server-launch.properties") != null || zip.getEntry("net/fabricmc/loader/impl/launch/server/FabricServerLauncher.class") != null) return "fabric"; if (zip.getEntry("META-INF/mods.toml") != null) return "forge"; } catch (IOException ignored) { } if (Files.isDirectory(root.resolve("plugins"))) return "paper"; if (Files.isDirectory(root.resolve("mods"))) return "fabric"; return ""; }
    private String normalizeVersion(String raw) { if (raw == null) return ""; Matcher matcher = Pattern.compile("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?").matcher(raw); return matcher.find() ? matcher.group() : raw.strip(); }
    private String normalizeLoader(String raw) { String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT); if (value.contains("neoforge")) return "neoforge"; if (value.contains("forge")) return "forge"; if (value.contains("fabric")) return "fabric"; if (value.contains("quilt")) return "quilt"; if (value.contains("purpur")) return "purpur"; if (value.contains("paper")) return "paper"; if (value.contains("spigot")) return "spigot"; if (value.contains("bukkit")) return "bukkit"; if (value.contains("vanilla")) return "vanilla"; return value.strip(); }
    private String apiLoader(String selected) { return "purpur".equals(selected) ? "paper" : selected; }
    private String projectType(String selected) { return isPluginLoader(selected) ? "plugin" : "mod"; }
    private String contentDirectory(String selected) { return isPluginLoader(selected) ? "plugins" : "mods"; }
    private boolean isPluginLoader(String selected) { return Set.of("paper", "purpur", "spigot", "bukkit").contains(selected); }
    private String compact(long value) { if (value >= 1_000_000) return String.format(Locale.US, "%.1fM", value / 1_000_000.0); if (value >= 1_000) return String.format(Locale.US, "%.1fK", value / 1_000.0); return Long.toString(value); }
    private Properties load(Path file) { Properties values = new Properties(); try { if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); } } catch (IOException ignored) { } return values; }
    private void addField(GridPane grid, int column, String title, Node field) { VBox box = new VBox(4, new Label(title), field); grid.add(box, column, 0); GridPane.setHgrow(box, Priority.ALWAYS); }
    private VBox card(String title, Node... nodes) { Label heading = new Label(title); heading.getStyleClass().add("section-title"); VBox box = new VBox(10, heading); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Tab tab(String title, Node content) { Tab tab = new Tab(title, content); tab.setClosable(false); return tab; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private void error(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private void info(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, LanguageManager.text(message), ButtonType.OK); alert.setHeaderText(LanguageManager.text(title)); alert.showAndWait(); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, "aeromc-" + name); thread.setDaemon(true); thread.start(); }
    private record ServerTarget(Path jar) { Path folder() { return jar.getParent(); } String name() { return folder().getFileName().toString(); } @Override public String toString() { return name() + "  •  " + folder(); } }
    private record Detected(String version, String loader, String description) { }
    private record InstallOutcome(String message) { }
}
