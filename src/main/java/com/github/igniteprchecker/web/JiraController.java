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
import com.github.igniteprchecker.tc.TcClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
 * the TeamCity token; an Auto visa also has the server store it, encrypted, until that visa is posted
 * ({@link VisaSubscriptions}).
 */
@RestController
@RequestMapping("/api")
public class JiraController {
    private static final Logger log = LoggerFactory.getLogger(JiraController.class);

    private final JiraClient jira;
    private final TcClient tc;
    private final BlockerAnalyzer analyzer;
    private final SessionCodec codec;
    private final VisaService visas;
    private final VisaSubscriptions visaSubs;
    private final StandingVisas standing;
    private final GithubClient github;
    private final PendingCommits pending;
    private final boolean cookieSecure;

    public JiraController(JiraClient jira, TcClient tc,
        BlockerAnalyzer analyzer, SessionCodec codec, VisaService visas,
        VisaSubscriptions visaSubs, StandingVisas standing, GithubClient github, PendingCommits pending,
        @Value("${session.cookie-secure}") boolean cookieSecure) {
        this.jira = jira;
        this.tc = tc;
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
        @RequestAttribute(AuthInterceptor.SESSION_ATTR) SessionCodec.Session session,
        @RequestAttribute(AuthInterceptor.CHECKED_AT_ATTR) long checkedAt) {
        if (req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "empty token"));

