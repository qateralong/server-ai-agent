package com.bebebe.agent.transport.messages;

import java.util.List;

public record StatusPush(String hostname, String os, String user, String display,
                         List<String> capabilities, String version, long uptimeSeconds) {

    public StatusPush {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    public static StatusPush local(List<String> capabilities, String version, java.time.Instant startedAt) {
        String display = System.getenv("WAYLAND_DISPLAY");
        if (display == null || display.isBlank()) {
            display = System.getenv("DISPLAY");
        }
        return new StatusPush(
                localHostname(),
                System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""),
                System.getProperty("user.name", "?"),
                display == null ? "" : display,
                capabilities,
                version == null ? "" : version,
                Math.max(0, java.time.Duration.between(startedAt, java.time.Instant.now()).toSeconds()));
    }

    private static String localHostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }
}
