package com.aerogroup.mcpanel;

import javafx.geometry.Insets;
import javafx.application.HostServices;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.util.function.Consumer;

/** Uygulama genelindeki dil, görünüm ve performans tercihlerini tek yerde toplar. */
public final class SettingsPane {
    private final PanelConfig config;
    private final Consumer<Boolean> liveMapToggle;
    private final Consumer<Boolean> credentialVaultToggle;
    private final UpdateCenterPane updateCenter;

    public SettingsPane(PanelConfig config, Consumer<Boolean> liveMapToggle, Consumer<Boolean> credentialVaultToggle, HostServices hostServices) {
        this.config = config; this.liveMapToggle = liveMapToggle; this.credentialVaultToggle = credentialVaultToggle; this.updateCenter = new UpdateCenterPane(config, hostServices);
    }

    public Node buildView() {
        ComboBox<String> language = new ComboBox<>(); language.getItems().addAll("Türkçe", "İngilizce"); language.getSelectionModel().select("en".equals(LanguageManager.load()) ? "İngilizce" : "Türkçe");
        Button apply = button("Dili Uygula", "primary"); apply.setOnAction(event -> { String code = "İngilizce".equals(language.getValue()) ? "en" : "tr"; Parent root = language.getScene().getRoot(); LanguageManager.apply(root, code); });
        Label languageNote = note("Arayüz dili anında değiştirilir ve sonraki açılış için saklanır.");
        FlowPane languageControls = new FlowPane(9, 9, language, apply);

        CheckBox liveMap = new CheckBox("Canlı Harita özelliğini etkinleştir"); liveMap.setSelected(config.isLiveMapEnabled());
        Label mapNote = note("Kapatıldığında Canlı Harita sekmesiyle birlikte konum sorguları, çizim zamanlayıcısı ve Exaroton harita dinleyicileri tamamen durdurulur. Değişiklik anında uygulanır.");
        liveMap.setOnAction(event -> { boolean previous = !liveMap.isSelected(); config.setLiveMapEnabled(liveMap.isSelected()); try { config.save(); liveMapToggle.accept(liveMap.isSelected()); } catch (IOException error) { liveMap.setSelected(previous); config.setLiveMapEnabled(previous); showError(error.getMessage()); } });

        CheckBox automaticVault = new CheckBox("Exaroton ve Discord kimlik bilgilerini bu cihazda otomatik aç");
        automaticVault.setSelected(config.isAutomaticCredentialVaultEnabled());
        Label vaultState = note(vaultStatus(automaticVault.isSelected()));
        Label vaultNote = note("Etkin olduğunda geçerli API anahtarı ve webhook ilk başarılı kullanımdan sonra bu işletim sistemi kullanıcısına ve cihaza bağlı AES-256-GCM kasasında saklanır. Sonraki açılışta alanlara yazılmadan kullanılır. Gizli alanlarda kopyala, kes, sağ tık ve sürükleme kapalıdır. Bu koruma, aynı kullanıcı yetkisiyle çalışan kötü amaçlı yazılımlara karşı mutlak güvence vermez.");
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

        Label organization = note("Sunucu işlemleri Sunucular'da, oyuncu ve yapılandırma işlemleri Yönetim'de, tanılama araçları Kontrol Merkezi'nde, kurulum ve bakım araçları ise Araçlar'da bulunur.");
        VBox page = new VBox(14,
                card("DİL & ARAYÜZ", languageControls, languageNote),
                updateCenter.buildView(),
                card("GÜVENLİ KİMLİK BİLGİLERİ", automaticVault, vaultState, vaultNote),
                card("EXAROTON BAŞLATMA", exarotonReadiness, readinessNote),
                card("PERFORMANS ÖZELLİKLERİ", liveMap, mapNote),
                card("ARAYÜZ DÜZENİ", organization));
        page.setPadding(new Insets(18)); ScrollPane scroll = new ScrollPane(page); scroll.setFitToWidth(true); scroll.getStyleClass().add("control-scroll"); return scroll;
    }

    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Label note(String text) { Label label = new Label(text); label.setWrapText(true); label.getStyleClass().add("muted"); return label; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private String vaultStatus(boolean enabled) {
        if (!enabled) return "Otomatik kasa kapalı; cihaz bağlı kopyalar silinir ve gizli bilgiler her oturumda yeniden istenir.";
        boolean exaroton = DeviceCredentialStore.exists(DeviceCredentialStore.Kind.EXAROTON), discord = DeviceCredentialStore.exists(DeviceCredentialStore.Kind.DISCORD);
        if (exaroton && discord) return "Exaroton ve Discord otomatik kasası hazır.";
        if (exaroton) return "Exaroton kasası hazır; Discord webhook ilk başarılı kullanımda eklenecek.";
        if (discord) return "Discord kasası hazır; Exaroton anahtarı ilk başarılı bağlantıda eklenecek.";
        return "Otomatik kasa açık; anahtarlar ilk başarılı kullanımda güvenli kasaya eklenecek.";
    }
    private void showError(String text) { Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(text == null ? "Bilinmeyen hata" : text), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
}
