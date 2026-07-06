package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.TeamcityProperties;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.session.SessionCodec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * The JIRA "visa": posts the analysis verdict as a comment to the PR's IGNITE ticket, using the
 * user's own JIRA Personal Access Token. The PAT travels in the same encrypted session cookie as
 * the TeamCity token — nothing is stored server-side.
 */
@RestController
@RequestMapping("/api")
public class JiraController {
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(3650);

    private final JiraClient jira;
    private final BlockerAnalyzer analyzer;
    private final SessionCodec codec;
    private final TeamcityProperties tc;
    private final boolean cookieSecure;
    private final String publicUrl;

    public JiraController(JiraClient jira, BlockerAnalyzer analyzer, SessionCodec codec, TeamcityProperties tc,
        @Value("${session.cookie-secure:true}") boolean cookieSecure,
        @Value("${app.public-url:https://v888764.hosted-by-vdsina.com}") String publicUrl) {
        this.jira = jira;
        this.analyzer = analyzer;
        this.codec = codec;
        this.tc = tc;
        this.cookieSecure = cookieSecure;
        this.publicUrl = publicUrl;
    }

    /** Where to create a Personal Access Token in ASF JIRA (profile deep link for the UI hint). */
    @GetMapping("/jira-config")
    public Map<String, String> config() {
        return Map.of("patUrl", jira.baseUrl()
            + "/secure/ViewProfile.jspa?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens");
    }

    /** Validates the PAT against JIRA and re-issues the session cookie with it on board. */
    @PostMapping("/jira-token")
    public ResponseEntity<?> saveToken(@RequestBody TokenRequest req,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        if (req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "empty token"));

        Optional<String> who = jira.myself(req.token().trim());
        if (who.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "JIRA rejected the token"));

        String cookie = codec.encode(username, tcToken, req.token().trim());

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, ResponseCookie.from(AuthInterceptor.COOKIE, cookie)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(COOKIE_MAX_AGE).build().toString())
            .body(Map.of("jiraUser", who.get()));
    }

    /** Posts the verdict as a comment ("visa") to the ticket. 412 when the session has no JIRA token. */
    @PostMapping("/jira-visa")
    public ResponseEntity<?> visa(@RequestParam int pr, @RequestParam String issue,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(value = AuthInterceptor.JIRA_ATTR, required = false) String jiraToken) {
        if (jiraToken == null)
            return ResponseEntity.status(412).body(Map.of("error", "no JIRA token in the session"));
        if (!issue.matches("IGNITE-\\d+"))
            return ResponseEntity.badRequest().body(Map.of("error", "bad issue key"));

        Optional<AnalysisResult> res = analyzer.analyze(tcToken, pr);
        if (res.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "no finished RunAll build for PR " + pr));

        try {
            String url = jira.addComment(jiraToken, issue, visaBody(pr, res.get()));

            return ResponseEntity.ok(Map.of("url", url));
        }
        catch (RestClientResponseException e) {
            return ResponseEntity.status(502)
                .body(Map.of("error", "JIRA rejected the comment (" + e.getStatusCode() + ")"));
        }
    }

    /** The verdict in JIRA wiki markup — tcbot-visa style: green when clean, red with the blocker list. */
    private String visaBody(int pr, AnalysisResult r) {
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
            b.append("(!) *").append(r.brokenSuites().size()).append(" broken suite(s)* (failed without running tests):\n");
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

    /** The PAT as pasted by the user. */
    public record TokenRequest(String token) {
    }
}
