package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.PendingCommits;
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
    private final PendingCommits pending;

    public AnalyzeController(BlockerAnalyzer analyzer, RunDeltaStore deltas, PendingCommits pending) {
        this.analyzer = analyzer;
        this.deltas = deltas;
        this.pending = pending;
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
     * code. Always fresh (the head can change any time), and cheap: one TeamCity + one GitHub call. The
     * build may be any run, a single suite's too: the "ai" prompts ask it about the run they quote.
     */
    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        PendingCommits.Ahead ahead = pending.since(token, pr, build);
        if (ahead == null)
            return Map.of("pending", false);

        Map<String, Object> out = new java.util.HashMap<>();
        out.put("pending", true);
        out.put("ahead", ahead.commits());
        out.put("caveat", Caveats.pushedSince(Math.max(ahead.commits(), 1)));
        out.put("builtSha", ahead.builtShort());
        out.put("headSha", ahead.headShort());
        out.put("builtRevision", ahead.builtRevision());
        out.put("rewritten", ahead.rewritten());

        return out;
    }

    private static ResponseEntity<?> respond(int pr, Optional<AnalysisResult> result) {
        return result.<ResponseEntity<?>>map(r -> ResponseEntity.ok(Served.of(r)))
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "no RunAll build found for PR " + pr)));
    }

    /**
     * The analysis as the page gets it: with how its verdict stands and the caveats behind that, as the server words
     * them, so the page's head says what the PR list, the visa and the PR comment say. Commits pushed since the run
     * come with /api/pending.
     */
    public record Served(@JsonUnwrapped AnalysisResult result, Caveats.Standing standing, List<String> caveats) {
        static Served of(AnalysisResult r) {
            return new Served(r, Caveats.standing(r, null), Caveats.of(r, null));
        }
    }
}
