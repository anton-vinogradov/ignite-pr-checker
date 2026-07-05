package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.AnalysisCache;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, project-wide "fix master" queue: tests failing on master, ranked by flakiness (backs the "Flaky tests" page). */
@RestController
@RequestMapping("/api")
public class LeaderboardController {
    private final AnalysisCache cache;

    public LeaderboardController(AnalysisCache cache) {
        this.cache = cache;
    }

    @GetMapping("/top-flaky")
    public List<AnalysisCache.TopFlaky> topFlaky(@RequestParam(defaultValue = "30") int limit) {
        return cache.topFlaky(Math.min(Math.max(limit, 1), 100));
    }
}
