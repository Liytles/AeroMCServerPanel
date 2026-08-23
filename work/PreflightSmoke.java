package com.aerogroup.mcpanel;

import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.jar.*;

public final class PreflightSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("aeromc-preflight-"); Path jar = root.resolve("server.jar");
        try {
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) { output.putNextEntry(new JarEntry("server.txt")); output.write("ok".getBytes()); output.closeEntry(); }
            int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            Files.writeString(root.resolve("server.properties"), "server-port=" + port + System.lineSeparator());
            Files.writeString(root.resolve("eula.txt"), "eula=false" + System.lineSeparator());
            PreflightEngine.Report before = PreflightEngine.inspect(jar, 2048);
            require(before.hasCritical() && before.hasFixable(), "EULA blocks startup and is fixable");
            PreflightEngine.applySafeFixes(jar, before);
            PreflightEngine.Report after = PreflightEngine.inspect(jar, 2048);
            require(!after.hasCritical(), "safe fixes clear EULA critical issue");
            require(PreflightEngine.requiredJava("1.20.4") == 17, "Java 17 mapping");
            require(PreflightEngine.requiredJava("1.20.5") == 21, "Java 21 mapping");
            System.out.println("preflight-ok");
        } finally { delete(root); }
    }

    private static void delete(Path root) throws IOException { if (!Files.exists(root)) return; try (var paths = Files.walk(root)) { for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path); } }
    private static void require(boolean value, String name) { if (!value) throw new IllegalStateException("Smoke test failed: " + name); }
}
