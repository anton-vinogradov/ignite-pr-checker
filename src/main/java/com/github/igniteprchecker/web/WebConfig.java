package com.github.igniteprchecker.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Applies {@link AuthInterceptor} to the endpoints that require a logged-in user, and {@link LiveTokenGuard} to the
 * actions that must not run on a token TeamCity has since revoked.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    /**
     * What {@link LiveTokenGuard} checks before: GitHub link and unlink, the options, ending other logins, restart,
     * update, flush.
     */
    static final String[] LIVE_TOKEN_ACTIONS = {"/api/github/link", "/api/github/unlink", "/api/auto-visa-all",
        "/api/logins/end", "/api/logins/end-others", "/api/restart", "/api/update", "/api/flush-caches"};

    private final AuthInterceptor auth;

    private final LiveTokenGuard live;

    public WebConfig(AuthInterceptor auth, LiveTokenGuard live) {
        this.auth = auth;
        this.live = live;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth)
            .addPathPatterns("/api/analyze", "/api/refresh", "/api/trigger", "/api/rerun-blockers",
                "/api/rerun-suite", "/api/rerun-suites", "/api/runs", "/api/cancel-all", "/api/update", "/api/restart", "/api/users", "/api/github-token", "/api/github/link", "/api/github/unlink", "/api/visa", "/api/auto-visa-all", "/api/test-details",
                "/api/flush-caches", "/api/delta", "/api/causes", "/api/progress", "/api/pending",
                "/api/settling", "/api/logins", "/api/logins/end", "/api/logins/end-others");
        registry.addInterceptor(auth).addPathPatterns("/api/pr-tests", "/api/pr-title");
        registry.addInterceptor(auth).addPathPatterns("/api/journal");
        registry.addInterceptor(auth).addPathPatterns("/api/token-expiry");
        registry.addInterceptor(auth).addPathPatterns("/api/language");
        registry.addInterceptor(auth).addPathPatterns("/api/your-prs");
        registry.addInterceptor(auth).addPathPatterns("/api/client/your-prs", "/api/client/analyze",
            "/api/client/refresh", "/api/client/runs", "/api/client/trigger", "/api/client/cancel-all",
            "/api/client/settling", "/api/client/visa", "/api/client/pr-title");
        registry.addInterceptor(live).addPathPatterns(LIVE_TOKEN_ACTIONS);
    }
}
