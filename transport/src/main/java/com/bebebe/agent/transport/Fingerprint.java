package com.bebebe.agent.transport;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.HexFormat;
import java.util.Locale;

public final class Fingerprint {

    private Fingerprint() {
    }

    public static String sha256(Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Cannot compute certificate fingerprint", e);
        }
    }

    public static String normalize(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        String hex = fingerprint.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (hex.length() != 64) {
            return hex;
        }
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(HexFormat.of().parseHex(hex));
    }

    public static boolean matches(String expected, String actual) {
        String a = normalize(expected);
        String b = normalize(actual);
        return a.length() == 95 && MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                b.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
