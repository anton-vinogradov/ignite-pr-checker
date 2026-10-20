package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Asks TeamCity whether the session's token still works before an action that changes whom the checker acts for or
 * what the service does: linking or unlinking a GitHub account, switching standing options, which may store a JIRA or
 * GitHub token on the server, ending the user's other logins, posting a visa with the user's stored JIRA token,
 * restarting, updating or flushing the service. The login guard knows a token is revoked only once a call under it
 * failed, so a copied cookie, or one whose token was revoked in TeamCity a moment ago, passes it. A GitHub account
 * linked through such a cookie would run /run-all under its owner's TeamCity account even after the owner logged out,
 * and such a cookie could log its owner out of the browsers whose tokens work, or post visas in their name. A 401
 * refuses the action and, from then on, the token's sessions, as {@link ApiExceptionHandler} does. A firewall 403 or an
 * outage lets the action through. The actions are rare, so one call each costs little.
 */
@Component
public class LiveTokenGuard implements HandlerInterceptor {
    /** What a refused action answers, as {@link ApiExceptionHandler} answers a refused call. */
    private static final String REFUSED = "{\"error\":\"" + ApiExceptionHandler.TOKEN_REJECTED
        + "\",\"tokenRejected\":true}";

    private final TcClient tc;

    private final Warmer warmer;

    public LiveTokenGuard(TcClient tc, Warmer warmer) {
        this.tc = tc;
        this.warmer = warmer;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws IOException {
        if (HttpMethod.POST.matches(req.getMethod())
            && req.getAttribute(AuthInterceptor.TOKEN_ATTR) instanceof String token && refusedNow(tc, warmer, token)) {
            AuthInterceptor.refuse(resp, REFUSED);

            return false;
        }

        return true;
    }

    /** Whether TeamCity refuses the token now (401); a refusal is recorded, so the guard refuses its sessions. */
    static boolean refusedNow(TcClient tc, Warmer warmer, String token) {
        if (!tc.tokenRejected(token))
            return false;

        warmer.revoke(token);

        return true;
    }
}
