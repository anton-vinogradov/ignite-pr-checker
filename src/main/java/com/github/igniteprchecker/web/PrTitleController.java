package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
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
 * recently updated. The page's head names the PR's ticket from it, and the JIRA visa posts there. For a merged or
 * closed PR, the answer also says which, when, and at which commit: the page's head tags the PR with it. The open list
 * answers first, then the merged PR as it stood at its merge, then GitHub. GitHub is asked under the bot's token,
 * whose hourly quota also serves PR commands and visas. So GitHub's answer, a title or "no such PR", is kept for five
 * minutes, and GitHub is asked about {@value #ASKED_PER_HOUR} PRs an hour at most, however many numbers readers try.
 */
@RestController
@RequestMapping("/api")
public class PrTitleController {
    private static final Logger log = LoggerFactory.getLogger(PrTitleController.class);

    /** How long an answer is kept. */
    static final long KEPT_MS = 5 * 60_000L;

    /** How many PRs GitHub is asked about in an hour, for all readers together. */
    static final int ASKED_PER_HOUR = 60;

    private static final long HOUR_MS = 60 * 60_000L;

    private final GithubClient github;

    private final MergedVerdicts merged;

    private final LongSupplier clock;

    private final Map<Integer, Known> known = new ConcurrentHashMap<>();

    /** When GitHub was asked, over the last hour. */
    private final Deque<Long> asked = new ArrayDeque<>();

    @Autowired
    public PrTitleController(GithubClient github, MergedVerdicts merged) {
        this(github, merged, System::currentTimeMillis);
    }

    PrTitleController(GithubClient github, MergedVerdicts merged, LongSupplier clock) {
        this.github = github;
        this.merged = merged;
        this.clock = clock;
    }

    /**
     * {@code title}: the PR's title; 404 when GitHub has no such PR or could not be asked. A merged or closed PR also
     * gets what {@link #answer} adds.
     */
    @GetMapping("/pr-title")
    public ResponseEntity<?> title(@RequestParam int pr) {
        return known(pr).filter(p -> p.title() != null).<ResponseEntity<?>>map(p -> ResponseEntity.ok(answer(p)))
            .orElseGet(() -> notKnown(pr));
    }

    /**
     * The PR's title, and for a merged PR or one closed without a merge, what the page's head says of it: {@code
     * state}, "merged" or "closed"; {@code closedAt}, when, in epoch seconds; {@code head}, the sha of its head commit
     * then, when known.
     */
    static Map<String, Object> answer(GithubClient.PullState pull) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", pull.title());
        if (!pull.open() && pull.closedAt() > 0) {
            out.put("state", pull.merged() ? "merged" : "closed");
            out.put("closedAt", pull.closedAt());
            if (pull.head() != null)
                out.put("head", pull.head());
        }

        return out;
    }

    /**
     * The PR's title as it is now, for the visa that goes to the ticket it names: from the open list, which follows a
     * renamed title, for an open PR, else as the page gets it.
     */
    Optional<String> titleNow(int pr) {
        Optional<String> listed = listed(pr);

        return listed.isPresent() ? listed : knownTitle(pr);
    }

    /** The PR's title as the page gets it; empty when GitHub has no such PR or could not be asked. */
    Optional<String> knownTitle(int pr) {
        return known(pr).map(GithubClient.PullState::title);
    }

    /** The PR as the page gets it; empty when GitHub has no such PR or could not be asked. */
    private Optional<GithubClient.PullState> known(int pr) {
        long now = clock.getAsLong();
        Known k = known.get(pr);
        if (k == null || now - k.at() >= KEPT_MS) {
            k = lookUp(pr, now);
            if (k == null)
                return Optional.empty();

            known.values().removeIf(old -> now - old.at() >= KEPT_MS);
            known.put(pr, k);
        }

        return Optional.ofNullable(k.pull());
    }

    /** The PR, or a null one when GitHub has no such PR; null when GitHub could not be asked. */
    private Known lookUp(int pr, long now) {
        Optional<String> listed = listed(pr);
        if (listed.isPresent())
            return new Known(new GithubClient.PullState(listed.get(), true, false), now);

        Optional<GithubClient.PullState> atMerge = merged.atMerge(pr).filter(p -> p.title() != null);
        if (atMerge.isPresent())
            return new Known(atMerge.get(), now);

        if (!mayAskGithub(now))
            return null;

        try {
            return new Known(github.pullState(pr).orElse(null), now);
        }
        catch (RuntimeException e) {
            log.debug("could not ask GitHub for the title of PR {}: {}", pr, e.toString());

            return null;
        }
    }

    /** The title of the PR in the open list; empty when the list does not hold it. */
    private Optional<String> listed(int pr) {
        return github.openPrs().stream().filter(p -> p.number() == pr).map(PrSummary::title).filter(Objects::nonNull)
            .findFirst();
    }

    private synchronized boolean mayAskGithub(long now) {
        while (!asked.isEmpty() && now - asked.peekFirst() >= HOUR_MS)
            asked.pollFirst();

        if (asked.size() >= ASKED_PER_HOUR)
            return false;

        asked.addLast(now);

        return true;
    }

    private static ResponseEntity<?> notKnown(int pr) {
        return ResponseEntity.status(404).body(Map.of("error", "no title known for PR " + pr));
    }

    /** A PR, null for one GitHub does not have, and when it was learned. */
    private record Known(GithubClient.PullState pull, long at) {
    }
}
