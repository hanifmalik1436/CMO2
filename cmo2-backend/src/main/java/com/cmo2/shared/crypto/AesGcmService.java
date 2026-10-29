package com.cmo2.shared.crypto;

import com.cmo2.shared.config.Cmo2Properties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for secrets at rest (e.g. platform API keys,
 * MFA secrets). This is the primitive underneath the envelope-encryption scheme
 * described in the architecture: per-tenant data keys wrapped by the master key.
 *
 * <p>For the foundation we encrypt directly under the master key; Phase 3 introduces
 * per-tenant data keys on top of this same GCM primitive.
 *
 * <p>Output format: Base64( IV(12 bytes) || ciphertext+tag ). GCM provides authenticity,
 * so tampering is detected on decrypt.
 */
@Service
public class AesGcmService {

    private static final int IV_LENGTH = 12;       // 96-bit nonce (GCM standard)
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec masterKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmService(Cmo2Properties props) {
        byte[] keyBytes = Base64.getDecoder().decode(props.crypto().masterKey());
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "cmo2.crypto.master-key must decode to exactly 32 bytes (AES-256). Got " + keyBytes.length);
        }
        this.masterKey = new SecretKeySpec(keyBytes, "AES");
    }

    /** Encrypts UTF-8 plaintext, returns Base64(IV || ciphertext+tag). */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Inverse of {@link #encrypt(String)}. */
    public String decrypt(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(in, 0, iv, 0, IV_LENGTH);
            byte[] ciphertext = new byte[in.length - IV_LENGTH];
            System.arraycopy(in, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
