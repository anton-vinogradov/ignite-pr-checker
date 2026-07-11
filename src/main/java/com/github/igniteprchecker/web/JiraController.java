package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import java.time.Duration;
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
    private final VisaService visas;
    private final VisaSubscriptions visaSubs;
    private final StandingVisas standing;
    private final boolean cookieSecure;

    public JiraController(JiraClient jira, BlockerAnalyzer analyzer, SessionCodec codec, VisaService visas,
        VisaSubscriptions visaSubs, StandingVisas standing,
        @Value("${session.cookie-secure:true}") boolean cookieSecure) {
        this.jira = jira;
        this.analyzer = analyzer;
        this.codec = codec;
        this.visas = visas;
        this.visaSubs = visaSubs;
        this.standing = standing;
        this.cookieSecure = cookieSecure;
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

    /** Toggles the standing auto-visa: every finished RunAll the user triggered gets a visa posted. */
    @PostMapping("/auto-visa-all")
    public ResponseEntity<?> standingVisa(@RequestParam(defaultValue = "false") boolean visa,
        @RequestParam(defaultValue = "false") boolean rerun,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        @RequestAttribute(value = AuthInterceptor.JIRA_ATTR, required = false) String jiraToken) {
        if (!visa && !rerun) {
            standing.disable(username);

            return ResponseEntity.ok(Map.of("visa", false, "rerun", false));
        }
        if (visa) { // only the visa needs JIRA; rerun-only works with the TC token alone
            if (jiraToken == null)
                return ResponseEntity.status(412).body(Map.of("error", "no JIRA token in the session"));
            if (jira.myself(jiraToken).isEmpty())
                return ResponseEntity.status(412).body(Map.of("error", "JIRA rejected the stored token — re-enter it"));
        }

        standing.enable(username, tcToken, visa ? jiraToken : null, visa, rerun);

        return ResponseEntity.ok(Map.of("visa", visa, "rerun", rerun));
    }

    /** The logged-in user's standing options. */
    @GetMapping("/auto-visa-all")
    public Map<String, Object> standingVisaStatus(@RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        return Map.of("visa", standing.visaOn(username), "rerun", standing.rerunOn(username));
    }

    /** Arms the one-shot auto-visa: posts to the ticket when this PR's next RunAll finishes. */
    @PostMapping("/auto-visa")
    public ResponseEntity<?> armAutoVisa(@RequestParam int pr, @RequestParam String issue,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        @RequestAttribute(value = AuthInterceptor.JIRA_ATTR, required = false) String jiraToken) {
        if (jiraToken == null)
            return ResponseEntity.status(412).body(Map.of("error", "no JIRA token in the session"));
        if (!issue.matches("IGNITE-\\d+"))
            return ResponseEntity.badRequest().body(Map.of("error", "bad issue key"));
        if (jira.myself(jiraToken).isEmpty())
            return ResponseEntity.status(412).body(Map.of("error", "JIRA rejected the stored token — re-enter it"));

        visaSubs.arm(pr, issue, jiraToken, username);

        return ResponseEntity.ok(Map.of("armed", true, "issue", issue));
    }

    /** Cancels a pending auto-visa (removes the stored token with it). */
    @PostMapping("/auto-visa-cancel")
    public ResponseEntity<?> cancelAutoVisa(@RequestParam int pr) {
        visaSubs.cancel(pr);

        return ResponseEntity.ok(Map.of("armed", false));
    }

    /** Whether an auto-visa is armed for the PR (and for which issue). */
    @GetMapping("/auto-visa")
    public Map<String, Object> autoVisaStatus(@RequestParam int pr) {
        return visaSubs.armedIssue(pr)
            .<Map<String, Object>>map(issue -> Map.of("armed", true, "issue", issue))
            .orElse(Map.of("armed", false));
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
            String url = jira.addComment(jiraToken, issue, visas.compose(pr, res.get()));

            return ResponseEntity.ok(Map.of("url", url));
        }
        catch (RestClientResponseException e) {
            return ResponseEntity.status(502)
                .body(Map.of("error", "JIRA rejected the comment (" + e.getStatusCode() + ")"));
        }
    }

    /** The PAT as pasted by the user. */
    public record TokenRequest(String token) {
    }
}
