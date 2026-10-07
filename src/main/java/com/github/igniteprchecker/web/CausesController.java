package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.CauseClusters;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Groups the current blockers of a PR by failure signature ("Root causes" on the PR page). */
@RestController
@RequestMapping("/api")
public class CausesController {
    private final BlockerAnalyzer analyzer;
    private final CauseClusters causes;

    public CausesController(BlockerAnalyzer analyzer, CauseClusters causes) {
        this.analyzer = analyzer;
        this.causes = causes;
    }

    /**
     * {@code cached=true}: only what was already computed for the current blockers (204 when nothing was),
     * for the cause count in the header: grouping fetches up to 80 failure messages from TeamCity, which only
     * opening the tab should cost.
     */
    @GetMapping("/causes")
    public ResponseEntity<?> causes(@RequestParam int pr, @RequestParam(defaultValue = "false") boolean cached,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        Optional<AnalysisResult> res = analyzer.analyze(token, pr);
        if (res.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "no RunAll build found for PR " + pr));

        if (cached) {
            return causes.cached(res.get()).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
        }

        return ResponseEntity.ok(causes.clusters(token, res.get()));
    }
}
