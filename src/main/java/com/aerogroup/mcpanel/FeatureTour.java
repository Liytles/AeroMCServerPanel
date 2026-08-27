package com.aerogroup.mcpanel;

import javafx.geometry.*;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** AeroMC'nin ilk açılışında gösterilen kısa, tekrar açılabilir özellik turu. */
public final class FeatureTour {
    private record Page(String eyebrow, String title, String body) { }

    private static final List<Page> PAGES = List.of(
            new Page("AEROMC'YE HOŞ GELDİN", "Bütün sunucuların tek merkezde",
                    "Yerel JAR, Exaroton, Aternos ve Pterodactyl sunucularını Sunucular bölümünden yönetebilirsin. Ana Panel ise favorilerini ve önemli bildirimlerini tek bakışta gösterir."),
            new Page("SUNUCUNU KORU", "Sorunu büyümeden yakala",
                    "Kontrol Merkezi; sağlık puanı, Kriz Modu, Çökme Doktoru ve Tek Tık Lag Analizi ile performans sorunlarını anlaşılır biçimde açıklar."),
            new Page("İŞLERİ OTOMATİKLEŞTİR", "Yedek, görev ve kredi koruması",
                    "Planlı yedekler, Exaroton programları, oyuncusuz durdurma ve kredi eşikleri gereksiz masrafı ve unutulan işleri azaltır."),
            new Page("HER ŞEY HAZIR", "Önemli olaylar artık kaybolmaz",
                    "Ana Panel'deki Bildirim Merkezi çökme, yedek, güncelleme, oyuncu ve otomasyon olaylarını saklar. Bu turu Ayarlar → Dil & Arayüz bölümünden tekrar açabilirsin.")
    );

    private FeatureTour() { }

    public static void show(Window owner, Runnable completed) {
        Stage stage = new Stage();
        if (owner != null) { stage.initOwner(owner); stage.initModality(Modality.WINDOW_MODAL); }
        stage.setTitle(LanguageManager.text("AeroMC Kısa Özellik Turu"));
        stage.setResizable(false);

        Label eyebrow = new Label(); eyebrow.getStyleClass().add("section-title");
        Label title = new Label(); title.getStyleClass().add("tour-title"); title.setWrapText(true);
        Label body = new Label(); body.getStyleClass().add("tour-body"); body.setWrapText(true);
        Label progressText = new Label(); progressText.getStyleClass().add("muted");
        ProgressBar progress = new ProgressBar(); progress.setMaxWidth(Double.MAX_VALUE);
        Button skip = button("Turu Geç", "secondary"), back = button("Geri", "secondary"), next = button("İleri", "primary");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, skip, spacer, back, next); controls.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(14, eyebrow, title, body, progressText, progress, controls); card.getStyleClass().addAll("card", "tour-card");
        VBox root = new VBox(card); root.setPadding(new Insets(20)); root.getStyleClass().add("app-root");
        Scene scene = new Scene(root, 620, 390);
        if (owner != null && owner.getScene() != null) scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        stage.setScene(scene);

        int[] index = {0}; AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> { if (finished.compareAndSet(false, true)) completed.run(); stage.close(); };
        Runnable render = () -> {
            Page page = PAGES.get(index[0]);
            eyebrow.setText(LanguageManager.text(page.eyebrow())); title.setText(LanguageManager.text(page.title())); body.setText(LanguageManager.text(page.body()));
            progressText.setText(LanguageManager.text("Adım") + " " + (index[0] + 1) + " / " + PAGES.size()); progress.setProgress((index[0] + 1.0) / PAGES.size());
            back.setDisable(index[0] == 0); next.setText(LanguageManager.text(index[0] == PAGES.size() - 1 ? "Turu Bitir" : "İleri"));
        };
        back.setOnAction(event -> { if (index[0] > 0) { index[0]--; render.run(); } });
        next.setOnAction(event -> { if (index[0] == PAGES.size() - 1) finish.run(); else { index[0]++; render.run(); } });
        skip.setOnAction(event -> finish.run()); stage.setOnCloseRequest(event -> { if (finished.compareAndSet(false, true)) completed.run(); });
        render.run(); stage.show();
    }

    private static Button button(String text, String style) { Button button = new Button(LanguageManager.text(text)); button.getStyleClass().add(style); return button; }
}
