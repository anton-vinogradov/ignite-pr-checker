package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards protected endpoints: resolves the session cookie and exposes the user's token as a request attribute.
 * A session whose TeamCity token TeamCity has since rejected (401) is refused: the cookie never expires, so
 * otherwise a revoked token would keep working here for good.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final UserDirectory users;

    public static final String COOKIE = "IPRC_SESSION";
    public static final String TOKEN_ATTR = "tcToken";
    public static final String USER_ATTR = "tcUser";
    public static final String JIRA_ATTR = "jiraToken";
    public static final String GH_ATTR = "ghToken";

    static final String REVOKED = "TeamCity rejected your token — log in again";

    private final SessionCodec codec;
    private final Warmer warmer;

    public AuthInterceptor(SessionCodec codec, Warmer warmer, UserDirectory users) {
        this.users = users;
        this.codec = codec;
        this.warmer = warmer;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        Optional<SessionCodec.Session> session = cookie(req).flatMap(codec::decode);
        if (session.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "login required");

            return false;
        }

        if (warmer.tokenRevoked(session.get().token())) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("{\"error\":\"" + REVOKED + "\"}");

            return false;
        }

        req.setAttribute(TOKEN_ATTR, session.get().token());
        req.setAttribute(USER_ATTR, session.get().username());
        if (session.get().jiraToken() != null)
            req.setAttribute(JIRA_ATTR, session.get().jiraToken());
        if (session.get().ghToken() != null)
            req.setAttribute(GH_ATTR, session.get().ghToken());
        users.touch(session.get().username());
        warmer.offerToken(session.get().token());

        return true;
    }

    /** The session of a request to a public endpoint, if it carries one that would pass this guard. */
    public Optional<SessionCodec.Session> signedIn(HttpServletRequest req) {
        return cookie(req).flatMap(codec::decode).filter(s -> !warmer.tokenRevoked(s.token()));
    }

    private static Optional<String> cookie(HttpServletRequest req) {
        if (req.getCookies() == null)
            return Optional.empty();

        for (Cookie c : req.getCookies()) {
            if (COOKIE.equals(c.getName()))
                return Optional.of(c.getValue());
        }

        return Optional.empty();
    }
}
