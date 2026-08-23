package com.aerogroup.mcpanel;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Ayarlar içindeki GitHub Releases tabanlı AeroMC uygulama güncelleme merkezi. */
public final class UpdateCenterPane {
    private final PanelConfig config;
    private final HostServices hostServices;
    private final UpdateService service = new UpdateService();
    private final Label current = metric(BuildInfo.displayVersion()), latest = metric("-");
    private final Label state = new Label("Güncelleme kontrolü bekleniyor");
    private final TextArea notes = new TextArea();
    private final ProgressBar progress = new ProgressBar(0);
    private final ComboBox<String> channel = new ComboBox<>();
    private final CheckBox automatic = new CheckBox("AeroMC açıldığında güncellemeleri kontrol et");
    private final Button check = button("Güncellemeyi Kontrol Et", "secondary"), download = button("İndir ve Kur", "primary"), releasePage = button("GitHub Sürümünü Aç", "secondary");
    private UpdateService.ReleaseInfo selected;
    private Path downloaded;
    private boolean checking, downloading;

    public UpdateCenterPane(PanelConfig config, HostServices hostServices) { this.config = config; this.hostServices = hostServices; }

    public Node buildView() {
        channel.getItems().setAll("Kararlı", "Beta"); channel.getSelectionModel().select("beta".equals(config.getUpdateChannel()) ? "Beta" : "Kararlı");
        automatic.setSelected(config.isAutomaticUpdateCheckEnabled());
        channel.setOnAction(event -> { config.setUpdateChannel("Beta".equals(channel.getValue()) ? "beta" : "stable"); saveConfig(); selected = null; downloaded = null; latest.setText("-"); notes.clear(); download.setDisable(true); state.setText("Kanal değişti • yeniden kontrol et"); });
        automatic.setOnAction(event -> { config.setAutomaticUpdateCheckEnabled(automatic.isSelected()); saveConfig(); });
        check.setOnAction(event -> check(false)); download.setOnAction(event -> downloadOrOpen()); releasePage.setOnAction(event -> openReleasePage());
        download.setDisable(true); releasePage.setDisable(true);
        notes.setEditable(false); notes.setWrapText(true); notes.setPromptText("Sürüm notları burada gösterilecek"); notes.setPrefRowCount(5); notes.setMaxHeight(150);
        progress.setMaxWidth(Double.MAX_VALUE); state.setWrapText(true); state.getStyleClass().add("muted");
        TilePane versions = new TilePane(12, 12, metricCard("KURULU SÜRÜM", current), metricCard("SON YAYIN", latest)); versions.setPrefColumns(2); versions.setPrefTileWidth(260);
        FlowPane controls = new FlowPane(9, 9, new Label("Kanal"), channel, automatic, check, download, releasePage); controls.setAlignment(Pos.CENTER_LEFT);
        Label security = new Label("Paket yalnızca Liytles/AeroMCServerPanel GitHub Releases adresinden indirilir. Dosya adı, boyut, HTTPS yönlendirmesi ve yayınla gelen SHA-256 değeri doğrulanmadan kurucu açılmaz. Güncelleme; sunucu klasörlerine, ayarlara veya kimlik kasalarına dokunmaz."); security.setWrapText(true); security.getStyleClass().add("muted");
        VBox card = card("AEROMC GÜNCELLEME MERKEZİ", versions, controls, progress, state, notes, security);
        if (automatic.isSelected()) Platform.runLater(() -> check(true));
        return card;
    }

