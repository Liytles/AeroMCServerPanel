package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.DeviceCredentialStore;
import com.aerogroup.mcpanel.aeroguard.SecurityAuditEngine;
import com.aerogroup.mcpanel.aeroguard.MasterPasswordManager;

import javafx.geometry.Insets;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.geometry.Pos;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

/** Uygulama genelindeki dil, görünüm ve performans tercihlerini tek yerde toplar. */
public final class SettingsPane {
    private final PanelConfig config;
    private final Consumer<Boolean> liveMapToggle;
    private final Consumer<Boolean> credentialVaultToggle;
    private final Runnable featureTour;
    private final UpdateCenterPane updateCenter;
    private final HostServices hostServices;

    public SettingsPane(PanelConfig config, Consumer<Boolean> liveMapToggle, Consumer<Boolean> credentialVaultToggle, Runnable featureTour, HostServices hostServices) {
        this.config = config; this.liveMapToggle = liveMapToggle; this.credentialVaultToggle = credentialVaultToggle; this.featureTour = featureTour; this.hostServices = hostServices; this.updateCenter = new UpdateCenterPane(config, hostServices);
    }

    public Node buildView() {
        ComboBox<String> language = new ComboBox<>(); language.getItems().addAll("Türkçe", "İngilizce"); language.getSelectionModel().select("en".equals(LanguageManager.load()) ? "İngilizce" : "Türkçe");
        Button apply = button("Dili Uygula", "primary"); apply.setOnAction(event -> { String code = "İngilizce".equals(language.getValue()) ? "en" : "tr"; Parent root = language.getScene().getRoot(); LanguageManager.apply(root, code); });
        Label languageNote = note("Arayüz dili anında değiştirilir ve sonraki açılış için saklanır.");
        Button openTour = button("Kısa Özellik Turunu Aç", "secondary"); openTour.setOnAction(event -> featureTour.run());
        FlowPane languageControls = new FlowPane(9, 9, language, apply, openTour);

        Label securityScore = new Label("-"); securityScore.getStyleClass().add("metric"); Label securityState = note("Güvenlik taraması bekleniyor");
        ListView<SecurityAuditEngine.Finding> securityFindings = new ListView<>(); securityFindings.setPrefHeight(230); securityFindings.setPlaceholder(new Label("Henüz güvenlik bulgusu yok"));
        securityFindings.setCellFactory(view -> new ListCell<>() { @Override protected void updateItem(SecurityAuditEngine.Finding finding, boolean empty) { super.updateItem(finding, empty); if (empty || finding == null) { setText(null); return; } String icon = switch (finding.level()) { case PASS -> "✓"; case WARNING -> "⚠"; case CRITICAL -> "✕"; }; setText(icon + "  " + finding.title() + "\n    " + finding.detail()); setWrapText(true); } });
        Button scanSecurity = button("Güvenliği Tara", "primary"), harden = button("Dosya İzinlerini Güçlendir", "secondary");
        Runnable refreshSecurity = () -> applySecurityReport(SecurityAuditEngine.scan(config), securityScore, securityState, securityFindings);
        scanSecurity.setOnAction(event -> refreshSecurity.run()); harden.setOnAction(event -> { try { int changed = SecurityAuditEngine.hardenPermissions(); refreshSecurity.run(); securityState.setText("İzin güçlendirme tamamlandı • " + changed + " öğe güncellendi"); NotificationCenter.shared().publish(NotificationCenter.Severity.SUCCESS, "Güvenlik", "AeroMC izinleri güçlendirildi", changed + " dosya veya klasör yalnızca bu kullanıcıya sınırlandırıldı."); } catch (IOException error) { showError(error.getMessage()); } });
        Label securityNote = note("Tarama yalnızca yerel ayarları ve izinleri okur. Güçlendirme; AeroMC veri klasörünü 700, hassas dosyaları 600 iznine çeker. Windows ve macOS'ta desteklenmeyen POSIX izinleri değiştirilmez.");
        VBox securityCard = card("AEROMC GÜVENLİK KALKANI", new HBox(10, securityScore, securityState), new FlowPane(9, 9, scanSecurity, harden), securityFindings, securityNote); refreshSecurity.run();

        CheckBox liveMap = new CheckBox("Canlı Harita özelliğini etkinleştir"); liveMap.setSelected(config.isLiveMapEnabled());
        Label mapNote = note("Kapatıldığında Canlı Harita sekmesiyle birlikte konum sorguları, çizim zamanlayıcısı ve Exaroton harita dinleyicileri tamamen durdurulur. Değişiklik anında uygulanır.");
        liveMap.setOnAction(event -> { boolean previous = !liveMap.isSelected(); config.setLiveMapEnabled(liveMap.isSelected()); try { config.save(); liveMapToggle.accept(liveMap.isSelected()); } catch (IOException error) { liveMap.setSelected(previous); config.setLiveMapEnabled(previous); showError(error.getMessage()); } });

        CheckBox askMasterPassword = new CheckBox("AeroMC her açıldığında ana parola sor"); askMasterPassword.setSelected(config.isAskMasterPasswordOnLaunch());
        Label masterPasswordState = note(MasterPasswordManager.isConfigured() ? "Ana parola hazır. Parola yalnızca doğrulama özeti olarak saklanır; geri getirilemez." : "Ana parola henüz oluşturulmadı; sonraki açılışta oluşturman istenir.");
        Label masterPasswordNote = note("Ayar aktarımı ve içe aktarma işlemleri ana parola doğrulaması ister. Yeni bilgisayarda aktarımı açabilmek için ilk açılışta aynı ana parolayı oluştur.");
        askMasterPassword.setOnAction(event -> {
            boolean enabled = askMasterPassword.isSelected(), previous = !enabled; config.setAskMasterPasswordOnLaunch(enabled);
            try { config.save(); } catch (IOException error) { askMasterPassword.setSelected(previous); config.setAskMasterPasswordOnLaunch(previous); showError(error.getMessage()); }
        });

        Button exportSettings = button("Ayarları Dışa Aktar", "primary"); Button importSettings = button("Ayarları İçe Aktar", "secondary");
        Label transferNote = note("Ayarlar AES-256-GCM ile şifrelenmiş .aeromc-settings dosyası olarak taşınır. Sunucu dünyaları, günlükler, ana parola doğrulaması ve cihaza bağlı otomatik kasalar güvenlik nedeniyle aktarılmaz.");
        exportSettings.setOnAction(event -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("AeroMC ayarlarını dışa aktar"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("AeroMC ayar dosyası", "*.aeromc-settings")); chooser.setInitialFileName("aeromc-settings.aeromc-settings");
            File target = chooser.showSaveDialog(exportSettings.getScene().getWindow()); if (target == null) return;
            MasterPasswordDialogs.verifiedPassword(exportSettings.getScene().getWindow(), "Ayarları Dışa Aktar", "Ayar yedeğini şifrelemek için AeroMC ana parolanı doğrula.").ifPresent(password -> {
                try { AeroMCSettingsTransfer.exportTo(target.toPath(), password); NotificationCenter.shared().publish(NotificationCenter.Severity.SUCCESS, "Ayarlar", "AeroMC ayarları dışa aktarıldı", target.getName()); showInfo("Ayarlar şifreli olarak dışa aktarıldı."); }
                catch (Exception error) { showError(error.getMessage()); } finally { Arrays.fill(password, '\0'); }
            });
        });
        importSettings.setOnAction(event -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("AeroMC ayarlarını içe aktar"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("AeroMC ayar dosyası", "*.aeromc-settings"));
            File source = chooser.showOpenDialog(importSettings.getScene().getWindow()); if (source == null) return;
            MasterPasswordDialogs.verifiedPassword(importSettings.getScene().getWindow(), "Ayarları İçe Aktar", "Aktarım dosyasını açmak için AeroMC ana parolanı doğrula.").ifPresent(password -> {
                try { int files = AeroMCSettingsTransfer.importFrom(source.toPath(), password); NotificationCenter.shared().publish(NotificationCenter.Severity.SUCCESS, "Ayarlar", "AeroMC ayarları içe aktarıldı", files + " ayar dosyası geri yüklendi."); showInfo(files + " ayar dosyası içe aktarıldı. Değişikliklerin tamamı için AeroMC'yi yeniden başlat."); }
                catch (Exception error) { showError(error.getMessage()); } finally { Arrays.fill(password, '\0'); }
            });
        });

        CheckBox automaticVault = new CheckBox("Exaroton, Pterodactyl ve Discord kimlik bilgilerini bu cihazda otomatik aç");
        automaticVault.setSelected(config.isAutomaticCredentialVaultEnabled());
        Label vaultState = note(vaultStatus(automaticVault.isSelected()));
        Label vaultNote = note("Etkin olduğunda geçerli Exaroton/Pterodactyl API anahtarları ve Discord webhook'u ilk başarılı kullanımdan sonra bu işletim sistemi kullanıcısına ve cihaza bağlı AES-256-GCM kasasında saklanır. Sonraki açılışta alanlara yazılmadan kullanılır. Gizli alanlarda kopyala, kes, sağ tık ve sürükleme kapalıdır. Bu koruma, aynı kullanıcı yetkisiyle çalışan kötü amaçlı yazılımlara karşı mutlak güvence vermez.");
        automaticVault.setOnAction(event -> {
            boolean enabled = automaticVault.isSelected(), previous = !enabled;
            config.setAutomaticCredentialVaultEnabled(enabled);
            try {
                config.save(); credentialVaultToggle.accept(enabled); vaultState.setText(vaultStatus(enabled));
            } catch (RuntimeException | IOException error) {
                automaticVault.setSelected(previous); config.setAutomaticCredentialVaultEnabled(previous);
                try { config.save(); } catch (IOException ignored) { }
                showError(error.getMessage());
            }
        });

        CheckBox exarotonReadiness = new CheckBox("Exaroton sunucusunu başlatmadan önce hazırlık denetimi yap");
        exarotonReadiness.setSelected(config.isExarotonReadinessCheckEnabled());
        Label readinessNote = note("Açıkken adres, yazılım, RAM ve kredi kontrol edilir. Exaroton cevap vermezse denetim en geç 12 saniyede sonlandırılır ve sunucuyu denetimsiz başlatma seçeneği gösterilir. Kapalıyken Başlat düğmesi isteği doğrudan Exaroton'a gönderir.");
        exarotonReadiness.setOnAction(event -> {
            boolean enabled = exarotonReadiness.isSelected(), previous = !enabled;
            config.setExarotonReadinessCheckEnabled(enabled);
            try { config.save(); }
            catch (IOException error) { exarotonReadiness.setSelected(previous); config.setExarotonReadinessCheckEnabled(previous); showError(error.getMessage()); }
        });

        CheckBox inGameCommands = new CheckBox("OP oyuncuları için oyun içi AeroMC komutlarını etkinleştir");
        inGameCommands.setSelected(config.isInGameCommandsEnabled());
        Label inGameNote = note("AeroMC açıkken konsoldaki .aeromc sağlık, performans, oyuncular, kriz, durum, yardım ve duyur <mesaj> sohbetlerini algılar; yalnız ops.json içindeki OP oyunculara tellraw ile cevap verir. Duyurular 160 karakterle sınırlıdır. Yerel JAR, Exaroton ve Pterodactyl desteklenir. Aternos yönetim konsolu sunmadığından kullanılamaz.");
        inGameCommands.setOnAction(event -> {
            boolean enabled = inGameCommands.isSelected(), previous = !enabled; config.setInGameCommandsEnabled(enabled);
            try { config.save(); }
            catch (IOException error) { inGameCommands.setSelected(previous); config.setInGameCommandsEnabled(previous); showError(error.getMessage()); }
        });

        Label javaState = note("Minecraft Java aranıyor...");
        Button chooseJava = button("Java Seç", "primary");
        Button refreshJava = button("Yeniden Tara", "secondary");
        Button automaticJava = button("Otomatik Seçime Dön", "secondary");
        chooseJava.setOnAction(event -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Minecraft için java veya java.exe seç");
            Path current = config.getJavaExecutable();
            if (current != null && current.getParent() != null && current.getParent().toFile().isDirectory()) chooser.setInitialDirectory(current.getParent().toFile());
            File selected = chooser.showOpenDialog(chooseJava.getScene().getWindow());
            if (selected != null) saveJavaSelection(selected.toPath(), javaState);
        });
        refreshJava.setOnAction(event -> refreshJavaStatus(javaState));
        automaticJava.setOnAction(event -> {
            Path previous = config.getJavaExecutable(); config.setJavaExecutable(null);
            try { config.save(); refreshJavaStatus(javaState); }
            catch (IOException error) { config.setJavaExecutable(previous); showError(error.getMessage()); }
        });
        FlowPane javaControls = new FlowPane(9, 9, chooseJava, refreshJava, automaticJava);
        Label javaNote = note("Özel seçim sunucu başlatma ve Başlatma Kontrolü tarafından birlikte kullanılır. Seçili dosya sonradan kaldırılırsa AeroMC gömülü Java, AEROMC_JAVA, JAVA_HOME ve sistem PATH seçeneklerine güvenli biçimde geri döner.");
        refreshJavaStatus(javaState);

        Label organization = note("Sunucu işlemleri Sunucular'da, oyuncu ve yapılandırma işlemleri Yönetim'de, tanılama araçları Kontrol Merkezi'nde, kurulum ve bakım araçları ise Araçlar'da bulunur.");
        Label publisherText = new Label("Made and Published by");
        Hyperlink publisherLink = new Hyperlink("Liytles (GitHub)");
        publisherLink.setTooltip(new Tooltip("AeroMCServerPanel GitHub sayfasını aç"));
        publisherLink.setOnAction(event -> {
            try { hostServices.showDocument("https://github.com/Liytles/AeroMCServerPanel"); }
            catch (Exception error) { showError("GitHub sayfası açılamadı: " + error.getMessage()); }
        });
        HBox publisher = new HBox(5, publisherText, publisherLink); publisher.setAlignment(Pos.CENTER); publisher.getStyleClass().add("publisher-footer");
        VBox page = new VBox(14,
                card("DİL & ARAYÜZ", languageControls, languageNote),
                securityCard,
                updateCenter.buildView(),
                card("MINECRAFT JAVA YÖNETİMİ", javaState, javaControls, javaNote),
                card("AEROMC ANA PAROLASI", askMasterPassword, masterPasswordState, masterPasswordNote),
                card("GÜVENLİ AYAR AKTARIMI", new FlowPane(9, 9, exportSettings, importSettings), transferNote),
                card("GÜVENLİ KİMLİK BİLGİLERİ", automaticVault, vaultState, vaultNote),
                card("EXAROTON BAŞLATMA", exarotonReadiness, readinessNote),
                card("OYUN İÇİ AEROMC KOMUTLARI", inGameCommands, inGameNote),
                card("PERFORMANS ÖZELLİKLERİ", liveMap, mapNote),
                card("ARAYÜZ DÜZENİ", organization),
                publisher);
        page.setPadding(new Insets(18)); ScrollPane scroll = new ScrollPane(page); scroll.setFitToWidth(true); scroll.getStyleClass().add("control-scroll"); return scroll;
    }

    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Label note(String text) { Label label = new Label(text); label.setWrapText(true); label.getStyleClass().add("muted"); return label; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private void saveJavaSelection(Path executable, Label state) {
        state.setText("Seçilen Java doğrulanıyor...");
        Thread worker = new Thread(() -> {
            Path previous = config.getJavaExecutable();
            try {
                JavaRuntimeResolver.RuntimeInfo info = JavaRuntimeResolver.inspect(executable);
                config.setJavaExecutable(info.executable()); config.save();
                Platform.runLater(() -> state.setText(javaDescription(info)));
            } catch (Exception error) {
                config.setJavaExecutable(previous);
                Platform.runLater(() -> { refreshJavaStatus(state); showError(error.getMessage()); });
            }
        }, "aeromc-java-selection"); worker.setDaemon(true); worker.start();
    }
    private void refreshJavaStatus(Label state) {
        state.setText("Minecraft Java aranıyor...");
        Thread worker = new Thread(() -> {
            try {
                Path preferred = config.getJavaExecutable(); JavaRuntimeResolver.RuntimeInfo info = JavaRuntimeResolver.resolve(preferred);
                String text = javaDescription(info);
                if (preferred != null && !info.executable().equals(preferred.toAbsolutePath().normalize())) text += " • Özel seçim kullanılamadığı için otomatik Java'ya dönüldü";
                String result = text; Platform.runLater(() -> state.setText(result));
            } catch (IOException error) { Platform.runLater(() -> state.setText("Java bulunamadı • " + error.getMessage())); }
        }, "aeromc-java-scan"); worker.setDaemon(true); worker.start();
    }
    private String javaDescription(JavaRuntimeResolver.RuntimeInfo info) { return "Java " + info.feature() + " hazır • " + info.source() + " • " + info.executable(); }
    private String vaultStatus(boolean enabled) {
        if (!enabled) return "Otomatik kasa kapalı; cihaz bağlı kopyalar silinir ve gizli bilgiler her oturumda yeniden istenir.";
        boolean exaroton = DeviceCredentialStore.exists(DeviceCredentialStore.Kind.EXAROTON);
        boolean pterodactyl = DeviceCredentialStore.exists(DeviceCredentialStore.Kind.PTERODACTYL);
        boolean discord = DeviceCredentialStore.exists(DeviceCredentialStore.Kind.DISCORD);
        int ready = (exaroton ? 1 : 0) + (pterodactyl ? 1 : 0) + (discord ? 1 : 0);
        if (ready == 3) return "Exaroton, Pterodactyl ve Discord otomatik kasaları hazır.";
        if (ready == 0) return "Otomatik kasa açık; kimlik bilgileri ilk başarılı kullanımda güvenli kasaya eklenecek.";
        return ready + " / 3 otomatik kasa hazır • eksik bilgiler ilk başarılı kullanımda eklenecek.";
    }
    private void applySecurityReport(SecurityAuditEngine.Report report, Label score, Label state, ListView<SecurityAuditEngine.Finding> findings) { score.setText(report.score() + " / 100"); state.setText(report.state()); findings.getItems().setAll(report.findings()); }
    private void showError(String text) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(text == null ? "Bilinmeyen hata" : text), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private void showInfo(String text) { Alert alert = new Alert(Alert.AlertType.INFORMATION, LanguageManager.text(text), ButtonType.OK); alert.setHeaderText(LanguageManager.text("AeroMC ayarları")); alert.showAndWait(); }
}
