package com.aerogroup.mcpanel;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Yalnız Linux dışındaki platformlarda tembel yüklenen AWT sistem tepsisi bildirimi. */
final class AwtDesktopNotifier {
    private static TrayIcon icon;
    private static boolean initialized;
    private AwtDesktopNotifier() { }

    static synchronized void show(String title, String message) {
        if (!initialized) initialize();
        if (icon != null) icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }
    private static void initialize() {
        initialized = true;
        try {
            if (!SystemTray.isSupported()) return;
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics(); graphics.setColor(new Color(39, 137, 184)); graphics.fillOval(1, 1, 14, 14); graphics.dispose();
            icon = new TrayIcon(image, "AeroMC Server Panel"); icon.setImageAutoSize(true); SystemTray.getSystemTray().add(icon);
        } catch (Exception ignored) { icon = null; }
    }
}
