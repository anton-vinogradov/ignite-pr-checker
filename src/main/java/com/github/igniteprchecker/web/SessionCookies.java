package com.github.igniteprchecker.web;

import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie as the browser gets it. Chrome keeps a cookie at most 400 days from the response that set it,
 * whatever it asks for, so every browser is asked for that, and a request with a cookie older than
 * {@link #RENEW_AFTER} gets it again: a login in use does not end on its own, and one left unused ends 400 days
 * after its last use, give or take a day.
 *
 * <p>Over HTTPS the cookie is named {@link #HOST_COOKIE}: a browser keeps a {@code __Host-} cookie only as this host
 * set it, Secure, for the whole site and for no other host. A page of another host under the same site, such as a
 * neighbour of the second name the checker answers to, cannot plant its own session in the visitor's browser then.
 * Over plain HTTP, on a local run, it keeps the name {@link AuthInterceptor#COOKIE}, as a browser refuses a
 * {@code __Host-} cookie without Secure.
 */
final class SessionCookies {
    /** The longest Chrome keeps a cookie. */
    static final Duration MAX_AGE = Duration.ofDays(400);

    /** How old a cookie gets before a request renews it; most requests set no cookie. */
    static final Duration RENEW_AFTER = Duration.ofDays(1);

    /** The name of the session cookie over HTTPS. */
    static final String HOST_COOKIE = "__Host-" + AuthInterceptor.COOKIE;

    private SessionCookies() {
    }

    /** The cookie carrying an encoded session. */
    static ResponseCookie issued(String value, boolean secure) {
        return base(name(secure), value, secure).maxAge(MAX_AGE).build();
    }

    /** The cookie that removes the session from the browser. */
    static ResponseCookie cleared(boolean secure) {
        return base(name(secure), "", secure).maxAge(0).build();
    }

    /** The cookie that removes a session cookie under the name earlier versions gave it over HTTPS. */
    static ResponseCookie clearedEarlier() {
        return base(AuthInterceptor.COOKIE, "", true).maxAge(0).build();
    }

    /** Whether the session's cookie is old enough to be issued again. */
    static boolean due(SessionCodec.Session session, long nowMs) {
        return nowMs - session.issuedAt() > RENEW_AFTER.toMillis();
    }

    /**
     * The session a request's cookie carries. Over HTTPS a cookie under the old name counts only as an earlier version
     * issued it, and for {@link SessionCodec#EARLIER_TAKEN_FOR} after its issue, so a login in use survives the upgrade;
     * a copy of a newer cookie set under the old name does not count.
     */
    static Optional<Carried> carried(HttpServletRequest req, SessionCodec codec, boolean secure) {
        Optional<SessionCodec.Session> named = value(req, name(secure)).flatMap(codec::decode);
        if (named.isPresent() || !secure)
            return named.map(s -> new Carried(s, false));

        return value(req, AuthInterceptor.COOKIE).flatMap(codec::decodeEarlier).map(s -> new Carried(s, true));
    }

    /** Whether the request still carries a cookie under the name earlier versions gave it over HTTPS. */
    static boolean earlierLeft(HttpServletRequest req, boolean secure) {
        return secure && value(req, AuthInterceptor.COOKIE).isPresent();
    }

    /**
     * Whether the request carries, under the name earlier versions gave it over HTTPS, a cookie an earlier version
     * issued; {@link #carried} takes it only within {@link SessionCodec#EARLIER_TAKEN_FOR} of its issue.
     */
    static boolean earlierIssued(HttpServletRequest req, SessionCodec codec, boolean secure) {
        return secure && value(req, AuthInterceptor.COOKIE).filter(codec::issuedByEarlier).isPresent();
    }

    private static String name(boolean secure) {
        return secure ? HOST_COOKIE : AuthInterceptor.COOKIE;
    }

    private static Optional<String> value(HttpServletRequest req, String name) {
        if (req.getCookies() == null)
            return Optional.empty();

        for (Cookie c : req.getCookies()) {
            if (name.equals(c.getName()))
                return Optional.of(c.getValue());
        }

        return Optional.empty();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String name, String value, boolean secure) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/");
    }

    /** A session a request carries; {@code earlier}: under the name earlier versions gave the cookie over HTTPS. */
    record Carried(SessionCodec.Session session, boolean earlier) {
    }
}
