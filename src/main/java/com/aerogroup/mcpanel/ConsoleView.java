package com.aerogroup.mcpanel;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.*;

/** Hata, uyarı, oyuncu ve panel satırlarını renklerle ayıran canlı konsol. */
public final class ConsoleView extends ScrollPane {
    private static final int MAX_VISIBLE = 600, MAX_PENDING = 1_000, FLUSH_BATCH = 80;
    private final TextFlow lines = new TextFlow();
    private final Deque<String> pending = new ArrayDeque<>();
    private int dropped;
    private final Timeline flusher = new Timeline(new KeyFrame(Duration.millis(200), event -> flushPending()));
    public ConsoleView() { setContent(lines); setFitToWidth(true); getStyleClass().add("rich-console"); lines.getStyleClass().add("console-flow"); flusher.setCycleCount(Animation.INDEFINITE); flusher.play(); }
    public void append(String value) {
        if (value == null || value.isEmpty()) return;
        synchronized (pending) { if (pending.size() >= MAX_PENDING) { pending.removeFirst(); dropped++; } pending.addLast(value); }
    }
    public void clearConsole() { if (!Platform.isFxApplicationThread()) { Platform.runLater(this::clearConsole); return; } synchronized (pending) { pending.clear(); dropped = 0; } lines.getChildren().clear(); }
    private void flushPending() {
        List<String> batch = new ArrayList<>(FLUSH_BATCH + 1); int skipped;
        synchronized (pending) { skipped = dropped; dropped = 0; while (!pending.isEmpty() && batch.size() < FLUSH_BATCH) batch.add(pending.removeFirst()); }
        if (skipped > 0) batch.add(0, "[AeroMC] Arayüzü korumak için " + skipped + " eski konsol satırı atlandı.");
        if (batch.isEmpty()) return;
        batch = compact(batch); List<Text> nodes = new ArrayList<>(batch.size()); for (String value : batch) nodes.add(styled(value)); lines.getChildren().addAll(nodes);
        int excess = lines.getChildren().size() - MAX_VISIBLE; if (excess > 0) lines.getChildren().remove(0, excess);
        if (getVvalue() >= .96 || lines.getChildren().size() <= nodes.size()) setVvalue(1.0);
    }
    private List<String> compact(List<String> values) { if (values.size() < 2) return values; List<String> result = new ArrayList<>(); String previous = values.get(0); int count = 1; for (int index = 1; index < values.size(); index++) { String value = values.get(index); if (value.equals(previous)) count++; else { result.add(count == 1 ? previous : previous.stripTrailing() + "  ×" + count); previous = value; count = 1; } } result.add(count == 1 ? previous : previous.stripTrailing() + "  ×" + count); return result; }
    private Text styled(String value) { String lower = value.toLowerCase(Locale.ROOT); Text text = new Text(value.endsWith("\n") ? value : value + "\n"); text.getStyleClass().add(lower.contains("error") || lower.contains("exception") || lower.contains("crash") ? "console-error" : lower.contains("warn") ? "console-warn" : lower.contains("joined") || lower.contains("online") ? "console-success" : lower.startsWith("[panel]") || value.startsWith(">") ? "console-panel" : "console-line"); return text; }
}
