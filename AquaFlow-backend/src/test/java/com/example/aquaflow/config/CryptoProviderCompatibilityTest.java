package com.example.aquaflow.config;

import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSSigner;
import com.qcloud.cos.http.HttpMethodName;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline provider migration checks. No provider registration or COS network calls. */
class CryptoProviderCompatibilityTest {
    @Test
    void runtimeHasOneProviderFromTheMaintainedArtifact() throws Exception {
        var type = BouncyCastleProvider.class;
        var resource = "org/bouncycastle/jce/provider/BouncyCastleProvider.class";
        var locations = Collections.list(type.getClassLoader().getResources(resource));
        assertEquals(1, locations.size(), "Duplicate BC implementations must not coexist");
        assertTrue(locations.get(0).toString().contains("bcprov-jdk18on-1.86"));
        assertEquals("BC", new BouncyCastleProvider().getName());
    }

    @Test
    void aesGcmInteroperatesInBothDirections() throws Exception {
        var provider = new BouncyCastleProvider();
        var key = new SecretKeySpec(new byte[32], "AES");
        var params = new GCMParameterSpec(128, new byte[12]);
        var data = "offline-compatibility".getBytes(StandardCharsets.UTF_8);
        var aad = "notification".getBytes(StandardCharsets.UTF_8);
        for (boolean encryptWithBc : new boolean[]{true, false}) {
            var encrypt = encryptWithBc ? Cipher.getInstance("AES/GCM/NoPadding", provider)
                    : Cipher.getInstance("AES/GCM/NoPadding");
            var decrypt = encryptWithBc ? Cipher.getInstance("AES/GCM/NoPadding")
                    : Cipher.getInstance("AES/GCM/NoPadding", provider);
            encrypt.init(Cipher.ENCRYPT_MODE, key, params);
            encrypt.updateAAD(aad);
            byte[] ciphertext = encrypt.doFinal(data);
            decrypt.init(Cipher.DECRYPT_MODE, key, params);
            decrypt.updateAAD(aad);
            assertArrayEquals(data, decrypt.doFinal(ciphertext));
            ciphertext[0] ^= 1;
            decrypt.init(Cipher.DECRYPT_MODE, key, params);
            decrypt.updateAAD(aad);
            assertThrows(javax.crypto.AEADBadTagException.class, () -> decrypt.doFinal(ciphertext));
        }
    }

    @Test
    void rsaSignaturesInteroperateInBothDirections() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        byte[] data = "offline-rsa".getBytes(StandardCharsets.UTF_8);
        for (boolean signWithBc : new boolean[]{true, false}) {
            var bc = new BouncyCastleProvider();
            var signer = signWithBc ? Signature.getInstance("SHA256withRSA", bc)
                    : Signature.getInstance("SHA256withRSA");
            var verifier = signWithBc ? Signature.getInstance("SHA256withRSA")
                    : Signature.getInstance("SHA256withRSA", bc);
            signer.initSign(pair.getPrivate()); signer.update(data);
            byte[] signature = signer.sign();
            verifier.initVerify(pair.getPublic()); verifier.update(data);
            assertTrue(verifier.verify(signature));
            verifier.initVerify(pair.getPublic()); verifier.update(new byte[]{1});
            assertFalse(verifier.verify(signature));
        }
    }

    @Test
    void cosSdkStillSignsOfflineWithSyntheticCredentials() {
        var signer = new COSSigner();
        var credentials = new BasicCOSCredentials("offline-key", "offline-secret");
        var start = new Date(1_759_622_400_000L);
        var end = new Date(start.getTime() + 60_000);
        String first = signer.buildAuthorizationStr(HttpMethodName.GET, "/fixture.txt", Map.of(),
                Map.of(), credentials, start, end, false);
        String again = signer.buildAuthorizationStr(HttpMethodName.GET, "/fixture.txt", Map.of(),
                Map.of(), credentials, start, end, false);
        String changedPath = signer.buildAuthorizationStr(HttpMethodName.GET, "/other.txt", Map.of(),
                Map.of(), credentials, start, end, false);
        assertEquals(first, again);
        assertNotEquals(first, changedPath);
        assertTrue(first.contains("q-ak=offline-key"));
        assertTrue(first.matches(".*q-signature=[0-9a-f]{40}.*"));
    }
}
