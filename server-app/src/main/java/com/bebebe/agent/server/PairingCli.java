package com.bebebe.agent.server;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.transport.PairingTokens;
import com.bebebe.agent.transport.ServerCertificate;
import com.bebebe.agent.transport.TransportConfig;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class PairingCli {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private PairingCli() {
    }

    static int run(AppConfig config, String[] args) {
        return run(config, args, System.out, System.err);
    }

    static int run(AppConfig config, String[] args, PrintStream out, PrintStream err) {
        TransportConfig transport = TransportConfig.from(config.section(TransportConfig.SECTION));
        PairingTokens tokens = new PairingTokens(transport.clientsFile());
        switch (args[0]) {
            case "pair" -> {
                String name = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "client";
                PairingTokens.Pairing pairing = tokens.issue(name);
                ServerCertificate cert = ServerCertificate.ensure(transport.keystore());
                out.println("Client «" + name + "» paired. Put this into the client config (the token is shown once):");
                out.println();
                out.println("[server]");
                out.println("url = \"wss://<this-server-address>:" + transport.port() + "\"");
                out.println("fingerprint = \"" + cert.fingerprint() + "\"");
                out.println("client_id = \"" + pairing.clientId() + "\"");
                out.println("token = \"" + pairing.token() + "\"");
                return 0;
            }
            case "clients" -> {
                var list = tokens.list();
                if (list.isEmpty()) {
                    out.println("No paired clients. Issue one: server-app pair <name>");
                    return 0;
                }
                for (PairingTokens.PairedClient c : list) {
                    out.printf("%-10s %-24s issued %s, last seen %s%n", c.id(), c.name(),
                            TIME.format(c.createdAt()), c.lastSeenAt() == null ? "never" : TIME.format(c.lastSeenAt()));
                }
                return 0;
            }
            case "revoke" -> {
                if (args.length < 2) {
                    err.println("Usage: server-app revoke <client_id>");
                    return 2;
                }
                boolean done = tokens.revoke(args[1]);
                out.println(done ? "Client " + args[1] + " revoked; its current connection closes on the next reconnect"
                        : "No client " + args[1]);
                return done ? 0 : 1;
            }
            default -> {
                err.println("Unknown command: " + args[0] + ". Available: pair <name>, clients, revoke <client_id>");
                return 2;
            }
        }
    }
}
