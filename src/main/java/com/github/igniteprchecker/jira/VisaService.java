package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Composes the verdict ("visa") in JIRA wiki markup — tcbot style: green when clean, red with the
 * list. "No blockers" is only ever green when the run behind it actually covered the PR; an
 * interrupted run, suites without a reliable result, or commits pushed since are stated instead.
 */
@Component
public class VisaService {
    private final TeamcityProperties tc;
    private final String publicUrl;

    public VisaService(TeamcityProperties tc,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl) {
        this.tc = tc;
        this.publicUrl = publicUrl;
    }

    /** The verdict in GitHub markdown, for a PR comment mirror of the visa. */
    public String composeMarkdown(int pr, AnalysisResult r) {
        return composeMarkdown(pr, r, null);
    }

    /**
     * The verdict in GitHub markdown. {@code commitsAhead} is how many commits the PR head is ahead
     * of the analysed run (null when unknown) — a verdict for superseded code says so.
     */
    public String composeMarkdown(int pr, AnalysisResult r, Integer commitsAhead) {
        String base = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        StringBuilder b = new StringBuilder();
        b.append("**[Ignite PR Checker](").append(publicUrl).append("/?pr=").append(pr)
            .append(")** verdict · RunAll build [").append(r.buildId()).append("](").append(base)
            .append("build/").append(r.buildId()).append(") · ").append(r.suitesRan())
            .append(" suites ran, ").append(r.suitesReused()).append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        List<String> caveats = Caveats.of(r, commitsAhead);
        if (blockers.isEmpty() && caveats.isEmpty()) {
            b.append("✅ **No blockers** — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        if (!caveats.isEmpty()) {
            b.append("⚠️ **This run doesn't cover the PR fully:**\n");
            caveats.forEach(c -> b.append("- ").append(c).append('\n'));
            b.append('\n');
        }

        if (!r.brokenSuites().isEmpty()) {
            b.append("⚠️ **").append(r.brokenSuites().size()).append(" broken suite(s)** (no reliable run):\n");
            r.brokenSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(String.join(" · ", s.problems())).append(shortfall(s)).append('\n'));
            b.append('\n');
        }

        if (!r.shrunkSuites().isEmpty()) {
            b.append("🔍 **").append(r.shrunkSuites().size())
                .append(" suite(s) ran fewer tests than on master** (tests that never ran can't fail):\n");
            r.shrunkSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(s.tests()).append(" tests vs ").append(s.baseline())
                .append(" on master (**−").append(s.dropPct()).append("%**)\n"));
            b.append('\n');
        }

        if (blockers.isEmpty()) {
            // No blocker was found — but say plainly that this run couldn't have found one either.
            b.append("🔎 **No blockers found — but the run above can't prove the PR is clean.** ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out. ")
                .append("Re-run once the above is sorted out.");
        }
        else {
            long suites = blockers.stream().map(TestVerdict::suiteBuildId).distinct().count();
            b.append("❌ **").append(blockers.size()).append(" blocker(s) in ").append(suites).append(" suite(s):**\n");
            blockers.stream().limit(10).forEach(t ->
                b.append("- ").append(t.suiteName()).append(": `").append(t.name()).append("`\n"));
            if (blockers.size() > 10)
                b.append("… and ").append(blockers.size() - 10).append(" more\n");
        }

        return b.toString();
    }

    /** The tests a broken suite never got to, stated under its cause rather than as a finding of its own. */
    private static String shortfall(BrokenSuite s) {
        return s.tests() > 0 && s.baseline() > s.tests()
            ? " — ran " + s.tests() + " of master's " + s.baseline() + " tests" : "";
    }

    public String compose(int pr, AnalysisResult r) {
        return compose(pr, r, null);
    }

    /** The same verdict in JIRA wiki markup; see {@link #composeMarkdown(int, AnalysisResult, Integer)}. */
    public String compose(int pr, AnalysisResult r, Integer commitsAhead) {
        String base = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        StringBuilder b = new StringBuilder();
        b.append("[Ignite PR Checker|").append(publicUrl).append("/?pr=").append(pr).append("] verdict for PR ")
            .append(pr).append(" · RunAll build [").append(r.buildId()).append('|').append(base).append("build/")
            .append(r.buildId()).append("] · ").append(r.suitesRan()).append(" suites ran, ")
            .append(r.suitesReused()).append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        List<String> caveats = Caveats.of(r, commitsAhead);
        if (blockers.isEmpty() && caveats.isEmpty()) {
            b.append("(/) *No blockers* — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        if (!caveats.isEmpty()) {
            b.append("(!) *This run doesn't cover the PR fully:*\n");
            caveats.forEach(c -> b.append("- ").append(c).append('\n'));
            b.append('\n');
        }

        if (!r.brokenSuites().isEmpty()) {
            b.append("(!) *").append(r.brokenSuites().size()).append(" broken suite(s)* (failed without a reliable run):\n");
            r.brokenSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(String.join(" · ", s.problems())).append(shortfall(s)).append('\n'));
            b.append('\n');
        }

        if (!r.shrunkSuites().isEmpty()) {
            b.append("(?) *").append(r.shrunkSuites().size())
                .append(" suite(s) ran fewer tests than on master* (tests that never ran can't fail):\n");
            r.shrunkSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(s.tests()).append(" tests vs ").append(s.baseline())
                .append(" on master (*-").append(s.dropPct()).append("%*)\n"));
            b.append('\n');
        }

        if (blockers.isEmpty()) {
            b.append("(?) *No blockers found — but the run above can't prove the PR is clean.* ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out. ")
                .append("Re-run once the above is sorted out.");
        }
        else {
            long suites = blockers.stream().map(TestVerdict::suiteBuildId).distinct().count();
            b.append("(x) *").append(blockers.size()).append(" blocker(s) in ").append(suites).append(" suite(s):*\n");
            blockers.stream().limit(10).forEach(t ->
                b.append("- ").append(t.suiteName()).append(": {{").append(t.name()).append("}}\n"));
            if (blockers.size() > 10)
                b.append("… and ").append(blockers.size() - 10).append(" more\n");
        }

        return b.toString();
    }
}
