package com.aerogroup.mcpanel;

import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Haftalık raporun veri bağlama, biçimlendirme ve JavaFX görünümünü ProToolsPane'den ayırır. */
final class WeeklyReportPane {
    record Snapshot(boolean remote, int localMemoryMb, List<SmartThresholdAdvisor.Sample> performance,
                    List<FleetHealthHistory.Sample> fleet, List<CrisisHistory.Entry> crises,
                    List<DiagnosticHistory.Entry> diagnostics, List<WeeklyReportEngine.PlayerInput> players) {
        Snapshot {
            performance = List.copyOf(performance); fleet = List.copyOf(fleet); crises = List.copyOf(crises);
            diagnostics = List.copyOf(diagnostics); players = List.copyOf(players);
        }
    }

    private final Supplier<Snapshot> source;
    private final ObservableList<String> ramRows = FXCollections.observableArrayList(), errorRows = FXCollections.observableArrayList(), healthRows = FXCollections.observableArrayList(), playerRows = FXCollections.observableArrayList();
    private final Label ramTitle = new Label("RAM & KREDİ TASARRUFU"), healthTitle = new Label("FİLO SAĞLIK KARŞILAŞTIRMASI"), ramNote = new Label();

    WeeklyReportPane(Supplier<Snapshot> source) { this.source = Objects.requireNonNull(source); }

    Node buildView() {
        ListView<String> ram = wrappedList(ramRows, 150), errors = wrappedList(errorRows, 130), health = wrappedList(healthRows, 150), players = wrappedList(playerRows, 105);
        Button refresh = button("Raporu Yenile", "primary"); refresh.setOnAction(event -> refresh());
        Label scope = new Label("Son 7 gündeki yerel kayıtlar kullanılır. Rapor yalnızca AeroMC açıkken gözlenen süreyi ve olayları sayar; eksik veriyi tahmin gibi göstermez."); scope.setWrapText(true); scope.getStyleClass().add("muted");
        ramNote.setWrapText(true); ramNote.getStyleClass().add("muted");
        VBox ramCard = reportCard(ramTitle, ramNote, ram), errorCard = card("HAFTANIN EN SIK HATALARI", errors), healthCard = reportCard(healthTitle, health), playerCard = card("YENİ & DÖNEN OYUNCULAR", players);
        GridPane upper = reportRow(ramCard, errorCard, 62, 38), lower = reportRow(healthCard, playerCard, 55, 45);
        refresh(); return page(card("HAFTALIK RAPOR MERKEZİ", new HBox(8, refresh), scope), upper, lower);
    }

