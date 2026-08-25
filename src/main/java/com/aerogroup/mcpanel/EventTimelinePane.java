package com.aerogroup.mcpanel;

import javafx.application.Platform;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Sunucu olay geçmişinin görünümünü, kalıcılığını ve tekrar engelini tek yerde tutar. */
final class EventTimelinePane {
    static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "event-timeline.log");
    private final ObservableList<String> events = FXCollections.observableArrayList();

    EventTimelinePane() { load(); }

    Node buildView() {
        ListView<String> list = new ListView<>(events); list.setPlaceholder(new Label("Henüz kaydedilmiş olay yok")); VBox.setVgrow(list, Priority.ALWAYS);
        Button copy = button("Son Olayları Kopyala", "secondary"), clear = button("Zaman Çizelgesini Temizle", "danger");
        copy.setOnAction(event -> { javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent(); content.putString(String.join(System.lineSeparator(), events)); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); });
        clear.setOnAction(event -> { if (confirm("Tüm olay zaman çizelgesi silinsin mi?")) { events.clear(); save(); } });
        Label note = new Label("Başlatma, kapanma/çökme, oyuncu, yedek, kriz modu ve Spark profil olayları bu bilgisayarda saklanır."); note.getStyleClass().add("muted");
        return page(card("SUNUCU OLAY ZAMAN ÇİZELGESİ", note, list, new HBox(8, copy, clear)));
    }

    void record(String category, String detail) {
        Runnable add = () -> {
            String clean = trim(detail == null ? "Bilinmeyen olay" : detail, 220);
            String suffix = "  •  " + category + "  •  " + clean;
            if (!events.isEmpty() && events.get(0).endsWith(suffix)) return;
            events.add(0, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")) + suffix);
            while (events.size() > 300) events.remove(events.size() - 1);
            save();
        };
        if (Platform.isFxApplicationThread()) add.run(); else Platform.runLater(add);
    }

    private void load() { try { if (Files.exists(FILE)) { List<String> saved = Files.readAllLines(FILE, StandardCharsets.UTF_8); events.setAll(saved.stream().filter(value -> !value.isBlank()).limit(300).toList()); } } catch (IOException ignored) { } }
    private void save() { try { Files.createDirectories(FILE.getParent()); atomicWrite(FILE, String.join(System.lineSeparator(), events) + (events.isEmpty() ? "" : System.lineSeparator())); } catch (IOException ignored) { } }
    private void atomicWrite(Path file, String text) throws IOException { Path temp = Files.createTempFile(file.getParent(), ".aeromc-events-", ".tmp"); Files.writeString(temp, text, StandardCharsets.UTF_8); try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); } }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private VBox page(Node... children) { VBox page = new VBox(14, children); page.setPadding(new Insets(18)); for (Node child : children) VBox.setVgrow(child, Priority.ALWAYS); return page; }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private static String trim(String value, int max) { String clean = value.strip(); return clean.length() <= max ? clean : clean.substring(0, max) + "…"; }
}
