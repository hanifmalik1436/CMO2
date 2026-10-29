package com.cmo2.integrations.gohighlevel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the verification logic with a throwaway Ed25519 keypair — only GHL holds the
 * private half of the real key, so a genuine GHL signature can't be produced in a test.
 */
class GhlSignatureVerifierTest {

    private static final String BODY = "{\"type\":\"ContactCreate\",\"locationId\":\"loc_123\"}";

    private KeyPair keyPair;
    private GhlSignatureVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        verifier = new GhlSignatureVerifier(publicKeyBase64);
    }

    private String sign(String body) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        assertThat(verifier.verify(BODY, sign(BODY))).isTrue();
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        String signature = sign(BODY);
        assertThat(verifier.verify(BODY.replace("loc_123", "loc_999"), signature)).isFalse();
    }

    @Test
    void rejectsATamperedSignature() throws Exception {
        byte[] raw = Base64.getDecoder().decode(sign(BODY));
        raw[0] ^= 0x01;
        assertThat(verifier.verify(BODY, Base64.getEncoder().encodeToString(raw))).isFalse();
    }

    @Test
    void rejectsAMissingOrBlankSignature() {
        assertThat(verifier.verify(BODY, null)).isFalse();
        assertThat(verifier.verify(BODY, "  ")).isFalse();
    }

    @Test
    void rejectsASignatureThatIsNotBase64() {
        assertThat(verifier.verify(BODY, "not*base64!")).isFalse();
    }

    @Test
    void rejectsEverythingWhenTheConfiguredKeyIsInvalid() throws Exception {
        GhlSignatureVerifier broken = new GhlSignatureVerifier("not-a-key");
        assertThat(broken.verify(BODY, sign(BODY))).isFalse();
    }
}
