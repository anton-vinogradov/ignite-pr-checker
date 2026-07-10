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
        @Value("${app.public-url:https://v888764.hosted-by-vdsina.com}") String publicUrl) {
        this.tc = tc;
        this.publicUrl = publicUrl;
    }

    public String compose(int pr, AnalysisResult r) {
        String base = tc.baseUrl().endsWith("/") ? tc.baseUrl() : tc.baseUrl() + "/";
        StringBuilder b = new StringBuilder();
        b.append("[Ignite PR Checker|").append(publicUrl).append("/?pr=").append(pr).append("] verdict for PR ")
            .append(pr).append(" · RunAll build [").append(r.buildId()).append('|').append(base).append("build/")
            .append(r.buildId()).append("] · ").append(r.suitesRan()).append(" suites ran, ")
            .append(r.suitesReused()).append(" reused\n\n");

        List<TestVerdict> blockers = r.blockers();
        if (blockers.isEmpty() && r.brokenSuites().isEmpty()) {
            b.append("(/) *No blockers* — nothing in this run looks caused by this PR. ")
                .append(r.filtered().size()).append(" pre-existing/flaky tests filtered out.");
            return b.toString();
        }

        if (!r.brokenSuites().isEmpty()) {
            b.append("(!) *").append(r.brokenSuites().size()).append(" broken suite(s)* (failed without a reliable run):\n");
            r.brokenSuites().forEach(s -> b.append("- ").append(s.suiteName()).append(": ")
                .append(String.join(" · ", s.problems())).append('\n'));
            b.append('\n');
        }

        if (blockers.isEmpty())
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
