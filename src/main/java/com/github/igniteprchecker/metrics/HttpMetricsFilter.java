package com.github.igniteprchecker.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Records the latency/outcome of each served {@code /api/*} request for the status page. */
@Component
public class HttpMetricsFilter extends OncePerRequestFilter {
    private final Metrics metrics;

    public HttpMetricsFilter(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        long t0 = System.nanoTime();
        try {
            chain.doFilter(req, res);
        }
        finally {
            String path = req.getRequestURI(); // path only, no query string
            if (path.startsWith("/api/")) {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                metrics.recordHttp(path, res.getStatus() < 500, ms);
            }
        }
    }
}
