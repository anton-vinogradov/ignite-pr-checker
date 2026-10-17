package com.github.igniteprchecker.style;

import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.style.CheckstyleRunner.Violation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Checkstyle autofix at command time: fetch the PR's changed .java files, run the repo's own
 * checkstyle on them, apply the mechanical {@link Fixers}, verify the result got strictly better,
 * and push ONE fix commit to the author's branch under their own token — all before the run is
 * triggered, so a trivial style failure can't waste a four-hour RunAll. Guard-rails: opt-in per
 * user, only on the commander's OWN pull request, never force-pushed, and the commit is clearly
 * labelled.
 */
@Component
public class StyleFixService {
    private static final Logger log = LoggerFactory.getLogger(StyleFixService.class);

    /** More changed files than this means the run isn't about style anyway — skip quietly. */
    private static final int MAX_FILES = 50;

    /** All changed files of one command downloaded together, at most: the service runs in a 512 MB heap. */
    private static final long MAX_TOTAL_BYTES = 5_000_000;

    /** Violations left after the fix that the comment lists; the rest are counted. */
    private static final int LISTED = 50;

    private final GithubClient github;
    private final CheckstyleRunner checkstyle;

    /** Whose GitHub token to drop when GitHub refuses it; null for a fix that keeps no tokens. */
    private final StandingVisas standing;

    /** A style fix that keeps no tokens: a refused one is only reported. */
    public StyleFixService(GithubClient github, CheckstyleRunner checkstyle) {
        this(github, checkstyle, null);
    }

    @Autowired
    public StyleFixService(GithubClient github, CheckstyleRunner checkstyle, StandingVisas standing) {
        this.github = github;
        this.checkstyle = checkstyle;
        this.standing = standing;
    }

