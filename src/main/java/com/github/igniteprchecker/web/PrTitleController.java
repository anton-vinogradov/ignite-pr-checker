package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The title of one PR, for a page opened on a PR the open list does not hold: merged, closed, or past the 50 most
 * recently updated. The page's head names the PR's ticket from it, and the JIRA visa posts there. The open list answers
 * first, then the title a merged PR had at its merge, then GitHub. An answer is kept for five minutes, so GitHub is
 * asked about one PR at most that often.
 */
@RestController
@RequestMapping("/api")
public class PrTitleController {
    private static final Logger log = LoggerFactory.getLogger(PrTitleController.class);

    /** How long a title is kept. */
    static final long KEPT_MS = 5 * 60_000L;

    private final GithubClient github;

    private final MergedVerdicts merged;

    private final LongSupplier clock;

    private final Map<Integer, Known> known = new ConcurrentHashMap<>();

    @Autowired
    public PrTitleController(GithubClient github, MergedVerdicts merged) {
        this(github, merged, System::currentTimeMillis);
    }

    PrTitleController(GithubClient github, MergedVerdicts merged, LongSupplier clock) {
        this.github = github;
        this.merged = merged;
        this.clock = clock;
    }

    /** {@code title}: the PR's title; 404 when GitHub has no such PR or could not be asked. */
    @GetMapping("/pr-title")
    public ResponseEntity<?> title(@RequestParam int pr) {
        long now = clock.getAsLong();
        Known k = known.get(pr);
        if (k == null || now - k.at() >= KEPT_MS) {
            Optional<String> title = lookUp(pr);
            if (title.isEmpty())
                return ResponseEntity.status(404).body(Map.of("error", "GitHub has no title of PR " + pr));

            known.values().removeIf(old -> now - old.at() >= KEPT_MS);
            k = new Known(title.get(), now);
            known.put(pr, k);
        }

        return ResponseEntity.ok(Map.of("title", k.title()));
    }

    private Optional<String> lookUp(int pr) {
        Optional<String> listed = github.openPrs().stream().filter(p -> p.number() == pr).map(PrSummary::title)
            .filter(Objects::nonNull).findFirst();
        if (listed.isPresent())
            return listed;

        Optional<String> atMerge = merged.title(pr);
        if (atMerge.isPresent())
            return atMerge;

        try {
            return github.pullState(pr).map(GithubClient.PullState::title);
        }
        catch (RuntimeException e) {
            log.debug("could not ask GitHub for the title of PR {}: {}", pr, e.toString());

            return Optional.empty();
        }
    }

    /** A title and when it was learned. */
    private record Known(String title, long at) {
    }
}