        Optional<String> who;
        try {
            who = jira.myself(req.token().trim());
        }
        catch (RuntimeException e) {
            return uncheckedToken("JIRA", e);
        }
        if (who.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "JIRA rejected the token"));

        String cookie = codec.encode(session.withJiraToken(req.token().trim()), checkedAt);

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, SessionCookies.issued(cookie, cookieSecure).toString())
            .body(Map.of("jiraUser", who.get()));
    }

    /** Changes the standing options with no time zone from the browser. */
    public ResponseEntity<?> standingVisa(Boolean visa, Boolean rerun, Boolean gh, Boolean style, Boolean commands,
        Boolean suites, String tcToken, String username, String jiraToken, String ghToken) {
        return standingVisa(visa, rerun, gh, style, commands, suites, null, tcToken, username, jiraToken, ghToken);
    }

    /**
     * Changes the standing options named in the request and nothing else; a 412 names what the change
     * needs ({@code need}: jira, github or login), a 502 says the service could not check the token. {@code tz}
     * is the browser's time zone. Answers with the whole state, like the GET.
     */
    @PostMapping("/auto-visa-all")
    public ResponseEntity<?> standingVisa(@RequestParam(required = false) Boolean visa,
        @RequestParam(required = false) Boolean rerun,
        @RequestParam(required = false) Boolean gh,
        @RequestParam(required = false) Boolean style,
        @RequestParam(required = false) Boolean commands,
        @RequestParam(required = false) Boolean suites,
        @RequestParam(required = false) String tz,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        @RequestAttribute(value = AuthInterceptor.JIRA_ATTR, required = false) String jiraToken,
        @RequestAttribute(value = AuthInterceptor.GH_ATTR, required = false) String ghToken) {
        Optional<StandingVisas.Refusal> refused = standing.change(username, tcToken, jiraToken, ghToken,
            new StandingVisas.OptionChange(visa, rerun, gh, style, commands, suites), tz);
        if (refused.isPresent() && refused.get().need() == null)
            return ResponseEntity.status(502).body(Map.of("error", refused.get().error()));
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

    /** Proves a GitHub login with no time zone from the browser. */
    public ResponseEntity<?> saveGithubLogin(TokenRequest req, String tcToken, String username) {
        return saveGithubLogin(req, null, tcToken, username);
    }

    /**
     * Proves the user's GitHub login with a token they paste once, and switches PR commands on. GitHub names the
     * account the token belongs to; the checker keeps its login and id and forgets the token, which goes into no
     * cookie, no file and no log. A login typed in by hand would let anyone take someone else's, whose /run-all
     * would then go out under the claimant's TeamCity token. {@code tz}, the browser's time zone, goes with it.
     * Answers with the whole settings state.
     */
    @PostMapping("/github-login")
    public ResponseEntity<?> saveGithubLogin(@RequestBody TokenRequest req,
        @RequestParam(required = false) String tz,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        String token = req.token() == null ? "" : req.token().strip();
        if (token.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "empty token"));

        Optional<GithubClient.GhUser> who;
        try {
            who = github.ghIdentity(token);
        }
        catch (RuntimeException e) {
            return uncheckedToken("GitHub", e);
        }
        if (who.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "GitHub rejected the token"));

        return switch (standing.linkGhLogin(username, tcToken, who.get(), tz)) {
            case "app" -> ResponseEntity.status(409).body(Map.of("error", "this token is of @" + who.get().login()
                + ", the checker's own GitHub account: prove your own account with a token of it"));
            case "token" -> ResponseEntity.status(409).body(Map.of("error", "the GitHub token the checker keeps for"
                + " you proves @" + standing.ghLoginOf(username) + ", and that is your login"));
            default -> ResponseEntity.ok(standing.settings(username));
        };
    }

    /** Validates a GitHub PAT and re-issues the session cookie with it on board. */
    @PostMapping("/github-token")
    public ResponseEntity<?> saveGithubToken(@RequestBody TokenRequest req,
        @RequestAttribute(AuthInterceptor.SESSION_ATTR) SessionCodec.Session session,
        @RequestAttribute(AuthInterceptor.CHECKED_AT_ATTR) long checkedAt) {
        if (req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "empty token"));

        Optional<GithubClient.GhUser> who;
        try {
            who = github.ghIdentity(req.token().trim());
        }
        catch (RuntimeException e) {
            return uncheckedToken("GitHub", e);
        }
        if (who.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "GitHub rejected the token"));

        String cookie = codec.encode(session.withGhToken(req.token().trim()), checkedAt);

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, SessionCookies.issued(cookie, cookieSecure).toString())
            .body(Map.of("githubUser", who.get().login()));
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
        try {
            if (jira.myself(jiraToken).isEmpty())
                return ResponseEntity.status(412).body(Map.of("error", "JIRA rejected the stored token — re-enter it"));
        }
        catch (RuntimeException e) {
            return uncheckedToken("JIRA", e);
        }

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
        if (armed.refusedFor() != null)
            out.put("tokenRejectedFor", armed.refusedFor());
        out.put("others", armed.others());
        String owner = standing.visaOwnerOfRunUnderWay(pr);
        if (owner != null)
            out.put("standingBy", owner);

        return out;
    }

    /**
     * Whether the verdict of the PR's run {@code build} is final, or auto re-runs are still settling it: the
     * page shows the waves, which only the PR comment and the visa told before.
     */
    @GetMapping("/settling")
    public StandingVisas.Phase settling(@RequestParam int pr, @RequestParam long build) {
        return standing.phase(pr, build);
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

        long buildId = res.get().buildId();
        try {
            String url = jira.addComment(jiraToken, issue, visas.compose(pr, res.get(),
                pending.countSince(tcToken, pr, buildId), revision(tcToken, buildId)));

            return ResponseEntity.ok(Map.of("url", url));
        }
        catch (RestClientResponseException e) {
            if (jira.tokenRejected(jiraToken))
                return ResponseEntity.status(412).body(Map.of("error", "JIRA rejected your token — paste a new one"));

            return ResponseEntity.status(502)
                .body(Map.of("error", "JIRA rejected the comment (" + e.getStatusCode() + ")"));
        }
    }

    /** The service could not check a token: it may well be good, so the user is asked to try again, not for another. */
    private static ResponseEntity<?> uncheckedToken(String service, RuntimeException e) {
        log.warn("{} could not check a token: {}", service, e.toString());

        return ResponseEntity.status(502)
            .body(Map.of("error", service + " could not check the token right now — try again in a minute"));
    }

    /** The commit the build tested, for the visa to name; null when TeamCity does not say. */
    private String revision(String tcToken, long buildId) {
        try {
            return tc.buildRevision(tcToken, buildId).orElse(null);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** The PAT as pasted by the user. */
    public record TokenRequest(String token) {
    }
}
