package com.bebebe.agent.transport.remote;

import com.bebebe.agent.transport.TransportServer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ClientSelector {

    private final TransportServer server;
    private final String preferredClientId;

    public ClientSelector(TransportServer server, String preferredClientId) {
        this.server = server;
        this.preferredClientId = preferredClientId == null ? "" : preferredClientId.strip();
    }

    public Optional<TransportServer.ClientInfo> pick(String capability) {
        List<TransportServer.ClientInfo> clients = server.clients().stream()
                .filter(c -> capability == null || c.status() == null || c.status().capabilities().isEmpty()
                        || c.status().capabilities().contains(capability))
                .toList();
        if (clients.isEmpty()) {
            return Optional.empty();
        }
        if (!preferredClientId.isEmpty()) {
            Optional<TransportServer.ClientInfo> preferred = clients.stream()
                    .filter(c -> c.clientId().equals(preferredClientId)).findFirst();
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        return clients.stream().max(Comparator.comparing(TransportServer.ClientInfo::connectedAt));
    }

    public static String noClientMessage() {
        return "нет связи с компьютером: ни один клиент не подключён к серверу";
    }
}
