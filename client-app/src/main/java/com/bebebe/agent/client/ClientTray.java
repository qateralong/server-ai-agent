package com.bebebe.agent.client;

import com.bebebe.agent.transport.TransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Optional;

final class ClientTray implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClientTray.class);

    private final Optional<TrayIcon> icon;
    private final String serverLabel;

    ClientTray(String serverLabel, Runnable onExit) {
        this.serverLabel = serverLabel;
        this.icon = install(onExit);
    }

    private Optional<TrayIcon> install(Runnable onExit) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.info("System tray unavailable (headless or no AppIndicator) -- running without an icon");
            return Optional.empty();
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem exit = new MenuItem("Quit");
            exit.addActionListener(e -> onExit.run());
            menu.add(exit);
            TrayIcon trayIcon = new TrayIcon(dot(Color.GRAY), "Server AI Agent client: connecting...", menu);
            trayIcon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(trayIcon);
            log.info("Tray icon installed");
            return Optional.of(trayIcon);
        } catch (Exception e) {
            log.warn("Tray icon not installed: {}", e.toString());
            return Optional.empty();
        }
    }

    void update(TransportClient.State state) {
        icon.ifPresent(i -> {
            Color color = switch (state) {
                case CONNECTED -> new Color(0x23, 0x86, 0x36);
                case REJECTED -> new Color(0xda, 0x36, 0x33);
                default -> Color.GRAY;
            };
            i.setImage(dot(color));
            i.setToolTip("Bebebe: " + describe(state) + " (" + serverLabel + ")");
        });
    }

    static String describe(TransportClient.State state) {
        return switch (state) {
            case CONNECTED -> "connected";
            case CONNECTING, AUTHENTICATING -> "connecting...";
            case RECONNECTING -> "no connection, reconnecting...";
            case REJECTED -> "rejected by the server -- a new pairing is needed";
            case STOPPED -> "stopped";
            case NEW -> "starting";
        };
    }

    static BufferedImage dot(Color color) {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.fillOval(2, 2, 12, 12);
        g.dispose();
        return image;
    }

    @Override
    public void close() {
        icon.ifPresent(i -> {
            try {
                SystemTray.getSystemTray().remove(i);
            } catch (RuntimeException ignored) {

            }
        });
    }
}
