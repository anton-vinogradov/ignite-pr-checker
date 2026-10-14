package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards protected endpoints: resolves the session cookie and exposes the user's token as a request attribute.
 * A session whose TeamCity token TeamCity has since rejected (401) is refused: the session does not time out, so
 * otherwise a revoked token would keep working here for good. The refusal carries {@code tokenRejected}, as
 * {@link ApiExceptionHandler}'s does, so the page can tell it from a missing session and say why it logs out. A
 * session issued before its user logged out is refused with {@code sessionEnded}. A cookie older than
 * {@link SessionCookies#RENEW_AFTER} is issued again with the answer, so the browser keeps a login in use.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final UserDirectory users;

    public static final String COOKIE = "IPRC_SESSION";
    public static final String TOKEN_ATTR = "tcToken";
    public static final String USER_ATTR = "tcUser";
    public static final String JIRA_ATTR = "jiraToken";
    public static final String GH_ATTR = "ghToken";

    /** The whole session: what a re-issued cookie starts from, so the fields it does not change stay. */
    public static final String SESSION_ATTR = "session";

    /**
     * When the guard found the session live (epoch ms): a cookie re-issued from it says it was issued then, so a logout
     * while the request waits on JIRA or GitHub ends that cookie too.
     */
    public static final String CHECKED_AT_ATTR = "sessionCheckedAt";

    static final String REVOKED = "TeamCity rejected your token — log in again";

    static final String ENDED = "You logged out of the checker, here or in another browser — log in again";

    private final SessionCodec codec;
    private final Warmer warmer;
    private final StandingVisas standing;

    @Value("${session.cookie-secure:false}")
    private boolean cookieSecure;

    public AuthInterceptor(SessionCodec codec, Warmer warmer, UserDirectory users, StandingVisas standing) {
        this.users = users;
        this.codec = codec;
        this.warmer = warmer;
        this.standing = standing;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        Optional<SessionCodec.Session> session = cookie(req).flatMap(codec::decode);
        if (session.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "login required");

            return false;
        }

        if (warmer.tokenRevoked(session.get().token())) {
            refuse(resp, "{\"error\":\"" + REVOKED + "\",\"tokenRejected\":true}");

            return false;
        }
        long checkedAt = System.currentTimeMillis();
        if (users.ended(session.get())) {
            refuse(resp, "{\"error\":\"" + ENDED + "\",\"sessionEnded\":true}");

            return false;
        }

        renewIfDue(session.get(), checkedAt, resp);
        req.setAttribute(SESSION_ATTR, session.get());
        req.setAttribute(CHECKED_AT_ATTR, checkedAt);
        req.setAttribute(TOKEN_ATTR, session.get().token());
        req.setAttribute(USER_ATTR, session.get().username());
        if (session.get().jiraToken() != null)
            req.setAttribute(JIRA_ATTR, session.get().jiraToken());
        if (session.get().ghToken() != null)
            req.setAttribute(GH_ATTR, session.get().ghToken());
        users.touch(session.get().username());
        warmer.offerToken(session.get().token());
        standing.tcTokenOffered(session.get().username(), session.get().token());

        return true;
    }

    /** The session of a request to a public endpoint, if it carries one that would pass this guard. */
    public Optional<SessionCodec.Session> signedIn(HttpServletRequest req) {
        return cookie(req).flatMap(codec::decode).filter(s -> !warmer.tokenRevoked(s.token()) && !users.ended(s));
    }

    /** Issues the session's cookie again when it is old enough; see {@link SessionCookies}. */
    private void renewIfDue(SessionCodec.Session session, long checkedAt, HttpServletResponse resp) {
        if (SessionCookies.due(session, checkedAt)) {
            resp.addHeader(HttpHeaders.SET_COOKIE,
                SessionCookies.issued(codec.encode(session, checkedAt), cookieSecure).toString());
        }
    }

    private static void refuse(HttpServletResponse resp, String json) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(json);
    }

    static Optional<String> cookie(HttpServletRequest req) {
        if (req.getCookies() == null)
            return Optional.empty();

        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName()))
                return Optional.of(c.getValue());
        }

        return Optional.empty();
    }
}
