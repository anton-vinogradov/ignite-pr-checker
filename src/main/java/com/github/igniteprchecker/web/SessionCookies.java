package com.github.igniteprchecker.web;

import com.github.igniteprchecker.session.SessionCodec;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie as the browser gets it. Chrome keeps a cookie at most 400 days from the response that set it,
 * whatever it asks for, so every browser is asked for that, and a request with a cookie older than
 * {@link #RENEW_AFTER} gets it again: a login in use does not end on its own.
 */
final class SessionCookies {
    /** The longest Chrome keeps a cookie. */
    static final Duration MAX_AGE = Duration.ofDays(400);

    /** How old a cookie gets before a request renews it; most requests set no cookie. */
    static final Duration RENEW_AFTER = Duration.ofDays(30);

    private SessionCookies() {
    }

    /** The cookie carrying an encoded session. */
    static ResponseCookie issued(String value, boolean secure) {
        return base(value, secure).maxAge(MAX_AGE).build();
    }

    /** The cookie that removes the session from the browser. */
    static ResponseCookie cleared(boolean secure) {
        return base("", secure).maxAge(0).build();
    }

    /** Whether the session's cookie is old enough to be issued again. */
    static boolean due(SessionCodec.Session session, long nowMs) {
        return nowMs - session.issuedAt() > RENEW_AFTER.toMillis();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value, boolean secure) {
        return ResponseCookie.from(AuthInterceptor.COOKIE, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/");
    }
}
