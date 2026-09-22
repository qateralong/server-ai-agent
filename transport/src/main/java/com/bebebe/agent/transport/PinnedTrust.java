package com.bebebe.agent.transport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public final class PinnedTrust {

    private PinnedTrust() {
    }

    public static SSLContext sslContextFor(String expectedFingerprint) {
        String expected = Fingerprint.normalize(expectedFingerprint);
        if (expected.length() != 95) {
            throw new IllegalArgumentException("Server fingerprint must be SHA-256 (64 hex): " + expectedFingerprint);
        }
        X509TrustManager pinned = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not used");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Server presented no certificate");
                }
                String actual = Fingerprint.sha256(chain[0]);
                if (!Fingerprint.matches(expected, actual)) {
                    throw new CertificateException("Server fingerprint mismatch: expected "
                            + expected + ", got " + actual);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {pinned}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot build client SSLContext", e);
        }
    }
}
