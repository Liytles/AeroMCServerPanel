package com.aerogroup.mcpanel;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Tek seferlik görevlerin arayüzünü, kalıcılığını ve çalışma zamanını yönetir. */
final class ScheduledTasksPane {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "scheduled-jobs.properties");

    interface Actions {
        void backup();
        void restart();
        void stop();
        void announce(String message);
    }

    private final BooleanSupplier remote;
    private final Actions actions;
    private final BiConsumer<String, String> eventRecorder;
    private final BiConsumer<String, String> notificationSender;
    private final Consumer<String> findingRecorder;
    private final ObservableList<Job> jobs = FXCollections.observableArrayList();
    private final ComboBox<String> scheduledAction = new ComboBox<>();
    private final Timeline scheduler = new Timeline(new KeyFrame(Duration.seconds(5), event -> runDueJobs()));

    ScheduledTasksPane(BooleanSupplier remote, Actions actions,
                       BiConsumer<String, String> eventRecorder,
                       BiConsumer<String, String> notificationSender,
                       Consumer<String> findingRecorder) {
        this.remote = remote;
        this.actions = actions;
        this.eventRecorder = eventRecorder;
        this.notificationSender = notificationSender;
        this.findingRecorder = findingRecorder;
        load();
        scheduler.setCycleCount(Animation.INDEFINITE);
    }

    VBox buildView() {
        updateProvider();
        Spinner<Integer> minutes = new Spinner<>(1, 10080, 30);
        TextField message = new TextField();
        message.setPromptText("Duyuru metni");
        message.setPrefWidth(360);
        Button add = button("Görevi Planla", "primary");
        add.setOnAction(event -> add(scheduledAction.getValue(), minutes.getValue(), message.getText().trim()));
        ListView<Job> list = new ListView<>(jobs);
        list.setPrefHeight(220);
        list.setMinHeight(130);
        list.setMaxHeight(280);
        Button remove = button("Seçili Görevi Sil", "danger");
        remove.setOnAction(event -> {
            Job selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                jobs.remove(selected);
                save();
            }
        });
        FlowPane row = new FlowPane(8, 8, scheduledAction, new Label("dakika sonra"), minutes, message, add);
        row.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Buradaki görevler tek seferliktir. Sürekli Exaroton saat, kredi, çökme ve oyuncusuz-durdurma kuralları Exaroton Otomasyon Merkezi'nde yönetilir.");
        note.setWrapText(true);
        note.getStyleClass().add("muted");
        scheduler.play();
        return card("TEK SEFERLİK GÖREV ZAMANLAYICISI", row, note, list, remove);
    }

    void updateProvider() {
        String selected = scheduledAction.getValue();
        if (remote.getAsBoolean()) scheduledAction.getItems().setAll("Yeniden başlat", "Sunucuyu durdur", "Duyuru gönder");
        else scheduledAction.getItems().setAll("Yedek al", "Yeniden başlat", "Sunucuyu durdur", "Duyuru gönder");
        if (selected != null && scheduledAction.getItems().contains(selected)) scheduledAction.setValue(selected);
        else scheduledAction.getSelectionModel().selectFirst();
    }

    void shutdown() {
        scheduler.stop();
    }

    void pause() {
        scheduler.pause();
    }

    void resume() {
        scheduler.play();
    }

    private void add(String action, int minutes, String message) {
        if (action == null) return;
        if (action.equals("Duyuru gönder") && message.isBlank()) {
            findingRecorder.accept("Duyuru metni boş olduğu için görev eklenmedi.");
            return;
        }
        jobs.add(new Job(UUID.randomUUID().toString(), LocalDateTime.now().plusMinutes(minutes), action, message));
        jobs.sort(Comparator.comparing(Job::due));
        save();
        eventRecorder.accept("Otomasyon", action + " görevi " + minutes + " dakika sonrası için planlandı.");
    }

    private void runDueJobs() {
        List<Job> due = jobs.stream().filter(job -> !job.due().isAfter(LocalDateTime.now())).toList();
        for (Job job : due) {
            execute(job);
            jobs.remove(job);
        }
        if (!due.isEmpty()) save();
    }

    private void execute(Job job) {
        eventRecorder.accept("Otomasyon", "Planlanan görev çalıştırıldı: " + job.action());
        switch (job.action()) {
            case "Yedek al" -> actions.backup();
            case "Yeniden başlat" -> actions.restart();
            case "Sunucuyu durdur" -> actions.stop();
            case "Duyuru gönder" -> actions.announce(job.message());
            default -> { }
        }
        notificationSender.accept(job.action(), job.message());
        findingRecorder.accept(job.action() + " görevi çalıştırıldı.");
    }

    private void load() {
        Properties data = loadProperties();
        for (String id : data.stringPropertyNames()) {
            try {
                String[] parts = data.getProperty(id).split("\\|", 3);
                LocalDateTime due = LocalDateTime.parse(parts[0]);
                if (due.isAfter(LocalDateTime.now())) {
                    String message = new String(Base64.getDecoder().decode(parts.length > 2 ? parts[2] : ""), StandardCharsets.UTF_8);
                    jobs.add(new Job(id, due, parts[1], message));
                }
            } catch (Exception ignored) { }
        }
        jobs.sort(Comparator.comparing(Job::due));
    }

    private void save() {
        Properties data = new Properties();
        for (Job job : jobs) {
            String message = Base64.getEncoder().encodeToString(job.message().getBytes(StandardCharsets.UTF_8));
            data.setProperty(job.id(), job.due() + "|" + job.action() + "|" + message);
        }
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                data.store(writer, "AeroMC scheduled jobs");
            }
        } catch (IOException ignored) { }
    }

    private Properties loadProperties() {
        Properties values = new Properties();
        try {
            if (Files.exists(FILE)) try (Reader reader = Files.newBufferedReader(FILE)) {
                values.load(reader);
            }
        } catch (IOException ignored) { }
        return values;
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

    private record Job(String id, LocalDateTime due, String action, String message) {
        @Override public String toString() {
            return due.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) + "  •  " + action + (message.isBlank() ? "" : "  •  " + message);
        }
    }
}
