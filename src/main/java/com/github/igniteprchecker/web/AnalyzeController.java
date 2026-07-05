package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
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

    /** Blocker changes vs the previous run (null when nothing to compare) + the per-build blocker trend. */
    @GetMapping("/delta")
    public DeltaResponse delta(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return new DeltaResponse(deltas.delta(pr), deltas.history(pr));
    }

    /** The /api/delta payload: the two-run comparison and the blocker-count history. */
    public record DeltaResponse(RunDeltaStore.Delta delta, List<RunDeltaStore.Point> history) {
    }

    private static ResponseEntity<?> respond(int pr, Optional<AnalysisResult> result) {
        return result.<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "no RunAll build found for PR " + pr)));
    }
}
