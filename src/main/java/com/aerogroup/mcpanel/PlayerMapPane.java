package com.aerogroup.mcpanel;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.regex.*;

/** Konsol sorgularından oyuncu koordinatlarını alıp canlı X/Z haritasında gösterir. */
public final class PlayerMapPane {
    private static final Pattern POSITION = Pattern.compile("([A-Za-z0-9_]{1,16}) has the following entity data:\\s*\\[(-?\\d+(?:\\.\\d+)?)[dDfF]?,\\s*(-?\\d+(?:\\.\\d+)?)[dDfF]?,\\s*(-?\\d+(?:\\.\\d+)?)[dDfF]?\\]");
    private static final Pattern DIMENSION = Pattern.compile("([A-Za-z0-9_]{1,16}) has the following entity data:\\s*[\"']?(minecraft:[a-z0-9_./-]+)[\"']?");
    private final ServerManager local;
    private final ExarotonPane exaroton;
    private final PterodactylPane pterodactyl;
    private final ComboBox<String> provider = new ComboBox<>(FXCollections.observableArrayList("Yerel JAR", "Exaroton", "Pterodactyl"));
    private final ComboBox<String> dimension = new ComboBox<>(FXCollections.observableArrayList("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
    private final Label connection = new Label("Hazır"), scaleLabel = new Label();
    private final Canvas canvas = new Canvas(760, 560);
    private final ObservableList<PlayerPoint> rows = FXCollections.observableArrayList();
    private final Map<String, PlayerPoint> points = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Queue<ParsedLocation> pendingLocations = new ConcurrentLinkedQueue<>();
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(5), event -> requestPositions()), new KeyFrame(Duration.seconds(1), event -> expireAndDraw()));
    private final CheckBox autoCenter = new CheckBox("Oyuncuları otomatik ortala"), trails = new CheckBox("Hareket izlerini göster");
    private TableView<PlayerPoint> table;
    private double centerX, centerZ, zoom = .12, dragX, dragY, startCenterX, startCenterZ;
    private String selectedPlayer;
    private boolean viewBuilt, active, drawQueued;
    private final Consumer<String> remoteConsoleListener = this::onRemoteConsole;
    private final Consumer<ExarotonPane.ProSnapshot> remoteSnapshotListener = this::onRemoteSnapshot;
    private final Consumer<String> pterodactylConsoleListener = this::onPterodactylConsole;
    private final Consumer<PterodactylPane.ProSnapshot> pterodactylSnapshotListener = this::onPterodactylSnapshot;
    private final ChangeListener<String> remoteSelectionListener = (observable, oldName, newName) -> Platform.runLater(() -> { if (!"Exaroton sunucusu seçilmedi".equals(newName)) provider.getSelectionModel().select("Exaroton"); updateConnection(); });
    private final ChangeListener<String> pterodactylSelectionListener = (observable, oldName, newName) -> Platform.runLater(() -> { if (!"Pterodactyl sunucusu seçilmedi".equals(newName)) provider.getSelectionModel().select("Pterodactyl"); updateConnection(); });

    public PlayerMapPane(ServerManager local, ExarotonPane exaroton, PterodactylPane pterodactyl) {
        this.local = local; this.exaroton = exaroton; this.pterodactyl = pterodactyl; poll.setCycleCount(Animation.INDEFINITE);
        exaroton.addProConsoleListener(remoteConsoleListener);
        exaroton.addProSnapshotListener(remoteSnapshotListener);
        exaroton.activeServerNameProperty().addListener(remoteSelectionListener);
        pterodactyl.addConsoleListener(pterodactylConsoleListener);
        pterodactyl.addSnapshotListener(pterodactylSnapshotListener);
        pterodactyl.activeServerNameProperty().addListener(pterodactylSelectionListener);
    }

