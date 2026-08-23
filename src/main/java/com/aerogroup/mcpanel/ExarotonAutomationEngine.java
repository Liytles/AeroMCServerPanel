package com.aerogroup.mcpanel;

import java.time.*;

/** Exaroton otomasyon kurallarını JavaFX ve ağ katmanından bağımsız değerlendirir. */
public final class ExarotonAutomationEngine {
    public enum Action { NONE, START, STOP, RECOVER, BLOCKED }
    public record Window(boolean enabled, LocalTime start, LocalTime stop) { }
    public record Config(boolean enabled, boolean scheduleEnabled, Window weekday, Window weekend,
                         boolean crashRecovery, int maxRecoveryAttempts, boolean idleStop, int idleMinutes,
                         boolean dailyBudgetEnabled, double dailyBudget, boolean weeklyBudgetEnabled, double weeklyBudget) { }
    public record State(boolean online, boolean offline, boolean crashed, boolean transitional, int players,
                        Instant emptySince, int recoveryAttempts, double spentToday, double spentThisWeek) { }
    public record Decision(Action action, String reason) { }

    private ExarotonAutomationEngine() { }

    public static Decision evaluate(Config config, ZonedDateTime now, State state) {
        if (!config.enabled()) return none("Tüm otomasyonlar kapalı");
        String budgetReason = budgetReason(config, state);
        if (budgetReason != null) return state.online() ? new Decision(Action.STOP, budgetReason) : new Decision(Action.BLOCKED, budgetReason);

        boolean scheduledNow = !config.scheduleEnabled() || scheduleContains(config, now);
        if (config.scheduleEnabled() && !scheduledNow) {
            if (state.online()) return new Decision(Action.STOP, "Çalışma programı sona erdi");
            return none("Program dışı saat");
        }

        if (state.crashed()) {
            if (!config.crashRecovery()) return new Decision(Action.BLOCKED, "Sunucu çöktü; otomatik kurtarma kapalı");
            if (state.recoveryAttempts() >= Math.max(1, config.maxRecoveryAttempts())) return new Decision(Action.BLOCKED, "Çökme kurtarma deneme sınırına ulaştı");
            return new Decision(Action.RECOVER, "Çökme algılandı; kurtarma denemesi");
        }
        if (config.scheduleEnabled() && state.offline()) return new Decision(Action.START, "Çalışma programı başladı");
        if (state.transitional()) return none("Sunucu geçiş durumunda");
        if (config.idleStop() && state.online() && state.players() == 0 && state.emptySince() != null) {
            long minutes = Duration.between(state.emptySince(), now.toInstant()).toMinutes();
            if (minutes >= Math.max(1, config.idleMinutes())) return new Decision(Action.STOP, "Oyuncu gelmedi; boşta kalma süresi doldu");
            return none("Oyuncu bekleniyor • " + Math.max(0, config.idleMinutes() - minutes) + " dk kaldı");
        }
        return none(state.online() ? "Sunucu izleniyor" : "Başlatma koşulu bekleniyor");
    }

    public static boolean scheduleContains(Config config, ZonedDateTime now) {
        LocalTime time = now.toLocalTime();
        Window today = window(config, now.getDayOfWeek());
        if (containsFromStart(today, time)) return true;
        Window previous = window(config, now.minusDays(1).getDayOfWeek());
        return previous.enabled() && crossesMidnight(previous) && time.isBefore(previous.stop());
    }

    public static LocalTime parseTime(String value) {
        if (value == null || !value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Saat HH:mm biçiminde olmalı: " + value);
        return LocalTime.parse(value);
    }

    private static Window window(Config config, DayOfWeek day) { return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? config.weekend() : config.weekday(); }
    private static boolean crossesMidnight(Window window) { return window.start().isAfter(window.stop()); }
    private static boolean containsFromStart(Window window, LocalTime time) {
        if (!window.enabled() || window.start().equals(window.stop())) return false;
        if (crossesMidnight(window)) return !time.isBefore(window.start());
        return !time.isBefore(window.start()) && time.isBefore(window.stop());
    }
    private static String budgetReason(Config config, State state) {
        if (config.dailyBudgetEnabled() && state.spentToday() >= config.dailyBudget()) return String.format(java.util.Locale.US, "Günlük %.2f kredi bütçesi doldu", config.dailyBudget());
        if (config.weeklyBudgetEnabled() && state.spentThisWeek() >= config.weeklyBudget()) return String.format(java.util.Locale.US, "Haftalık %.2f kredi bütçesi doldu", config.weeklyBudget());
        return null;
    }
    private static Decision none(String reason) { return new Decision(Action.NONE, reason); }
}
