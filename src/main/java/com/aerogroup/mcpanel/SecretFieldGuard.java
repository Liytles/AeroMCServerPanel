package com.aerogroup.mcpanel;

import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.PasswordField;

/** Gizli alanların panoya veya sürükle-bırak yoluyla çıkarılmasını engeller. */
public final class SecretFieldGuard {
    private SecretFieldGuard() { }

    public static void protect(PasswordField field) {
        field.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> event.consume());
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isShortcutDown() && (event.getCode() == KeyCode.C || event.getCode() == KeyCode.X || event.getCode() == KeyCode.INSERT)) event.consume();
            if (event.isShiftDown() && event.getCode() == KeyCode.DELETE) event.consume();
        });
        field.addEventFilter(MouseEvent.DRAG_DETECTED, event -> event.consume());
    }
}
