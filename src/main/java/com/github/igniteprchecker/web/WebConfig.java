package com.github.igniteprchecker.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies {@link AuthInterceptor} to the endpoints that require a logged-in user. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth;

    public WebConfig(AuthInterceptor auth) {
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth)
            .addPathPatterns("/api/analyze", "/api/refresh", "/api/trigger", "/api/rerun-blockers",
                "/api/rerun-suite", "/api/rerun-suites", "/api/runs", "/api/cancel-all", "/api/update", "/api/restart", "/api/users", "/api/jira-token", "/api/github-token", "/api/github-login", "/api/jira-visa", "/api/auto-visa", "/api/auto-visa-cancel", "/api/auto-visa-all", "/api/test-details",
                "/api/flush-caches", "/api/delta", "/api/causes", "/api/progress");
    }
}
