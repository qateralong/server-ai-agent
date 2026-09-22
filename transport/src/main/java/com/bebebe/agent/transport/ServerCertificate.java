package com.bebebe.agent.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ServerCertificate {

    private static final Logger log = LoggerFactory.getLogger(ServerCertificate.class);
    static final String ALIAS = "bebebe-server";

    private final Path keystore;
    private final Path passwordFile;
    private final char[] password;
    private final KeyStore store;
    private final X509Certificate certificate;

    private ServerCertificate(Path keystore, Path passwordFile, char[] password,
                              KeyStore store, X509Certificate certificate) {
        this.keystore = keystore;
        this.passwordFile = passwordFile;
        this.password = password;
        this.store = store;
        this.certificate = certificate;
    }

    public static ServerCertificate ensure(Path keystore) {
        Path passwordFile = keystore.resolveSibling(keystore.getFileName() + ".pass");
        try {
            Files.createDirectories(keystore.toAbsolutePath().getParent());
            if (!Files.exists(keystore) || !Files.exists(passwordFile)) {
                generate(keystore, passwordFile);
            }
            char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip().toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, password);
            }
            X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
            if (certificate == null) {
                throw new IllegalStateException("No entry " + ALIAS + " in " + keystore);
            }
            return new ServerCertificate(keystore, passwordFile, password, store, certificate);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot prepare server certificate: " + keystore, e);
        }
    }

    static List<String> keytoolCommand(Path keystore, String password) {
        return List.of(keytoolBinary(),
                "-genkeypair",
                "-alias", ALIAS,
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-validity", "3650",
                "-dname", "CN=bebebe-agent",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", password,
                "-keypass", password);
    }

    private static String keytoolBinary() {
        Path fromJavaHome = Path.of(System.getProperty("java.home"), "bin", "keytool");
        return Files.isExecutable(fromJavaHome) ? fromJavaHome.toString() : "keytool";
    }

    private static void generate(Path keystore, Path passwordFile) throws IOException {
        Files.deleteIfExists(keystore);
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        String password = HexFormat.of().formatHex(raw);

        List<String> command = keytoolCommand(keystore, password);
        log.info("Generating self-signed server certificate: {}", keystore);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("keytool did not finish within 60 s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during keytool", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("keytool exited with code " + process.exitValue() + ": " + output.strip());
        }
        Files.writeString(passwordFile, password, StandardCharsets.UTF_8);
        restrict(passwordFile);
        restrict(keystore);
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Cannot set permissions 600 on {}: {}", file, e.getMessage());
        }
    }

    public SSLContext sslContext() {
        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build server SSLContext", e);
        }
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public String fingerprint() {
        return Fingerprint.sha256(certificate);
    }

    public Path keystore() {
        return keystore;
    }

    Path passwordFile() {
        return passwordFile;
    }
}
