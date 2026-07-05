package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runs the blocker analysis for a PR using the logged-in user's TeamCity token. */
@RestController
@RequestMapping("/api")
public class AnalyzeController {
    private final BlockerAnalyzer analyzer;
    private final RunDeltaStore deltas;

    public AnalyzeController(BlockerAnalyzer analyzer, RunDeltaStore deltas) {
        this.analyzer = analyzer;
        this.deltas = deltas;
    }

    /** Serves the cached analysis (recomputing only on a cache miss). */
    @GetMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return respond(pr, analyzer.analyze(token, pr));
    }

    /** Forces a fresh recompute (ignoring the cache) and returns it — backs the manual refresh button. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return respond(pr, analyzer.forceRefresh(token, pr));
    }

    /** Blocker changes between the PR's two latest runs (null body when there is nothing to compare yet). */
    @GetMapping("/delta")
    public RunDeltaStore.Delta delta(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return deltas.delta(pr);
    }

    private static ResponseEntity<?> respond(int pr, Optional<AnalysisResult> result) {
        return result.<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "no RunAll build found for PR " + pr)));
    }
}
