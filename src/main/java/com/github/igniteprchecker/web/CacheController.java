package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.AnalysisCache;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets a logged-in user drop the shared analysis caches from the status page ("Flush caches"). */
@RestController
@RequestMapping("/api")
public class CacheController {
    private final AnalysisCache cache;

    public CacheController(AnalysisCache cache) {
        this.cache = cache;
    }

    /** The {@code token} attribute is only present for authenticated requests, so this needs a login. */
    @PostMapping("/flush-caches")
    public AnalysisCache.Cleared flush(@RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return cache.clear();
    }
}
