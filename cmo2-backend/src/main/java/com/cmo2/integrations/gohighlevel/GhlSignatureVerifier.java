package com.cmo2.integrations.gohighlevel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies GoHighLevel's inbound webhook signatures. GHL signs every webhook with its own
 * private key (Ed25519, header {@code X-GHL-Signature}) and publishes the matching public
 * key — there is no shared secret to configure or exchange. See
 * marketplace.gohighlevel.com/docs/webhook/WebhookIntegrationGuide. The legacy RSA header
 * ({@code X-WH-Signature}) was deprecated by GHL on 2026-09-01 and is intentionally not
 * supported here.
 */
@Service
public class GhlSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GhlSignatureVerifier.class);
    private static final String ALGORITHM = "Ed25519";

    private final PublicKey publicKey;

    public GhlSignatureVerifier(
            @Value("${cmo2.integrations.gohighlevel.webhook-public-key:MCowBQYDK2VwAyEAi2HR1srL4o18O8BRa7gVJY7G7bupbN3H9AwJrHCDiOg=}")
            String publicKeyBase64) {
        this.publicKey = loadPublicKey(publicKeyBase64);
    }

    /** True only if {@code signatureHeader} is a valid Ed25519 signature of {@code rawBody} by the configured key. */
    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank() || publicKey == null) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureHeader));
        } catch (Exception e) {
            log.warn("GHL signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private PublicKey loadPublicKey(String base64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            log.error("Failed to load GHL webhook public key — every GHL webhook signature will "
                    + "fail verification until this is fixed: {}", e.getMessage());
            return null;
        }
    }
}
