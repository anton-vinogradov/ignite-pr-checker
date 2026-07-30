package com.github.igniteprchecker.style;

import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.style.CheckstyleRunner.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Checkstyle autofix at command time: fetch the PR's changed .java files, run the repo's own
 * checkstyle on them, apply the mechanical {@link Fixers}, verify the result got strictly better,
 * and push ONE fix commit to the author's branch under their own PAT — all before the run is
 * triggered, so a trivial style failure can't waste a four-hour RunAll. Guard-rails: opt-in per
 * user, only on the commander's OWN pull request, never force-pushed, and the commit is clearly
 * labelled.
 */
@Component
public class StyleFixService {
    private static final Logger log = LoggerFactory.getLogger(StyleFixService.class);

    /** More changed files than this means the run isn't about style anyway — skip quietly. */
    private static final int MAX_FILES = 50;

    private final GithubClient github;
    private final CheckstyleRunner checkstyle;

    public StyleFixService(GithubClient github, CheckstyleRunner checkstyle) {
        this.github = github;
        this.checkstyle = checkstyle;
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
            for (String path : paths)
                files.put(path, github.rawFile(head.headRepo(), head.headSha(), path));

            CheckstyleRunner.CheckResult first = checkstyle.check(files);
            List<Violation> before = first.violations();
            String skippedNote = first.skipped().isEmpty() ? ""
                : " " + first.skipped().size() + " file(s) were too large for in-process checkstyle and were"
                    + " not checked (" + first.skipped().get(0) + (first.skipped().size() > 1 ? ", …" : "") + ").";
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

            if (remaining.size() >= before.size())
                return "🎨 _Checkstyle: " + before.size() + " violation(s) in the changed files — none mechanically"
                    + " fixable (javadoc/naming/wrapping need a human). The Checkstyle suite WILL fail." + skippedNote + "_";

            Map<String, String> changed = new LinkedHashMap<>();
            for (var e : fixed.entrySet()) {
                if (!e.getValue().equals(files.get(e.getKey())))
                    changed.put(e.getKey(), e.getValue());
            }

            String sha = github.commitFiles(actor.ghToken(), head.headRepo(), head.headRef(), head.headSha(),
                changed, "Checkstyle autofix by Ignite PR Checker (requested via PR command)");

            log.info("style autofix for PR {}: {} -> {} violation(s), {} file(s), commit {}",
                pr, before.size(), remaining.size(), changed.size(), sha.substring(0, 8));

            return "🎨 _Checkstyle autofix: fixed " + (before.size() - remaining.size()) + " of " + before.size()
                + " violation(s) in " + changed.size() + " file(s) — commit " + sha.substring(0, 8) + "."
                + (remaining.isEmpty() ? "" : " " + remaining.size()
                    + " remain (javadoc/naming/wrapping need a human) — the Checkstyle suite may still fail.")
                + skippedNote + "_";
        }
        catch (Throwable e) {
            // Throwable, not Exception: checkstyle wraps OOM into java.lang.Error, and a style
            // failure must never cost the user their run — the trigger proceeds regardless.
            log.warn("style autofix for PR {} failed: {}", pr, e.toString());

            return "🎨 _Checkstyle autofix failed (" + e.getClass().getSimpleName() + ") — running as-is._";
        }
    }
}
