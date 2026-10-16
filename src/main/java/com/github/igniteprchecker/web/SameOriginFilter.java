package com.github.igniteprchecker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses a request that changes something at /api/ when a page of another site sends it. The browser sends the
 * session cookie with such a request: SameSite=Lax lets a POST through from a host of the same site, and the second
 * name the checker answers to, v888764.hosted-by-vdsina.com, shares its site with every other host there. A form on
 * such a page could switch on the visitor's options, which then store the visitor's TeamCity and JIRA tokens. The
 * browser names where a request comes from in Sec-Fetch-Site; a browser too old for it sends Origin, compared with the
 * origin the request went to, as APP_PUBLIC_URL may be empty. A request with neither, as a script sends, goes through.
 */
@Component
public class SameOriginFilter extends OncePerRequestFilter {
    static final String REFUSED = "{\"error\":\"Refused: this request came from a page of another site\"}";

    /** The methods that change nothing; the OAuth callback, a GET, comes from GitHub's page. */
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/") || SAFE.contains(req.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        if (fromAnotherSite(req)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write(REFUSED);

            return;
        }

        chain.doFilter(req, res);
    }

    private static boolean fromAnotherSite(HttpServletRequest req) {
        String site = req.getHeader("Sec-Fetch-Site");
        if (site != null)
            return !site.equals("same-origin") && !site.equals("none");

        String origin = req.getHeader("Origin");

        return origin != null && !origin.equalsIgnoreCase(targetOrigin(req));
    }

    /** The origin the browser sent the request to, as the proxy in front of the checker passes it on. */
    private static String targetOrigin(HttpServletRequest req) {
        return first(req.getHeader("X-Forwarded-Proto"), req.getScheme()) + "://"
            + first(req.getHeader("X-Forwarded-Host"), req.getHeader("Host"));
    }

    /** The first of a proxy header's comma-separated values, or {@code otherwise} without the header. */
    private static String first(String header, String otherwise) {
        return header == null || header.isBlank() ? otherwise : header.split(",")[0].strip();
    }
}
