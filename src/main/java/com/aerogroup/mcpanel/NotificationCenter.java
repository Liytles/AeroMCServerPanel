package com.aerogroup.mcpanel;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Uygulamadaki önemli olayları güvenli, kalıcı ve sınırlı bir listede toplar. */
public final class NotificationCenter {
    public enum Severity { INFO, SUCCESS, WARNING, CRITICAL }
    public enum EventType { ONLINE, OFFLINE, PLAYER, CRASH, PERFORMANCE, BACKUP, AUTOMATION, UPDATE, CREDIT, SYSTEM }
    public record Entry(String id, Instant time, Severity severity, String category, String title, String message, boolean read) {
        Entry withRead(boolean value) { return new Entry(id, time, severity, category, title, message, value); }
        public String timeText() { return time.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")); }
    }

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".aeromc-panel", "notifications.log");
    private static final String SERVER_SOURCE_PREFIX = "Sunucu • ";
    private static final Pattern DISCORD_WEBHOOK = Pattern.compile("https://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/webhooks/\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_SECRET = Pattern.compile("(?i)(api[ _-]?key|authorization|token|webhook)\\s*[:=]\\s*\\S+");
    private static final int LIMIT = 160;
    private static final NotificationCenter SHARED = new NotificationCenter(FILE);

    private final Path file;
    private final Path preferencesFile;
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final ObservableList<String> serverSources = FXCollections.observableArrayList();
    private final Set<String> disabledServerSources = new LinkedHashSet<>();
    private final Map<String, EnumSet<EventType>> disabledEvents = new LinkedHashMap<>();
    private final Map<String, Instant> lastDesktopNotifications = new HashMap<>();
    private final ReadOnlyIntegerWrapper unreadCount = new ReadOnlyIntegerWrapper();
    private final BooleanProperty enabled = new SimpleBooleanProperty(true), collapsed = new SimpleBooleanProperty(false);
    private boolean quietHoursEnabled;
    private LocalTime quietStart = LocalTime.of(23, 0), quietEnd = LocalTime.of(8, 0);
    private int cooldownSeconds = 60;

    NotificationCenter(Path file) {
        this.file = file; this.preferencesFile = file.resolveSibling("notification-settings.properties"); loadPreferences(); entries.setAll(load(file));
        entries.stream().map(Entry::category).filter(NotificationCenter::isServerSource).forEach(this::registerServerSourceNow); registerServerSourceNow(serverSource("Yerel JAR", "")); updateUnread();
    }
    public static NotificationCenter shared() { return SHARED; }
    public ObservableList<Entry> entries() { return FXCollections.unmodifiableObservableList(entries); }
    public ReadOnlyIntegerProperty unreadCountProperty() { return unreadCount.getReadOnlyProperty(); }
    public int unreadCount() { return unreadCount.get(); }
    public ReadOnlyBooleanProperty enabledProperty() { return enabled; }
    public boolean isEnabled() { return enabled.get(); }
    public void setEnabled(boolean value) { enabled.set(value); savePreferences(); }
    public ReadOnlyBooleanProperty collapsedProperty() { return collapsed; }
    public boolean isCollapsed() { return collapsed.get(); }
    public void setCollapsed(boolean value) { collapsed.set(value); savePreferences(); }
    public ObservableList<String> serverSources() { return FXCollections.unmodifiableObservableList(serverSources); }
    public boolean isServerSourceEnabled(String source) { return !disabledServerSources.contains(source); }
    public void setServerSourceEnabled(String source, boolean value) { if (!isServerSource(source)) return; if (value) disabledServerSources.remove(source); else disabledServerSources.add(source); savePreferences(); }
    public boolean accepts(String source) { return enabled.get() && (!isServerSource(source) || isServerSourceEnabled(source)); }
    public boolean accepts(String source, String title, String message) { return accepts(source) && (!isServerSource(source) || isEventEnabled(source, inferEventType(title, message))); }
    public boolean isEventEnabled(String source, EventType type) { return !disabledEvents.getOrDefault(source, EnumSet.noneOf(EventType.class)).contains(type); }
    public void setEventEnabled(String source, EventType type, boolean value) { if (!isServerSource(source) || type == null) return; EnumSet<EventType> disabled = disabledEvents.computeIfAbsent(source, ignored -> EnumSet.noneOf(EventType.class)); if (value) disabled.remove(type); else disabled.add(type); if (disabled.isEmpty()) disabledEvents.remove(source); savePreferences(); }
    public boolean isQuietHoursEnabled() { return quietHoursEnabled; }
    public LocalTime getQuietStart() { return quietStart; }
    public LocalTime getQuietEnd() { return quietEnd; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public void configureDelivery(boolean quietEnabled, LocalTime start, LocalTime end, int cooldown) { quietHoursEnabled = quietEnabled; quietStart = Objects.requireNonNull(start); quietEnd = Objects.requireNonNull(end); cooldownSeconds = Math.max(0, Math.min(3600, cooldown)); savePreferences(); }
    public void registerServerSource(String source) { Runnable action = () -> registerServerSourceNow(safeText(source, 80)); if (Platform.isFxApplicationThread()) action.run(); else try { Platform.runLater(action); } catch (IllegalStateException ignored) { action.run(); } }
    public void unregisterServerSource(String source) { serverSources.remove(source); disabledServerSources.remove(source); disabledEvents.remove(source); savePreferences(); }
    public static String serverSource(String provider, String name) { String suffix = safeText(name, 45); return SERVER_SOURCE_PREFIX + safeText(provider, 25) + (suffix.isBlank() ? "" : " • " + suffix); }
    public static boolean isServerSource(String source) { return Objects.toString(source, "").startsWith(SERVER_SOURCE_PREFIX); }

    public void publish(String category, String title, String message) { publish(inferSeverity(title, message), category, title, message); }
    public void publish(Severity severity, String category, String title, String message) {
        String safeCategory = safeText(category, 80); if (isServerSource(safeCategory)) registerServerSource(safeCategory); if (!accepts(safeCategory, title, message)) return;
        Runnable action = () -> add(new Entry(UUID.randomUUID().toString(), Instant.now(), severity == null ? Severity.INFO : severity,
                safeCategory, safeText(title, 100), safeText(message, 320), false));
        if (Platform.isFxApplicationThread()) action.run(); else try { Platform.runLater(action); } catch (IllegalStateException ignored) { action.run(); }
    }

    public void markRead(Entry entry) {
        if (entry == null || entry.read()) return; int index = entries.indexOf(entry); if (index < 0) return;
        entries.set(index, entry.withRead(true)); updateUnread(); save();
    }
    public void markAllRead() { for (int i = 0; i < entries.size(); i++) entries.set(i, entries.get(i).withRead(true)); updateUnread(); save(); }
    public void clear() { entries.clear(); updateUnread(); save(); }
    public synchronized boolean allowDesktopNotification(String source, String title, String message, Instant now) {
        if (!accepts(source, title, message) || isQuietTime(now.atZone(ZoneId.systemDefault()).toLocalTime(), quietHoursEnabled, quietStart, quietEnd)) return false;
        String key = source + "\u0000" + inferEventType(title, message) + "\u0000" + safeText(title, 100); Instant previous = lastDesktopNotifications.get(key);
        if (cooldownSeconds > 0 && previous != null && Duration.between(previous, now).getSeconds() < cooldownSeconds) return false;
        lastDesktopNotifications.put(key, now); return true;
    }

    private void add(Entry entry) {
        if (!entries.isEmpty()) {
            Entry latest = entries.get(0);
            if (latest.title().equals(entry.title()) && latest.message().equals(entry.message()) && Duration.between(latest.time(), entry.time()).abs().toSeconds() < 8) return;
        }
        entries.add(0, entry); while (entries.size() > LIMIT) entries.remove(entries.size() - 1); updateUnread(); save();
    }
    private void updateUnread() { unreadCount.set((int) entries.stream().filter(entry -> !entry.read()).count()); }
    private void registerServerSourceNow(String source) { if (!isServerSource(source) || serverSources.contains(source)) return; serverSources.add(source); serverSources.sort(String.CASE_INSENSITIVE_ORDER); savePreferences(); }
    private void save() {
        try {
            Files.createDirectories(file.getParent()); Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, entries.stream().map(NotificationCenter::encode).toList(), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }
    private void loadPreferences() {
        Properties values = new Properties();
        try { if (Files.isRegularFile(preferencesFile)) try (var reader = Files.newBufferedReader(preferencesFile, StandardCharsets.UTF_8)) { values.load(reader); } }
        catch (IOException ignored) { }
        enabled.set(Boolean.parseBoolean(values.getProperty("enabled", "true"))); collapsed.set(Boolean.parseBoolean(values.getProperty("collapsed", "false")));
        quietHoursEnabled = Boolean.parseBoolean(values.getProperty("quiet.enabled", "false")); quietStart = time(values.getProperty("quiet.start"), LocalTime.of(23, 0)); quietEnd = time(values.getProperty("quiet.end"), LocalTime.of(8, 0)); cooldownSeconds = bounded(values.getProperty("cooldown.seconds"), 60, 0, 3600);
        int known = number(values.getProperty("known.count")), disabled = number(values.getProperty("disabled.count")), rules = number(values.getProperty("rules.count"));
        for (int i = 0; i < known; i++) { String source = values.getProperty("known." + i, ""); if (isServerSource(source) && !serverSources.contains(source)) serverSources.add(source); }
        for (int i = 0; i < disabled; i++) { String source = values.getProperty("disabled." + i, ""); if (isServerSource(source)) disabledServerSources.add(source); }
        for (int i = 0; i < rules; i++) { String[] rule = values.getProperty("rule." + i, "").split("\\|", 2); try { String source = decode64(rule[0]); EventType type = EventType.valueOf(rule[1]); if (isServerSource(source)) disabledEvents.computeIfAbsent(source, ignored -> EnumSet.noneOf(EventType.class)).add(type); } catch (Exception ignored) { } }
    }
    private void savePreferences() {
        try {
            Files.createDirectories(preferencesFile.getParent()); Properties values = new Properties(); values.setProperty("enabled", Boolean.toString(enabled.get())); values.setProperty("collapsed", Boolean.toString(collapsed.get())); values.setProperty("quiet.enabled", Boolean.toString(quietHoursEnabled)); values.setProperty("quiet.start", quietStart.toString()); values.setProperty("quiet.end", quietEnd.toString()); values.setProperty("cooldown.seconds", Integer.toString(cooldownSeconds));
            values.setProperty("known.count", Integer.toString(serverSources.size())); for (int i = 0; i < serverSources.size(); i++) values.setProperty("known." + i, serverSources.get(i));
            values.setProperty("disabled.count", Integer.toString(disabledServerSources.size())); int index = 0; for (String source : disabledServerSources) values.setProperty("disabled." + index++, source);
            int ruleIndex = 0; for (var rule : disabledEvents.entrySet()) for (EventType type : rule.getValue()) values.setProperty("rule." + ruleIndex++, base64(rule.getKey()) + "|" + type.name()); values.setProperty("rules.count", Integer.toString(ruleIndex));
            Path temporary = preferencesFile.resolveSibling(preferencesFile.getFileName() + ".tmp"); try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { values.store(writer, "AeroMC notification preferences"); }
            try { Files.move(temporary, preferencesFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, preferencesFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ignored) { }
    }

    static List<Entry> load(Path file) {
        if (!Files.isRegularFile(file)) return List.of();
        try { return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(NotificationCenter::decode).flatMap(Optional::stream).limit(LIMIT).toList(); }
        catch (IOException ignored) { return List.of(); }
    }
    static String encode(Entry entry) {
        return String.join("|", entry.id(), Long.toString(entry.time().toEpochMilli()), entry.severity().name(), Boolean.toString(entry.read()),
                base64(entry.category()), base64(entry.title()), base64(entry.message()));
    }
    static Optional<Entry> decode(String line) {
        try {
            String[] values = line.split("\\|", 7); if (values.length != 7) return Optional.empty();
            return Optional.of(new Entry(values[0], Instant.ofEpochMilli(Long.parseLong(values[1])), Severity.valueOf(values[2]), decode64(values[4]), decode64(values[5]), decode64(values[6]), Boolean.parseBoolean(values[3])));
        } catch (Exception ignored) { return Optional.empty(); }
    }
    static Severity inferSeverity(String title, String message) {
        String value = (Objects.toString(title, "") + " " + Objects.toString(message, "")).toLowerCase(Locale.ROOT);
        if (contains(value, "çök", "crash", "başarısız", "failed", "açılamadı", "kritik")) return Severity.CRITICAL;
        if (contains(value, "uyarı", "düşük kredi", "yüksek ram", "offline", "erişilemiyor", "zaman aş")) return Severity.WARNING;
        if (contains(value, "hazır", "başarılı", "online", "tamamlandı", "güncel", "doğrulandı")) return Severity.SUCCESS;
        return Severity.INFO;
    }
    static EventType inferEventType(String title, String message) {
        String value = (Objects.toString(title, "") + " " + Objects.toString(message, "")).toLowerCase(Locale.ROOT);
        if (contains(value, "çök", "crash")) return EventType.CRASH;
        if (contains(value, "oyuncu", "player", "katıldı", "joined")) return EventType.PLAYER;
        if (contains(value, "yedek", "backup")) return EventType.BACKUP;
        if (contains(value, "ram", "kriz", "lag", "spark", "tps", "performans")) return EventType.PERFORMANCE;
        if (contains(value, "otomatik", "otomasyon", "program", "schedule")) return EventType.AUTOMATION;
        if (contains(value, "güncelle", "update", "sürüm", "kurucu")) return EventType.UPDATE;
        if (contains(value, "kredi", "credit", "bakiye")) return EventType.CREDIT;
        if (contains(value, "offline", "kapandı", "kapalı", "erişilemedi", "durduruldu")) return EventType.OFFLINE;
        if (contains(value, "online", "açıldı", "başladı", "hazır")) return EventType.ONLINE;
        return EventType.SYSTEM;
    }
    static boolean isQuietTime(LocalTime now, boolean enabled, LocalTime start, LocalTime end) { if (!enabled || start.equals(end)) return false; return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end) : !now.isBefore(start) || now.isBefore(end); }
    static String safeText(String value, int limit) {
        String cleaned = DISCORD_WEBHOOK.matcher(Objects.toString(value, "").replace('\n', ' ').replace('\r', ' ').trim()).replaceAll("[gizli webhook]");
        cleaned = NAMED_SECRET.matcher(cleaned).replaceAll("$1=[gizli]");
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, Math.max(0, limit - 1)) + "…";
    }
    private static boolean contains(String value, String... needles) { for (String needle : needles) if (value.contains(needle)) return true; return false; }
    private static int number(String value) { try { return Math.max(0, Integer.parseInt(value)); } catch (Exception ignored) { return 0; } }
    private static int bounded(String value, int fallback, int min, int max) { try { return Math.max(min, Math.min(max, Integer.parseInt(value))); } catch (Exception ignored) { return fallback; } }
    private static LocalTime time(String value, LocalTime fallback) { try { return LocalTime.parse(value); } catch (Exception ignored) { return fallback; } }
    private static String base64(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode64(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
}
