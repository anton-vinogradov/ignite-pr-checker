package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.jira.StandingVisas;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the open-PR list for the navigation pane, enriched with each PR's last-known blocker count, how its
 * verdict stands against the PR's current head, and the step its run under way is at. Who triggered a PR's run is a
 * TeamCity username, so only signed-in viewers get it. The tabs Mine and Involved list the PRs of the user's linked
 * GitHub account, see {@link YourPrs}.
 */
@RestController
@RequestMapping("/api")
public class PrsController {
    private final GithubClient github;
    private final BlockerAnalyzer analyzer;
    private final AuthInterceptor auth;

    private final YourPrs yours;

    private final StandingVisas visas;

    public PrsController(GithubClient github, BlockerAnalyzer analyzer, AuthInterceptor auth, YourPrs yours,
        StandingVisas visas) {
        this.github = github;
        this.analyzer = analyzer;
        this.auth = auth;
        this.yours = yours;
        this.visas = visas;
    }

    @GetMapping("/prs")
    public List<PrSummary> prs(HttpServletRequest req) {
        boolean signedIn = auth.signedIn(req).isPresent();

        return github.openPrs().stream()
            .map(p -> listed(p, signedIn ? analyzer.triggeredBy(p.number()) : null))
            .toList();
    }

    /**
     * The step of each PR with a run under way, as {@link #prs} lists it. An open page asks every minute, so that its
     * rows follow their runs: the answer comes from the checker's memory, and neither GitHub nor TeamCity is asked.
     */
    @GetMapping("/run-steps")
    public Map<Integer, PrSummary.RunStep> runSteps() {
        return visas.runSteps();
    }

    /** The PRs of the tabs Mine and Involved, badged as {@link #prs} badges them. */
    @GetMapping("/your-prs")
    public YourPrs.Lists yourPrs(@RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        YourPrs.Lists found = yours.of(username);

        return new YourPrs.Lists(found.login(), badged(found.mine()), badged(found.involved()), found.problem());
    }

    private List<PrSummary> badged(List<PrSummary> prs) {
        return prs == null ? null : prs.stream().map(p -> listed(p, analyzer.triggeredBy(p.number()))).toList();
    }

    private PrSummary listed(PrSummary p, String triggeredBy) {
        PrSummary.RunStep run = visas.runStep(p.number()).orElse(null);
        Caveats.Glance g = analyzer.glance(p.number());
        if (g == null)
            return new PrSummary(p.number(), p.title(), p.url(), triggeredBy, null, null, p.headSha(), null, run);

        Caveats.Standing s = g.against(p.headSha());

        return new PrSummary(p.number(), p.title(), p.url(), triggeredBy, g.blockers(), s == Caveats.Standing.CLEAN,
            p.headSha(), s.name(), run);
    }
}
