package com.example.aquaflow.service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in bridge for the instance-local certificate supplied by WeChat Cloud Run.
 * Builds a private in-memory truststore; never writes a system/JRE truststore or global SSL defaults.
 * The caller must scope its socket factory to the fixed WeChat HTTPS destination.
 */
final class WeChatCloudCallTls {
    private static final Path OFFICIAL_CERTIFICATE = Path.of("/app/cert/certificate.crt");
    private static final long WAIT_MILLIS = 5000;
    private static final int MAX_CERTIFICATE_BYTES = 65536;
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    record Material(SSLSocketFactory socketFactory, String sha256, Instant notBefore, Instant notAfter,
                    int defaultRootCount, X509TrustManager trustManager) {
        /** Restrict the additional anchor to the one approved HTTPS origin, keeping hostname verification. */
        void prepare(HttpURLConnection connection) throws IOException {
            requireApprovedDestination(connection);
            var https = (HttpsURLConnection) connection;
            Instant now = Instant.now();
            if (now.isBefore(notBefore) || !now.isBefore(notAfter)) {
                throw new IOException("WX_CLOUD_CALL_TLS_INSTANCE_CERTIFICATE_OUTSIDE_VALIDITY");
            }
            https.setSSLSocketFactory(socketFactory);
            // Keep the standard hostname verifier; never follow a redirect with the added anchor.
            https.setInstanceFollowRedirects(false);
        }
    }

    private WeChatCloudCallTls() {}

    /** Read the platform's fixed mount on first use, with a finite readiness wait for this request. */
    static Material loadOfficialForRequest() {
        return load(OFFICIAL_CERTIFICATE, WAIT_MILLIS);
    }

    /** Reject other origins before waiting for the platform certificate or adding any trust. */
    static void requireApprovedDestination(HttpURLConnection connection) throws IOException {
        var destination = connection.getURL();
        if (!(connection instanceof HttpsURLConnection)
                || !"api.weixin.qq.com".equalsIgnoreCase(destination.getHost())
                || (destination.getPort() != -1 && destination.getPort() != 443)) {
            throw new IOException("WX_CLOUD_CALL_TLS_DESTINATION_REJECTED");
        }
    }

    /** Package-private seam for isolated fixture tests; the application exposes no configurable path. */
    static Material load(Path file, long waitMillis) {
        if (waitMillis < 0 || waitMillis > WAIT_MILLIS) {
            throw new IllegalArgumentException("WX_CLOUD_CALL_TLS_INVALID_WAIT");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        String failureType = "CertificateUnavailable";
        while (true) {
            try {
                return material(readCertificate(file));
            } catch (IOException | GeneralSecurityException failure) {
                // No exception messages, PEM, request URLs, credentials or environment values.
                failureType = failure.getClass().getSimpleName();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("WX_CLOUD_CALL_TLS_CERTIFICATE_UNAVAILABLE path="
                        + OFFICIAL_CERTIFICATE + " failureType=" + failureType);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("WX_CLOUD_CALL_TLS_READINESS_WAIT_INTERRUPTED");
            }
        }
    }

    private static X509Certificate readCertificate(Path file) throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IOException("CertificateNotReadable");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_CERTIFICATE_BYTES + 1);
        }
        if (bytes.length == 0 || bytes.length > MAX_CERTIFICATE_BYTES) {
            throw new GeneralSecurityException("CertificateSizeRejected");
        }
        var certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new java.io.ByteArrayInputStream(bytes));
        if (certificates.size() != 1 || !(certificates.iterator().next() instanceof X509Certificate cert)) {
            throw new GeneralSecurityException("ExpectedSingleX509Certificate");
        }
        cert.checkValidity();
        // The documented mount may be a self-signed endpoint certificate rather than a CA:TRUE root.
        // Do not require CA:TRUE or treat it as a reusable enterprise/global root.
        if (!cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
            throw new GeneralSecurityException("ExpectedInstanceSelfSignedCertificate");
        }
        cert.verify(cert.getPublicKey());
        List<String> usages = cert.getExtendedKeyUsage();
        if (usages != null && !usages.contains(SERVER_AUTH)) {
            throw new GeneralSecurityException("CertificateNotForServerAuthentication");
        }
        return cert;
    }

    private static Material material(X509Certificate instanceCertificate) throws IOException, GeneralSecurityException {
        TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        defaults.init((KeyStore) null);
        KeyStore scoped = KeyStore.getInstance(KeyStore.getDefaultType());
        scoped.load(null, null);
        int roots = 0;
        for (var manager : defaults.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                for (var certificate : x509.getAcceptedIssuers()) {
                    scoped.setCertificateEntry("default-root-" + roots++, certificate);
                }
            }
        }
        if (roots == 0) {
            throw new GeneralSecurityException("DefaultTrustAnchorsUnavailable");
        }
        scoped.setCertificateEntry("wechat-cloud-call-instance", instanceCertificate);
        TrustManagerFactory scopedManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        scopedManagers.init(scoped);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, scopedManagers.getTrustManagers(), null);
        String fingerprint = HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(instanceCertificate.getEncoded()));
        X509TrustManager manager = null;
        for (var candidate : scopedManagers.getTrustManagers()) {
            if (candidate instanceof X509TrustManager x509) manager = x509;
        }
        if (manager == null) throw new GeneralSecurityException("ScopedTrustManagerUnavailable");
        return new Material(context.getSocketFactory(), fingerprint, instanceCertificate.getNotBefore().toInstant(),
                instanceCertificate.getNotAfter().toInstant(), roots, manager);
    }
}
