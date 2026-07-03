package com.github.igniteprchecker.web;

import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the open-PR list for the navigation pane. */
@RestController
@RequestMapping("/api")
public class PrsController {
    private final GithubClient github;

    public PrsController(GithubClient github) {
        this.github = github;
    }

    @GetMapping("/prs")
    public List<PrSummary> prs() {
        return github.openPrs();
    }
}
