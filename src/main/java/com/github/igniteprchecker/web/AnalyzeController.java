package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.RunDeltaStore;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import java.util.HashMap;
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
    private final PrHeads heads;

    public AnalyzeController(BlockerAnalyzer analyzer, RunDeltaStore deltas, PendingCommits pending, PrHeads heads) {
        this.analyzer = analyzer;
        this.deltas = deltas;
        this.pending = pending;
        this.heads = heads;
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
     * code. One TeamCity call, and the head as {@link PrHeads} knows it. The build may be any run of the
     * PR, a single suite's too: the "ai" prompts ask it about the run they quote. {@code checked} is false
     * when TeamCity or GitHub could not tell, the build is not of the PR, or GitHub was not asked.
     */
    @GetMapping("/pending")
    public Map<String, Object> pending(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        PendingCommits.Ahead ahead;
        try {
            ahead = pending.movedSince(token, pr, build, heads::of, heads::spare);
        }
        catch (RuntimeException e) {
            return Map.of("pending", false, "checked", false);
        }

        return ahead == null ? Map.of("pending", false, "checked", true)
            : answer(ahead, Caveats.pushedSince(Math.max(ahead.commits(), 1)));
    }

    private static Map<String, Object> answer(PendingCommits.Ahead ahead, String caveat) {
        Map<String, Object> out = new HashMap<>();
        out.put("pending", true);
        out.put("ahead", ahead.commits());
        out.put("caveat", caveat);
        out.put("builtSha", ahead.builtShort());
        out.put("headSha", ahead.headShort());
        out.put("builtRevision", ahead.builtRevision());
        out.put("rewritten", ahead.rewritten());

        return out;
    }

    private ResponseEntity<?> respond(int pr, Optional<AnalysisResult> result) {
        return result.<ResponseEntity<?>>map(r -> ResponseEntity.ok(Served.of(r, pending.listedPast(r))))
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "no RunAll build found for PR " + pr)));
    }

    /**
     * The analysis as the page gets it: with how its verdict stands and the caveats behind that, as the server words
     * them, so the page's head says what the PR list, the visa and the PR comment say. Commits pushed since the run
     * come with /api/pending; {@code pushed} is its answer ahead of time when the PR list already shows the head past
     * the run, the way the list badges it, and null otherwise.
     */
    public record Served(@JsonUnwrapped AnalysisResult result, Caveats.Standing standing, List<String> caveats,
        Map<String, Object> pushed) {
        static Served of(AnalysisResult r) {
            return of(r, null);
        }

        static Served of(AnalysisResult r, PendingCommits.Ahead listed) {
            return new Served(r, Caveats.standing(r, null), Caveats.of(r, null),
                listed == null ? null : answer(listed, Caveats.headMoved()));
        }
    }
}
