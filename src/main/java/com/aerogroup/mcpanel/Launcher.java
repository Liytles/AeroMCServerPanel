package com.aerogroup.mcpanel;

/** Paketli sürümde JavaFX'i normal main metodundan başlatan platform-bağımsız giriş noktası. */
public final class Launcher {
    private Launcher() { }
    public static void main(String[] args) { AppDiagnostics.install(); MainApp.main(args); }
}
