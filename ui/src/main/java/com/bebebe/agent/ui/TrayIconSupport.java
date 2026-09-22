package com.bebebe.agent.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

final class TrayIconSupport {

    private static final Logger log = LoggerFactory.getLogger(TrayIconSupport.class);

    private TrayIconSupport() {
    }

    static boolean install(Runnable showWindow, Runnable quit) {
        try {
            if (!SystemTray.isSupported()) {
                log.info("Tray not supported by the environment (GNOME without AppIndicator) -- the window returns via the launcher or Telegram");
                return false;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem show = new MenuItem("Show window");
            show.addActionListener(e -> showWindow.run());
            MenuItem exit = new MenuItem("Quit the agent");
            exit.addActionListener(e -> quit.run());
            menu.add(show);
            menu.addSeparator();
            menu.add(exit);
            TrayIcon icon = new TrayIcon(image(), "Server AI Agent", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> showWindow.run());
            SystemTray.getSystemTray().add(icon);
            log.info("Tray icon added");
            return true;
        } catch (Throwable e) {
            log.info("Tray unavailable: {}", e.toString());
            return false;
        }
    }

    private static BufferedImage image() {
        int size = 22;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x23, 0x86, 0x36));
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(Color.WHITE);
        g.fillOval(8, 8, size - 16, size - 16);
        g.dispose();
        return img;
    }
}
