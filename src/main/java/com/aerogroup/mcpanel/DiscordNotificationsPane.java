package com.aerogroup.mcpanel;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Discord ayarları, kasa işlemleri, filtreler ve gönderim kuyruğunun tek sahibidir. */
final class DiscordNotificationsPane {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "discord-notifications.properties");
    private final PanelConfig config;
    private final BooleanSupplier remote;
    private final Supplier<String> serverName;
    private final PasswordField webhook = new PasswordField();
    private final CheckBox enabled = new CheckBox("Discord bildirimleri açık"), status = new CheckBox("Açılma / kapanma"), crash = new CheckBox("Çökme ve kritik hata"), players = new CheckBox("Oyuncu girişleri"), performance = new CheckBox("RAM ve Kriz Modu"), maintenance = new CheckBox("Bakım ve yedek"), automation = new CheckBox("Zamanlanmış görevler"), mentionCritical = new CheckBox("Kritik olayda rolü etiketle");
    private final TextField displayName = new TextField("AeroMC"), roleId = new TextField();
    private final Label state = new Label("Discord webhook bağlantısı bekleniyor");
    private final Button loadSaved = button("Kayıtlı Webhook'u Aç", "secondary"), deleteSaved = button("Kaydı Sil", "danger");
    private final DiscordWebhookClient client = new DiscordWebhookClient();
    private volatile String automaticWebhook;
    private boolean viewBuilt, vaultLoading;

    DiscordNotificationsPane(PanelConfig config, BooleanSupplier remote, Supplier<String> serverName) { this.config = config; this.remote = remote; this.serverName = serverName; loadPreferences(); }

    Node buildView() {
        webhook.setPromptText("https://discord.com/api/webhooks/..."); webhook.setPrefWidth(560); SecretFieldGuard.protect(webhook); displayName.setPromptText("Webhook görünen adı"); displayName.setPrefWidth(180); roleId.setPromptText("Discord rol kimliği"); roleId.setPrefWidth(210); state.getStyleClass().add("muted");
        roleId.setDisable(!mentionCritical.isSelected()); mentionCritical.setOnAction(event -> roleId.setDisable(!mentionCritical.isSelected()));
        Button test = button("Test Mesajı", "secondary"), save = button("Ayarları Kaydet", "primary"), store = button("Şifreli Sakla", "secondary");
        test.setOnAction(event -> send(DiscordNotificationEngine.Type.TEST, "AeroMC bağlantı testi", "Webhook bağlantısı başarılı. Bildirim merkezi kullanıma hazır 🎮", false, true)); save.setOnAction(event -> savePreferences(true)); store.setOnAction(event -> storeWebhook()); loadSaved.setOnAction(event -> loadWebhook()); deleteSaved.setOnAction(event -> deleteWebhook()); loadSaved.setDisable(!DiscordWebhookStore.exists()); deleteSaved.setDisable(!DiscordWebhookStore.exists());
        FlowPane connection = new FlowPane(8, 8, enabled, webhook, test, store, loadSaved, deleteSaved); connection.setAlignment(Pos.CENTER_LEFT);
        FlowPane filters = new FlowPane(14, 9, status, crash, players, performance, maintenance, automation);
        FlowPane identity = new FlowPane(9, 9, new Label("Görünen ad"), displayName, mentionCritical, roleId, save); identity.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Bildirimler renkli embed olarak gönderilir. Oyuncu ve konsol metinlerinin rol veya @everyone etiketi oluşturması engellenir. Webhook yalnızca ana parolayla şifrelenerek kalıcı saklanabilir."); note.setWrapText(true); note.getStyleClass().add("muted");
        viewBuilt = true; updateVaultPrompt(); if (config.isAutomaticCredentialVaultEnabled()) Platform.runLater(this::loadAutomaticWebhook);
        return card("DISCORD BİLDİRİM MERKEZİ", connection, filters, identity, state, note);
    }

    void send(DiscordNotificationEngine.Type type, String title, String message, boolean critical) { send(type, title, message, critical, false); }
    void send(DiscordNotificationEngine.Type type, String title, String message, boolean critical, boolean force) {
        DiscordNotificationEngine.Settings settings = settings(); DiscordNotificationEngine.Event event = new DiscordNotificationEngine.Event(type, title, message, remote.getAsBoolean() ? "Exaroton" : "Yerel JAR", serverName.get(), critical); if (!force && !DiscordNotificationEngine.shouldSend(settings, event)) return;
        String entered = webhook.getText().trim(), selected = entered.isBlank() ? automaticWebhook : entered; URI uri;
        try { uri = DiscordNotificationEngine.validateWebhook(selected); } catch (IllegalArgumentException error) { state.setText(error.getMessage()); if (force) showError(error.getMessage()); return; }
        state.setText("Discord bildirimi gönderiliyor..."); client.send(uri, DiscordNotificationEngine.payload(settings, event)).thenAccept(result -> Platform.runLater(() -> { state.setText(result.message()); if (result.success() && config.isAutomaticCredentialVaultEnabled() && !entered.isBlank()) { automaticWebhook = entered; webhook.clear(); updateVaultPrompt(); storeAutomaticWebhook(entered); } if (force && result.success()) info("Discord bağlantısı başarılı", "Test embed mesajı gönderildi."); else if (force && !result.success()) showError(result.message()); }));
    }

    void setAutomaticCredentialVaultEnabled(boolean value) {
        if (!value) { automaticWebhook = null; webhook.clear(); try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.DISCORD); } catch (IOException error) { throw new IllegalStateException("Discord otomatik kasası silinemedi: " + error.getMessage(), error); } updateVaultPrompt(); if (viewBuilt) state.setText("Otomatik Discord kasası kapatıldı"); return; }
        updateVaultPrompt(); String entered = webhook.getText().trim(); if (!entered.isBlank()) { try { DiscordNotificationEngine.validateWebhook(entered); storeAutomaticWebhook(entered); return; } catch (IllegalArgumentException ignored) { } } if (viewBuilt) loadAutomaticWebhook();
    }
    void shutdown() { client.close(); webhook.clear(); automaticWebhook = null; }

    private void loadPreferences() { Properties values = loadProperties(); enabled.setSelected(Boolean.parseBoolean(values.getProperty("enabled", "false"))); status.setSelected(Boolean.parseBoolean(values.getProperty("status", "true"))); crash.setSelected(Boolean.parseBoolean(values.getProperty("crash", "true"))); players.setSelected(Boolean.parseBoolean(values.getProperty("players", "false"))); performance.setSelected(Boolean.parseBoolean(values.getProperty("performance", "true"))); maintenance.setSelected(Boolean.parseBoolean(values.getProperty("maintenance", "true"))); automation.setSelected(Boolean.parseBoolean(values.getProperty("automation", "true"))); mentionCritical.setSelected(Boolean.parseBoolean(values.getProperty("mentionCritical", "false"))); displayName.setText(values.getProperty("username", "AeroMC")); roleId.setText(values.getProperty("roleId", "")); }
    private void savePreferences(boolean notify) {
        if (mentionCritical.isSelected() && !DiscordNotificationEngine.validRole(roleId.getText())) { showError("Kritik etiketleme için 17–20 haneli Discord rol kimliği gir."); return; }
        Properties values = new Properties(); values.setProperty("enabled", Boolean.toString(enabled.isSelected())); values.setProperty("status", Boolean.toString(status.isSelected())); values.setProperty("crash", Boolean.toString(crash.isSelected())); values.setProperty("players", Boolean.toString(players.isSelected())); values.setProperty("performance", Boolean.toString(performance.isSelected())); values.setProperty("maintenance", Boolean.toString(maintenance.isSelected())); values.setProperty("automation", Boolean.toString(automation.isSelected())); values.setProperty("mentionCritical", Boolean.toString(mentionCritical.isSelected())); values.setProperty("username", displayName.getText().isBlank() ? "AeroMC" : displayName.getText().trim()); values.setProperty("roleId", roleId.getText().trim()); saveProperties(values);
        String entered = webhook.getText().trim(); if (config.isAutomaticCredentialVaultEnabled() && !entered.isBlank()) { try { DiscordNotificationEngine.validateWebhook(entered); storeAutomaticWebhook(entered); } catch (IllegalArgumentException error) { showError(error.getMessage()); return; } }
        if (notify) state.setText("Discord bildirim ayarları kaydedildi" + (config.isAutomaticCredentialVaultEnabled() && !entered.isBlank() ? " • webhook kasaya ekleniyor" : ""));
    }
    private DiscordNotificationEngine.Settings settings() { EnumSet<DiscordNotificationEngine.Type> types = EnumSet.noneOf(DiscordNotificationEngine.Type.class); if (status.isSelected()) types.add(DiscordNotificationEngine.Type.STATUS); if (crash.isSelected()) types.add(DiscordNotificationEngine.Type.CRASH); if (players.isSelected()) types.add(DiscordNotificationEngine.Type.PLAYER); if (performance.isSelected()) types.add(DiscordNotificationEngine.Type.PERFORMANCE); if (maintenance.isSelected()) { types.add(DiscordNotificationEngine.Type.MAINTENANCE); types.add(DiscordNotificationEngine.Type.BACKUP); } if (automation.isSelected()) types.add(DiscordNotificationEngine.Type.AUTOMATION); return new DiscordNotificationEngine.Settings(enabled.isSelected(), types, displayName.getText(), mentionCritical.isSelected(), roleId.getText()); }

    private void loadAutomaticWebhook() { if (!config.isAutomaticCredentialVaultEnabled() || automaticWebhook != null || vaultLoading || !DeviceCredentialStore.exists(DeviceCredentialStore.Kind.DISCORD)) { updateVaultPrompt(); return; } vaultLoading = true; state.setText("Otomatik Discord kasası açılıyor..."); Task<String> task = new Task<>() { protected String call() throws Exception { String value = DeviceCredentialStore.load(DeviceCredentialStore.Kind.DISCORD); DiscordNotificationEngine.validateWebhook(value); return value; } }; task.setOnSucceeded(event -> { vaultLoading = false; if (!config.isAutomaticCredentialVaultEnabled()) { automaticWebhook = null; updateVaultPrompt(); return; } automaticWebhook = task.getValue(); webhook.clear(); updateVaultPrompt(); state.setText("Webhook otomatik cihaz kasasından hazır"); }); task.setOnFailed(event -> { vaultLoading = false; automaticWebhook = null; updateVaultPrompt(); state.setText("Otomatik webhook kasası açılamadı; webhook'u yeniden gir"); }); run(task, "discord-auto-vault-load"); }
    private void storeAutomaticWebhook(String url) { if (!config.isAutomaticCredentialVaultEnabled()) return; vaultLoading = true; Task<Void> task = new Task<>() { protected Void call() throws Exception { DeviceCredentialStore.save(DeviceCredentialStore.Kind.DISCORD, url); return null; } }; task.setOnSucceeded(event -> { vaultLoading = false; if (!config.isAutomaticCredentialVaultEnabled()) { automaticWebhook = null; try { DeviceCredentialStore.delete(DeviceCredentialStore.Kind.DISCORD); } catch (IOException ignored) { } updateVaultPrompt(); return; } automaticWebhook = url; webhook.clear(); updateVaultPrompt(); state.setText("Webhook otomatik cihaz kasasında hazır"); }); task.setOnFailed(event -> { vaultLoading = false; state.setText("Webhook otomatik kasaya kaydedilemedi"); showError("Webhook otomatik kasaya kaydedilemedi: " + rootMessage(task.getException())); }); run(task, "discord-auto-vault-save"); }
    private void updateVaultPrompt() { if (config.isAutomaticCredentialVaultEnabled() && (automaticWebhook != null || DeviceCredentialStore.exists(DeviceCredentialStore.Kind.DISCORD))) webhook.setPromptText("Bu cihazın güvenli kasasından otomatik kullanılıyor"); else if (config.isAutomaticCredentialVaultEnabled()) webhook.setPromptText("Webhook'u bir kez gir; otomatik kasaya kaydedilir"); else webhook.setPromptText("https://discord.com/api/webhooks/..."); }
    private void storeWebhook() { String url = webhook.getText().trim(); try { DiscordNotificationEngine.validateWebhook(url); } catch (IllegalArgumentException error) { showError(error.getMessage()); return; } Optional<char[]> password = passwordDialog("Discord Webhook'unu Şifrele", true); if (password.isEmpty()) return; char[] value = password.get(); try { DiscordWebhookStore.save(url, value); loadSaved.setDisable(false); deleteSaved.setDisable(false); state.setText("Webhook şifreli olarak saklandı"); if (config.isAutomaticCredentialVaultEnabled()) storeAutomaticWebhook(url); } catch (Exception error) { showError("Webhook saklanamadı: " + rootMessage(error)); } finally { Arrays.fill(value, '\0'); } }
    private void loadWebhook() { Optional<char[]> password = passwordDialog("Discord Webhook Kasasını Aç", false); if (password.isEmpty()) return; char[] value = password.get(); try { String url = DiscordWebhookStore.load(value); if (config.isAutomaticCredentialVaultEnabled()) { automaticWebhook = url; webhook.clear(); storeAutomaticWebhook(url); } else webhook.setText(url); state.setText("Şifreli webhook bu oturum için açıldı"); updateVaultPrompt(); } catch (Exception error) { showError("Webhook açılamadı. Parola yanlış veya kayıt bozuk olabilir."); } finally { Arrays.fill(value, '\0'); } }
    private void deleteWebhook() { if (!confirm("Şifreli Discord webhook kaydı silinsin mi?")) return; try { DiscordWebhookStore.delete(); webhook.clear(); loadSaved.setDisable(true); deleteSaved.setDisable(true); state.setText(automaticWebhook == null ? "Şifreli webhook kaydı silindi" : "Parolalı kayıt silindi • otomatik cihaz kasası hazır"); } catch (Exception error) { showError(rootMessage(error)); } }
    private Optional<char[]> passwordDialog(String title, boolean confirmPassword) { Dialog<char[]> dialog = new Dialog<>(); dialog.setTitle(title); dialog.setHeaderText(confirmPassword ? "En az 8 karakterlik ana parola belirle. Parola kaydedilmez." : "Şifreli webhook'u açmak için ana parolayı gir."); PasswordField first = new PasswordField(); SecretFieldGuard.protect(first); first.setPromptText("Ana parola"); VBox fields = new VBox(8, first); PasswordField second = new PasswordField(); SecretFieldGuard.protect(second); if (confirmPassword) { second.setPromptText("Ana parolayı tekrar yaz"); fields.getChildren().add(second); } dialog.getDialogPane().setContent(fields); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL); dialog.setResultConverter(button -> { if (button != ButtonType.OK) return null; if (first.getText().length() < 8) { showError("Ana parola en az 8 karakter olmalı."); return null; } if (confirmPassword && !first.getText().equals(second.getText())) { showError("Ana parolalar eşleşmiyor."); return null; } return first.getText().toCharArray(); }); return dialog.showAndWait(); }

    private Properties loadProperties() { Properties values = new Properties(); try { if (Files.exists(FILE)) try (Reader reader = Files.newBufferedReader(FILE)) { values.load(reader); } } catch (IOException ignored) { } return values; }
    private void saveProperties(Properties values) { try { Files.createDirectories(FILE.getParent()); try (Writer writer = Files.newBufferedWriter(FILE)) { values.store(writer, "AeroMC Discord notification preferences"); } } catch (IOException ignored) { } }
    private void run(Task<?> task, String name) { Thread thread = new Thread(task, "aeromc-" + name); thread.setDaemon(true); thread.start(); }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private void showError(String message) { if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(message)); return; } Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message == null ? "Bilinmeyen hata" : message), ButtonType.OK); alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı")); alert.showAndWait(); }
    private void info(String title, String message) { Alert alert = new Alert(Alert.AlertType.INFORMATION, LanguageManager.text(message), ButtonType.OK); alert.setHeaderText(LanguageManager.text(title)); alert.showAndWait(); }
    private String rootMessage(Throwable error) { Throwable cause = error; while (cause != null && cause.getCause() != null) cause = cause.getCause(); return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage(); }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private static Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
}