    public Node buildView() {
        viewBuilt = true;
        provider.getSelectionModel().selectFirst(); provider.setOnAction(event -> { updateConnection(); points.clear(); rows.clear(); selectedPlayer = null; requestDraw(); });
        dimension.getSelectionModel().selectFirst(); dimension.setOnAction(event -> { if (autoCenter.isSelected()) fitPlayers(); requestDraw(); });
        autoCenter.setSelected(true); trails.setSelected(true); autoCenter.setOnAction(event -> { if (autoCenter.isSelected()) fitPlayers(); requestDraw(); }); trails.setOnAction(event -> requestDraw());
        Button refresh = button("Şimdi Yenile", "primary"), center = button("Haritayı Ortala", "secondary"), selected = button("Seçili Oyuncuya Git", "secondary"), zoomIn = button("＋", "secondary"), zoomOut = button("−", "secondary"), resetZoom = button("Yakınlığı Sıfırla", "secondary");
        refresh.setOnAction(event -> requestPositions()); center.setOnAction(event -> { autoCenter.setSelected(true); fitPlayers(); requestDraw(); }); selected.setOnAction(event -> centerSelected());
        zoomIn.setOnAction(event -> zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, 1.5)); zoomOut.setOnAction(event -> zoomAt(canvas.getWidth() / 2, canvas.getHeight() / 2, 1 / 1.5)); resetZoom.setOnAction(event -> { autoCenter.setSelected(false); zoom = .12; requestDraw(); });
        connection.getStyleClass().add("metric-small"); scaleLabel.getStyleClass().add("muted"); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, new Label("Sağlayıcı"), provider, connection, new Label("Boyut"), dimension, spacer, refresh, center, selected); controls.setAlignment(Pos.CENTER_LEFT);
        HBox options = new HBox(10, autoCenter, trails, zoomOut, zoomIn, resetZoom, scaleLabel, new Label("Fare: sürükle = kaydır, tekerlek = yakınlaştır, noktaya tıkla = seç")); options.setAlignment(Pos.CENTER_LEFT);

        StackPane map = new StackPane(canvas); map.getStyleClass().add("map-surface"); canvas.widthProperty().bind(map.widthProperty()); canvas.heightProperty().bind(map.heightProperty()); canvas.widthProperty().addListener((obs, oldValue, newValue) -> requestDraw()); canvas.heightProperty().addListener((obs, oldValue, newValue) -> requestDraw()); installMouseControls();
        table = new TableView<>(rows); table.getColumns().add(column("Oyuncu", point -> point.name)); table.getColumns().add(column("X", point -> format(point.x))); table.getColumns().add(column("Y", point -> format(point.y))); table.getColumns().add(column("Z", point -> format(point.z))); table.getColumns().add(column("Son Veri", point -> point.lastSeen.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss")))); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN); table.setPrefWidth(330); table.getSelectionModel().selectedItemProperty().addListener((obs, oldPoint, newPoint) -> { if (newPoint != null) { selectedPlayer = newPoint.name; requestDraw(); } });
        VBox mapCard = card("CANLI OYUNCU HARİTASI", map); HBox.setHgrow(mapCard, Priority.ALWAYS); VBox.setVgrow(map, Priority.ALWAYS);
        VBox listCard = card("HARİTADAKİ OYUNCULAR", table); listCard.setPrefWidth(350); VBox.setVgrow(table, Priority.ALWAYS);
        HBox body = new HBox(14, mapCard, listCard); HBox.setHgrow(mapCard, Priority.ALWAYS); VBox page = new VBox(12, controls, options, body); page.setPadding(new Insets(18)); VBox.setVgrow(body, Priority.ALWAYS);
        updateConnection(); return page;
    }

    public void setActive(boolean value) {
        active = value;
        if (!viewBuilt) return;
        if (active) { refreshRows(); if (autoCenter.isSelected()) fitPlayers(); requestPositions(); poll.play(); requestDraw(); }
        else poll.stop();
    }

    public void onLocalConsole(String line) { if (isLocal()) parseLine(line); }
    private void onRemoteConsole(String line) { if (isExaroton()) parseLine(line); }
    private void onPterodactylConsole(String line) { if (isPterodactyl()) parseLine(line); }
    private void onRemoteSnapshot(ExarotonPane.ProSnapshot snapshot) { Platform.runLater(() -> { if (isExaroton()) { connection.setText(snapshot.name() + " • " + snapshot.status()); retainPlayers(snapshot.playerNames()); } }); }
    private void onPterodactylSnapshot(PterodactylPane.ProSnapshot snapshot) { Platform.runLater(() -> { if (isPterodactyl()) { connection.setText(snapshot.name() + " • " + snapshot.status()); if (!snapshot.playerNames().isEmpty()) retainPlayers(snapshot.playerNames()); } }); }
    public void onLocalPlayers(List<String> names) { if (isLocal()) Platform.runLater(() -> retainPlayers(names)); }
    private void parseLine(String line) {
        ParsedLocation parsed = parseConsoleLine(line); if (parsed == null) return;
        while (pendingLocations.size() >= 1_000) pendingLocations.poll(); pendingLocations.offer(parsed);
    }
    static ParsedLocation parseConsoleLine(String line) { Matcher position = POSITION.matcher(line); if (position.find()) return new ParsedLocation(position.group(1), Double.parseDouble(position.group(2)), Double.parseDouble(position.group(3)), Double.parseDouble(position.group(4)), null); Matcher world = DIMENSION.matcher(line); return world.find() ? new ParsedLocation(world.group(1), 0, 0, 0, world.group(2)) : null; }
    private void updatePosition(String name, double x, double y, double z) {
        PlayerPoint point = points.computeIfAbsent(name, PlayerPoint::new); point.x = x; point.y = y; point.z = z; point.lastSeen = Instant.now(); point.history.addLast(new MapPosition(x, z)); while (point.history.size() > 40) point.history.removeFirst(); refreshRows(); if (autoCenter.isSelected()) fitPlayers(); requestDraw();
    }
    private void updateDimension(String name, String value) { PlayerPoint point = points.computeIfAbsent(name, PlayerPoint::new); point.dimension = value; point.lastSeen = Instant.now(); refreshRows(); if (autoCenter.isSelected()) fitPlayers(); requestDraw(); }
    private void retainPlayers(Collection<String> names) { Set<String> activeNames = new HashSet<>(names); points.entrySet().removeIf(entry -> !activeNames.contains(entry.getKey()) && java.time.Duration.between(entry.getValue().lastSeen, Instant.now()).toSeconds() > 20); refreshRows(); requestDraw(); }
    private void refreshRows() { if (!active) return; String world = dimension.getValue(); rows.setAll(points.values().stream().filter(point -> world.equals(point.dimension)).toList()); if (table != null) table.refresh(); }

    private void requestPositions() {
        updateConnection(); String pos = "execute as @a run data get entity @s Pos", dim = "execute as @a run data get entity @s Dimension";
        if (isExaroton()) {
            if (!exaroton.hasActiveServer()) { connection.setText("Exaroton sunucusu seçilmedi"); return; }
            try { exaroton.executeAdminCommand(pos).exceptionally(error -> { Platform.runLater(() -> connection.setText("Konum sorgusu reddedildi")); return null; }); exaroton.executeAdminCommand(dim).exceptionally(error -> null); }
            catch (Exception error) { connection.setText("Sorgu gönderilemedi"); }
        } else if (isPterodactyl()) {
            if (!pterodactyl.hasActiveServer()) { connection.setText("Pterodactyl sunucusu seçilmedi"); return; }
            pterodactyl.executeAdminCommand(pos).exceptionally(error -> { Platform.runLater(() -> connection.setText("Konum sorgusu reddedildi")); return null; });
            pterodactyl.executeAdminCommand(dim).exceptionally(error -> null);
        } else {
            if (!local.isRunning()) { connection.setText("Yerel sunucu kapalı"); return; }
            try { local.command(pos); local.command(dim); connection.setText("Yerel sunucu • canlı"); } catch (IOException error) { connection.setText("Konum sorgusu gönderilemedi"); }
        }
    }
    private void updateConnection() { connection.setText(isExaroton() ? exaroton.getActiveServerName() : isPterodactyl() ? pterodactyl.getActiveServerName() : (local.isRunning() ? "Yerel sunucu • canlı" : "Yerel sunucu kapalı")); }
    private boolean isLocal() { return "Yerel JAR".equals(provider.getValue()); }
    private boolean isExaroton() { return "Exaroton".equals(provider.getValue()); }
    private boolean isPterodactyl() { return "Pterodactyl".equals(provider.getValue()); }
    private void expireAndDraw() {
        ParsedLocation parsed; boolean changed = false; while ((parsed = pendingLocations.poll()) != null) { PlayerPoint point = points.computeIfAbsent(parsed.name, PlayerPoint::new); point.lastSeen = Instant.now(); if (parsed.dimension != null) point.dimension = parsed.dimension; else { point.x = parsed.x; point.y = parsed.y; point.z = parsed.z; point.history.addLast(new MapPosition(parsed.x, parsed.z)); while (point.history.size() > 40) point.history.removeFirst(); } changed = true; }
        Instant cutoff = Instant.now().minusSeconds(30); changed |= points.entrySet().removeIf(entry -> entry.getValue().lastSeen.isBefore(cutoff)); if (changed) { refreshRows(); if (autoCenter.isSelected()) fitPlayers(); requestDraw(); }
    }

    private void fitPlayers() {
        List<PlayerPoint> visible = visiblePoints(); if (visible.isEmpty()) { centerX = centerZ = 0; return; }
        double minX = visible.stream().mapToDouble(point -> point.x).min().orElse(0), maxX = visible.stream().mapToDouble(point -> point.x).max().orElse(0), minZ = visible.stream().mapToDouble(point -> point.z).min().orElse(0), maxZ = visible.stream().mapToDouble(point -> point.z).max().orElse(0); centerX = (minX + maxX) / 2; centerZ = (minZ + maxZ) / 2;
        if (visible.size() > 1 && canvas.getWidth() > 100 && canvas.getHeight() > 100) { double width = Math.max(100, maxX - minX), height = Math.max(100, maxZ - minZ); zoom = clamp(Math.min((canvas.getWidth() - 100) / width, (canvas.getHeight() - 100) / height), .01, 4); }
    }
    private void centerSelected() { PlayerPoint point = selectedPlayer == null ? null : points.get(selectedPlayer); if (point != null) { autoCenter.setSelected(false); dimension.getSelectionModel().select(point.dimension); centerX = point.x; centerZ = point.z; zoom = Math.max(zoom, .5); requestDraw(); } }
    private List<PlayerPoint> visiblePoints() { String world = dimension.getValue(); return points.values().stream().filter(point -> world.equals(point.dimension)).toList(); }

    private void draw() {
        double width = canvas.getWidth(), height = canvas.getHeight(); if (width <= 0 || height <= 0) return; GraphicsContext g = canvas.getGraphicsContext2D(); g.setFill(Color.web("#091016")); g.fillRect(0, 0, width, height); drawGrid(g, width, height);
        for (PlayerPoint point : visiblePoints()) {
            Color color = playerColor(point.name); if (trails.isSelected() && point.history.size() > 1) { g.setStroke(color.deriveColor(0, 1, 1, .38)); g.setLineWidth(2); MapPosition previous = null; for (MapPosition position : point.history) { if (previous != null) g.strokeLine(sx(previous.x, width), sz(previous.z, height), sx(position.x, width), sz(position.z, height)); previous = position; } }
            double x = sx(point.x, width), z = sz(point.z, height), radius = point.name.equals(selectedPlayer) ? 10 : 7; g.setFill(Color.color(0, 0, 0, .55)); g.fillOval(x - radius - 2, z - radius - 2, (radius + 2) * 2, (radius + 2) * 2); g.setFill(color); g.fillOval(x - radius, z - radius, radius * 2, radius * 2); g.setFill(Color.WHITE); g.fillText(point.name + "  " + (int) point.x + ", " + (int) point.z, x + radius + 5, z - 5);
        }
        if (visiblePoints().isEmpty()) { g.setFill(Color.web("#78909f")); g.fillText(LanguageManager.text("Bu boyutta henüz canlı oyuncu konumu alınmadı."), 24, 38); }
        drawCrosshair(g, width, height);
        scaleLabel.setText(String.format(Locale.US, "Ölçek: %.3f px/blok • Nişan X: %.0f Z: %.0f", zoom, centerX, centerZ));
    }
    private void requestDraw() {
        if (!viewBuilt || !active || drawQueued) return;
        drawQueued = true;
        Platform.runLater(() -> { drawQueued = false; if (active) draw(); });
    }
    private void drawCrosshair(GraphicsContext g, double width, double height) {
        double x = width / 2, y = height / 2; g.setLineWidth(3); g.setStroke(Color.color(0, 0, 0, .75)); g.strokeLine(x - 15, y, x + 15, y); g.strokeLine(x, y - 15, x, y + 15); g.strokeOval(x - 6, y - 6, 12, 12);
        g.setLineWidth(1.5); g.setStroke(Color.web("#f2c94c")); g.strokeLine(x - 14, y, x - 4, y); g.strokeLine(x + 4, y, x + 14, y); g.strokeLine(x, y - 14, x, y - 4); g.strokeLine(x, y + 4, x, y + 14); g.strokeOval(x - 5, y - 5, 10, 10);
    }
    private void drawGrid(GraphicsContext g, double width, double height) {
        double[] steps = {10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000}; double step = steps[steps.length - 1]; for (double candidate : steps) if (candidate * zoom >= 65) { step = candidate; break; }
        g.setLineWidth(1); g.setFont(javafx.scene.text.Font.font(10)); double minX = centerX - width / 2 / zoom, maxX = centerX + width / 2 / zoom, minZ = centerZ - height / 2 / zoom, maxZ = centerZ + height / 2 / zoom;
        for (double x = Math.floor(minX / step) * step; x <= maxX; x += step) { double screen = sx(x, width); g.setStroke(Math.abs(x) < .001 ? Color.web("#3b718a") : Color.web("#1d303b")); g.strokeLine(screen, 0, screen, height); g.setFill(Color.web("#617985")); g.fillText(String.format("%.0f", x), screen + 3, 13); }
        for (double z = Math.floor(minZ / step) * step; z <= maxZ; z += step) { double screen = sz(z, height); g.setStroke(Math.abs(z) < .001 ? Color.web("#3b718a") : Color.web("#1d303b")); g.strokeLine(0, screen, width, screen); g.setFill(Color.web("#617985")); g.fillText(String.format("%.0f", z), 3, screen - 3); }
    }
    private void installMouseControls() {
        canvas.setOnMousePressed(event -> { if (event.getButton() == MouseButton.PRIMARY) { dragX = event.getX(); dragY = event.getY(); startCenterX = centerX; startCenterZ = centerZ; } });
        canvas.setOnMouseDragged(event -> { if (event.isPrimaryButtonDown()) { autoCenter.setSelected(false); centerX = startCenterX - (event.getX() - dragX) / zoom; centerZ = startCenterZ - (event.getY() - dragY) / zoom; requestDraw(); } });
        canvas.setOnMouseClicked(event -> { if (event.getButton() != MouseButton.PRIMARY || event.isStillSincePress() == false) return; PlayerPoint nearest = null; double distance = 18; for (PlayerPoint point : visiblePoints()) { double d = Math.hypot(event.getX() - sx(point.x, canvas.getWidth()), event.getY() - sz(point.z, canvas.getHeight())); if (d < distance) { nearest = point; distance = d; } } if (nearest != null) { selectedPlayer = nearest.name; table.getSelectionModel().select(nearest); requestDraw(); } });
        canvas.setOnScroll(event -> { if (event.getDeltaY() != 0) zoomAt(event.getX(), event.getY(), event.getDeltaY() > 0 ? 1.45 : 1 / 1.45); event.consume(); });
    }
    private void zoomAt(double screenX, double screenY, double factor) { autoCenter.setSelected(false); double oldZoom = zoom, worldX = centerX + (screenX - canvas.getWidth() / 2) / oldZoom, worldZ = centerZ + (screenY - canvas.getHeight() / 2) / oldZoom; zoom = clamp(oldZoom * factor, .005, 40); centerX = worldX - (screenX - canvas.getWidth() / 2) / zoom; centerZ = worldZ - (screenY - canvas.getHeight() / 2) / zoom; requestDraw(); }
    private double sx(double worldX, double width) { return (worldX - centerX) * zoom + width / 2; }
    private double sz(double worldZ, double height) { return (worldZ - centerZ) * zoom + height / 2; }
    private double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private Color playerColor(String name) { return Color.hsb(Math.floorMod(name.hashCode(), 360), .72, .95); }
    private String format(double value) { return String.format(Locale.US, "%.1f", value); }
    private TableColumn<PlayerPoint, String> column(String title, java.util.function.Function<PlayerPoint, String> value) { TableColumn<PlayerPoint, String> column = new TableColumn<>(title); column.setCellValueFactory(row -> new ReadOnlyStringWrapper(value.apply(row.getValue()))); return column; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private VBox card(String title, Node... nodes) { Label heading = new Label(title); heading.getStyleClass().add("section-title"); VBox card = new VBox(10, heading); card.getChildren().addAll(nodes); card.getStyleClass().add("card"); return card; }
    public void shutdown() { poll.stop(); exaroton.removeProConsoleListener(remoteConsoleListener); exaroton.removeProSnapshotListener(remoteSnapshotListener); exaroton.activeServerNameProperty().removeListener(remoteSelectionListener); pterodactyl.removeConsoleListener(pterodactylConsoleListener); pterodactyl.removeSnapshotListener(pterodactylSnapshotListener); pterodactyl.activeServerNameProperty().removeListener(pterodactylSelectionListener); pendingLocations.clear(); points.clear(); rows.clear(); }
    private static final class PlayerPoint { final String name; double x, y, z; String dimension = "minecraft:overworld"; Instant lastSeen = Instant.now(); final Deque<MapPosition> history = new ArrayDeque<>(); PlayerPoint(String name) { this.name = name; } }
    private record MapPosition(double x, double z) { }
    record ParsedLocation(String name, double x, double y, double z, String dimension) { }
}
