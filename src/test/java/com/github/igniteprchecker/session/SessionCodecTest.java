package com.github.igniteprchecker.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.SessionProperties;
import org.junit.jupiter.api.Test;

class SessionCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private SessionCodec codec(String secret) {
        return new SessionCodec(new SessionProperties(false, secret), mapper);
    }

    @Test
    void roundTrip() {
        SessionCodec codec = codec("top-secret");

        String cookie = codec.encode("bob", "tc-token-xyz", "jira-pat");
        SessionCodec.Session s = codec.decode(cookie).orElseThrow();

        assertThat(s.username()).isEqualTo("bob");
        assertThat(s.token()).isEqualTo("tc-token-xyz");
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

    @Test
    void rejectsTamperedAndMissing() {
        SessionCodec codec = codec("top-secret");
        String cookie = codec.encode("bob", "tc-token-xyz", "jira-pat");

        assertThat(codec.decode(cookie.substring(0, cookie.length() - 2) + "xx")).isEmpty();
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("")).isEmpty();
        assertThat(codec.decode("not-base64-$$$")).isEmpty();
    }
}
