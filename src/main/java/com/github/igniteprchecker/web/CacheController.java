package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.AnalysisCache;
import com.github.igniteprchecker.analysis.Warmer;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the operator drop the shared analysis caches from the status page ("Flush caches"). */
@RestController
@RequestMapping("/api")
public class CacheController {
    private final AnalysisCache cache;
    private final Warmer warmer;
    private final AdminActions admin;

    public CacheController(AnalysisCache cache, Warmer warmer, AdminActions admin) {
        this.cache = cache;
        this.warmer = warmer;
        this.admin = admin;
    }

    @PostMapping("/flush-caches")
    public ResponseEntity<?> flush(@RequestAttribute(AuthInterceptor.USER_ATTR) String user) {
        Optional<AdminActions.Refusal> refused = admin.claim(user, AdminActions.Action.FLUSH);
        if (refused.isPresent())
            return refused.get().response();

        AnalysisCache.Cleared cleared = cache.clear();
        warmer.triggerWarm(); // refill the newest PRs in the background so visitors don't hit a cold recompute

        return ResponseEntity.ok(cleared);
    }
}
