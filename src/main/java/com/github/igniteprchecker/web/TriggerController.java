package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/** Triggers builds for a PR on TeamCity (using the logged-in user's token) and lists current runs. */
@RestController
@RequestMapping("/api")
public class TriggerController {
    private final TcClient tc;
    private final BlockerAnalyzer analyzer;
    private final RerunTracker reruns;

    public TriggerController(TcClient tc, BlockerAnalyzer analyzer, RerunTracker reruns) {
        this.tc = tc;
        this.analyzer = analyzer;
        this.reruns = reruns;
    }

    /** Queue the whole RunAll chain. {@code top} puts it at the head of the queue. */
    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(@RequestParam int pr, @RequestParam(defaultValue = "false") boolean top,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        try {
            return ResponseEntity.ok(Map.of("triggered", List.of(brief(track(pr, tc.triggerRunAll(token, pr, top))))));
        }
        catch (RestClientResponseException e) {
            return teamCityError(e);
        }
    }

    /** Re-run only the suites that contain the current blockers. */
    @PostMapping("/rerun-blockers")
    public ResponseEntity<?> rerunBlockers(@RequestParam int pr, @RequestParam(defaultValue = "false") boolean top,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        Optional<AnalysisResult> res = analyzer.analyze(token, pr);
        if (res.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "no RunAll build found for PR " + pr));

        List<String> suites = res.get().blockers().stream()
            .map(TestVerdict::suite)
            .filter(s -> s != null && !s.isBlank())
            .distinct()
            .toList();

        if (suites.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "no blocker suites to re-run"));

        try {
            List<Map<String, Object>> triggered = suites.stream()
                .map(suite -> brief(track(pr, tc.triggerBuild(token, suite, pr, top))))
                .toList();

            return ResponseEntity.ok(Map.of("triggered", triggered));
        }
        catch (RestClientResponseException e) {
            return teamCityError(e);
        }
    }

    /** Re-run a single suite (buildType) for the PR — backs the per-suite Rerun buttons. */
    @PostMapping("/rerun-suite")
    public ResponseEntity<?> rerunSuite(@RequestParam int pr, @RequestParam String suite,
        @RequestParam(defaultValue = "false") boolean top,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        if (suite.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "missing suite"));

        try {
            return ResponseEntity.ok(Map.of("triggered", List.of(brief(track(pr, tc.triggerBuild(token, suite, pr, top))))));
        }
        catch (RestClientResponseException e) {
            return teamCityError(e);
        }
    }

    /** Tool-triggered builds that are still queued/running (public; feeds the live suite chips). */
    @GetMapping("/reruns")
    public List<RerunTracker.ActiveRerun> reruns() {
        return reruns.active();
    }

    /** Builds the user launched (RunAll and re-run suites) currently queued or running for the PR. */
    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        // Seed the rerun tracker from what's actually live: recovers entries lost to a restart and
        // picks up builds triggered outside the tool (straight from the TeamCity UI).
        return tc.currentUserBuilds(token, pr).stream().map(b -> brief(track(pr, b))).toList();
    }

    /** Cancel every user-launched build (RunAll or re-run suite) currently queued or running for the PR. */
    @PostMapping("/cancel-all")
    public ResponseEntity<?> cancelAll(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        try {
            return ResponseEntity.ok(Map.of("cancelled", tc.cancelUserBuilds(token, pr)));
        }
        catch (RestClientResponseException e) {
            return teamCityError(e);
        }
    }

    /** Registers a queued/running build with the rerun tracker, passing the build through. */
    private TcModel.Build track(int pr, TcModel.Build b) {
        reruns.record(pr, b);

        return b;
    }

    private static Map<String, Object> brief(TcModel.Build b) {
        String name = b.buildType() != null && b.buildType().name() != null ? b.buildType().name() : "";

        return Map.of(
            "buildId", b.id(),
            "state", b.state() == null ? "queued" : b.state(),
            "name", name,
            "btId", b.buildTypeId() == null ? "" : b.buildTypeId(),
            "webUrl", b.webUrl() == null ? "" : b.webUrl());
    }

    private static ResponseEntity<?> teamCityError(RestClientResponseException e) {
        return ResponseEntity.status(502).body(Map.of("error", "TeamCity rejected the request (" + e.getStatusCode() + ")"));
    }
}
