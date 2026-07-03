package com.github.igniteprchecker.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.config.SessionProperties;
import org.junit.jupiter.api.Test;

class SessionCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private SessionCodec codec(int ttlMinutes, String secret) {
        return new SessionCodec(new SessionProperties(ttlMinutes, false, secret), mapper);
    }

    @Test
    void roundTrip() {
        SessionCodec codec = codec(60, "top-secret");

        String cookie = codec.encode("bob", "tc-token-xyz");
        SessionCodec.Session s = codec.decode(cookie).orElseThrow();

        assertThat(s.username()).isEqualTo("bob");
        assertThat(s.token()).isEqualTo("tc-token-xyz");
    }

    @Test
    void survivesRestartWithSameSecret() {
        // Same secret, a fresh instance == a server restart. The old cookie must still decode.
        String cookie = codec(60, "stable-secret").encode("bob", "tc-token-xyz");

        assertThat(codec(60, "stable-secret").decode(cookie)).isPresent();
    }

    @Test
    void rejectsCookieFromDifferentSecret() {
        String cookie = codec(60, "secret-A").encode("bob", "tc-token-xyz");

        assertThat(codec(60, "secret-B").decode(cookie)).isEmpty();
    }

    @Test
    void rejectsTamperedAndMissing() {
        SessionCodec codec = codec(60, "top-secret");
        String cookie = codec.encode("bob", "tc-token-xyz");

        assertThat(codec.decode(cookie.substring(0, cookie.length() - 2) + "xx")).isEmpty();
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("")).isEmpty();
        assertThat(codec.decode("not-base64-$$$")).isEmpty();
    }

    @Test
    void rejectsExpired() {
        SessionCodec codec = codec(-1, "top-secret");

        assertThat(codec.decode(codec.encode("bob", "tc-token-xyz"))).isEmpty();
    }
}
