package com.aerogroup.mcpanel;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.function.IntConsumer;

/** Safe, reviewable presets for the most common AeroMC server setups. */
public final class ServerProfilesPane {
    private final PanelConfig config;
    private final ExarotonPane exaroton;
    private final IntConsumer localMemory;
    private final ComboBox<Profile> picker = new ComboBox<>();
    private final Label title = new Label();
    private final Label detail = new Label();
    private final Label changes = new Label();
    private final Label state = new Label();

    public ServerProfilesPane(PanelConfig config, ExarotonPane exaroton, IntConsumer localMemory) {
        this.config = config; this.exaroton = exaroton; this.localMemory = localMemory;
    }

    public Node buildView() {
        picker.setItems(FXCollections.observableArrayList(Profile.values()));
        picker.getSelectionModel().select(Profile.fromId(config.getServerProfile()));
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setOnAction(event -> showProfile(picker.getValue()));
        title.getStyleClass().add("metric"); detail.setWrapText(true); detail.getStyleClass().add("muted"); changes.setWrapText(true); changes.getStyleClass().add("muted"); state.getStyleClass().add("muted");
        Button apply = button("PROFİLİ UYGULA", "primary"); apply.setOnAction(event -> apply());
        FlowPane actions = new FlowPane(10, 8, apply); actions.setAlignment(Pos.CENTER_LEFT);
        showProfile(picker.getValue());
        Label safety = new Label("Profil, yalnız aşağıdaki açıkça belirtilen tercihleri değiştirir. Sunucuyu başlatmaz, durdurmaz, API anahtarlarını değiştirmez ve oyuncuları etkilemez. Çalışan yerel sunucuda RAM değişimi bir sonraki başlatmada uygulanır."); safety.setWrapText(true); safety.getStyleClass().add("muted");
        VBox page = new VBox(14, card("SUNUCU PROFİLLERİ", picker, title, detail, changes, actions, state), card("GÜVENLİ UYGULAMA", safety)); page.setPadding(new Insets(18)); return page;
    }

    private void showProfile(Profile profile) {
        if (profile == null) return;
        title.setText(LanguageManager.text(profile.label)); detail.setText(LanguageManager.text(profile.description)); changes.setText(LanguageManager.text(profile.changes));
    }
    private void apply() {
        Profile profile = picker.getValue(); if (profile == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(profile.changes) + "\n\n" + LanguageManager.text("Uygulansın mı?"), ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText(LanguageManager.text("Sunucu profili uygulanacak"));
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        config.setServerProfile(profile.id);
        config.setCrisisModeEnabled(profile.crisisEnabled);
        config.setCrisisTpsThreshold(profile.tpsThreshold);
        config.setCrisisRamThreshold(profile.ramThreshold);
        config.setCrisisTriggerSeconds(profile.triggerSeconds);
        if (profile.minimumLocalRamMb > 0 && config.getMemoryMb() < profile.minimumLocalRamMb) {
            config.setMemoryMb(profile.minimumLocalRamMb); localMemory.accept(profile.minimumLocalRamMb);
        }
        exaroton.applyServerProfile(profile.id);
        try { config.save(); state.setText(LanguageManager.text(profile.label) + " " + LanguageManager.text("uygulandı.") + " " + LanguageManager.text(profile.result)); }
        catch (IOException error) { state.setText(LanguageManager.text("Profil bellekte uygulandı ancak kaydedilemedi:") + " " + error.getMessage()); }
    }
    private static VBox card(String heading, Node... children) {
        Label label = new Label(heading); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(children); box.getStyleClass().add("card"); return box;
    }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }

    private enum Profile {
        FRIENDS("friends", "Arkadaş SMP", "Dengeli ve sessiz koruma; küçük topluluk sunucuları için.", "Kriz Modu açılır: TPS 15, RAM %92, 12 sn tetikleme. Yerel RAM en az 2 GiB olur. Exaroton'da oyuncusuz durdurma tercihi 10 dk olarak hazırlanır; otomasyon kendiliğinden açılmaz.", true, 15, 92, 12, 2048, "Mevcut otomasyonlar kapalıysa kapalı kalır."),
        PERFORMANCE("performance", "Performans", "Yoğun oyunculu veya modlu sunucularda hızlı erken uyarı.", "Kriz Modu açılır: TPS 18, RAM %85, 8 sn tetikleme. Yerel RAM en az 4 GiB olur. Exaroton için çökme kurtarma tercihi hazırlanır; otomasyon kendiliğinden açılmaz.", true, 18, 85, 8, 4096, "RAM değişimi sonraki yerel başlatmada geçerlidir."),
        MAINTENANCE("maintenance", "Bakım", "Bakım sırasında güvenli görünürlük ve daha sakin kriz eşikleri.", "Kriz Modu açılır: TPS 16, RAM %90, 15 sn tetikleme. Başlatma/durdurma veya bakım modu otomatik çalıştırılmaz.", true, 16, 90, 15, 0, "Bakım işlemlerini yine sen başlatırsın."),
        ECONOMY("economy", "Ekonomi", "Exaroton kredi harcamasını önceleyen güvenli yapı.", "Kriz Modu açılır: TPS 16, RAM %90, 12 sn tetikleme. Exaroton düşük kredi eşiği 3 krediye hazırlanır; eşikte otomatik durdurma açık değildir.", true, 16, 90, 12, 0, "Kredi eşiğinde önce bildirim gelir; durdurma için ayrıca onay gerekir.");
        final String id, label, description, changes, result; final boolean crisisEnabled; final double tpsThreshold, ramThreshold; final int triggerSeconds, minimumLocalRamMb;
        Profile(String id, String label, String description, String changes, boolean crisisEnabled, double tpsThreshold, double ramThreshold, int triggerSeconds, int minimumLocalRamMb, String result) { this.id = id; this.label = label; this.description = description; this.changes = changes; this.crisisEnabled = crisisEnabled; this.tpsThreshold = tpsThreshold; this.ramThreshold = ramThreshold; this.triggerSeconds = triggerSeconds; this.minimumLocalRamMb = minimumLocalRamMb; this.result = result; }
        static Profile fromId(String id) { for (Profile value : values()) if (value.id.equals(id)) return value; return FRIENDS; }
        @Override public String toString() { return LanguageManager.text(label); }
    }
}