    void refresh() {
        Snapshot data = source.get(); Instant now = Instant.now(); boolean english = LanguageManager.isEnglish(); WeeklyReportEngine.Report report;
        if (data.remote()) {
            ramTitle.setText(english ? "RAM & CREDIT SAVINGS" : "RAM & KREDİ TASARRUFU"); healthTitle.setText(english ? "FLEET HEALTH COMPARISON" : "FİLO SAĞLIK KARŞILAŞTIRMASI");
            ramNote.setText(english ? "Exaroton savings use the official 1 credit / GiB RAM / hour rate, upper 10% RAM band and 20% headroom. Lowering RAM also lowers CPU share; verify under peak load." : "Exaroton tasarrufu resmî 1 kredi / GiB RAM / saat tarifesiyle; RAM üst %10 değeri ve %20 güvenli pay kullanılarak hesaplanır. RAM'i azaltmak CPU payını da düşürür: öneriyi önce yoğun saatte doğrula.");
            report = WeeklyReportEngine.generate(now, data.performance(), data.fleet(), data.crises(), data.diagnostics(), data.players());
            if (report.ramSuggestions().isEmpty()) ramRows.setAll(english ? "No Exaroton fleet/RAM data yet. Connect the account and leave AeroMC open while the server runs." : "Henüz Exaroton filo/RAM verisi yok. Hesabı bağla ve sunucu çalışırken AeroMC'yi açık bırak.");
            else ramRows.setAll(report.ramSuggestions().stream().map(value -> formatRamSuggestion(value, english)).toList());
            if (report.fleet().isEmpty()) healthRows.setAll(english ? "Fleet comparison needs Exaroton observations." : "Filo karşılaştırması için Exaroton gözlemi gerekiyor.");
            else { List<String> rows = new ArrayList<>(); boolean stableMarked = false; for (WeeklyReportEngine.FleetScore value : report.fleet()) { boolean mostStable = value.ready() && !stableMarked; rows.add(formatFleet(value, mostStable, english)); if (mostStable) stableMarked = true; } healthRows.setAll(rows); }
        } else {
            String localSource = NotificationCenter.serverSource("Yerel JAR", "");
            List<SmartThresholdAdvisor.Sample> performance = data.performance().stream().filter(value -> value.source().equals(localSource)).toList();
            List<CrisisHistory.Entry> crises = data.crises().stream().filter(value -> value.source().equals(localSource)).toList();
            List<DiagnosticHistory.Entry> diagnostics = data.diagnostics().stream().filter(value -> value.source().equals(localSource) || value.source().equals("Geçmiş olay")).toList();
            report = WeeklyReportEngine.generate(now, performance, List.of(), crises, diagnostics, data.players()); WeeklyReportEngine.LocalSummary local = WeeklyReportEngine.localSummary(now, localSource, data.performance(), data.crises());
            ramTitle.setText(english ? "LOCAL RAM & CPU SUMMARY" : "YEREL RAM & CPU ÖZETİ"); healthTitle.setText(english ? "LOCAL SERVER HEALTH" : "YEREL SUNUCU SAĞLIĞI");
            ramNote.setText(english ? "Uses only measurements observed while the local JAR is running. Any RAM recommendation includes 20% headroom and never changes the setting automatically." : "Yalnızca Yerel JAR çalışırken gözlenen ölçümler kullanılır. RAM değerlendirmesi %20 güvenli pay bırakır ve ayarı asla otomatik değiştirmez.");
            ramRows.setAll(formatLocalPerformance(local, data.localMemoryMb(), english)); healthRows.setAll(formatLocalHealth(local, report, english));
        }
        if (report.errors().isEmpty()) errorRows.setAll(english ? "No recurring server error was detected in the last 7 days." : "Son 7 günde tekrarlayan bir sunucu hatası yakalanmadı.");
        else errorRows.setAll(report.errors().stream().map(value -> formatError(value, english)).toList());
        WeeklyReportEngine.PlayerSummary players = report.players(); playerRows.setAll(english
                ? players.newPlayers() + " new • " + players.returningPlayers() + " returned after 30+ days • " + players.seenThisWeek() + " seen this week • " + players.dormantPlayers() + " absent for 30+ days\nTracked player profiles: " + players.trackedPlayers()
                : players.newPlayers() + " yeni • " + players.returningPlayers() + " oyuncu 30+ gün sonra döndü • " + players.seenThisWeek() + " oyuncu bu hafta görüldü • " + players.dormantPlayers() + " oyuncu 30+ gündür görünmüyor\nTakip edilen oyuncu profili: " + players.trackedPlayers());
    }

    private List<String> formatLocalPerformance(WeeklyReportEngine.LocalSummary value, int configuredMemoryMb, boolean english) {
        if (!value.ready()) return List.of(english ? "Collecting local performance data (" + value.samples() + "/20 samples). Leave the local server running in AeroMC for about 10 minutes." : "Yerel performans verisi toplanıyor (" + value.samples() + "/20 örnek). Yerel sunucuyu AeroMC içinde yaklaşık 10 dakika çalışır bırak.");
        List<String> rows = new ArrayList<>(); int allocated = Math.max(512, configuredMemoryMb);
        String cpu = Double.isFinite(value.averageCpuPercent()) ? String.format(Locale.US, english ? " • CPU average %.1f%% / upper band %.1f%%" : " • CPU ortalama %%%.1f / üst bant %%%.1f", value.averageCpuPercent(), value.p90CpuPercent()) : (english ? " • CPU history is still being collected" : " • CPU geçmişi yeni toplanıyor");
        rows.add(String.format(Locale.US, english ? "Allocated %,d MB • RAM average %.1f%% / upper band %.1f%%%s" : "Ayrılan %,d MB • RAM ortalama %%%.1f / üst bant %%%.1f%s", allocated, value.averageRamPercent(), value.p90RamPercent(), cpu));
        int suggested = Math.max(1024, (int) Math.ceil(allocated * (value.p90RamPercent() / 100.0) / .80 / 256.0) * 256); suggested = Math.min(allocated, suggested);
        if (value.p90RamPercent() >= 92) rows.add(english ? "RAM headroom is low. Use Spark and optimize heavy content; consider more RAM only after confirming peak usage." : "RAM güvenli payı düşük. Spark ile ağır içerikleri incele; yalnızca yoğun kullanım doğrulanırsa RAM artırmayı değerlendir.");
        else if (suggested <= allocated - 512) rows.add(english ? "Conservative headroom suggests " + allocated + " → " + suggested + " MB may be tested during peak hours. AeroMC will not apply it automatically." : "Güvenli pay hesabına göre " + allocated + " → " + suggested + " MB yoğun saatte denenebilir. AeroMC bunu otomatik uygulamaz.");
        else rows.add(english ? "The current RAM allocation looks appropriate; no safe reduction is suggested." : "Mevcut RAM ayırımı uygun görünüyor; güvenli bir azaltım önerilmiyor.");
        return rows;
    }

