package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.MasterPasswordManager;
import com.aerogroup.mcpanel.aeroguard.SecretFieldGuard;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import java.util.Arrays;
import java.util.Optional;

/** Small blocking dialogs used before access to the panel and sensitive transfers. */
public final class MasterPasswordDialogs {
    private MasterPasswordDialogs() { }
    public static boolean unlockAtStartup(Window owner, PanelConfig config) {
        try {
            if (!MasterPasswordManager.isConfigured()) return create(owner);
            return !config.isAskMasterPasswordOnLaunch() || verify(owner, "AeroMC Kilidi", "AeroMC'yi açmak için ana parolanı gir.").isPresent();
        } catch (Exception error) { error(owner, error.getMessage()); return false; }
    }
    public static Optional<char[]> verifiedPassword(Window owner, String title, String content) {
        try { return verify(owner, title, content); } catch (Exception error) { error(owner, error.getMessage()); return Optional.empty(); }
    }
    private static boolean create(Window owner) throws Exception {
        Dialog<ButtonType> dialog = base(owner, "AeroMC Ana Parolasını Oluştur", "AeroMC'yi kullanmadan önce bir ana parola oluştur.");
        PasswordField first = protectedField("En az 12 karakter"); PasswordField confirm = protectedField("Parolayı tekrar gir");
        GridPane grid = new GridPane(); grid.setHgap(10); grid.setVgap(10); grid.addRow(0, new Label("Ana parola"), first); grid.addRow(1, new Label("Tekrar"), confirm); dialog.getDialogPane().setContent(grid); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); ok.setText("Oluştur"); ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> { if (first.getText().length() < 12 || !first.getText().equals(confirm.getText())) { event.consume(); error(owner, "Ana parola en az 12 karakter olmalı ve iki alan aynı olmalı."); } });
        Optional<ButtonType> result = dialog.showAndWait(); if (result.orElse(ButtonType.CANCEL) != ButtonType.OK) return false;
        char[] password = first.getText().toCharArray(); try { MasterPasswordManager.create(password); return true; } finally { Arrays.fill(password, '\0'); first.clear(); confirm.clear(); }
    }
    private static Optional<char[]> verify(Window owner, String title, String content) throws Exception {
        Dialog<ButtonType> dialog = base(owner, title, content); PasswordField field = protectedField("AeroMC ana parolası"); dialog.getDialogPane().setContent(field); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK); ok.setText("Doğrula");
        Optional<ButtonType> result = dialog.showAndWait(); if (result.orElse(ButtonType.CANCEL) != ButtonType.OK) { field.clear(); return Optional.empty(); }
        char[] password = field.getText().toCharArray(); field.clear(); if (!MasterPasswordManager.verify(password)) { Arrays.fill(password, '\0'); error(owner, "Ana parola doğrulanamadı."); return Optional.empty(); } return Optional.of(password);
    }
    private static Dialog<ButtonType> base(Window owner, String title, String content) { Dialog<ButtonType> dialog = new Dialog<>(); dialog.setTitle(title); dialog.setHeaderText(content); if (owner != null) dialog.initOwner(owner); dialog.setResizable(false); return dialog; }
    private static PasswordField protectedField(String prompt) { PasswordField field = new PasswordField(); field.setPromptText(prompt); SecretFieldGuard.protect(field); return field; }
    private static void error(Window owner, String text) { Alert alert = new Alert(Alert.AlertType.ERROR, text == null ? "İşlem tamamlanamadı." : text, ButtonType.OK); alert.setHeaderText("AeroMC ana parolası"); if (owner != null) alert.initOwner(owner); alert.showAndWait(); }
}
