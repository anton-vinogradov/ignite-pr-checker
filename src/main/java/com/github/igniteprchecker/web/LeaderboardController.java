package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.FlakyStats;
import com.github.igniteprchecker.analysis.MasterSuites;
import com.github.igniteprchecker.config.AnalysisProperties;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, project-wide "fix master" queue: tests failing on master, ranked by flakiness, and the suites whose master
 * runs end without a test verdict (backs the "Master" page).
 */
@RestController
@RequestMapping("/api")
public class LeaderboardController {
    private final FlakyStats flaky;

    private final MasterSuites suites;

    private final AnalysisProperties analysis;

    public LeaderboardController(FlakyStats flaky, MasterSuites suites, AnalysisProperties analysis) {
        this.flaky = flaky;
        this.suites = suites;
        this.analysis = analysis;
    }

    /**
     * {@code tracked} lets the UI tell "nothing recorded yet" from a genuinely clean master; {@code muted} is how
     * many tracked tests are left out as muted, {@code prDays} how far back the PR counts go, and {@code window} how
     * many master runs a test's history holds at most. {@code limit} cuts each group of {@code tests}; the three group
     * counts precede that limit. {@code causes} are those of the newly broken tests among {@code tests}, which name
     * theirs by the commit of their first failure. {@code suites} are the suites with a problem run among their master
     * runs, whose letters {@code suiteKinds} names; {@code suiteChains} is how many master chains those runs were read
     * from, 0 while none was: no suite is then known to have reported its tests.
     */
    @GetMapping("/top-flaky")
    public Map<String, Object> topFlaky(@RequestParam(defaultValue = "30") int limit) {
        FlakyStats.GroupSizes sizes = flaky.groupSizes();
        List<FlakyStats.TopFlaky> tests = flaky.top(Math.min(Math.max(limit, 1), 100));

        return Map.ofEntries(
            Map.entry("tracked", flaky.trackedCount()),
            Map.entry("tests", tests),
            Map.entry("causes", flaky.causes(tests)),
            Map.entry("attentionCount", sizes.attention()),
            Map.entry("flakyCount", sizes.flaky()),
            Map.entry("brokenCount", sizes.broken()),
            Map.entry("muted", flaky.mutedCount()),
            Map.entry("prDays", FlakyStats.RETAIN_DAYS),
            Map.entry("window", analysis.historyDepth()),
            Map.entry("suites", suites.problemSuites()),
            Map.entry("suiteKinds", MasterSuites.kinds()),
            Map.entry("suiteChains", suites.chainsRead()));
    }
}
