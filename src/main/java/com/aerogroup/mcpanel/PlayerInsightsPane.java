package com.aerogroup.mcpanel;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Oyuncu oturumlarını, başarı kartlarını ve haftalık rapor girdilerini ProToolsPane'den ayırır. */
final class PlayerInsightsPane {
    record Changes(List<String> joined, List<String> left) { }
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "player-history.properties");
    private static final Pattern JOIN = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) joined the game"), LEAVE = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) left the game"), ADVANCEMENT = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) has (?:made the advancement|completed the challenge|reached the goal)"), DEATH = Pattern.compile("(?:]: )?([A-Za-z0-9_]{1,16}) (?:died|was |fell |drowned|blew up|burned|hit the ground|starved|suffocated|withered|experienced kinetic energy|went up in flames|tried to swim in lava)", Pattern.CASE_INSENSITIVE);
    private final ObservableList<PlayerProfile> rows = FXCollections.observableArrayList();
    private final Map<String, PlayerProfile> profiles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> online = new HashSet<>();
    private final VBox achievementPreview = new VBox(10);

    PlayerInsightsPane() { load(); }

    Node buildView() {
        TableView<PlayerProfile> table = new TableView<>(rows);
        table.getColumns().add(column("Oyuncu", p -> p.name)); table.getColumns().add(column("Son Görülme", p -> formatTime(p.lastSeen))); table.getColumns().add(column("Giriş", p -> Integer.toString(p.joins))); table.getColumns().add(column("Oyun Süresi", p -> durationText(p.totalSeconds + activeSeconds(p)))); table.getColumns().add(column("Ölüm", p -> Integer.toString(p.deaths))); table.getColumns().add(column("İlerleme", p -> Integer.toString(p.advancements)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN); VBox.setVgrow(table, Priority.ALWAYS);
        achievementPreview.getStyleClass().addAll("achievement-preview", "card"); achievementPreview.setPrefWidth(310); achievementPreview.setMinWidth(260);
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> updateAchievementCard(selected));
        if (!rows.isEmpty()) { table.getSelectionModel().selectFirst(); updateAchievementCard(rows.get(0)); } else updateAchievementCard(null);
        HBox body = new HBox(14, table, achievementPreview); HBox.setHgrow(table, Priority.ALWAYS); VBox.setVgrow(body, Priority.ALWAYS);
        Button clear = button("Geçmişi Temizle", "danger"); clear.setOnAction(event -> { if (confirm("Tüm oyuncu geçmişi temizlensin mi?")) { profiles.clear(); rows.clear(); save(); } });
        Label note = new Label("Veriler panel açıkken görülen giriş/çıkışlardan ve çevrimiçi oyuncu listesinden oluşturulur."); note.getStyleClass().add("muted");
        return page(card("OYUNCU BAŞARI KARTLARI", note, body, clear));
    }

    void acceptConsole(String line) {
        Matcher joined = JOIN.matcher(line), left = LEAVE.matcher(line), advancement = ADVANCEMENT.matcher(line), death = DEATH.matcher(line);
        if (joined.find()) Platform.runLater(() -> markJoined(joined.group(1)));
        if (left.find()) Platform.runLater(() -> markLeft(left.group(1)));
        if (advancement.find()) Platform.runLater(() -> markAdvancement(advancement.group(1)));
        if (death.find()) Platform.runLater(() -> markDeath(death.group(1)));
    }

    Changes acceptPlayers(List<String> names) {
        Set<String> incoming = new HashSet<>(names); List<String> joined = new ArrayList<>(), left = new ArrayList<>();
        for (String name : incoming) if (!online.contains(name)) { markJoined(name); joined.add(name); }
        for (String name : new HashSet<>(online)) if (!incoming.contains(name)) { markLeft(name); left.add(name); }
        online.clear(); online.addAll(incoming); refreshRows(); return new Changes(List.copyOf(joined), List.copyOf(left));
    }

    List<WeeklyReportEngine.PlayerInput> weeklyInputs() { return profiles.values().stream().map(profile -> new WeeklyReportEngine.PlayerInput(profile.name, instant(profile.firstSeen), instant(profile.lastSeen), instant(profile.returnedAt))).toList(); }
    void disconnectAll() { for (String name : new HashSet<>(online)) markLeft(name); }

    private void markJoined(String name) { if (online.contains(name)) return; PlayerProfile profile = profiles.computeIfAbsent(name, PlayerProfile::new); long now = Instant.now().getEpochSecond(); if (profile.firstSeen == 0) profile.firstSeen = now; if (profile.lastSeen > 0 && now - profile.lastSeen >= Duration.ofDays(30).toSeconds()) profile.returnedAt = now; profile.lastSeen = now; profile.joins++; profile.activeSince = now; online.add(name); refreshRows(); save(); }
    private void markLeft(String name) { PlayerProfile profile = profiles.get(name); if (profile == null) return; long now = Instant.now().getEpochSecond(); if (profile.activeSince > 0) profile.totalSeconds += Math.max(0, now - profile.activeSince); profile.activeSince = 0; profile.lastSeen = now; online.remove(name); refreshRows(); save(); }
    private void markDeath(String name) { PlayerProfile profile = profiles.computeIfAbsent(name, PlayerProfile::new); profile.deaths++; profile.lastSeen = Instant.now().getEpochSecond(); refreshRows(); updateAchievementCard(profile); save(); }
    private void markAdvancement(String name) { PlayerProfile profile = profiles.computeIfAbsent(name, PlayerProfile::new); profile.advancements++; profile.lastSeen = Instant.now().getEpochSecond(); refreshRows(); updateAchievementCard(profile); save(); }
    private long activeSeconds(PlayerProfile profile) { return profile.activeSince <= 0 ? 0 : Math.max(0, Instant.now().getEpochSecond() - profile.activeSince); }
    private void refreshRows() { rows.setAll(profiles.values()); }
    private void load() { Properties data = loadProperties(); for (String name : data.stringPropertyNames()) { try { String[] parts = data.getProperty(name).split(","); PlayerProfile p = new PlayerProfile(name); p.firstSeen = Long.parseLong(parts[0]); p.lastSeen = Long.parseLong(parts[1]); p.joins = Integer.parseInt(parts[2]); p.totalSeconds = Long.parseLong(parts[3]); p.deaths = parts.length > 4 ? Integer.parseInt(parts[4]) : 0; p.advancements = parts.length > 5 ? Integer.parseInt(parts[5]) : 0; p.returnedAt = parts.length > 6 ? Long.parseLong(parts[6]) : 0; profiles.put(name, p); } catch (Exception ignored) { } } refreshRows(); }
    private void save() { Properties data = new Properties(); profiles.forEach((name, p) -> data.setProperty(name, p.firstSeen + "," + p.lastSeen + "," + p.joins + "," + (p.totalSeconds + activeSeconds(p)) + "," + p.deaths + "," + p.advancements + "," + p.returnedAt)); try { Files.createDirectories(FILE.getParent()); try (Writer writer = Files.newBufferedWriter(FILE)) { data.store(writer, "AeroMC player history"); } } catch (IOException ignored) { } }
    private Properties loadProperties() { Properties values = new Properties(); try { if (Files.exists(FILE)) try (Reader reader = Files.newBufferedReader(FILE)) { values.load(reader); } } catch (IOException ignored) { } return values; }

    private void updateAchievementCard(PlayerProfile profile) {
        achievementPreview.getChildren().clear(); Label heading = new Label("OYUNCU BAŞARI KARTI"); heading.getStyleClass().add("section-title"); achievementPreview.getChildren().add(heading);
        if (profile == null) { Label empty = new Label("Kartını görmek için tablodan bir oyuncu seç."); empty.setWrapText(true); empty.getStyleClass().add("muted"); achievementPreview.getChildren().add(empty); return; }
        long seconds = profile.totalSeconds + activeSeconds(profile), hours = seconds / 3600; int score = Math.min(9999, profile.joins * 10 + (int) hours * 5 + profile.advancements * 20 + Math.min(250, profile.deaths * 2));
        String rank = hours >= 40 || score >= 1000 ? "SUNUCU EFSANESİ" : hours >= 15 || score >= 500 ? "USTA OYUNCU" : hours >= 5 || score >= 180 ? "MACERACI" : "YENİ KAHRAMAN";
        Label name = new Label(profile.name); name.getStyleClass().add("achievement-name"); Label title = new Label(rank); title.getStyleClass().add("achievement-rank"); Label stats = new Label("★ " + score + " puan\n" + profile.joins + " giriş  •  " + durationText(seconds) + "\n" + profile.advancements + " ilerleme  •  " + profile.deaths + " ölüm"); stats.getStyleClass().add("achievement-stats");
        FlowPane badges = new FlowPane(7, 7); for (String badge : achievements(profile, hours)) { Label label = new Label(badge); label.getStyleClass().add("achievement-badge"); badges.getChildren().add(label); }
        Button copy = button("Kart Metnini Kopyala", "secondary"); copy.setOnAction(event -> { javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent(); content.putString("AeroMC • " + profile.name + "\n" + rank + " • " + score + " puan\n" + durationText(seconds) + " • " + profile.joins + " giriş • " + profile.advancements + " ilerleme\n" + String.join(" • ", achievements(profile, hours))); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); });
        achievementPreview.getChildren().addAll(name, title, stats, badges, copy);
    }
    private List<String> achievements(PlayerProfile profile, long hours) { List<String> result = new ArrayList<>(); result.add("İlk Adım"); if (profile.joins >= 5) result.add("Sadık Oyuncu"); if (profile.joins >= 25) result.add("Müdavim"); if (hours >= 5) result.add("Uzun Soluklu"); if (hours >= 25) result.add("Maratoncu"); if (profile.advancements >= 5) result.add("Kaşif"); if (profile.advancements >= 20) result.add("Başarım Avcısı"); if (profile.deaths == 0 && hours >= 2) result.add("Hayatta Kalan"); return result; }
    private TableColumn<PlayerProfile, String> column(String name, java.util.function.Function<PlayerProfile, String> value) { TableColumn<PlayerProfile, String> column = new TableColumn<>(name); column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data.getValue()))); return column; }
    private static Instant instant(long epoch) { return epoch <= 0 ? null : Instant.ofEpochSecond(epoch); }
    private static String formatTime(long epoch) { return epoch <= 0 ? "-" : Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")); }
    private static String durationText(long seconds) { long hours = seconds / 3600, minutes = seconds % 3600 / 60; return hours > 0 ? hours + " sa " + minutes + " dk" : minutes + " dk"; }
    private boolean confirm(String message) { Alert alert = new Alert(Alert.AlertType.CONFIRMATION, LanguageManager.text(message), ButtonType.YES, ButtonType.NO); alert.setHeaderText(LanguageManager.text("Onay gerekiyor")); return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES; }
    private VBox page(Node... children) { VBox page = new VBox(14, children); page.setPadding(new Insets(18)); for (Node child : children) VBox.setVgrow(child, Priority.ALWAYS); return page; }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private static final class PlayerProfile { final String name; long firstSeen, lastSeen, returnedAt, totalSeconds, activeSince; int joins, deaths, advancements; PlayerProfile(String name) { this.name = name; } }
}
