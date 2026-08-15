package com.aerogroup.mcpanel;

import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/** Hata, uyarı, oyuncu ve panel satırlarını renklerle ayıran canlı konsol. */
public final class ConsoleView extends ScrollPane {
    private final TextFlow lines = new TextFlow();
    public ConsoleView() { setContent(lines); setFitToWidth(true); getStyleClass().add("rich-console"); lines.getStyleClass().add("console-flow"); }
    public void append(String value) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> append(value)); return; }
        String lower = value.toLowerCase(); Text text = new Text(value.endsWith("\n") ? value : value + "\n");
        text.getStyleClass().add(lower.contains("error") || lower.contains("exception") || lower.contains("crash") ? "console-error" : lower.contains("warn") ? "console-warn" : lower.contains("joined") || lower.contains("online") ? "console-success" : lower.startsWith("[panel]") || value.startsWith(">") ? "console-panel" : "console-line");
        lines.getChildren().add(text); if (lines.getChildren().size() > 1200) lines.getChildren().remove(0, 150); layout(); setVvalue(1.0);
    }
    public void clearConsole() { lines.getChildren().clear(); }
}
