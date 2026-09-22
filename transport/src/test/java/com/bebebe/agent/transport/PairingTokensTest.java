package com.bebebe.agent.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairingTokensTest {

    @TempDir
    Path temp;

    @Test
    void issuedTokenPassesForeignDoesNot() {
        PairingTokens tokens = new PairingTokens(temp.resolve("clients.json"));

        PairingTokens.Pairing pairing = tokens.issue("ноутбук");

        assertEquals(64, pairing.token().length(), "32 random bytes in hex");
        assertTrue(tokens.verify(pairing.clientId(), pairing.token()).isPresent());
        assertEquals("ноутбук", tokens.verify(pairing.clientId(), pairing.token()).orElseThrow().name());
        assertTrue(tokens.verify(pairing.clientId(), pairing.token() + "0").isEmpty());
        assertTrue(tokens.verify("другой", pairing.token()).isEmpty());
        assertTrue(tokens.verify(pairing.clientId(), "").isEmpty());
        assertTrue(tokens.verify(null, null).isEmpty());
    }

    @Test
    void fileHasHashNotTokenAndOwnerOnlyPermissions() throws Exception {
        Path file = temp.resolve("clients.json");
        PairingTokens.Pairing pairing = new PairingTokens(file).issue("пк");

        String content = Files.readString(file);
        assertFalse(content.contains(pairing.token()), "the token never reaches the disk in plain text");
        assertTrue(content.contains(PairingTokens.hash(pairing.token())));
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    }

    @Test
    void clientsSurviveRestartAndCanBeRevoked() {
        Path file = temp.resolve("clients.json");
        PairingTokens.Pairing a = new PairingTokens(file).issue("а");
        PairingTokens.Pairing b = new PairingTokens(file).issue("б");
        assertNotEquals(a.clientId(), b.clientId());

        PairingTokens reloaded = new PairingTokens(file);
        assertEquals(2, reloaded.list().size());
        assertTrue(reloaded.verify(a.clientId(), a.token()).isPresent());

        assertTrue(reloaded.revoke(a.clientId()));
        assertFalse(reloaded.revoke(a.clientId()), "nothing to revoke the second time");
        assertTrue(new PairingTokens(file).verify(a.clientId(), a.token()).isEmpty(), "revocation recorded");
        assertTrue(new PairingTokens(file).verify(b.clientId(), b.token()).isPresent(), "the neighbour is untouched");
    }

    @Test
    void connectionIsTimestamped() {
        PairingTokens tokens = new PairingTokens(temp.resolve("clients.json"));
        PairingTokens.Pairing p = tokens.issue("x");
        assertEquals(null, tokens.list().getFirst().lastSeenAt());

        tokens.touch(p.clientId());

        assertTrue(tokens.list().getFirst().lastSeenAt() != null);
    }

    @Test
    void tokenIssuedByAnotherProcessIsVisibleWithoutRestart() throws Exception {
        Path file = temp.resolve("clients.json");
        PairingTokens server = new PairingTokens(file);
        assertTrue(server.list().isEmpty());

        Thread.sleep(20);
        PairingTokens.Pairing issuedElsewhere = new PairingTokens(file).issue("новый ноут");

        assertTrue(server.verify(issuedElsewhere.clientId(), issuedElsewhere.token()).isPresent(),
                "the server rereads the file when it changed externally");
        assertEquals(1, server.list().size());
    }
}
