package com.bebebe.agent.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCertificateTest {

    @TempDir
    Path temp;

    @Test
    void certificateIsCreatedOnceAndFingerprintIsStable() {
        Path keystore = temp.resolve("tls").resolve("server.p12");

        ServerCertificate first = ServerCertificate.ensure(keystore);
        ServerCertificate again = ServerCertificate.ensure(keystore);

        assertTrue(Files.exists(keystore));
        assertEquals(first.fingerprint(), again.fingerprint(), "a restart does not reissue the certificate");
        assertEquals(95, first.fingerprint().length(), "SHA-256 with colons: " + first.fingerprint());
        assertEquals("CN=bebebe-agent", first.certificate().getSubjectX500Principal().getName());
        assertTrue(first.sslContext().getProtocol().startsWith("TLS"));
    }

    @Test
    void keystoreAndPasswordOwnerOnly() throws Exception {
        ServerCertificate cert = ServerCertificate.ensure(temp.resolve("server.p12"));

        Set<PosixFilePermission> owner = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertEquals(owner, Files.getPosixFilePermissions(cert.keystore()));
        assertEquals(owner, Files.getPosixFilePermissions(cert.passwordFile()));
        assertFalse(Files.readString(cert.passwordFile()).isBlank());
    }

    @Test
    void differentKeystoresGiveDifferentFingerprintsAndCommandHasNoShell() {
        String a = ServerCertificate.ensure(temp.resolve("a.p12")).fingerprint();
        String b = ServerCertificate.ensure(temp.resolve("b.p12")).fingerprint();
        assertNotEquals(a, b);

        List<String> command = ServerCertificate.keytoolCommand(temp.resolve("x.p12"), "pw");
        assertTrue(command.getFirst().endsWith("keytool"));
        assertTrue(command.contains("-genkeypair"));
        assertTrue(command.contains("PKCS12"));
        assertEquals(temp.resolve("x.p12").toString(), command.get(command.indexOf("-keystore") + 1));
    }

    @Test
    void fingerprintIsNormalisedAndComparedCaseInsensitively() {
        String fp = "AB:CD".repeat(16).replaceAll(":$", "");

        String hex = "0123456789abcdef".repeat(4);
        String normalized = Fingerprint.normalize(hex);
        assertEquals(95, normalized.length());
        assertTrue(Fingerprint.matches(normalized, hex.toUpperCase()));
        assertTrue(Fingerprint.matches(hex, normalized.toLowerCase()));
        assertFalse(Fingerprint.matches(hex, "f" + hex.substring(1)));
        assertFalse(Fingerprint.matches("short", "short"), "not SHA-256 -- never matches");
        assertFalse(fp.isEmpty());
    }
}
