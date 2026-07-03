package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runs the blocker analysis for a PR using the logged-in user's TeamCity token. */
@RestController
@RequestMapping("/api")
public class AnalyzeController {
    private final BlockerAnalyzer analyzer;

    public AnalyzeController(BlockerAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    @GetMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        Optional<AnalysisResult> result = analyzer.analyze(token, pr);

        return result.<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404)
                .body(Map.of("error", "no RunAll build found for PR " + pr)));
    }
}
