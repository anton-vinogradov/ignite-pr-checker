package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.FlakyStats;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, project-wide "fix master" queue: tests failing on master, ranked by flakiness (backs the "Flaky tests" page). */
@RestController
@RequestMapping("/api")
public class LeaderboardController {
    private final FlakyStats flaky;

    public LeaderboardController(FlakyStats flaky) {
        this.flaky = flaky;
    }

    /** {@code tracked} lets the UI tell "nothing recorded yet" from a genuinely clean master. */
    @GetMapping("/top-flaky")
    public Map<String, Object> topFlaky(@RequestParam(defaultValue = "30") int limit) {
        return Map.of(
            "tracked", flaky.trackedCount(),
            "tests", flaky.top(Math.min(Math.max(limit, 1), 100)));
    }
}
