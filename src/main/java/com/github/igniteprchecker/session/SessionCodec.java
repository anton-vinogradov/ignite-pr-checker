package com.github.igniteprchecker.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.SessionProperties;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 * server-side session store and logins survive restarts. The session does not expire on its own —
 * it stays valid until the user logs out (or the token is revoked, or {@code session.secret} is
 * rotated). The token is confidential inside the cookie; tampering fails the GCM auth tag.
 */
@Component
public class SessionCodec {
    private static final Logger log = LoggerFactory.getLogger(SessionCodec.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public SessionCodec(SessionProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.key = deriveKey(props.secret());
    }

    /** @return the cookie value carrying the encrypted session. */
    public String encode(String username, String token) {
        return encode(username, token, null);
    }

    /** @return the cookie value carrying the encrypted session, with an optional JIRA PAT. */
    public String encode(String username, String token, String jiraToken) {
        return encode(username, token, jiraToken, null);
    }

    /** @return the cookie value carrying the encrypted session, with optional JIRA and GitHub PATs. */
    public String encode(String username, String token, String jiraToken, String ghToken) {
        try {
            byte[] plain = mapper.writeValueAsBytes(new Payload(username, token, jiraToken, ghToken));

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

    /** Encrypts an arbitrary secret with the session key (AES-GCM) — for short-lived at-rest storage. */
    public String encryptString(String plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();

            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        }
        catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    /** Decrypts a value produced by {@link #encryptString}; empty if tampered or the key rotated. */
    public Optional<String> decryptString(String encoded) {
        try {
            byte[] in = Base64.getUrlDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(in, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(in, IV_LEN, in.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            return Optional.of(new String(cipher.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Decrypts a cookie value; empty if missing or tampered. */
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

            return Optional.of(new Session(p.username(), p.token(), p.jiraToken(), p.ghToken()));
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

    public record Session(String username, String token, String jiraToken, String ghToken) {
    }

    /** Old cookies also carried an {@code exp}; it is ignored now that sessions don't time out. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(String username, String token, String jiraToken, String ghToken) {
    }
}
