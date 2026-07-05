package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.AnalysisCache;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, project-wide view of the tests blocking the most open PRs (backs the "Top blockers" page). */
@RestController
@RequestMapping("/api")
public class LeaderboardController {
    private final AnalysisCache cache;

    public LeaderboardController(AnalysisCache cache) {
        this.cache = cache;
    }

    @GetMapping("/top-blockers")
    public List<AnalysisCache.TopBlocker> topBlockers(@RequestParam(defaultValue = "30") int limit) {
        return cache.topBlockers(Math.min(Math.max(limit, 1), 100));
    }
}
