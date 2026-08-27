package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.CommandSecurity;
import com.aerogroup.mcpanel.aeroguard.CrashLoopGuard;
import com.aerogroup.mcpanel.aeroguard.SafePathGuard;
import com.aerogroup.mcpanel.aeroguard.SecurityAuditEngine;

import java.nio.file.*;
import java.time.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class SecurityHardeningSmoke {
    public static void main(String[] args) throws Exception {
        require(CommandSecurity.assess("list").risk() == CommandSecurity.Risk.SAFE, "safe command");
        require(CommandSecurity.assess("gamerule doDaylightCycle false").risk() == CommandSecurity.Risk.SENSITIVE, "sensitive command");
        require(CommandSecurity.assess("op Intruder").risk() == CommandSecurity.Risk.CRITICAL, "critical command");
        require(CommandSecurity.assess("minecraft:stop").risk() == CommandSecurity.Risk.CRITICAL, "namespaced critical command");
        require(CommandSecurity.assess("whitelist off").risk() == CommandSecurity.Risk.CRITICAL, "critical whitelist command");
        denied(() -> CommandSecurity.requireRemoteGeneric("stop"), "remote critical command blocked");
        denied(() -> CommandSecurity.requireRemoteGeneric("gamerule keepInventory true"), "remote sensitive command blocked");
        denied(() -> CommandSecurity.assess("say hi\nstop"), "multiline command blocked");
        denied(() -> CommandSecurity.playerName("@a"), "selector player blocked");

        Path root = Files.createTempDirectory("aeromc-safe-root"), inside = Files.writeString(root.resolve("server.jar"), "jar"), outside = Files.createTempFile("aeromc-outside", ".txt");
        require(SafePathGuard.serverJar(inside).equals(inside.toRealPath()), "real server jar accepted");
        require(SafePathGuard.resolve(root, "plugins/example.jar", true).startsWith(root), "safe missing path accepted");
        denied(() -> SafePathGuard.requireWithin(root, root.resolve("../escape.txt"), true), "path traversal blocked");
        try { Path link = root.resolve("outside-link"); Files.createSymbolicLink(link, outside); denied(() -> SafePathGuard.requireWithin(root, link, false), "symbolic link blocked"); } catch (UnsupportedOperationException ignored) { }

        denied(() -> BoundedStreams.readString(new ByteArrayInputStream("12345678901".getBytes(StandardCharsets.UTF_8)), 10, StandardCharsets.UTF_8), "oversized network response blocked");
        Path boundedOutput = root.resolve("bounded-download.bin");
        denied(() -> BoundedStreams.copyToFile(new ByteArrayInputStream(new byte[11]), boundedOutput, 10), "oversized download blocked");
        require(!Files.exists(boundedOutput), "partial oversized download removed");

        CrashLoopGuard guard = new CrashLoopGuard(3, Duration.ofMinutes(5), Duration.ofMinutes(15)); Instant now = Instant.parse("2026-08-24T12:00:00Z");
        require(guard.record(now).restartAllowed(), "first crash restart"); require(guard.record(now.plusSeconds(30)).restartAllowed(), "second crash restart");
        require(!guard.record(now.plusSeconds(60)).restartAllowed() && guard.isLocked(now.plusSeconds(61)), "third crash locks loop");
        require(guard.record(now.plus(Duration.ofMinutes(16))).restartAllowed(), "loop lock expires");

        int changed = SecurityAuditEngine.hardenPermissions(); SecurityAuditEngine.Report report = SecurityAuditEngine.scan(new PanelConfig());
        require(changed >= 0 && report.score() >= 0 && report.score() <= 100 && !report.findings().isEmpty(), "security audit report");
        System.out.println("security-hardening-ok");
    }

    private static void denied(Throwing action, String name) { try { action.run(); throw new IllegalStateException("Smoke test failed: " + name); } catch (SecurityException | java.io.IOException | IllegalArgumentException expected) { } catch (Exception error) { throw new IllegalStateException(error); } }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
