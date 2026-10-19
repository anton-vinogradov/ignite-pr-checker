package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards protected endpoints: resolves the session cookie and exposes the user's token and login as request
 * attributes. A session whose TeamCity token TeamCity has since rejected (401) is refused: the session does not time
 * out, so otherwise a revoked token would keep working here for good. The refusal carries {@code tokenRejected}, as
 * {@link ApiExceptionHandler}'s does, so the page can tell it from a missing session and say why it logs out. A
 * session whose login has ended is refused with {@code sessionEnded}; a cookie of an earlier version gets its login
 * here, see {@link Logins}. A cookie older than {@link SessionCookies#RENEW_AFTER} is issued again with the answer, so
 * the browser keeps a login in use, and so is one under the name earlier versions gave it over HTTPS, under the new
 * name, one with a JIRA token, without it, as the checker does not use that token, and one that names no login, with
 * its login.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final UserDirectory users;

    /** The session cookie's name over plain HTTP, and over HTTPS before {@link SessionCookies#HOST_COOKIE}. */
    public static final String COOKIE = "IPRC_SESSION";
    public static final String TOKEN_ATTR = "tcToken";
    public static final String USER_ATTR = "tcUser";
    /** The id of the request's login, see {@link Logins}. */
    public static final String LOGIN_ATTR = "tcLogin";

    static final String REVOKED = "TeamCity rejected your token — log in again";

    /**
     * Why the browser is at the login form when its login has ended. It names no cause: the user may have ended it from
     * another browser, a login past {@link Logins#KEPT_PER_USER} may have ended it, or the server may have lost it.
     */
    static final String ENDED = "Your login in this browser has ended. Please log in again.";

    private final SessionCodec codec;
    private final Warmer warmer;
    private final Logins logins;
    private final StandingVisas standing;
    private final boolean cookieSecure;

    @Autowired
    public AuthInterceptor(SessionCodec codec, Warmer warmer, UserDirectory users, Logins logins,
        StandingVisas standing, @Value("${session.cookie-secure:false}") boolean cookieSecure) {
        this.users = users;
        this.codec = codec;
        this.warmer = warmer;
        this.logins = logins;
        this.standing = standing;
        this.cookieSecure = cookieSecure;
    }

    /** The guard of a local run, whose cookie goes over plain HTTP. */
    AuthInterceptor(SessionCodec codec, Warmer warmer, UserDirectory users, Logins logins, StandingVisas standing) {
        this(codec, warmer, users, logins, standing, false);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        Optional<SessionCookies.Carried> carried = SessionCookies.carried(req, codec, cookieSecure);
        Optional<SessionCodec.Session> session = carried.map(SessionCookies.Carried::session);
        if (session.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "login required");

            return false;
        }

        if (warmer.tokenRevoked(session.get().token())) {
            refuse(resp, "{\"error\":\"" + REVOKED + "\",\"tokenRejected\":true}");

            return false;
        }
        long checkedAt = System.currentTimeMillis();
        Optional<Logins.Login> login = logins.enter(session.get(), browser(req));
        if (login.isEmpty()) {
            refuse(resp, "{\"error\":\"" + ENDED + "\",\"sessionEnded\":true}");

            return false;
        }

        String user = session.get().username();
        String token = session.get().token();
        reissue(carried.get(), session.get().withLogin(login.get().id()), checkedAt, req, resp);
        req.setAttribute(TOKEN_ATTR, token);
        req.setAttribute(USER_ATTR, user);
        req.setAttribute(LOGIN_ATTR, login.get().id());
        users.touch(user);
        if (session.get().carriesJira() && !standing.visaOn(user))
            users.sawJiraInCookie(user);
        warmer.offerToken(user, token, login.get().id());
        standing.tcTokenOffered(user, token);

        return true;
    }

    /** The session of a request to a public endpoint, if it carries one that would pass this guard. */
    public Optional<SessionCodec.Session> signedIn(HttpServletRequest req) {
        return SessionCookies.carried(req, codec, cookieSecure).map(SessionCookies.Carried::session)
            .filter(s -> !warmer.tokenRevoked(s.token()) && logins.live(s));
    }

    /**
     * Issues the session's cookie again, in its {@code login}, when it is old enough, names no login or came under the
     * name earlier versions gave it, and removes a cookie under that name; see {@link SessionCookies}.
     */
    private void reissue(SessionCookies.Carried carried, SessionCodec.Session login, long checkedAt,
        HttpServletRequest req, HttpServletResponse resp) {
        if (carried.earlier() || SessionCookies.due(carried.session(), checkedAt)) {
            resp.addHeader(HttpHeaders.SET_COOKIE,
                SessionCookies.issued(codec.encode(login, checkedAt), cookieSecure).toString());
        }
        if (SessionCookies.earlierLeft(req, cookieSecure))
            resp.addHeader(HttpHeaders.SET_COOKIE, SessionCookies.clearedEarlier().toString());
    }

    /** The label of the request's browser, for a login made for it; see {@link BrowserLabel}. */
    static String browser(HttpServletRequest req) {
        return BrowserLabel.of(req.getHeader(HttpHeaders.USER_AGENT));
    }

    static void refuse(HttpServletResponse resp, String json) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(json);
    }
}
