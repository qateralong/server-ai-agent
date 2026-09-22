package com.bebebe.agent.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class PairingTokens {

    private static final Logger log = LoggerFactory.getLogger(PairingTokens.class);
    private static final ObjectMapper MAPPER = Codec.mapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record PairedClient(String id, String name, String tokenHash, Instant createdAt, Instant lastSeenAt) {
    }

    public record Pairing(String clientId, String token) {
    }

    private record StoredClient(String id, String name, String tokenHash, String createdAt, String lastSeenAt) {
        static StoredClient from(PairedClient c) {
            return new StoredClient(c.id(), c.name(), c.tokenHash(), c.createdAt().toString(),
                    c.lastSeenAt() == null ? null : c.lastSeenAt().toString());
        }

        PairedClient toClient() {
            return new PairedClient(id, name, tokenHash, Instant.parse(createdAt),
                    lastSeenAt == null ? null : Instant.parse(lastSeenAt));
        }
    }

    private record FileModel(List<StoredClient> clients) {
    }

    private final Path file;
    private final List<PairedClient> clients = new ArrayList<>();

    private java.nio.file.attribute.FileTime loadedAt;

    public PairingTokens(Path file) {
        this.file = file;
        load();
    }

    private void reloadIfChanged() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            java.nio.file.attribute.FileTime now = Files.getLastModifiedTime(file);
            if (loadedAt == null || !now.equals(loadedAt)) {
                clients.clear();
                load();
            }
        } catch (IOException e) {
            log.warn("Cannot check clients file time {}: {}", file, e.getMessage());
        }
    }

    public synchronized Pairing issue(String clientName) {
        String name = clientName == null || clientName.isBlank() ? "client" : clientName.strip();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        String id = newClientId();
        clients.add(new PairedClient(id, name, hash(token), Instant.now(), null));
        save();
        log.atInfo().addKeyValue("event", "transport.pair").addKeyValue("client_id", id)
                .log("Pairing token issued for client «{}» ({})", name, id);
        return new Pairing(id, token);
    }

    public synchronized Optional<PairedClient> verify(String clientId, String token) {
        if (clientId == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        reloadIfChanged();
        byte[] presented = hash(token).getBytes(StandardCharsets.US_ASCII);
        for (PairedClient client : clients) {
            if (client.id().equals(clientId)
                    && MessageDigest.isEqual(client.tokenHash().getBytes(StandardCharsets.US_ASCII), presented)) {
                return Optional.of(client);
            }
        }
        return Optional.empty();
    }

    public synchronized boolean revoke(String clientId) {
        boolean removed = clients.removeIf(c -> c.id().equals(clientId));
        if (removed) {
            save();
            log.atInfo().addKeyValue("event", "transport.revoke").addKeyValue("client_id", clientId)
                    .log("Client {} revoked", clientId);
        }
        return removed;
    }

    public synchronized void touch(String clientId) {
        for (int i = 0; i < clients.size(); i++) {
            PairedClient c = clients.get(i);
            if (c.id().equals(clientId)) {
                clients.set(i, new PairedClient(c.id(), c.name(), c.tokenHash(), c.createdAt(), Instant.now()));
                save();
                return;
            }
        }
    }

    public synchronized List<PairedClient> list() {
        reloadIfChanged();
        return List.copyOf(clients);
    }

    public Path file() {
        return file;
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.strip().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String newClientId() {
        while (true) {
            byte[] raw = new byte[4];
            RANDOM.nextBytes(raw);
            String id = HexFormat.of().formatHex(raw);
            if (clients.stream().noneMatch(c -> c.id().equals(id))) {
                return id;
            }
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            FileModel model = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), FileModel.class);
            if (model.clients() != null) {
                model.clients().forEach(c -> clients.add(c.toClient()));
            }
            loadedAt = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read clients list " + file, e);
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new FileModel(clients.stream().map(StoredClient::from).toList())), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {

            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            loadedAt = Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write clients list " + file, e);
        }
    }
}
