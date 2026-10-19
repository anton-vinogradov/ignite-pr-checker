package com.github.igniteprchecker.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.config.SessionProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Encodes a session into an encrypted cookie value (AES-GCM). The cookie carries the TeamCity token and names its
 * login, one of the server's records of who logged in from which browser, which hold no token. Each value carries when
 * it was issued, so the guard renews an old cookie before the browser drops it. The session does not time out on the
 * server; it ends when its login is ended, when TeamCity refuses the token, or when {@code session.secret} is rotated.
 * The token is confidential inside the cookie; tampering fails the GCM auth tag.
 */
@Component
public class SessionCodec {
    /** How long after its issue a cookie of an earlier version still counts; see {@link #decodeEarlier}. */
    public static final Duration EARLIER_TAKEN_FOR = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(SessionCodec.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    /** What {@link #keyCheck} signs: a fixed text, so the fingerprint names the key and nothing else. */
    private static final byte[] KEY_CHECK_TEXT = "ignite-pr-checker key check".getBytes(StandardCharsets.UTF_8);

    /** How many bytes of the signature {@link #keyCheck} keeps. */
    private static final int KEY_CHECK_BYTES = 8;

    private final SecretKey key;

    private final String keyCheck;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public SessionCodec(SessionProperties props, ObjectMapper mapper) {
        this.mapper = mapper;
        this.key = deriveKey(props.secret());
        this.keyCheck = keyCheckOf(key);
    }

    /**
     * A short fingerprint of the key: the start of an HMAC-SHA256 of a fixed text, in hex. Kept next to the stored
     * tokens, it tells that they were encrypted with another key from tokens that got damaged; it reveals nothing of
     * the key itself.
     */
    public String keyCheck() {
        return keyCheck;
    }

    private static String keyCheckOf(SecretKey key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));

            return HexFormat.of().formatHex(Arrays.copyOf(mac.doFinal(KEY_CHECK_TEXT), KEY_CHECK_BYTES));
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the cookie value carrying a new session: the TeamCity token alone. */
    public String encode(String username, String token) {
        return encode(new Session(username, token));
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
     * session the guard let through says it was issued when the guard checked it.
     */
    public String encode(Session session, long issuedAt) {
        try {
            byte[] plain = mapper.writeValueAsBytes(new Payload(session.username(), session.token(), null, issuedAt,
                Payload.NAMED_BY_CONNECTION, session.login()));

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
        return payload(cookie).map(Payload::session);
    }

    /**
     * Decrypts a cookie value an earlier version issued, before cookies over HTTPS were named {@code __Host-}, if it was
     * issued within {@link #EARLIER_TAKEN_FOR}; empty for one this version issued. A copy of a newer cookie under the old
     * name comes from someone who set it there. No cookie of the old format is issued any more, so none is taken once
     * that time has passed since the upgrade: a cookie someone kept cannot be planted in a visitor's browser for good.
     */
    public Optional<Session> decodeEarlier(String cookie) {
        long now = System.currentTimeMillis();

        return payload(cookie)
            .filter(p -> p.v() == null && p.iat() != null && now - p.iat() <= EARLIER_TAKEN_FOR.toMillis())
            .map(Payload::session);
    }

    /**
     * Whether the cookie value is one an earlier version issued, however long ago: {@link #decodeEarlier} may refuse it
     * for its age, yet its owner did log in.
     */
    public boolean issuedByEarlier(String cookie) {
        return payload(cookie).filter(p -> p.v() == null).isPresent();
    }

    private Optional<Payload> payload(String cookie) {
        if (cookie == null || cookie.isBlank())
            return Optional.empty();

        try {
            byte[] in = Base64.getUrlDecoder().decode(cookie);
            byte[] iv = Arrays.copyOfRange(in, 0, IV_LEN);
            byte[] ct = Arrays.copyOfRange(in, IV_LEN, in.length);

            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            return Optional.of(mapper.readValue(cipher.doFinal(ct), Payload.class));
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
     * cookies said so, and for a session not issued yet. {@code carriesJira}: the cookie carries a JIRA token an
     * earlier version put there. The checker uses none: the visa option takes the token with its switch and keeps it
     * on the server. {@code login}: the id of the login the cookie belongs to; null in a cookie of an earlier version,
     * which gets one at its first request.
     */
    public record Session(String username, String token, long issuedAt, boolean carriesJira, String login) {
        /** A session not issued yet. */
        public Session(String username, String token) {
            this(username, token, 0);
        }

        /** A session whose cookie carries no JIRA token. */
        public Session(String username, String token, long issuedAt) {
            this(username, token, issuedAt, false);
        }

        /** A session whose cookie names no login. */
        public Session(String username, String token, long issuedAt, boolean carriesJira) {
            this(username, token, issuedAt, carriesJira, null);
        }

        /** The same session in the login {@code id}. */
        public Session withLogin(String id) {
            return new Session(username, token, issuedAt, carriesJira, id);
        }

        @Override
        public String toString() {
            return "Session[username=" + username + ", token=" + Secrets.masked(token) + ", issuedAt=" + issuedAt
                + ", carriesJira=" + carriesJira + ", login=" + login + "]";
        }
    }

    /**
     * Old cookies also carried an {@code exp}, ignored now that sessions don't time out, and a {@code ghToken} or a
     * {@code jiraToken}, which the checker no longer takes from a cookie: they go at the cookie's next issue, and no
     * cookie it issues has them. {@code iat}, when the cookie was issued, is absent from the cookies of earlier
     * versions. {@code v} is the cookie's format, absent from the cookies issued before cookies over HTTPS were named
     * {@code __Host-}. {@code lid}, the login's id, is absent from the cookies issued before the server kept logins.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(String username, String token, @JsonInclude(JsonInclude.Include.NON_NULL) String jiraToken,
        Long iat, Integer v, @JsonInclude(JsonInclude.Include.NON_NULL) String lid) {
        /** The format of a cookie whose name says how it was sent: {@code __Host-} over HTTPS. */
        static final int NAMED_BY_CONNECTION = 2;

        Session session() {
            return new Session(username, token, iat == null ? 0 : iat, jiraToken != null && !jiraToken.isBlank(), lid);
        }

        @Override
        public String toString() {
            return "Payload[username=" + username + ", token=" + Secrets.masked(token) + ", jiraToken="
                + Secrets.masked(jiraToken) + ", iat=" + iat + ", v=" + v + ", lid=" + lid + "]";
        }
    }
}
