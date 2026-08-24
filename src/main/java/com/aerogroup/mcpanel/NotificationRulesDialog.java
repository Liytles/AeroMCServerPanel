package com.aerogroup.mcpanel;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Sunucu bazlı bildirim türleri, sessiz saatler ve tekrar beklemesi arayüzü. */
final class NotificationRulesDialog {
    private record RuleControls(CheckBox sourceEnabled, Map<NotificationCenter.EventType, CheckBox> events) { }
    private static final LinkedHashMap<NotificationCenter.EventType, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put(NotificationCenter.EventType.ONLINE, "Açılma"); TYPES.put(NotificationCenter.EventType.OFFLINE, "Kapanma"); TYPES.put(NotificationCenter.EventType.PLAYER, "Oyuncu");
        TYPES.put(NotificationCenter.EventType.CRASH, "Çökme"); TYPES.put(NotificationCenter.EventType.PERFORMANCE, "RAM / Kriz / Lag"); TYPES.put(NotificationCenter.EventType.BACKUP, "Yedek"); TYPES.put(NotificationCenter.EventType.AUTOMATION, "Otomasyon");
    }
    private NotificationRulesDialog() { }

    static void show(Window owner, NotificationCenter center) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle(LanguageManager.text("Bildirim Kuralları & Sessiz Saatler")); dialog.setHeaderText(LanguageManager.text("Her sunucudan hangi olayların gelebileceğini seç"));
        if (owner != null) { dialog.initOwner(owner); if (owner.getScene() != null) dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets()); }
        Map<String, RuleControls> controls = new LinkedHashMap<>(); VBox serverRows = new VBox(10);
        for (String source : center.serverSources()) {
            Label name = new Label(displaySource(source)); name.getStyleClass().add("server-card-title");
            CheckBox master = new CheckBox("Bu sunucudan bildirim al"); master.setSelected(center.isServerSourceEnabled(source));
            Map<NotificationCenter.EventType, CheckBox> eventControls = new EnumMap<>(NotificationCenter.EventType.class); FlowPane events = new FlowPane(12, 8);
            TYPES.forEach((type, label) -> { CheckBox box = new CheckBox(label); box.setSelected(center.isEventEnabled(source, type)); box.setDisable(!master.isSelected()); eventControls.put(type, box); events.getChildren().add(box); });
            master.setOnAction(event -> eventControls.values().forEach(box -> box.setDisable(!master.isSelected())));
            Label hint = new Label(source); hint.getStyleClass().add("muted"); hint.setWrapText(true);
            VBox row = new VBox(8, name, hint, master, events); row.getStyleClass().add("card"); serverRows.getChildren().add(row); controls.put(source, new RuleControls(master, eventControls));
        }
        ScrollPane serverScroll = new ScrollPane(serverRows); serverScroll.setFitToWidth(true); serverScroll.setPrefViewportHeight(330); serverScroll.getStyleClass().add("control-scroll");
        CheckBox quiet = new CheckBox("Sessiz saatleri etkinleştir"); quiet.setSelected(center.isQuietHoursEnabled());
        TextField start = new TextField(center.getQuietStart().toString()), end = new TextField(center.getQuietEnd().toString()); start.setPrefWidth(90); end.setPrefWidth(90);
        Spinner<Integer> cooldown = new Spinner<>(0, 3600, center.getCooldownSeconds(), 30); cooldown.setEditable(true); cooldown.setPrefWidth(120);
        FlowPane delivery = new FlowPane(9, 9, quiet, new Label("Başlangıç"), start, new Label("Bitiş"), end, new Label("Aynı uyarı beklemesi (sn)"), cooldown);
        Label note = new Label("Sessiz saatlerde olaylar Bildirim Merkezi geçmişine yazılır, yalnızca masaüstü popup'ı susturulur. Gece yarısını aşan aralıklar desteklenir: 23:00 → 08:00."); note.setWrapText(true); note.getStyleClass().add("muted");
        VBox content = new VBox(12, section("SUNUCU BAZLI KURALLAR"), serverScroll, section("SESSİZ SAATLER & TEKRAR KORUMASI"), delivery, note); content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setPrefSize(820, 650); ButtonType save = new ButtonType("Kuralları Kaydet", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != save) return;
        try {
            DateTimeFormatter format = DateTimeFormatter.ofPattern("H:mm"); LocalTime startTime = LocalTime.parse(start.getText().trim(), format), endTime = LocalTime.parse(end.getText().trim(), format);
            controls.forEach((source, rule) -> { center.setServerSourceEnabled(source, rule.sourceEnabled().isSelected()); rule.events().forEach((type, box) -> center.setEventEnabled(source, type, box.isSelected())); });
            center.configureDelivery(quiet.isSelected(), startTime, endTime, Integer.parseInt(cooldown.getEditor().getText().trim()));
        } catch (Exception error) { Alert alert = new Alert(Alert.AlertType.ERROR, "Saatleri 23:00 veya 08:30 biçiminde gir.", ButtonType.OK); alert.setHeaderText("Bildirim kuralları kaydedilemedi"); alert.showAndWait(); }
    }

    private static Label section(String text) { Label label = new Label(LanguageManager.text(text)); label.getStyleClass().add("section-title"); return label; }
    private static String displaySource(String source) { String value = source.substring("Sunucu • ".length()); return value.startsWith("Favori • ") ? LanguageManager.text("Favori") + " • " + value.substring("Favori • ".length()) : LanguageManager.text(value); }
}
