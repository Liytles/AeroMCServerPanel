package com.aerogroup.mcpanel;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Tek tık Spark profili, rapor yakalama ve TPS karşılaştırma akışını yönetir. */
final class SparkProfilerPane {
    private static final Path HISTORY_FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "spark-report-history.properties");

    private final ServerManager manager;
    private final ExarotonPane exaroton;
    private final HostServices hostServices;
    private final BooleanSupplier remote;
    private final BooleanSupplier online;
    private final DoubleSupplier currentTps;
    private final Supplier<String> source;
    private final BiConsumer<String, String> eventRecorder;
    private final Consumer<String> findingRecorder;
    private final SparkReportHistory history = new SparkReportHistory(HISTORY_FILE);
    private final ComboBox<String> duration = new ComboBox<>();
    private final Label state = new Label("1. Sunucuyu aç  •  2. Süreyi seç  •  3. Analizi başlat");
    private final Label comparison = new Label("İki ölçümlü rapordan sonra TPS değişimi burada görünecek");
    private final Timeline countdown = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateCountdown()));
    private final PauseTransition tpsTimeout = new PauseTransition(Duration.seconds(5));
    private Button startButton;
    private Button openButton;
    private volatile String latestReport;
    private volatile String pendingReport;
    private volatile String pendingSource;
    private volatile boolean tpsHeaderSeen;
    private volatile boolean profileRunning;
    private Instant deadline;

    SparkProfilerPane(ServerManager manager, ExarotonPane exaroton, HostServices hostServices,
                      BooleanSupplier remote, BooleanSupplier online, DoubleSupplier currentTps,
                      Supplier<String> source, BiConsumer<String, String> eventRecorder,
                      Consumer<String> findingRecorder) {
        this.manager = manager;
        this.exaroton = exaroton;
        this.hostServices = hostServices;
        this.remote = remote;
        this.online = online;
        this.currentTps = currentTps;
        this.source = source;
        this.eventRecorder = eventRecorder;
        this.findingRecorder = findingRecorder;
        duration.getItems().setAll("Hızlı • 60 saniye", "Normal • 3 dakika", "Detaylı • 5 dakika");
        duration.getSelectionModel().selectFirst();
        countdown.setCycleCount(Animation.INDEFINITE);
        tpsTimeout.setOnFinished(event -> finalizeReport(fallbackTps()));
    }

    VBox buildView() {
        duration.setPrefWidth(170);
        startButton = button("LAG ANALİZİNİ BAŞLAT", "primary");
        openButton = button("HAZIR RAPORU AÇ", "secondary");
        Button help = button("Spark Yoksa?", "secondary");
        startButton.setOnAction(event -> start());
        openButton.setOnAction(event -> openReport());
        help.setOnAction(event -> showHelp());
        openButton.setDisable(latestReport == null);
        state.setWrapText(true);
        state.getStyleClass().add("muted");
        comparison.setWrapText(true);
        comparison.getStyleClass().add("health-state");
        refreshComparison();
        FlowPane controls = new FlowPane(8, 8, new Label("Analiz süresi"), duration, startButton, openButton, help);
        controls.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Sunucu kasarken başlat ve hiçbir şey yapmadan geri sayımın bitmesini bekle. AeroMC rapor bağlantısını konsoldan kendisi yakalar; hazır olduğunda yalnızca 'Hazır Raporu Aç'a basarsın.");
        note.setWrapText(true);
        note.getStyleClass().add("muted");
        return card("TEK TIK LAG ANALİZİ", controls, state, comparison, note);
    }

    void acceptConsole(String line) {
        captureTps(line);
        captureReport(line);
        if (profileRunning && SparkAnalysisEngine.commandRejected(line)) {
            Platform.runLater(() -> fail("Spark komutu tanınmadı. 'Spark Yoksa?' düğmesindeki kısa kurulumu uygula."));
        }
    }

    void providerChanged() {
        cancel("Aktif sunucu değişti • Yeni analiz başlatabilirsin");
        refreshComparison();
    }

    void serverStopped(String message) {
        cancel(message);
    }

    void languageChanged() {
        refreshComparison();
    }

    void shutdown() {
        countdown.stop();
        tpsTimeout.stop();
    }

    private void start() {
        if (!online.getAsBoolean()) {
            showError("Lag Avcısı için seçili sunucu online olmalı.");
            return;
        }
        if (profileRunning) return;
        int seconds = SparkAnalysisEngine.durationSeconds(duration.getValue());
        String command = SparkAnalysisEngine.profilerCommand(seconds);
        startButton.setDisable(true);
        duration.setDisable(true);
        state.setText("Spark komutu sunucuya gönderiliyor...");
        if (remote.getAsBoolean()) {
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    exaroton.executeAdminCommand(command).join();
                    return null;
                }
            };
            task.setOnSucceeded(event -> beginCountdown(seconds));
            task.setOnFailed(event -> {
                resetControls();
                showError("Spark analizi başlatılamadı: " + rootMessage(task.getException()));
            });
            run(task, "spark-analysis-start");
            return;
        }
        try {
            manager.command(command);
            beginCountdown(seconds);
        } catch (Exception error) {
            resetControls();
            showError("Spark analizi başlatılamadı: " + rootMessage(error));
        }
    }

    private void beginCountdown(int seconds) {
        profileRunning = true;
        deadline = Instant.now().plusSeconds(seconds);
        state.setText("Veri toplanıyor • " + seconds + " saniye kaldı");
        countdown.playFromStart();
        eventRecorder.accept("Lag Avcısı", seconds + " saniyelik tek tık lag analizi başlatıldı.");
        findingRecorder.accept("Veri toplama başladı; rapor otomatik yakalanacak.");
    }

    private void updateCountdown() {
        if (!profileRunning || deadline == null) {
            countdown.stop();
            return;
        }
        long remaining = java.time.Duration.between(Instant.now(), deadline).toSeconds();
        if (remaining > 0) state.setText("Veri toplanıyor • " + remaining + " saniye kaldı • Sunucuyu normal kullanmaya devam et");
        else if (remaining >= -20) state.setText("Analiz tamamlandı • Spark rapor bağlantısı bekleniyor...");
        else fail("Rapor bağlantısı gelmedi. Spark kurulu değilse 'Spark Yoksa?' düğmesine bas.");
    }

    private void captureReport(String line) {
        Optional<String> report = SparkAnalysisEngine.trustedReportUrl(line);
        if (report.isEmpty()) return;
        String url = report.get();
        if (url.equals(latestReport) && !profileRunning) return;
        latestReport = url;
        pendingReport = url;
        pendingSource = source.get();
        tpsHeaderSeen = false;
        eventRecorder.accept("Lag Avcısı", "Spark raporu hazırlandı: " + latestReport);
        Platform.runLater(() -> {
            profileRunning = false;
            deadline = null;
            countdown.stop();
            resetControls();
            if (openButton != null) openButton.setDisable(false);
            state.setText("RAPOR HAZIR ✓ • TPS özeti ölçülüyor...");
            requestTps();
            tpsTimeout.playFromStart();
        });
    }

    private void captureTps(String line) {
        if (pendingReport == null) return;
        if (SparkAnalysisEngine.tpsHeader(line)) {
            tpsHeaderSeen = true;
            return;
        }
        if (!tpsHeaderSeen) return;
        OptionalDouble tps = SparkAnalysisEngine.fiveSecondTps(line);
        if (tps.isEmpty()) return;
        tpsHeaderSeen = false;
        Platform.runLater(() -> finalizeReport(tps.getAsDouble()));
    }

    private void requestTps() {
        try {
            if (!remote.getAsBoolean()) {
                manager.command("spark tps");
                return;
            }
            Task<Void> task = new Task<>() {
                @Override protected Void call() throws Exception {
                    exaroton.executeAdminCommand("spark tps").join();
                    return null;
                }
            };
            task.setOnFailed(event -> finalizeReport(fallbackTps()));
            run(task, "spark-tps-summary");
        } catch (Exception ignored) {
            finalizeReport(fallbackTps());
        }
    }

    private void finalizeReport(double tps) {
        if (pendingReport == null) return;
        tpsTimeout.stop();
        String reportSource = pendingSource;
        String url = pendingReport;
        pendingReport = null;
        pendingSource = null;
        tpsHeaderSeen = false;
        history.add(Instant.now(), reportSource, url, tps);
        String result = SparkReportHistory.comparisonText(history.comparison(reportSource), Double.isFinite(tps));
        comparison.setText(result);
        state.setText("RAPOR HAZIR ✓ • Şimdi 'Hazır Raporu Aç'a bas");
        findingRecorder.accept("Spark raporu hazır. " + result);
        eventRecorder.accept("Lag Avcısı", result);
        DesktopNotifier.show(reportSource, "AeroMC Lag Analizi", "Spark raporu hazır. " + result);
    }

    private void refreshComparison() {
        Optional<SparkReportHistory.Comparison> result = history.comparison(source.get());
        comparison.setText(result.isPresent() ? SparkReportHistory.comparisonText(result, true) : "İki ölçümlü rapordan sonra TPS değişimi burada görünecek");
    }

    private void openReport() {
        if (latestReport == null || latestReport.isBlank()) {
            showError("Henüz yakalanmış bir Spark raporu yok.");
            return;
        }
        try {
            hostServices.showDocument(latestReport);
        } catch (Exception error) {
            showError("Rapor açılamadı: " + rootMessage(error));
        }
    }

    private void fail(String message) {
        profileRunning = false;
        deadline = null;
        countdown.stop();
        resetControls();
        state.setText(message);
        eventRecorder.accept("Lag Avcısı", message);
    }

    private void cancel(String message) {
        if (profileRunning) fail(message);
    }

    private void resetControls() {
        if (startButton != null) startButton.setDisable(false);
        duration.setDisable(false);
        if (openButton != null) openButton.setDisable(latestReport == null);
    }

    private void showHelp() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                LanguageManager.text("Paper 1.21 ve üzerindeysen Spark genellikle sunucuyla birlikte gelir. Komut tanınmazsa Kontrol Merkezi → Tek Tık Mod Merkezi'ne gir, 'spark' ara, sunucuna yükle ve sunucuyu bir kez yeniden başlat. Sonra buraya dönüp yalnızca 'Lag Analizini Başlat'a bas."), ButtonType.OK);
        alert.setHeaderText(LanguageManager.text("Spark'ı hazırlamanın en kolay yolu"));
        alert.showAndWait();
    }

    private double fallbackTps() {
        double value = currentTps.getAsDouble();
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private VBox card(String title, javafx.scene.Node... nodes) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");
        VBox box = new VBox(11);
        box.getChildren().add(label);
        box.getChildren().addAll(nodes);
        box.getStyleClass().add("card");
        return box;
    }

    private Button button(String text, String style) {
        Button button = new Button(text);
        button.getStyleClass().add(style);
        return button;
    }

    private void showError(String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showError(message));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR, LanguageManager.text(message), ButtonType.OK);
        alert.setHeaderText(LanguageManager.text("İşlem tamamlanamadı"));
        alert.showAndWait();
    }

    private String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null) cause = cause.getCause();
        return cause == null || cause.getMessage() == null ? "Bilinmeyen hata" : cause.getMessage();
    }

    private void run(Task<?> task, String name) {
        Thread thread = new Thread(task, "aeromc-" + name);
        thread.setDaemon(true);
        thread.start();
    }
}
