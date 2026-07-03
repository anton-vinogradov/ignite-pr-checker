package com.github.igniteprchecker.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.SessionProperties;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Encodes a session into an encrypted, self-contained cookie value (AES-GCM), so there is no
 * server-side session store and logins survive restarts. The user's token is confidential inside
 * the cookie; tampering fails the GCM auth tag. Requires a stable {@code session.secret}.
 */
@Component
public class SessionCodec {
    private static final Logger log = LoggerFactory.getLogger(SessionCodec.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SessionProperties props;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public SessionCodec(SessionProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.key = deriveKey(props.secret());
    }

    /** @return the cookie value carrying an encrypted session that expires after the configured TTL. */
    public String encode(String username, String token) {
        try {
            long exp = Instant.now().plus(Duration.ofMinutes(props.ttlMinutes())).toEpochMilli();
            byte[] plain = mapper.writeValueAsBytes(new Payload(username, token, exp));

            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);

            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();

            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        }
        catch (Exception e) {
            throw new IllegalStateException("session encode failed", e);
        }
    }

    /** Decrypts and validates a cookie value; empty if missing, tampered, or expired. */
    public Optional<Session> decode(String cookie) {
        if (cookie == null || cookie.isBlank())
            return Optional.empty();

        try {
            byte[] in = Base64.getUrlDecoder().decode(cookie);
            byte[] iv = Arrays.copyOfRange(in, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(in, IV_LEN, in.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            Payload p = mapper.readValue(cipher.doFinal(ct), Payload.class);

            if (Instant.now().toEpochMilli() > p.exp())
                return Optional.empty();

            return Optional.of(new Session(p.username(), p.token()));
        }
        catch (Exception e) {
            return Optional.empty();
        }
    }

    private static SecretKey deriveKey(String secret) {
        byte[] material;
        if (secret == null || secret.isBlank()) {
            material = new byte[32];
            new SecureRandom().nextBytes(material);
            log.warn("session.secret not set: using a random key — logins will NOT survive a restart. "
                + "Set SESSION_SECRET in production.");
        }
        else {
            try {
                material = MessageDigest.getInstance("SHA-256").digest(secret.getBytes());
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        return new SecretKeySpec(material, "AES");
    }

    public record Session(String username, String token) {
    }

    private record Payload(String username, String token, long exp) {
    }
}