    private void check(boolean silent) {
        if (checking || downloading) return; checking = true; selected = null; downloaded = null; progress.setProgress(-1); check.setDisable(true); download.setDisable(true); releasePage.setDisable(true); state.setText("GitHub sürümleri kontrol ediliyor...");
        UpdateService.Channel selectedChannel = "Beta".equals(channel.getValue()) ? UpdateService.Channel.BETA : UpdateService.Channel.STABLE;
        Task<UpdateService.ReleaseInfo> task = new Task<>() { protected UpdateService.ReleaseInfo call() throws Exception { return service.check(selectedChannel); } };
        task.setOnSucceeded(event -> {
            checking = false; check.setDisable(false); progress.setProgress(0); selected = task.getValue(); latest.setText("v" + selected.version() + (selected.prerelease() ? " Beta" : "")); notes.setText(selected.notes().isBlank() ? "Bu yayın için sürüm notu girilmemiş." : selected.notes()); releasePage.setDisable(false);
            if (selected.installer() == null) { state.setText("Bu işletim sistemi için kurulum paketi bulunamadı."); return; }
            if (selected.checksum() == null) { state.setText("Kurulum paketinin .sha256 doğrulama dosyası eksik; güvenlik nedeniyle indirme kapatıldı."); return; }
            boolean newer = selected.isNewerThan(BuildInfo.version()); download.setDisable(!newer); state.setText(newer ? "Yeni AeroMC sürümü hazır • " + selected.installer().name() : "AeroMC güncel • " + BuildInfo.displayVersion());
            if (newer && silent) DesktopNotifier.show("AeroMC güncellemesi", "v" + selected.version() + " indirilmeye hazır.");
        });
        task.setOnFailed(event -> { checking = false; check.setDisable(false); progress.setProgress(0); state.setText("Güncelleme kontrolü başarısız: " + rootMessage(task.getException())); if (!silent) showError(rootMessage(task.getException())); });
        run(task, "aeromc-update-check");
    }

    private void downloadOrOpen() {
        if (downloaded != null) { openInstaller(); return; }
        if (selected == null || downloading) return; downloading = true; progress.setProgress(0); check.setDisable(true); download.setDisable(true); state.setText("Güncelleme indiriliyor...");
        UpdateService.ReleaseInfo release = selected;
        Task<Path> task = new Task<>() { protected Path call() throws Exception { return service.download(release, (received, total) -> Platform.runLater(() -> progress.setProgress(total <= 0 ? -1 : Math.min(1, received / (double) total)))); } };
        task.setOnSucceeded(event -> { downloading = false; check.setDisable(false); downloaded = task.getValue(); progress.setProgress(1); download.setText("Doğrulandı • Kurucuyu Aç"); download.setDisable(false); state.setText("SHA-256 doğrulandı • " + downloaded.getFileName()); });
        task.setOnFailed(event -> { downloading = false; check.setDisable(false); download.setDisable(false); progress.setProgress(0); state.setText("Güncelleme indirilemedi veya doğrulanamadı"); showError(rootMessage(task.getException())); });
        run(task, "aeromc-update-download");
    }

    private void openInstaller() {
        if (downloaded == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Doğrulanmış kurulum paketi açılacak. Kurulum başlayınca AeroMC'yi kapat. Sunucu ayarların ve güvenli kasaların korunacak.", ButtonType.YES, ButtonType.NO); confirm.setHeaderText("AeroMC " + selected.version() + " kurulumunu aç");
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(downloaded.toFile());
            else hostServices.showDocument(downloaded.toUri().toString());
            state.setText("Kurucu açıldı • kuruluma geçerken AeroMC'yi kapat");
        } catch (Exception error) { showError("Kurulum paketi açılamadı: " + rootMessage(error)); }
    }
    private void openReleasePage() {
        if (selected == null) return; try { hostServices.showDocument(selected.page().toString()); } catch (Exception error) { showError("GitHub sürüm sayfası açılamadı: " + rootMessage(error)); }
    }
    private void saveConfig() { try { config.save(); } catch (IOException error) { state.setText("Güncelleme tercihi kaydedilemedi: " + error.getMessage()); } }
    private VBox metricCard(String title, Label value) { VBox box = card(title, value); box.setMaxWidth(Double.MAX_VALUE); return box; }
    private static Label metric(String text) { Label label = new Label(text); label.getStyleClass().add("metric"); return label; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private static VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); thread.start(); }
    private void showError(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private static String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
}
