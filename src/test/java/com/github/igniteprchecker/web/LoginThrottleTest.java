package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Every POST /api/login asked ci2 "whoami" from the server's address, with no limit at all. A script hammering
 * the form could get that address blocked by ci2's WAF, and the checker would stop for every user.
 */
class LoginThrottleTest {
    private final AtomicLong now = new AtomicLong(1_000_000);

    private final LoginThrottle throttle = new LoginThrottle(now::get);

    private void after(Duration d) {
        now.addAndGet(d.toMillis());
    }

    /**
     * At 10 attempts per address, about 30 addresses kept the shared 30 TeamCity checks a minute used up, and nobody
     * could log in. At 3 it takes about 100.
     */
    @Test
    void aClientGetsThreeAttemptsPerTenMinutes() {
        for (int i = 0; i < 3; i++)
            assertThat(throttle.admitAttempt("203.0.113.7")).isNull();

        assertThat(throttle.admitAttempt("203.0.113.7")).isEqualTo(Duration.ofMinutes(10));
        assertThat(throttle.admitAttempt("198.51.100.1")).as("another client").isNull();

        after(Duration.ofMinutes(4));
        assertThat(throttle.admitAttempt("203.0.113.7")).isEqualTo(Duration.ofMinutes(6));

        after(Duration.ofMinutes(6));

        assertThat(throttle.admitAttempt("203.0.113.7")).isNull();
    }

    @Test
    void teamCityChecksAreCappedAcrossClients() {
        for (int i = 0; i < 30; i++)
            assertThat(throttle.admitCheck("203.0.113." + i)).isNull();

        assertThat(throttle.admitCheck("198.51.100.1")).isEqualTo(new LoginThrottle.Wait(Duration.ofMinutes(1), true));

        after(Duration.ofMinutes(1));

        assertThat(throttle.admitCheck("198.51.100.1")).isNull();
    }

    /**
     * A login the shared checks turned away used one of its client's 3 attempts, so other clients' logins could use up
     * the attempts of a user who retried as told.
     */
    @Test
    void aLoginTheSharedChecksTurnAwayCostsItsClientNoAttempt() {
        for (int i = 0; i < 30; i++)
            throttle.admitCheck("203.0.113." + i);

        for (int i = 0; i < 5; i++)
            assertThat(throttle.admitCheck("192.0.2.50")).extracting(LoginThrottle.Wait::shared).isEqualTo(true);

        after(Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++)
            assertThat(throttle.admitCheck("192.0.2.50")).isNull();

        assertThat(throttle.admitCheck("192.0.2.50")).isEqualTo(new LoginThrottle.Wait(Duration.ofMinutes(10), false));
        assertThat(throttle.admitAttempt("192.0.2.50")).as("a granted check is an attempt")
            .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aRejectedTokenIsRememberedForAWhile() {
        throttle.rejected("dead-token");

        assertThat(throttle.recentlyRejected("dead-token")).isTrue();
        assertThat(throttle.recentlyRejected("another-token")).isFalse();

        after(Duration.ofMinutes(15));

        assertThat(throttle.recentlyRejected("dead-token")).isFalse();
    }

    @Test
    void forwardedForCountsOnlyFromTheLocalProxy() {
        MockHttpServletRequest viaCaddy = new MockHttpServletRequest();
        viaCaddy.setRemoteAddr("127.0.0.1");
        viaCaddy.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.7");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.1");
        direct.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(LoginThrottle.clientOf(viaCaddy)).isEqualTo("203.0.113.7");
        assertThat(LoginThrottle.clientOf(direct)).isEqualTo("198.51.100.1");
    }
}
