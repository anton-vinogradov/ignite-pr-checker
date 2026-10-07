package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the open-PR list for the navigation pane, enriched with each PR's last-known blocker count. Who
 * triggered a PR's run is a TeamCity username, so only signed-in viewers get it.
 */
@RestController
@RequestMapping("/api")
public class PrsController {
    private final GithubClient github;
    private final BlockerAnalyzer analyzer;
    private final AuthInterceptor auth;

    public PrsController(GithubClient github, BlockerAnalyzer analyzer, AuthInterceptor auth) {
        this.github = github;
        this.analyzer = analyzer;
        this.auth = auth;
    }

    @GetMapping("/prs")
    public List<PrSummary> prs(HttpServletRequest req) {
        boolean signedIn = auth.signedIn(req).isPresent();

        return github.openPrs().stream()
            .map(p -> new PrSummary(p.number(), p.title(), p.url(),
                signedIn ? analyzer.triggeredBy(p.number()) : null, analyzer.blockerCount(p.number()),
                analyzer.provenClean(p.number())))
            .toList();
    }
}
