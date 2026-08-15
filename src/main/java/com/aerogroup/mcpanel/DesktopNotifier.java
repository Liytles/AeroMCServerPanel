package com.aerogroup.mcpanel;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Desteklenen sistemlerde küçük masaüstü bildirimleri gösterir. */
public final class DesktopNotifier {
    private static TrayIcon icon;
    static {
        try {
            if (SystemTray.isSupported()) {
                BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics(); graphics.setColor(new Color(39, 137, 184)); graphics.fillOval(1, 1, 14, 14); graphics.dispose();
                icon = new TrayIcon(image, "AeroMC Server Panel"); icon.setImageAutoSize(true); SystemTray.getSystemTray().add(icon);
            }
        } catch (Exception ignored) { icon = null; }
    }
    private DesktopNotifier() { }
    public static void show(String title, String message) { if (icon != null) icon.displayMessage(title, message, TrayIcon.MessageType.INFO); }
}
