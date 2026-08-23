package com.aerogroup.mcpanel;

import java.nio.file.*;
import java.time.*;

public final class ExarotonAutomationSmoke {
    public static void main(String[] args) throws Exception {
        var weekday = new ExarotonAutomationEngine.Window(true, LocalTime.of(18, 0), LocalTime.of(23, 0));
        var weekend = new ExarotonAutomationEngine.Window(true, LocalTime.of(10, 0), LocalTime.of(2, 0));
        var config = new ExarotonAutomationEngine.Config(true, true, weekday, weekend, true, 2, true, 15, true, 10, true, 50);
        ZoneId zone = ZoneId.of("Europe/Istanbul");
        ZonedDateTime friday = ZonedDateTime.of(2026, 8, 21, 19, 0, 0, 0, zone);
        var offline = new ExarotonAutomationEngine.State(false, true, false, false, 0, null, 0, 1, 4);
        require(ExarotonAutomationEngine.evaluate(config, friday, offline).action() == ExarotonAutomationEngine.Action.START, "weekday scheduled start");
        ZonedDateTime sundayNight = ZonedDateTime.of(2026, 8, 23, 23, 30, 0, 0, zone);
        require(ExarotonAutomationEngine.scheduleContains(config, sundayNight), "weekend overnight window");
        ZonedDateTime mondayAfterMidnight = ZonedDateTime.of(2026, 8, 24, 1, 0, 0, 0, zone);
        require(ExarotonAutomationEngine.scheduleContains(config, mondayAfterMidnight), "previous weekend window carries into monday");
        var overBudget = new ExarotonAutomationEngine.State(true, false, false, false, 3, null, 0, 10, 12);
        require(ExarotonAutomationEngine.evaluate(config, friday, overBudget).action() == ExarotonAutomationEngine.Action.STOP, "budget has priority");
        var crashed = new ExarotonAutomationEngine.State(false, false, true, false, 0, null, 1, 1, 4);
        require(ExarotonAutomationEngine.evaluate(config, friday, crashed).action() == ExarotonAutomationEngine.Action.RECOVER, "crash recovery");
        var idle = new ExarotonAutomationEngine.State(true, false, false, false, 0, friday.toInstant().minus(Duration.ofMinutes(16)), 0, 1, 4);
        require(ExarotonAutomationEngine.evaluate(config, friday, idle).action() == ExarotonAutomationEngine.Action.STOP, "idle stop");
        Path logFile = Files.createTempFile("aeromc-automation-", ".tsv");
        try { ExarotonAutomationLog log = new ExarotonAutomationLog(logFile); log.add("SMP", "OTOMASYON", "Başlatıldı"); require(new ExarotonAutomationLog(logFile).recent(10).size() == 1, "event persistence"); log.clear(); require(log.recent(10).isEmpty(), "event clear"); }
        finally { Files.deleteIfExists(logFile); }
        System.out.println("exaroton-automation-ok");
    }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
