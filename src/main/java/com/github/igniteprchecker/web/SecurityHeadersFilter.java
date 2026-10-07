package com.github.igniteprchecker.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Browser hardening headers on every response. Set here rather than in the reverse proxy: more than one
 * site in front of the app proxies to it, and a header added to only one of them is easy to miss.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    /**
     * Scripts only from the app's own {@code static/*.js}: markup that slips past {@code esc()} (a PR title, a test
     * name) cannot run an inline handler or script in a committer's session.
     */
    static final String CSP = "script-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; "
        + "form-action 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        res.setHeader("Content-Security-Policy", CSP);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "same-origin");
        chain.doFilter(req, res);
    }
}