    private List<String> formatLocalHealth(WeeklyReportEngine.LocalSummary value, WeeklyReportEngine.Report report, boolean english) {
        if (value.samples() == 0) return List.of(english ? "No local server observation yet. Start the local JAR through AeroMC to build this report." : "Henüz Yerel JAR gözlemi yok. Bu raporu oluşturmak için yerel sunucuyu AeroMC üzerinden başlat.");
        int errors = report.errors().stream().mapToInt(WeeklyReportEngine.ErrorRank::count).sum(); List<String> rows = new ArrayList<>();
        rows.add(String.format(Locale.US, english ? "%.1f observed running hours • %d performance samples" : "%.1f saat gözlenen çalışma • %d performans örneği", value.observedHours(), value.samples()));
        rows.add(english ? value.crises() + " Crisis Mode events • " + durationText(value.crisisSeconds(), true) + " total • " + errors + " classified errors" : value.crises() + " Kriz Modu • toplam " + durationText(value.crisisSeconds(), false) + " • " + errors + " sınıflandırılmış hata");
        if (Double.isFinite(value.averageTps())) rows.add(String.format(Locale.US, english ? "TPS average %.1f • lower band %.1f" : "TPS ortalama %.1f • alt bant %.1f", value.averageTps(), value.p10Tps())); return rows;
    }

    private String formatRamSuggestion(WeeklyReportEngine.RamSuggestion value, boolean english) {
        if (!value.ready()) return english ? value.server() + " • Collecting data (" + value.samples() + "/20 RAM samples, allocated " + (value.allocatedGiB() > 0 ? value.allocatedGiB() + " GiB" : "RAM unknown") + "). Select Exaroton in Control Center and leave the server online." : value.server() + " • Veri toplanıyor (" + value.samples() + "/20 RAM örneği, ayrılan " + (value.allocatedGiB() > 0 ? value.allocatedGiB() + " GiB" : "RAM bilinmiyor") + "). Kontrol Merkezi'nde Exaroton'u aktif seç ve sunucuyu online bırak.";
        String usage = String.format(Locale.US, english ? "average %.1f%% • peak band %.1f%%" : "ortalama %%%.1f • üst kullanım bandı %%%.1f", value.averagePercent(), value.p90Percent());
        if (value.suggestedGiB() >= value.allocatedGiB()) return value.server() + " • " + usage + " • " + (english ? "No safe RAM reduction suggested." : "Güvenli bir RAM azaltımı önerilmiyor.");
        return value.server() + " • " + usage + " • " + value.allocatedGiB() + " → " + value.suggestedGiB() + " GiB • " + String.format(Locale.US, english ? "about %.2f credits saved over %.1f observed online hours" : "gözlenen %.1f online saatte yaklaşık %.2f kredi tasarrufu", value.observedOnlineHours(), value.weeklySavings());
    }

