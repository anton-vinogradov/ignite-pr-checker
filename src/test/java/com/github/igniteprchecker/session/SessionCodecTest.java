package com.github.igniteprchecker.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.SessionProperties;
import org.junit.jupiter.api.Test;

/**
 * The session cookie survives a restart under the same secret and nothing else. v1.36.0 and earlier put the user's
 * JIRA token into it for the visa buttons: such a cookie still logs in, and no cookie issued since carries one.
 */
class SessionCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private SessionCodec codec(String secret) {
        return new SessionCodec(new SessionProperties(false, secret), mapper);
    }

    @Test
    void roundTrip() {
        SessionCodec codec = codec("top-secret");

        SessionCodec.Session session = new SessionCodec.Session("bob", "tc-token-xyz");

        assertThat(codec.decode(codec.encode(session))).hasValueSatisfying(s -> {
            assertThat(new SessionCodec.Session(s.username(), s.token())).isEqualTo(session);
            assertThat(s.issuedAt()).isPositive();
        });
    }

    @Test
    void aCookieWithAJiraTokenStillLogsIn() {
        SessionCodec codec = codec("top-secret");
        long issued = System.currentTimeMillis();
        String cookie = codec.encryptString("{\"username\":\"bob\",\"token\":\"tc-token-xyz\","
            + "\"jiraToken\":\"jira-pat\",\"iat\":" + issued + ",\"v\":2}");

        assertThat(codec.decode(cookie)).contains(new SessionCodec.Session("bob", "tc-token-xyz", issued, true));
    }

    @Test
    void noCookieIssuedCarriesAJiraToken() throws Exception {
        SessionCodec codec = codec("top-secret");

        String cookie = codec.encode(new SessionCodec.Session("bob", "tc-token-xyz", 1, true));

        assertThat(mapper.readTree(codec.decryptString(cookie).orElseThrow()).has("jiraToken")).isFalse();
        assertThat(codec.decode(cookie)).hasValueSatisfying(s -> assertThat(s.carriesJira()).isFalse());
    }

    @Test
    void survivesRestartWithSameSecret() {
        // Same secret, a fresh instance == a server restart. The old cookie must still decode.
        String cookie = codec("stable-secret").encode("bob", "tc-token-xyz");

        assertThat(codec("stable-secret").decode(cookie)).isPresent();
    }

    @Test
    void rejectsCookieFromDifferentSecret() {
        String cookie = codec("secret-A").encode("bob", "tc-token-xyz");

        assertThat(codec("secret-B").decode(cookie)).isEmpty();
    }

    /**
     * The tampered cookie had its last two characters replaced with "xx": with a fresh IV each time, the cookie that
     * already ended so, or differed only in the unused bits of the last character, decoded fine about once in a
     * thousand runs, and failed a release build.
     */
    @Test
    void rejectsTamperedAndMissing() {
        SessionCodec codec = codec("top-secret");
        String cookie = codec.encode(new SessionCodec.Session("bob", "tc-token-xyz"));
        int mid = cookie.length() / 2;
        char other = cookie.charAt(mid) == 'A' ? 'B' : 'A';

        assertThat(codec.decode(cookie.substring(0, mid) + other + cookie.substring(mid + 1))).isEmpty();
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("")).isEmpty();
        assertThat(codec.decode("not-base64-$$$")).isEmpty();
    }
}
