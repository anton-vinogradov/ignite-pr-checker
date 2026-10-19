package com.github.igniteprchecker.web;

import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.jira.StandingVisas;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The PRs of the tabs Mine and Involved of the PR list: what GitHub's search finds for the GitHub account the user
 * linked, asked under the checker's own token, never the user's. Each opening of the page and each click on a tab
 * asks for them, so an account's answer is kept a minute, and so is a failed search: meanwhile the last answer GitHub
 * gave stands.
 */
@Component
public class YourPrs {
    private static final Logger log = LoggerFactory.getLogger(YourPrs.class);

    /** How long GitHub's answer for an account is taken as it is, and how long a failed search waits. */
    static final long KEEP_MS = 60_000L;

    /** How many accounts' answers are kept at most. */
    static final int KEPT = 1_000;

    private final GithubClient github;

    private final StandingVisas standing;

    private final LongSupplier nowMs;

    /** The searches by account, the least recently asked first. */
    private final Map<String, Search> searches = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Search> eldest) {
            return size() > KEPT;
        }
    };

    @Autowired
    public YourPrs(GithubClient github, StandingVisas standing) {
        this(github, standing, System::currentTimeMillis);
    }

    YourPrs(GithubClient github, StandingVisas standing, LongSupplier nowMs) {
        this.github = github;
        this.standing = standing;
        this.nowMs = nowMs;
    }

    /** The user's PRs for the tabs; empty lists when the user linked no GitHub account. */
    public Lists of(String username) {
        String login = standing.ghLoginOf(username);
        if (login == null)
            return new Lists(null, List.of(), List.of(), null);
        if (!github.writes())
            return new Lists(login, null, null, Problem.NO_ACCOUNT);

        GithubClient.PrsOf found = search(login);

        return found == null ? new Lists(login, null, null, Problem.NO_ANSWER)
            : new Lists(login, found.mine(), found.involved(), null);
    }

    /** GitHub's last answer for the account; null when it has given none. One search at a time per account. */
    private GithubClient.PrsOf search(String login) {
        Search search;
        synchronized (searches) {
            search = searches.computeIfAbsent(login.toLowerCase(Locale.ROOT), k -> new Search());
        }
        synchronized (search) {
            long now = nowMs.getAsLong();
            if (search.asked && now - search.askedAt < KEEP_MS)
                return search.found;

            search.asked = true;
            search.askedAt = now;
            try {
                search.found = github.prsOf(login);
            }
            catch (RuntimeException e) {
                log.warn("GitHub did not find the PRs of @{}, asked again in a minute: {}", login, e.toString());
            }

            return search.found;
        }
    }

    /** A search of an account: whether and when it was asked, and GitHub's last answer to it. */
    private static final class Search {
        private boolean asked;

        private long askedAt;

        private GithubClient.PrsOf found;
    }

    /**
     * The PRs of the tabs: {@code login}, the user's linked GitHub account, null when none; {@code mine} and
     * {@code involved}, see {@link GithubClient.PrsOf}, null when {@code problem} says why the checker has none.
     */
    public record Lists(String login, List<PrSummary> mine, List<PrSummary> involved, Problem problem) {
    }

    /** Why the tabs have no PRs to show. */
    public enum Problem {
        /** The checker has no GitHub token, and GitHub answers its search only to a caller with one. */
        NO_ACCOUNT,

        /** GitHub did not answer the search, and has not answered it before. */
        NO_ANSWER
    }
}