    /**
     * The whole flow for an accepted command; the returned markdown line goes into the command
     * comment (null = nothing worth saying: clean PR, foreign PR, or too many files).
     */
    public String fixForCommand(int pr, StandingVisas.GhActor actor, String commanderLogin) {
        GithubClient.PrHead head = github.prHead(pr);
        if (!head.authorLogin().equals(commanderLogin)) {
            log.info("style autofix for PR {} skipped: commander {} is not the author {}",
                pr, commanderLogin, head.authorLogin());

            return null;
        }

        List<String> paths = github.prJavaFiles(pr);
        if (paths.isEmpty() || paths.size() > MAX_FILES)
            return null;

        try {
            Map<String, String> files = new LinkedHashMap<>();
            List<String> notChecked = new ArrayList<>();
            long total = 0;
            for (String path : paths) {
                int room = (int)Math.min(CheckstyleRunner.MAX_FILE_BYTES, MAX_TOTAL_BYTES - total);
                Optional<String> content = room <= 0 ? Optional.empty()
                    : github.rawFileUpTo(head.headRepo(), head.headSha(), path, room);
                if (content.isEmpty()) {
                    notChecked.add(path);
                    continue;
                }
                files.put(path, content.get());
                total += content.get().getBytes(StandardCharsets.UTF_8).length;
            }

            CheckstyleRunner.CheckResult first = checkstyle.check(files);
            List<Violation> before = first.violations();
            notChecked.addAll(first.skipped());
            String skippedNote = notChecked.isEmpty() ? ""
                : " " + count(notChecked.size(), "file") + (notChecked.size() == 1 ? " was" : " were")
                    + " too large for the checker and not checked ("
                    + notChecked.get(0) + (notChecked.size() > 1 ? ", …" : "") + ").";
            if (before.isEmpty())
                return skippedNote.isEmpty() ? null : "🎨 _Checkstyle:" + skippedNote + "_";

            // Fix -> re-check -> fix once more: import removals shift lines, so one extra pass
            // catches what the first one unblocked.
            Map<String, String> fixed = new LinkedHashMap<>(files);
            List<Violation> remaining = before;
            for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
                for (String path : fixed.keySet().stream().toList()) {
                    List<Violation> mine = remaining.stream().filter(v -> v.path().equals(path)).toList();
                    if (!mine.isEmpty())
                        fixed.put(path, Fixers.apply(fixed.get(path), mine));
                }
                remaining = checkstyle.check(fixed).violations();
            }

            String check = "[**Check java code**](" + github.checksUrl(pr) + ") check of this PR";
            boolean one = before.size() == 1;
            if (remaining.size() >= before.size())
                return "🎨 _Checkstyle: " + count(before.size(), "violation") + " in the changed files, "
                    + (one ? "not" : "none of them") + " mechanically fixable (javadoc, naming, wrapping need a human):"
                    + " the " + check + " may fail on " + (one ? "it." : "them.") + skippedNote + "_" + listed(before);

            Map<String, String> changed = new LinkedHashMap<>();
            for (var e : fixed.entrySet()) {
                if (!e.getValue().equals(files.get(e.getKey())))
                    changed.put(e.getKey(), e.getValue());
            }

            String sha;
            try {
                sha = github.commitFiles(actor.ghToken(), head.headRepo(), head.headRef(), head.headSha(), changed,
                    "Checkstyle autofix by Ignite PR Checker (requested via PR command)");
            }
            catch (RuntimeException e) {
                if (cannotPush(e)) {
                    log.warn("style autofix for PR {}: the token of {} may not push to {}: {}", pr, actor.username(),
                        head.headRepo(), e.toString());

                    return "🎨 _Checkstyle autofix: your GitHub token may not push to `" + head.headRepo() + "`, so the"
                        + " fix was not pushed — running as-is. A fine-grained token reaches only the repositories it"
                        + " was given; a PR from a branch of the repo itself or of someone else's fork needs a classic"
                        + " one with public_repo._";
                }
                if (!GithubClient.refusesToken(e))
                    throw e;

                // The one call made with the author's token: its refusal is theirs, and their token is dropped as
                // everywhere else.
                if (standing != null)
                    standing.dropGhToken(actor.username());
                log.warn("style autofix for PR {}: GitHub refused the token of {}", pr, actor.username());

                return "🎨 _Checkstyle autofix: GitHub refused your token, so the fix was not pushed — running as-is._";
            }

            log.info("style autofix for PR {}: {} -> {} violation(s), {} file(s), commit {}",
                pr, before.size(), remaining.size(), changed.size(), sha.substring(0, 8));

            return "🎨 _Checkstyle autofix: fixed " + (before.size() - remaining.size()) + " of " + before.size()
                + (one ? " violation" : " violations") + " in " + count(changed.size(), "file") + " and pushed `"
                + sha.substring(0, 7) + "` to your branch `" + head.headRef() + "` — `git pull` before your next"
                + " push." + left(remaining.size(), check) + skippedNote + "_" + listed(remaining);
        }
        catch (Throwable e) {
            // Throwable, not Exception: checkstyle wraps OOM into java.lang.Error, and a style
            // failure must never cost the user their run — the trigger proceeds regardless.
            log.warn("style autofix for PR {} failed: {}", pr, e.toString());

            return "🎨 _Checkstyle autofix failed (" + e.getClass().getSimpleName() + ") — running as-is._";
        }
    }

    /**
     * Whether GitHub answered the commit that the token may not write there: 403, other than its rate limit, or 404
     * for a repo it cannot see.
     */
    private static boolean cannotPush(RuntimeException e) {
        if (!(e instanceof org.springframework.web.client.RestClientResponseException rest))
            return false;

        int code = rest.getStatusCode().value();

        return code == 403 && !GithubClient.rateLimited(e) || code == 404;
    }

    /** " 2 remain, which need a human (…): the check … may still fail on them."; empty when none remain. */
    private static String left(int n, String check) {
        if (n == 0)
            return "";

        boolean one = n == 1;

        return " " + n + (one ? " remains, which needs" : " remain, which need") + " a human (javadoc, naming,"
            + " wrapping): the " + check + " may still fail on " + (one ? "it." : "them.");
    }

    /** "1 file", "2 files". */
    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The violations as "path:line — rule", folded; empty when there are none. */
    private static String listed(List<Violation> violations) {
        if (violations.isEmpty())
            return "";

        StringBuilder b = new StringBuilder("\n\n<details><summary>").append(count(violations.size(), "violation"))
            .append(" left</summary>\n\n");
        violations.stream().limit(LISTED).forEach(v -> b.append("- `").append(v.path()).append(':').append(v.line())
            .append("` — ").append(v.rule()).append('\n'));
        if (violations.size() > LISTED)
            b.append("- … and ").append(violations.size() - LISTED).append(" more\n");

        return b.append("\n</details>").toString();
    }
}
