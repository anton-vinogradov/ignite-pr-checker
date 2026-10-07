package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.jira.VisaSubscriptions;
import com.github.igniteprchecker.session.SessionCodec;
import java.time.Duration;
import java.util.LinkedHashMap;
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
    private final GithubClient github;
    private final PendingCommits pending;
    private final boolean cookieSecure;

    public JiraController(JiraClient jira, BlockerAnalyzer analyzer, SessionCodec codec, VisaService visas,
        VisaSubscriptions visaSubs, StandingVisas standing, GithubClient github, PendingCommits pending,
        @Value("${session.cookie-secure:true}") boolean cookieSecure) {
        this.jira = jira;
        this.analyzer = analyzer;
        this.codec = codec;
        this.visas = visas;
        this.visaSubs = visaSubs;
        this.standing = standing;
        this.github = github;
        this.pending = pending;
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

    /**
     * Changes the standing options named in the request and nothing else; a 412 names what the change
     * needs ({@code need}: jira, github or login). Answers with the whole state, like the GET.
     */
    @PostMapping("/auto-visa-all")
    public ResponseEntity<?> standingVisa(@RequestParam(required = false) Boolean visa,
        @RequestParam(required = false) Boolean rerun,
        @RequestParam(required = false) Boolean gh,
        @RequestParam(required = false) Boolean style,
        @RequestParam(required = false) Boolean commands,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        @RequestAttribute(value = AuthInterceptor.JIRA_ATTR, required = false) String jiraToken,
        @RequestAttribute(value = AuthInterceptor.GH_ATTR, required = false) String ghToken) {
        Optional<StandingVisas.Refusal> refused = standing.change(username, tcToken, jiraToken, ghToken,
            new StandingVisas.OptionChange(visa, rerun, gh, style, commands));
        if (refused.isPresent())
            return ResponseEntity.status(412)
                .body(Map.of("error", refused.get().error(), "need", refused.get().need()));

        return ResponseEntity.ok(standing.settings(username));
    }

    /** The logged-in user's standing options, and whether the server holds the tokens they run on. */
    @GetMapping("/auto-visa-all")
    public StandingVisas.Settings standingVisaStatus(@RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        return standing.settings(username);
    }

    /**
     * Links a GitHub login and switches PR commands on. The login is stored the way GitHub spells it,
     * and only if GitHub knows such a user. Answers with the whole settings state.
     */
    @PostMapping("/github-login")
    public ResponseEntity<?> saveGithubLogin(@RequestBody TokenRequest req,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        String typed = req.token() == null ? "" : req.token().strip().replaceFirst("^@", "");
        if (typed.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "empty login"));

        Optional<String> login;
        try {
            login = github.canonicalLogin(typed);
        }
        catch (RuntimeException e) {
            return ResponseEntity.status(502)
                .body(Map.of("error", "GitHub could not be asked — try again in a minute"));
        }
        if (login.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "no such GitHub user: " + typed));

        return switch (standing.linkGhLogin(username, tcToken, login.get())) {
            case "taken" -> ResponseEntity.status(409).body(Map.of("error", "@" + login.get() + " is linked to another"
                + " checker user. If the account is yours, switch on \"Comment my runs' verdicts\" with your GitHub"
                + " token: the token proves the account and takes the login over."));
            case "token" -> ResponseEntity.status(409).body(Map.of("error",
                "your login comes from your GitHub token: @" + standing.ghLoginOf(username)));
            default -> ResponseEntity.ok(standing.settings(username));
        };
    }

    /** Validates a GitHub PAT and re-issues the session cookie with it on board. */
    @PostMapping("/github-token")
    public ResponseEntity<?> saveGithubToken(@RequestBody TokenRequest req,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        @RequestAttribute(value = AuthInterceptor.JIRA_ATTR, required = false) String jiraToken) {
        if (req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "empty token"));

        java.util.Optional<String> who = github.ghUser(req.token().trim());
        if (who.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "GitHub rejected the token"));

        String cookie = codec.encode(username, tcToken, jiraToken, req.token().trim());

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, ResponseCookie.from(AuthInterceptor.COOKIE, cookie)
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/").maxAge(COOKIE_MAX_AGE).build().toString())
            .body(Map.of("githubUser", who.get()));
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

    /** Cancels the user's own pending auto-visa (removes their stored token with it); others' stay. */
    @PostMapping("/auto-visa-cancel")
    public ResponseEntity<?> cancelAutoVisa(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        visaSubs.cancel(pr, username);

        return ResponseEntity.ok(Map.of("armed", false));
    }

    /**
     * The PR's auto-visa as the user sees it: {@code armed}/{@code issue} for their own subscription,
     * {@code others} who armed one too, and {@code standingBy}, the user whose standing auto-visa posts
     * the verdict of the PR's run under way (then a one-shot one adds nothing).
     */
    @GetMapping("/auto-visa")
    public Map<String, Object> autoVisaStatus(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        VisaSubscriptions.Armed armed = visaSubs.armed(pr, username);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("armed", armed.issue() != null);
        if (armed.issue() != null)
            out.put("issue", armed.issue());
        out.put("others", armed.others());
        String owner = standing.visaOwnerOfRunUnderWay(pr);
        if (owner != null)
            out.put("standingBy", owner);

        return out;
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
            String url = jira.addComment(jiraToken, issue, visas.compose(pr, res.get(), pending.countSince(tcToken, pr, res.get().buildId())));

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
