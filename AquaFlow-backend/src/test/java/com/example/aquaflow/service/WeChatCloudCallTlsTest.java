package com.example.aquaflow.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Fresh synthetic keys in a temporary directory and loopback TLS only; no WeChat, DB or real configuration. */
class WeChatCloudCallTlsTest {
    @TempDir static Path fixtures;
    private static final char[] FIXTURE_PASSWORD = "isolated-test-only".toCharArray();
    private static Path first, second, wrongUsage, expired;

    @BeforeAll static void certificates() throws Exception {
        first = fixture("first", "serverAuth", false);
        second = fixture("second", "serverAuth", false);
        wrongUsage = fixture("wrong-usage", "codeSigning", false);
        expired = fixture("expired", "serverAuth", true);
    }

    private static Path fixture(String name, String eku, boolean old) throws Exception {
        Path identity = fixtures.resolve(name + ".p12"), cert = fixtures.resolve(name + ".crt");
        var command = new java.util.ArrayList<>(java.util.List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=api.weixin.qq.com", "-ext", "SAN=dns:api.weixin.qq.com",
                "-ext", "EKU=" + eku, "-validity", old ? "1" : "2",
                "-storetype", "PKCS12", "-keystore", identity.toString(),
                "-storepass", new String(FIXTURE_PASSWORD), "-noprompt"));
        if (old) command.addAll(java.util.List.of("-startdate", "2020/01/01 00:00:00"));
        Process generator = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(fixtures.resolve(name + ".generation.txt").toFile()).start();
        assertTrue(generator.waitFor(15, TimeUnit.SECONDS), "synthetic key generation must finish");
        assertEquals(0, generator.exitValue());
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(identity)) { store.load(input, FIXTURE_PASSWORD); }
        String pem = "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(store.getCertificate("server").getEncoded()) + "\n-----END CERTIFICATE-----\n";
        Files.writeString(cert, pem);
        return cert;
    }

    private static X509Certificate cert(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static X509TrustManager defaultManager() throws Exception {
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init((KeyStore) null);
        return Arrays.stream(managers.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst().orElseThrow();
    }

    @Test void retainsEveryDefaultRootAndAddsOnlyTheCurrentInstanceCertificate() throws Exception {
        var baseline = defaultManager();
        var material = WeChatCloudCallTls.load(first, 0);
        var accepted = java.util.Set.copyOf(Arrays.asList(material.trustManager().getAcceptedIssuers()));
        assertTrue(accepted.containsAll(Arrays.asList(baseline.getAcceptedIssuers())));
        assertEquals(baseline.getAcceptedIssuers().length, material.defaultRootCount());
        material.trustManager().checkServerTrusted(new X509Certificate[]{cert(first)}, "RSA");
        assertThrows(java.security.cert.CertificateException.class,
                () -> material.trustManager().checkServerTrusted(new X509Certificate[]{cert(second)}, "RSA"));
    }

    @Test void leavesGlobalHttpsDefaultsAndDefaultTrustUntouched() throws Exception {
        var sockets = HttpsURLConnection.getDefaultSSLSocketFactory();
        var verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        WeChatCloudCallTls.load(first, 0);
        assertSame(sockets, HttpsURLConnection.getDefaultSSLSocketFactory());
        assertSame(verifier, HttpsURLConnection.getDefaultHostnameVerifier());
        assertThrows(java.security.cert.CertificateException.class,
                () -> defaultManager().checkServerTrusted(new X509Certificate[]{cert(first)}, "RSA"));
    }

    @Test void appliesOnlyToTheApprovedHttpsOriginAndRetainsHostnameVerification() throws Exception {
        var material = WeChatCloudCallTls.load(first, 0);
        var connection = (HttpsURLConnection) new URL("https://api.weixin.qq.com/").openConnection();
        var verifier = connection.getHostnameVerifier();
        material.prepare(connection);
        assertSame(verifier, connection.getHostnameVerifier());
        assertSame(material.socketFactory(), connection.getSSLSocketFactory());
        assertFalse(connection.getInstanceFollowRedirects());
        for (String url : new String[]{"http://api.weixin.qq.com/", "https://other.invalid/",
                "https://api.weixin.qq.com.other.invalid/", "https://api.weixin.qq.com:444/"}) {
            var rejected = (java.net.HttpURLConnection) new URL(url).openConnection();
            assertThrows(IOException.class, () -> material.prepare(rejected));
        }
    }

    @Test void controlledTlsHandshakeAcceptsMountedIdentity() throws Exception {
        handshake(WeChatCloudCallTls.load(first, 0), "first", "api.weixin.qq.com");
    }

    @Test void serviceWiresTheScopedSocketFactoryWithTheConfiguredTimeouts() throws Exception {
        var material = WeChatCloudCallTls.load(first, 0);
        var service = new WeChatLoginService(170, 230, () -> material);
        var rest = (org.springframework.web.client.RestTemplate)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "restTemplate");
        var connection = (HttpsURLConnection) new URL("https://api.weixin.qq.com/").openConnection();
        var verifier = connection.getHostnameVerifier();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(rest.getRequestFactory(),
                "prepareConnection", connection, "GET");
        assertEquals(170, connection.getConnectTimeout());
        assertEquals(230, connection.getReadTimeout());
        assertSame(material.socketFactory(), connection.getSSLSocketFactory());
        assertSame(verifier, connection.getHostnameVerifier());
        assertFalse(connection.getInstanceFollowRedirects());
    }

    @Test void invalidTimeoutIsRejectedBeforeAnyCertificateRead() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new WeChatLoginService(0, 230, () -> {
            reads.incrementAndGet(); throw new AssertionError("must validate before reading a certificate");
        }));
        assertEquals(0, reads.get());
    }

    @Test void serviceLoadsLazilyAndRetriesAfterCertificateReadinessFailure() throws Exception {
        var material = WeChatCloudCallTls.load(first, 0);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var service = new WeChatLoginService(170, 230, () -> {
            if (reads.incrementAndGet() == 1) throw new IllegalStateException("synthetic-not-ready");
            return material;
        });
        assertEquals(0, reads.get(), "service creation must not wait for or read the certificate");
        var rest = (org.springframework.web.client.RestTemplate)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "restTemplate");
        var target = java.net.URI.create("https://api.weixin.qq.com/");
        assertThrows(IOException.class, () -> rest.getRequestFactory().createRequest(target, org.springframework.http.HttpMethod.GET));
        assertNotNull(rest.getRequestFactory().createRequest(target, org.springframework.http.HttpMethod.GET));
        assertNotNull(rest.getRequestFactory().createRequest(target, org.springframework.http.HttpMethod.GET));
        assertEquals(2, reads.get(), "only successful initialization is cached");
    }

    @Test void serviceRejectsOtherOriginsBeforeReadingTheCertificate() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var service = new WeChatLoginService(170, 230, () -> {
            reads.incrementAndGet(); throw new AssertionError("must not read for another origin");
        });
        var rest = (org.springframework.web.client.RestTemplate)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "restTemplate");
        assertThrows(IOException.class, () -> rest.getRequestFactory().createRequest(
                java.net.URI.create("https://other.invalid/"), org.springframework.http.HttpMethod.GET));
        assertEquals(0, reads.get());
    }

    @Test void documentedEnvironmentVariableSelectsTheLazyBridgeThroughSpring() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.SystemEnvironmentPropertySource("synthetic-cloud-call",
                            java.util.Map.of("WECHAT_MINIAPP_CLOUD_CALL_TLS_ENABLED", "true")));
            context.register(WeChatLoginService.class);
            context.refresh();
            var rest = (org.springframework.web.client.RestTemplate)
                    org.springframework.test.util.ReflectionTestUtils.getField(
                            context.getBean(WeChatLoginService.class), "restTemplate");
            assertTrue(rest.getRequestFactory().getClass().isAnonymousClass(), "the documented switch must reach the actual factory");
        }
    }

    @Test void controlledTlsHandshakeRejectsOtherIdentity() throws Exception {
        assertThrows(SSLHandshakeException.class,
                () -> handshake(WeChatCloudCallTls.load(first, 0), "second", "api.weixin.qq.com"));
    }

    @Test void controlledTlsHandshakeRejectsHostnameMismatch() throws Exception {
        assertThrows(SSLHandshakeException.class,
                () -> handshake(WeChatCloudCallTls.load(first, 0), "first", "other.invalid"));
    }

    @Test void missingFileFailsWithinTheConfiguredBound() {
        long start = System.nanoTime();
        var error = assertThrows(IllegalStateException.class,
                () -> WeChatCloudCallTls.load(fixtures.resolve("missing.crt"), 100));
        assertTrue(error.getMessage().contains("WX_CLOUD_CALL_TLS_CERTIFICATE_UNAVAILABLE"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
    }

    @Test void certificateCanArriveDuringTheFiniteStartupWait() throws Exception {
        Path late = fixtures.resolve("late.crt");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var copy = executor.submit(() -> { Thread.sleep(80); Files.copy(first, late); return null; });
            var material = WeChatCloudCallTls.load(late, 1000);
            copy.get(2, TimeUnit.SECONDS);
            assertEquals(WeChatCloudCallTls.load(first, 0).sha256(), material.sha256());
        } finally { executor.shutdownNow(); }
    }

    @Test void newInstanceUsesNewCertificateWithoutRetainingTheOldInstanceAnchor() throws Exception {
        Path mount = fixtures.resolve("renewed.crt");
        Files.copy(first, mount);
        var previous = WeChatCloudCallTls.load(mount, 0);
        Files.copy(second, mount, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var current = WeChatCloudCallTls.load(mount, 0);
        assertNotEquals(previous.sha256(), current.sha256());
        current.trustManager().checkServerTrusted(new X509Certificate[]{cert(second)}, "RSA");
        assertThrows(java.security.cert.CertificateException.class,
                () -> current.trustManager().checkServerTrusted(new X509Certificate[]{cert(first)}, "RSA"));
    }

    @Test void malformedEmptyOversizedAndMultipleCertificatesAreRejectedWithoutContentInErrors() throws Exception {
        for (String name : new String[]{"malformed", "empty", "large", "multiple"}) {
            Path file = fixtures.resolve(name + ".crt");
            Files.writeString(file, switch (name) {
                case "malformed" -> "synthetic-sensitive-content";
                case "empty" -> "";
                case "large" -> "X".repeat(65537);
                default -> Files.readString(first) + Files.readString(second);
            });
            var error = assertThrows(IllegalStateException.class, () -> WeChatCloudCallTls.load(file, 0));
            assertFalse(error.getMessage().contains("synthetic-sensitive-content"));
            assertNull(error.getCause());
        }
    }

    @Test void expiredAndWrongPurposeCertificatesAreRejected() {
        assertThrows(IllegalStateException.class, () -> WeChatCloudCallTls.load(expired, 0));
        assertThrows(IllegalStateException.class, () -> WeChatCloudCallTls.load(wrongUsage, 0));
    }

    private static void handshake(WeChatCloudCallTls.Material material, String identity, String hostname) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(fixtures.resolve(identity + ".p12"))) { keys.load(input, FIXTURE_PASSWORD); }
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, FIXTURE_PASSWORD);
        var serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        var executor = Executors.newSingleThreadExecutor();
        try (var listener = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(3000);
            var server = executor.submit(() -> {
                try (var peer = (SSLSocket) listener.accept()) {
                    peer.setSoTimeout(3000); peer.startHandshake();
                } catch (IOException expectedOnClientRejection) { /* the client assertion owns the outcome */ }
            });
            try (var transport = new Socket()) {
                transport.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()), 2000);
                try (var tls = (SSLSocket) material.socketFactory().createSocket(transport, hostname, listener.getLocalPort(), true)) {
                    tls.setSoTimeout(3000);
                    var parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                    tls.startHandshake();
                }
            } finally { server.get(4, TimeUnit.SECONDS); }
        } finally { executor.shutdownNow(); }
    }
}
