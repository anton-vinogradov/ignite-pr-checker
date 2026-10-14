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
 * server-side session store and logins survive restarts. Each value carries when it was issued: a logout
 * ends every session of its user issued until then, and the guard renews an old cookie before the
 * browser drops it. The session does not time out on the server; it ends at a logout, when TeamCity
 * refuses the token, or when {@code session.secret} is rotated. The token is confidential inside the
 * cookie; tampering fails the GCM auth tag.
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

    /** @return the cookie value carrying a new session: the TeamCity token alone. */
    public String encode(String username, String token) {
        return encode(new Session(username, token, null));
    }

    /**
     * @return the cookie value carrying the encrypted session, issued now. A cookie re-issued for one changed field is
     * encoded from the session it replaces, with that field changed, so the others stay.
     */
    public String encode(Session session) {
        return encode(session, System.currentTimeMillis());
    }

    /**
     * @return the cookie value carrying the encrypted session, issued at {@code issuedAt}. A cookie re-issued from a
     * session the guard let through says it was issued when the guard checked it: a logout after that check ends the
     * new cookie too, however long the answer took.
     */
    public String encode(Session session, long issuedAt) {
        try {
            byte[] plain = mapper.writeValueAsBytes(new Payload(session.username(), session.token(),
                session.jiraToken(), issuedAt));

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

            return Optional.of(new Session(p.username(), p.token(), p.jiraToken(), p.iat() == null ? 0 : p.iat()));
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

    /**
     * A user's session. {@code issuedAt} is when its cookie was issued (epoch ms); 0 for a cookie issued before
     * cookies said so, and for a session not issued yet.
     */
    public record Session(String username, String token, String jiraToken, long issuedAt) {
        /** A session not issued yet. */
        public Session(String username, String token, String jiraToken) {
            this(username, token, jiraToken, 0);
        }

        /** The same session under another TeamCity token. */
        public Session withToken(String tc) {
            return new Session(username, tc, jiraToken, issuedAt);
        }

        /** The same session with another JIRA PAT. */
        public Session withJiraToken(String jira) {
            return new Session(username, token, jira, issuedAt);
        }
    }

    /**
     * Old cookies also carried an {@code exp}, ignored now that sessions don't time out, and a {@code ghToken}, which
     * the checker no longer takes from a cookie: it goes at the cookie's next issue. {@code iat}, when the cookie was
     * issued, is absent from the cookies of earlier versions.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(String username, String token, String jiraToken, Long iat) {
    }
}
