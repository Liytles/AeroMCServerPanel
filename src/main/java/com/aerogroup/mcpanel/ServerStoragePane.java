package com.aerogroup.mcpanel;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;

/** Güvenli dosya editörü, mod/eklenti dosyaları ve dünya işlemlerini tek bileşende toplar. */
final class ServerStoragePane {
    private static final Set<String> SAFE_FILES = Set.of("server.properties", "eula.txt", "whitelist.json", "ops.json", "banned-players.json", "banned-ips.json");
    private final ServerManager manager;
    private final ExarotonPane exaroton;
    private final HostServices hostServices;
    private final BooleanSupplier remote;
    private final Consumer<Path> backupCompleted;
    private final ComboBox<String> fileChoice = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(SAFE_FILES.stream().sorted().toList()));
    private final TextArea fileEditor = new TextArea();
    private final Label fileState = new Label("Dosya seç");
    private final ComboBox<String> contentType = new ComboBox<>(javafx.collections.FXCollections.observableArrayList("Eklentiler (plugins)", "Modlar (mods)"));
    private final ListView<Path> contentList = new ListView<>(), worldList = new ListView<>();

    ServerStoragePane(ServerManager manager, ExarotonPane exaroton, HostServices hostServices, BooleanSupplier remote, Consumer<Path> backupCompleted) {
        this.manager = manager; this.exaroton = exaroton; this.hostServices = hostServices; this.remote = remote; this.backupCompleted = backupCompleted;
    }

    Node buildFilesView() {
        fileChoice.getSelectionModel().select("server.properties"); fileChoice.setOnAction(event -> refreshFile());
        Button load = button("Yükle", "secondary"), save = button("Güvenli Kaydet", "primary"), folder = button("Klasörü Aç", "secondary");
        load.setOnAction(event -> refreshFile()); save.setOnAction(event -> saveSelectedFile()); folder.setOnAction(event -> openServerFolder());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); fileState.getStyleClass().add("muted"); HBox row = new HBox(8, fileChoice, load, save, spacer, fileState, folder); row.setAlignment(Pos.CENTER_LEFT);
        fileEditor.setStyle("-fx-font-family:'JetBrains Mono','Monospace';"); VBox.setVgrow(fileEditor, Priority.ALWAYS); Label note = new Label("Güvenlik için yalnızca temel sunucu yapılandırma dosyaları düzenlenebilir. Kaydetmeden önce otomatik .bak kopyası alınır."); note.getStyleClass().add("muted");
        return page(card("GÜVENLİ DOSYA YÖNETİCİSİ", row, note, fileEditor));
    }

    Node buildContentView() {
        contentType.getSelectionModel().selectFirst(); contentType.setOnAction(event -> refreshContent());
        contentList.setCellFactory(list -> new ListCell<>() { protected void updateItem(Path value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : value.getFileName() + (ServerStoragePane.isDisabled(value) ? "  [DEVRE DIŞI]" : "  [AKTİF]")); } });
        Button refresh = button("Yenile", "secondary"), install = button("Dosya Ekle", "primary"), toggle = button("Etkinleştir / Devre Dışı", "secondary"); refresh.setOnAction(event -> refreshContent()); install.setOnAction(event -> importContent()); toggle.setOnAction(event -> toggleContent());
        HBox top = new HBox(8, contentType, refresh, install, toggle); VBox.setVgrow(contentList, Priority.ALWAYS); Label note = new Label("JAR dosyaları sunucu klasörüne kopyalanır. Değişikliklerin uygulanması için sunucuyu yeniden başlat."); note.getStyleClass().add("muted");
        return page(card("EKLENTİ VE MOD YÖNETİCİSİ", top, note, contentList));
    }

    Node buildWorldsView() {
        worldList.setCellFactory(list -> new ListCell<>() { protected void updateItem(Path value, boolean empty) { super.updateItem(value, empty); setText(empty || value == null ? null : value.getFileName().toString()); } });
        Button refresh = button("Yenile", "secondary"), backup = button("Dünyaları Yedekle", "primary"), restore = button("Seçileni Geri Yükle", "danger"); refresh.setOnAction(event -> refreshWorlds()); backup.setOnAction(event -> backupWorlds()); restore.setOnAction(event -> restoreWorld());
        TextField newName = new TextField(); newName.setPromptText("Yeni dünya adı"); Button create = button("Yeni Dünyayı Ayarla", "secondary"); create.setOnAction(event -> createWorld(newName.getText().trim()));
        HBox top = new HBox(8, refresh, backup, restore), createRow = new HBox(8, newName, create); HBox.setHgrow(newName, Priority.ALWAYS); VBox.setVgrow(worldList, Priority.ALWAYS); Label note = new Label("Geri yükleme sadece sunucu kapalıyken yapılır; mevcut dünya silinmez, tarihli bir kurtarma klasörüne taşınır."); note.getStyleClass().add("muted");
        return page(card("DÜNYA YÖNETİMİ", top, note, worldList, createRow));
    }

    void refreshAll() { refreshFile(); refreshContent(); refreshWorlds(); }
    void refreshFile() {
        if (remote.getAsBoolean()) { String name = fileChoice.getValue(); if (!SAFE_FILES.contains(name)) return; fileState.setText("Exaroton'dan yükleniyor..."); Task<String> task = new Task<>() { protected String call() throws Exception { return exaroton.readRemoteFile("/" + name).join(); } }; task.setOnSucceeded(event -> { fileEditor.setText(task.getValue()); fileState.setText("Exaroton'dan yüklendi"); }); task.setOnFailed(event -> { fileState.setText("Yüklenemedi"); showError(rootMessage(task.getException())); }); run(task, "exaroton-file-load"); return; }
        Path file = safeSelectedFile(); if (file == null) return; try { fileEditor.setText(Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : ""); fileState.setText(Files.exists(file) ? "Yüklendi" : "Yeni dosya"); } catch (IOException error) { showError(error.getMessage()); }
    }

    void backupWorlds() {
        if (remote.getAsBoolean()) { showError("Exaroton resmî API'si yedek oluşturma uç noktası sunmuyor. Diğer Pro işlemleri bağlı; yedeği Exaroton panelinden başlatmalısın."); return; }
        Task<Path> task = new Task<>() { protected Path call() throws Exception { return manager.createBackup(); } }; task.setOnSucceeded(event -> backupCompleted.accept(task.getValue())); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "world-backup");
    }

    private void saveSelectedFile() {
        if (remote.getAsBoolean()) { String name = fileChoice.getValue(); if (!SAFE_FILES.contains(name) || !confirm(name + " Exaroton sunucusunda güncellensin mi?")) return; fileState.setText("Exaroton'a kaydediliyor..."); Task<Void> task = new Task<>() { protected Void call() throws Exception { exaroton.writeRemoteFile("/" + name, fileEditor.getText()).join(); return null; } }; task.setOnSucceeded(event -> fileState.setText("Exaroton'a kaydedildi")); task.setOnFailed(event -> { fileState.setText("Kaydedilemedi"); showError(rootMessage(task.getException())); }); run(task, "exaroton-file-save"); return; }
        Path file = safeSelectedFile(); if (file == null) return; try { Files.createDirectories(file.getParent()); if (Files.exists(file)) Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING); atomicWrite(file, fileEditor.getText()); fileState.setText("Kaydedildi: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))); } catch (IOException error) { showError(error.getMessage()); }
    }
    private Path safeSelectedFile() { Path folder = manager.getServerFolder(); String name = fileChoice.getValue(); if (folder == null) { showError("Önce Yerel JAR sekmesinden server.jar seç."); return null; } if (!SAFE_FILES.contains(name)) { showError("Bu dosyanın düzenlenmesine izin verilmiyor."); return null; } try { return SafePathGuard.resolve(folder, name, true); } catch (IOException error) { showError(error.getMessage()); return null; } }
    private void openServerFolder() { if (remote.getAsBoolean()) { showError("Exaroton dosyaları uzakta olduğu için yerel klasör açılamaz; dosya editörünü kullanabilirsin."); return; } Path folder = manager.getServerFolder(); if (folder == null) showError("Önce bir server.jar seç."); else try { hostServices.showDocument(folder.toUri().toString()); } catch (Exception error) { showError("Klasör açılamadı: " + error.getMessage()); } }

    private Path contentFolder() { Path root = manager.getServerFolder(); if (root == null) return null; try { return SafePathGuard.resolve(root, contentType.getSelectionModel().getSelectedIndex() == 1 ? "mods" : "plugins", true); } catch (IOException error) { showError(error.getMessage()); return null; } }
    void refreshContent() {
        if (remote.getAsBoolean()) { String directory = contentType.getSelectionModel().getSelectedIndex() == 1 ? "/mods" : "/plugins"; Task<List<String>> task = new Task<>() { protected List<String> call() throws Exception { return exaroton.listRemoteDirectory(directory).join(); } }; task.setOnSucceeded(event -> contentList.getItems().setAll(task.getValue().stream().filter(name -> name.endsWith(".jar") || name.endsWith(".jar.disabled")).map(Path::of).toList())); task.setOnFailed(event -> contentList.getItems().clear()); run(task, "exaroton-content-list"); return; }
        Path folder = contentFolder(); if (folder == null) { contentList.getItems().clear(); return; } try { Files.createDirectories(folder); try (var files = Files.list(folder)) { contentList.getItems().setAll(files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && (path.getFileName().toString().endsWith(".jar") || path.getFileName().toString().endsWith(".jar.disabled"))).sorted().toList()); } } catch (IOException error) { showError(error.getMessage()); }
    }
    private void importContent() {
        Path folder = contentFolder(); if (!remote.getAsBoolean() && folder == null) { showError("Önce Yerel JAR sekmesinden server.jar seç."); return; } if (remote.getAsBoolean() && !exaroton.hasActiveServer()) { showError("Önce Exaroton sekmesinden bir sunucu seç."); return; }
        FileChooser chooser = new FileChooser(); chooser.setTitle("Eklenti veya mod JAR dosyası seç"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java arşivi", "*.jar")); File selected = chooser.showOpenDialog(contentList.getScene().getWindow()); if (selected == null) return;
        if (remote.getAsBoolean()) { String directory = contentType.getSelectionModel().getSelectedIndex() == 1 ? "/mods" : "/plugins"; Task<Void> task = new Task<>() { protected Void call() throws Exception { exaroton.uploadRemoteFile(directory, selected.toPath()).join(); return null; } }; task.setOnSucceeded(event -> { refreshContent(); info("Yükleme tamamlandı", selected.getName() + " Exaroton'a gönderildi."); }); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "exaroton-content-upload"); return; }
        try { Files.createDirectories(folder); Path target = SafePathGuard.requireWithin(manager.getServerFolder(), folder.resolve(selected.getName()), true); Files.copy(selected.toPath(), target, StandardCopyOption.REPLACE_EXISTING); refreshContent(); } catch (IOException error) { showError(error.getMessage()); }
    }
    private void toggleContent() { if (remote.getAsBoolean()) { showError("Exaroton API dosya yeniden adlandırmayı desteklemiyor. Dosya yükleme çalışır; devre dışı bırakma işlemini Exaroton panelinden yapabilirsin."); return; } Path selected = contentList.getSelectionModel().getSelectedItem(); if (selected == null) return; String name = selected.getFileName().toString(); Path target = selected.resolveSibling(isDisabled(selected) ? name.substring(0, name.length() - ".disabled".length()) : name + ".disabled"); try { Path root = manager.getServerFolder(); Files.move(SafePathGuard.requireWithin(root, selected, false), SafePathGuard.requireWithin(root, target, true)); refreshContent(); } catch (IOException error) { showError(error.getMessage()); } }
    private static boolean isDisabled(Path path) { return path.getFileName().toString().endsWith(".disabled"); }

    void refreshWorlds() { if (remote.getAsBoolean()) { Task<List<String>> task = new Task<>() { protected List<String> call() throws Exception { return exaroton.listRemoteDirectory("/").join(); } }; task.setOnSucceeded(event -> worldList.getItems().setAll(task.getValue().stream().filter(name -> name.endsWith("/")).map(name -> name.substring(0, name.length() - 1)).filter(name -> name.equals("world") || name.endsWith("_nether") || name.endsWith("_the_end")).map(Path::of).toList())); task.setOnFailed(event -> worldList.getItems().clear()); run(task, "exaroton-world-list"); return; } Path folder = manager.getServerFolder(); if (folder == null) { worldList.getItems().clear(); return; } try (var dirs = Files.list(folder)) { worldList.getItems().setAll(dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)).filter(path -> { String n = path.getFileName().toString(); return n.equals("world") || n.endsWith("_nether") || n.endsWith("_the_end") || Files.exists(path.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS); }).sorted().toList()); } catch (IOException error) { showError(error.getMessage()); } }
    private void createWorld(String name) { if (remote.getAsBoolean()) { showError("Exaroton'da yeni dünya yükleme resmî API tarafından sunulmuyor; server.properties içindeki level-name alanını Pro Dosyalar bölümünden değiştirebilirsin."); return; } if (!name.matches("[A-Za-z0-9_-]{1,40}")) { showError("Dünya adı yalnızca harf, rakam, _ ve - içerebilir."); return; } if (manager.isRunning()) { showError("Yeni dünya seçmeden önce sunucuyu durdur."); return; } Path folder = manager.getServerFolder(); if (folder == null) { showError("Önce server.jar seç."); return; } try { Path properties = SafePathGuard.resolve(folder, "server.properties", true); updateProperty(properties, "level-name", name); info("Dünya ayarlandı", name + " sunucu sonraki açılışta oluşturulacak."); refreshWorlds(); } catch (IOException error) { showError(error.getMessage()); } }
    private void restoreWorld() { if (remote.getAsBoolean()) { showError("Exaroton API dünya ZIP geri yüklemesine izin vermiyor. Bu işlem Exaroton web panelinden yapılmalı."); return; } Path selectedWorld = worldList.getSelectionModel().getSelectedItem(); if (selectedWorld == null) { showError("Geri yüklenecek dünyayı seç."); return; } if (manager.isRunning()) { showError("Geri yüklemeden önce sunucuyu durdur."); return; } FileChooser chooser = new FileChooser(); chooser.setTitle("AeroMC ZIP yedeğini seç"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP yedeği", "*.zip")); File zip = chooser.showOpenDialog(worldList.getScene().getWindow()); if (zip == null || !confirm(selectedWorld.getFileName() + " seçilen yedekten geri yüklensin mi?")) return; Task<Void> task = new Task<>() { protected Void call() throws Exception { restoreWorldZip(zip.toPath(), selectedWorld); return null; } }; task.setOnSucceeded(event -> { refreshWorlds(); info("Geri yükleme tamamlandı", "Eski dünya kurtarma klasörüne taşındı."); }); task.setOnFailed(event -> showError(rootMessage(task.getException()))); run(task, "world-restore"); }
    private void restoreWorldZip(Path zipFile, Path world) throws IOException {
        Path root = manager.getServerFolder(); if (root == null) throw new IOException("Sunucu klasörü bulunamadı."); root = root.toRealPath(LinkOption.NOFOLLOW_LINKS); world = SafePathGuard.requireWithin(root, world, false); if (!world.getParent().equals(root)) throw new IOException("Yalnızca sunucu kökündeki dünyalar geri yüklenebilir.");
        String worldName = world.getFileName().toString(), prefix = worldName + "/"; Path staging = SafePathGuard.resolve(root, ".aeromc-restore-" + UUID.randomUUID(), true), recovery = SafePathGuard.resolve(root, worldName + ".pre-restore-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), true); Files.createDirectory(staging); boolean extracted = false, movedOriginal = false; long total = 0; int entries = 0;
        try { try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) { ZipEntry entry; while ((entry = zip.getNextEntry()) != null) { if (++entries > 100_000) throw new IOException("ZIP çok fazla dosya içeriyor."); String name = entry.getName().replace('\\', '/'); if (!name.startsWith(prefix)) continue; Path target = SafePathGuard.requireWithin(staging, staging.resolve(name).normalize(), true); if (entry.isDirectory()) Files.createDirectories(target); else { Files.createDirectories(target.getParent()); total += copyZipLimited(zip, target, 20L * 1024 * 1024 * 1024 - total); } extracted = true; } } Path stagedWorld = SafePathGuard.requireWithin(staging, staging.resolve(worldName), false); if (!extracted || !Files.isDirectory(stagedWorld, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Yedekte " + worldName + " bulunamadı."); Files.move(world, recovery); movedOriginal = true; Files.move(stagedWorld, world); movedOriginal = false; }
        catch (IOException error) { if (movedOriginal && !Files.exists(world, LinkOption.NOFOLLOW_LINKS) && Files.exists(recovery, LinkOption.NOFOLLOW_LINKS)) Files.move(recovery, world); throw error; }
        finally { deleteStaging(staging); }
    }
    private long copyZipLimited(InputStream input, Path target, long remaining) throws IOException { if (remaining <= 0) throw new IOException("ZIP açılmış boyutu 20 GiB sınırını aşıyor."); long total = 0; byte[] buffer = new byte[64 * 1024]; try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) { int count; while ((count = input.read(buffer)) >= 0) { total += count; if (total > remaining) throw new IOException("ZIP açılmış boyutu 20 GiB sınırını aşıyor."); output.write(buffer, 0, count); } } return total; }
    private void deleteStaging(Path staging) { try { if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return; try (var paths = Files.walk(staging)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } } catch (IOException ignored) { } }

    private void atomicWrite(Path file, String text) throws IOException { Path temp = Files.createTempFile(file.getParent(), ".aeromc-file-", ".tmp"); Files.writeString(temp, text, StandardCharsets.UTF_8); try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); } }
    private void updateProperty(Path file, String key, String value) throws IOException { List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : new ArrayList<>(), out = new ArrayList<>(); boolean found = false; for (String line : lines) { if (!line.stripLeading().startsWith("#") && line.startsWith(key + "=")) { out.add(key + "=" + value); found = true; } else out.add(line); } if (!found) out.add(key + "=" + value); Files.createDirectories(file.getParent()); atomicWrite(file, String.join(System.lineSeparator(), out) + System.lineSeparator()); }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, "aeromc-" + name); thread.setDaemon(true); thread.start(); }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private void showError(String message) { if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(message)); return; } Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private void info(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, LanguageManager.text(message), ButtonType.OK); alert.setHeaderText(LanguageManager.text(title)); alert.showAndWait(); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
    private VBox page(Node... children) { VBox page = new VBox(14, children); page.setPadding(new Insets(18)); for (Node child : children) VBox.setVgrow(child, Priority.ALWAYS); return page; }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
}
