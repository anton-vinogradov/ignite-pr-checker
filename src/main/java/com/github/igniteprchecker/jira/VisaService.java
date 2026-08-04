package com.github.igniteprchecker.jira;

import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Composes the verdict ("visa") in JIRA wiki markup — tcbot style: green when clean, red with the list. */
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
        String base = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        StringBuilder b = new StringBuilder();
        b.append("**[Ignite PR Checker](").append(publicUrl).append("/?pr=").append(pr)
            .append(")** verdict · RunAll build [").append(r.buildId()).append("](").append(base)
            .append("build/").append(r.buildId()).append(") · ").append(r.suitesRan())
            .append(" suites ran, ").append(r.suitesReused()).append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        List<TestVerdict> watch = r.watch();
        // Green only when nothing at all needs attention — the same bar the web page uses. Tests that
        // just started failing on this code, or a chain that never finished, make "No blockers" a lie.
        if (blockers.isEmpty() && watch.isEmpty() && r.brokenSuites().isEmpty() && r.shrunkSuites().isEmpty()
            && !r.interrupted()) {
            b.append("✅ **No blockers** — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        if (r.interrupted()) {
            b.append("🛑 **This RunAll never finished** — ").append(r.canceledSuites())
                .append(" suite(s) were cancelled and never ran, so everything below is a **partial** verdict: ")
                .append("it says nothing about what those suites would have found.\n\n");
        }

        if (!r.brokenSuites().isEmpty()) {
            b.append("⚠️ **").append(r.brokenSuites().size()).append(" broken suite(s)** (no reliable run):\n");
            r.brokenSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(String.join(" · ", s.problems())).append('\n'));
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

        if (!watch.isEmpty()) {
            // Never say the re-runs are under way: this composes from the analysis alone and has no
            // idea whether anyone will re-run anything (a manual visa, a subscription, or auto-rerun
            // switched off). StandingVisas appends the real re-run state when there is one.
            b.append("👀 **").append(watch.size()).append(" test(s) started failing on this code** — not proven ")
                .append("blockers yet: too few runs of this revision to tell a break from a flake, so a re-run ")
                .append("of the suite decides it.\n");
            watch.stream().limit(10).forEach(t ->
                b.append("- ").append(t.suiteName()).append(": `").append(t.name()).append("`\n"));
            if (watch.size() > 10)
                b.append("… and ").append(watch.size() - 10).append(" more\n");
            b.append('\n');
        }

        if (blockers.isEmpty() && !watch.isEmpty())
            b.append("⚠️ **No proven blocker yet** — this is not an all-clear: see the tests above. ")
                .append(r.filtered().size()).append(" pre-existing/flaky filtered out.");
        else if (blockers.isEmpty())
            b.append("✅ No test blockers otherwise; ").append(r.filtered().size()).append(" pre-existing/flaky filtered out.");
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

    public String compose(int pr, AnalysisResult r) {
        String base = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        StringBuilder b = new StringBuilder();
        b.append("[Ignite PR Checker|").append(publicUrl).append("/?pr=").append(pr).append("] verdict for PR ")
            .append(pr).append(" · RunAll build [").append(r.buildId()).append('|').append(base).append("build/")
            .append(r.buildId()).append("] · ").append(r.suitesRan()).append(" suites ran, ")
            .append(r.suitesReused()).append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        List<TestVerdict> watch = r.watch();
        // Green only when nothing at all needs attention — the same bar the web page uses. Tests that
        // just started failing on this code, or a chain that never finished, make "No blockers" a lie.
        if (blockers.isEmpty() && watch.isEmpty() && r.brokenSuites().isEmpty() && r.shrunkSuites().isEmpty()
            && !r.interrupted()) {
            b.append("(/) *No blockers* — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        if (r.interrupted()) {
            b.append("(x) *This RunAll never finished* — ").append(r.canceledSuites())
                .append(" suite(s) were cancelled and never ran, so everything below is a *partial* verdict: ")
                .append("it says nothing about what those suites would have found.\n\n");
        }

        if (!r.brokenSuites().isEmpty()) {
            b.append("(!) *").append(r.brokenSuites().size()).append(" broken suite(s)* (failed without a reliable run):\n");
            r.brokenSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(String.join(" · ", s.problems())).append('\n'));
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

        if (!watch.isEmpty()) {
            // Never say the re-runs are under way — see composeMarkdown.
            b.append("(!) *").append(watch.size()).append(" test(s) started failing on this code* — not proven ")
                .append("blockers yet: too few runs of this revision to tell a break from a flake, so a re-run ")
                .append("of the suite decides it.\n");
            watch.stream().limit(10).forEach(t ->
                b.append("- ").append(t.suiteName()).append(": {{").append(t.name()).append("}}\n"));
            if (watch.size() > 10)
                b.append("… and ").append(watch.size() - 10).append(" more\n");
            b.append('\n');
        }

        if (blockers.isEmpty() && !watch.isEmpty())
            b.append("(!) *No proven blocker yet* — this is not an all-clear: see the tests above. ")
                .append(r.filtered().size()).append(" pre-existing/flaky filtered out.");
        else if (blockers.isEmpty())
            b.append("(/) No test blockers otherwise; ").append(r.filtered().size()).append(" pre-existing/flaky filtered out.");
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
