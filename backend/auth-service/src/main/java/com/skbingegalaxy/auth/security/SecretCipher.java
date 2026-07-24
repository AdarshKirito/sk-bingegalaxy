package com.skbingegalaxy.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for secrets that must be recoverable in plaintext at
 * runtime — today the TOTP shared secret.
 *
 * <p>WHY: a TOTP secret cannot be hashed, because the server has to recompute
 * codes from it on every verification. Stored in plaintext (as it was before
 * this class existed), anyone with a read of the {@code users} table — a DB
 * dump, a backup, a SQL-injection read, an over-broad support query — could mint
 * valid 2FA codes for every enrolled admin indefinitely, and the victim would
 * see nothing. Encrypting at rest means the DB alone is not enough; the attacker
 * also needs the application key, which lives in config, not in the database.
 *
 * <p>AES-256-GCM. GCM is authenticated, so tampering with a stored ciphertext is
 * detected on decrypt rather than silently producing a wrong secret. A fresh
 * random 96-bit IV is generated per encryption and prefixed to the ciphertext —
 * reusing an IV under GCM is catastrophic, so it is never derived or fixed.
 *
 * <p>Wire format: {@code gcm:v1:base64(iv ‖ ciphertext ‖ tag)}. The version tag
 * lets us rotate the scheme later without ambiguity.
 *
 * <p><b>Backward compatibility:</b> {@link #decrypt} returns any value lacking
 * the {@code gcm:v1:} prefix unchanged. Secrets enrolled before this rollout are
 * plaintext in the DB and keep working; they are re-encrypted the next time they
 * are written. That avoids a migration that would have to decrypt-and-rewrite
 * every row while the service is running.
 */
@Component
@Slf4j
public class SecretCipher {

    private static final String PREFIX = "gcm:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;      // 96-bit, the GCM-recommended size
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(
            @Value("${app.crypto.secret-key:}") String configuredKey,
            @Value("${app.jwt.secret}") String jwtSecret) {
        this.key = resolveKey(configuredKey, jwtSecret);
    }

    /**
     * Prefer a dedicated key so MFA secrets survive a JWT-secret rotation. When
     * none is configured we derive one from the JWT secret rather than falling
     * back to plaintext storage — a derived key still defeats a database-only
     * compromise, which is the threat this class exists for.
     */
    private SecretKey resolveKey(String configuredKey, String jwtSecret) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(configuredKey.trim());
            if (raw.length != 32) {
                throw new IllegalStateException(
                    "app.crypto.secret-key must be exactly 32 bytes (256 bits) base64-encoded; got "
                        + raw.length + " bytes. Generate with: openssl rand -base64 32");
            }
            return new SecretKeySpec(raw, "AES");
        }
        log.warn("app.crypto.secret-key is not set — deriving the MFA encryption key from "
            + "app.jwt.secret. This works, but rotating JWT_SECRET will make every enrolled "
            + "TOTP secret undecryptable. Set CRYPTO_SECRET_KEY (openssl rand -base64 32) "
            + "before enrolling users in production.");
        try {
            // Domain separation: the derived key must never equal the signing key,
            // so that a JWT-signing oracle cannot be turned into a decryption oracle.
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update("skbingegalaxy:mfa-secret-encryption:v1".getBytes(StandardCharsets.UTF_8));
            byte[] derived = sha256.digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive MFA encryption key", e);
        }
    }

    /** Encrypt a plaintext secret. Null/blank passes through untouched. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never fall back to storing plaintext — that would silently undo the
            // protection this class provides.
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * Decrypt a stored value. Values without the {@code gcm:v1:} prefix are
     * pre-encryption plaintext and are returned as-is.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        if (!stored.startsWith(PREFIX)) return stored;   // legacy plaintext
        try {
            byte[] blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (blob.length <= IV_BYTES) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[blob.length - IV_BYTES];
            System.arraycopy(blob, IV_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Wrong key (e.g. JWT secret rotated while using a derived key) or a
            // tampered row. Fail loudly: silently treating this as "no MFA" would
            // downgrade the account's security.
            log.error("Failed to decrypt stored secret — wrong encryption key or tampered value");
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }

    /** True when the value is already in encrypted form. */
    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