    private String formatError(WeeklyReportEngine.ErrorRank value, boolean english) {
        String title, action; switch (value.kind()) {
            case "OOM" -> { title = english ? "Out of memory" : "RAM yetersizliği"; action = english ? "Lower heavy mod/plugin load or raise RAM after checking usage." : "Ağır mod/eklenti yükünü azalt; kullanımı doğruladıktan sonra RAM'i artır."; }
            case "PORT" -> { title = english ? "Port conflict" : "Port çakışması"; action = english ? "Close the process using the port or change server-port." : "Portu kullanan işlemi kapat veya server-port değerini değiştir."; }
            case "MOD_DEPENDENCY" -> { title = english ? "Mod dependency mismatch" : "Mod bağımlılığı uyuşmazlığı"; action = english ? "Match the loader, Minecraft and dependency versions." : "Yükleyici, Minecraft ve bağımlılık sürümlerini eşleştir."; }
            case "PLUGIN" -> { title = english ? "Plugin load failure" : "Eklenti yükleme hatası"; action = english ? "Check plugin version and required dependencies." : "Eklenti sürümünü ve gerekli bağımlılıkları kontrol et."; }
            case "TICK" -> { title = english ? "Tick overload" : "Tick/performans yükü"; action = english ? "Use Spark and review view distance, entities and heavy plugins." : "Spark kullan; görüş mesafesi, varlıklar ve ağır eklentileri incele."; }
            case "JAVA_VERSION" -> { title = english ? "Wrong Java version" : "Yanlış Java sürümü"; action = english ? "Select a Java runtime compatible with the server version." : "Sunucu sürümüyle uyumlu Java çalışma zamanını seç."; }
            default -> { title = english ? "Java/server crash" : "Java/sunucu çökmesi"; action = english ? "Open Crash Doctor and inspect the first Caused by line." : "Çökme Doktoru'nu açıp ilk Caused by satırını incele."; }
        } return value.count() + "× " + title + " • " + action;
    }

    private String formatFleet(WeeklyReportEngine.FleetScore value, boolean mostStable, boolean english) {
        if (!value.ready()) { String coverage = String.format(Locale.US, "%.0f", value.observedHours() * 60); if (value.onlineHours() < (5.0 / 60.0)) return value.server() + " • " + (english ? "No meaningful online observation yet (" + coverage + " min coverage). Stability is not scored." : "Henüz anlamlı online gözlem yok (" + coverage + " dk kapsam). Stabilite puanı verilmedi."); return value.server() + " • " + (english ? "Collecting fleet data (" + coverage + "/10 min coverage). No stability score yet." : "Filo verisi toplanıyor (" + coverage + "/10 dk kapsam). Henüz stabilite puanı verilmedi."); }
        String badge = mostStable ? (english ? "Most stable • " : "En stabil • ") : ""; return badge + value.server() + " • " + value.score() + "/100 • " + value.crashes() + (english ? " crashes • " : " çökme • ") + value.crises() + (english ? " Crisis Mode events • " : " Kriz Modu • ") + String.format(Locale.US, english ? "%.1f observed online hours" : "%.1f saat gözlenen online süre", value.onlineHours());
    }

    private GridPane reportRow(Region left, Region right, double leftPercent, double rightPercent) { GridPane grid = new GridPane(); grid.setHgap(14); ColumnConstraints first = new ColumnConstraints(), second = new ColumnConstraints(); first.setPercentWidth(leftPercent); second.setPercentWidth(rightPercent); first.setHgrow(Priority.ALWAYS); second.setHgrow(Priority.ALWAYS); grid.getColumnConstraints().addAll(first, second); left.setMinWidth(0); right.setMinWidth(0); left.setMaxWidth(Double.MAX_VALUE); right.setMaxWidth(Double.MAX_VALUE); grid.add(left, 0, 0); grid.add(right, 1, 0); return grid; }
    private VBox reportCard(Label title, Node... nodes) { if (!title.getStyleClass().contains("section-title")) title.getStyleClass().add("section-title"); VBox box = new VBox(11, title); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private ListView<String> wrappedList(ObservableList<String> values, double height) { ListView<String> list = new ListView<>(values); list.setPrefHeight(height); list.setMinHeight(height); list.setCellFactory(view -> new ListCell<>() { private final Label label = new Label(); { label.setWrapText(true); label.setMaxHeight(Double.MAX_VALUE); label.maxWidthProperty().bind(view.widthProperty().subtract(34)); setContentDisplay(ContentDisplay.GRAPHIC_ONLY); } protected void updateItem(String item, boolean empty) { super.updateItem(item, empty); setText(null); if (empty || item == null) setGraphic(null); else { label.setText(item); setGraphic(label); setPrefHeight(Region.USE_COMPUTED_SIZE); } } }); return list; }
    private VBox page(Node... children) { VBox page = new VBox(14, children); page.setPadding(new Insets(18)); for (Node child : children) VBox.setVgrow(child, Priority.ALWAYS); return page; }
    private VBox card(String title, Node... nodes) { Label label = new Label(title); label.getStyleClass().add("section-title"); VBox box = new VBox(11, label); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box; }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private static String durationText(long seconds, boolean english) { long hours = seconds / 3600, minutes = seconds % 3600 / 60; return hours > 0 ? hours + (english ? " hr " : " sa ") + minutes + (english ? " min" : " dk") : minutes + (english ? " min" : " dk"); }
}
