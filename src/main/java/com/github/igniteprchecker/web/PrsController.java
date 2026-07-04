package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the open-PR list for the navigation pane, enriched with each PR's last-known blocker count. */
@RestController
@RequestMapping("/api")
public class PrsController {
    private final GithubClient github;
    private final BlockerAnalyzer analyzer;

    public PrsController(GithubClient github, BlockerAnalyzer analyzer) {
        this.github = github;
        this.analyzer = analyzer;
    }

    @GetMapping("/prs")
    public List<PrSummary> prs() {
        return github.openPrs().stream()
            .map(p -> new PrSummary(p.number(), p.title(), p.url(), analyzer.blockerCount(p.number())))
            .toList();
    }
}
