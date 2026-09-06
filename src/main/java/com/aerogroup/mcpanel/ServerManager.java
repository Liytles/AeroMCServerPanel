package com.aerogroup.mcpanel;

import com.aerogroup.mcpanel.aeroguard.SafePathGuard;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.function.Supplier;
import java.util.zip.*;

/** Minecraft sunucu işlemini ve canlı konsol akışını yönetir. */
public final class ServerManager {
    public interface Listener {
        void onConsole(String line);
        void onState(boolean running, String message);
        void onPlayers(List<String> players);
    }
    private static final Pattern PLAYER_LIST = Pattern.compile("There are \\d+ of a max of \\d+ players online:\\s*(.*)");
    private final Listener listener;
    private final Supplier<Path> preferredJava;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "aeromc-io"); t.setDaemon(true); return t; });
    /** Stop/restart operations must never race each other or reclaim the port concurrently. */
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "aeromc-lifecycle"); t.setDaemon(true); return t; });
    private Process process;
    private BufferedWriter console;
    private Path serverFolder;
    /** True while the managed JVM is flushing the world and releasing its network ports. */
    private boolean stopping;
    private boolean stopQueued;
    private boolean restartQueued;

    public ServerManager(Listener listener) { this(listener, () -> null); }
    public ServerManager(Listener listener, PanelConfig config) { this(listener, config == null ? () -> null : config::getJavaExecutable); }
    private ServerManager(Listener listener, Supplier<Path> preferredJava) { this.listener = listener; this.preferredJava = preferredJava; }
    public synchronized boolean isRunning() { return process != null && process.isAlive(); }
    public synchronized boolean isStopping() { return stopping; }
    public synchronized Path getServerFolder() { return serverFolder; }
    public synchronized long getProcessId() { return isRunning() ? process.pid() : -1L; }
    public synchronized void configure(Path jar) {
        try { if (jar != null) serverFolder = SafePathGuard.serverJar(jar).getParent(); } catch (IOException ignored) { serverFolder = null; }
    }

    public synchronized void start(Path jar, int memoryMb) throws IOException {
        if (isRunning()) throw new IllegalStateException("Sunucu zaten çalışıyor.");
        if (stopping) throw new IllegalStateException("Sunucu hâlâ güvenli biçimde kapanıyor. Port serbest kalınca tekrar başlatabilirsin.");
        Path safeJar = SafePathGuard.serverJar(jar); serverFolder = safeJar.getParent();
        JavaRuntimeResolver.RuntimeInfo runtime = JavaRuntimeResolver.resolve(preferredJava.get());
        listener.onConsole("[Panel] Minecraft Java " + runtime.feature() + " kullanılıyor: " + runtime.executable() + " (" + runtime.source() + ")");
        ProcessBuilder builder = new ProcessBuilder(runtime.executable().toString(), "-Xms" + Math.max(512, memoryMb / 2) + "M", "-Xmx" + memoryMb + "M", "-jar", safeJar.getFileName().toString(), "nogui");
        builder.directory(serverFolder.toFile()).redirectErrorStream(true);
        Process startedProcess = builder.start();
        process = startedProcess;
        stopping = false;
        console = new BufferedWriter(new OutputStreamWriter(startedProcess.getOutputStream()));
        listener.onState(true, "Sunucu başlatılıyor...");
        io.submit(() -> readConsole(startedProcess));
        io.submit(() -> {
            int code = 0;
            try { code = startedProcess.waitFor(); listener.onConsole("[Panel] Sunucu işlemi sona erdi (kod " + code + ")."); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finally { completeProcess(startedProcess, code); }
        });
    }
    private void readConsole(Process runningProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(runningProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                listener.onConsole(line);
                if (line.contains("Done (") || line.contains("For help, type")) listener.onState(true, "Sunucu online");
                Matcher match = PLAYER_LIST.matcher(line);
                if (match.find()) {
                    String names = match.group(1).trim();
                    listener.onPlayers(names.isEmpty() ? List.of() : Arrays.stream(names.split(",\\s*")).filter(value -> !value.isBlank()).toList());
                }
            }
        } catch (IOException error) { if (runningProcess.isAlive()) listener.onConsole("[Panel] Konsol okunamadı: " + error.getMessage()); }
    }
    public synchronized void command(String command) throws IOException {
        if (!isRunning()) throw new IOException("Sunucu çalışmıyor.");
        console.write(command); console.newLine(); console.flush();
    }
    public void requestPlayers() { try { if (isRunning()) command("list"); } catch (IOException ignored) { } }
    public void stop() {
        synchronized (this) {
            if (stopQueued || stopping || !isRunning()) return;
            stopQueued = true;
        }
        lifecycle.submit(() -> {
            try {
                Process current;
                synchronized (this) {
                    if (stopping || !isRunning()) return;
                    stopping = true;
                    current = process;
                }
                listener.onState(true, "Sunucu güvenli biçimde durduruluyor; dünya kaydediliyor ve port serbest bırakılıyor...");
                try { command("stop"); }
                catch (IOException error) { listener.onConsole("[Panel] Durdurma komutu gönderilemedi: " + error.getMessage()); }
                stopProcess(current, 25);
                if (!current.isAlive()) completeProcess(current, current.exitValue());
            } finally { synchronized (this) { stopQueued = false; } }
        });
    }
    public void restart(Path jar, int memoryMb) {
        synchronized (this) {
            if (restartQueued) return;
            restartQueued = true;
        }
        lifecycle.submit(() -> {
            try {
                Process current;
                synchronized (this) { current = process; stopping = current != null && current.isAlive(); }
                if (current != null && current.isAlive()) {
                    listener.onState(true, "Sunucu güvenli biçimde yeniden başlatılıyor; port serbest bırakılıyor...");
                    command("say Sunucu panel tarafından yeniden başlatılıyor.");
                    command("stop");
                    stopProcess(current, 25);
                }
                waitForCurrentProcessToClear(current, 5);
                start(jar, memoryMb);
            } catch (Exception error) { listener.onConsole("[Panel] Yeniden başlatma başarısız: " + error.getMessage()); }
            finally { synchronized (this) { restartQueued = false; } }
        });
    }

    /**
     * Wait for the server's normal stop first. If it is stuck, terminate its complete process
     * tree so a launcher or wrapper cannot keep the Minecraft listening port occupied.
     */
    private void stopProcess(Process current, int gracefulSeconds) {
        if (current == null) return;
        try {
            if (current.waitFor(gracefulSeconds, TimeUnit.SECONDS)) return;
            listener.onConsole("[Panel] Sunucu " + gracefulSeconds + " saniyede kapanmadı; işlem ve alt işlemler sonlandırılıyor.");
            terminateTree(current, false);
            if (current.waitFor(4, TimeUnit.SECONDS)) return;
            listener.onConsole("[Panel] Port hâlâ serbest değil; zorla sonlandırılıyor.");
            terminateTree(current, true);
            current.waitFor(4, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminateTree(current, true);
        }
    }

    private void terminateTree(Process current, boolean forcibly) {
        try {
            current.toHandle().descendants().forEach(child -> {
                if (forcibly) child.destroyForcibly(); else child.destroy();
            });
        } catch (SecurityException ignored) { }
        if (forcibly) current.destroyForcibly(); else current.destroy();
    }

    private void waitForCurrentProcessToClear(Process expected, int seconds) throws IOException, InterruptedException {
        if (expected == null) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (expected.isAlive() && System.nanoTime() < deadline) Thread.sleep(80);
        if (expected.isAlive()) throw new IOException("Eski sunucu işlemi kapanmadı; port güvenliği nedeniyle yeniden başlatma iptal edildi.");
        completeProcess(expected, expected.exitValue());
    }

    private void completeProcess(Process finished, int code) {
        boolean stillCurrent;
        synchronized (this) {
            stillCurrent = process == finished;
            if (stillCurrent) {
                process = null;
                console = null;
                stopping = false;
            }
        }
        if (stillCurrent) listener.onState(false, code == 0 ? "Sunucu kapalı • port serbest" : "Sunucu çöktü (kod " + code + ")");
    }
    public Path createBackup() throws IOException, InterruptedException {
        if (serverFolder == null) throw new IOException("Önce bir sunucu başlatılmalı.");
        boolean live = isRunning();
        if (live) { command("save-off"); command("save-all flush"); Thread.sleep(1200); }
        Path safeRoot = serverFolder.toRealPath(LinkOption.NOFOLLOW_LINKS); Path backupFolder = SafePathGuard.resolve(safeRoot, "backups", true);
        Files.createDirectories(backupFolder);
        Path output = backupFolder.resolve("backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".zip");
        List<String> names = List.of("world", "world_nether", "world_the_end", "server.properties", "whitelist.json", "ops.json", "banned-players.json", "banned-ips.json");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (String name : names) {
                Path source = SafePathGuard.resolve(safeRoot, name, true);
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) zipDirectory(safeRoot, source, zip);
                else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) addFile(safeRoot, source, zip);
            }
        } finally { if (live && isRunning()) command("save-on"); }
        return output;
    }
    private void zipDirectory(Path root, Path directory, ZipOutputStream zip) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)).toList()) addFile(root, SafePathGuard.requireWithin(root, path, false), zip);
        }
    }
    private void addFile(Path root, Path file, ZipOutputStream zip) throws IOException {
        String relative = root.relativize(file).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(relative)); Files.copy(file, zip); zip.closeEntry();
    }
    public void shutdown() { if (isRunning()) stop(); lifecycle.shutdown(); io.shutdown(); }
}
