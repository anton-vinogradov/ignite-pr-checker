package com.github.igniteprchecker.web;

import com.github.igniteprchecker.session.SessionStore;
import com.github.igniteprchecker.session.UserSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Guards protected endpoints: resolves the session cookie and exposes the user's token as a request attribute. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String COOKIE = "IPRC_SESSION";
    public static final String TOKEN_ATTR = "tcToken";
    public static final String USER_ATTR = "tcUser";

    private final SessionStore store;

    public AuthInterceptor(SessionStore store) {
        this.store = store;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        Optional<UserSession> session = cookie(req).flatMap(store::get);
        if (session.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "login required");

            return false;
        }

        req.setAttribute(TOKEN_ATTR, session.get().token());
        req.setAttribute(USER_ATTR, session.get().username());

        return true;
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
