package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.tc.TcClient;
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
    private final TcClient tc;
    private final GithubClient github;

    public AnalyzeController(BlockerAnalyzer analyzer, RunDeltaStore deltas, TcClient tc, GithubClient github) {
        this.analyzer = analyzer;
        this.deltas = deltas;
        this.tc = tc;
        this.github = github;
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

    /** Live progress of an in-flight compute for this PR (empty body when nothing is computing). */
    @GetMapping("/progress")
    public BlockerAnalyzer.Progress progress(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return analyzer.progressOf(pr);
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

    /**
     * Whether the PR head has moved since the analysed build — the verdict would then describe older
     * code. Always fresh (the head can change any time), and cheap: one TeamCity + one GitHub call.
     */
    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        String builtSha = tc.buildRevision(token, build).orElse(null);
        String headSha;
        try {
            headSha = github.prHead(pr).headSha();
        }
        catch (RuntimeException e) {
            headSha = null; // PR gone/merged, or GitHub blip — say nothing rather than a false alarm
        }
        if (builtSha == null || headSha == null || builtSha.equals(headSha))
            return Map.of("pending", false);

        GithubClient.Ahead ahead = github.compareAhead(builtSha, headSha);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("pending", true);
        out.put("ahead", ahead.ahead());
        out.put("builtSha", builtSha.substring(0, Math.min(7, builtSha.length())));
        out.put("headSha", ahead.headShort());

        return out;
    }

    private static ResponseEntity<?> respond(int pr, Optional<AnalysisResult> result) {
        return result.<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "no RunAll build found for PR " + pr)));
    }
}
