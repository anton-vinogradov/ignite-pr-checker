package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The standing options in ⚙, and the JIRA "visa" among them: the verdict of each RunAll a user starts goes to the PR's
 * IGNITE ticket under their own JIRA Personal Access Token while their Visa my runs in JIRA is on. The token comes with
 * the switch of that option and stays on the server, encrypted, while the option is on ({@link StandingVisas}). The PR
 * page shows where the visa of the PR's run stands.
 */
@RestController
@RequestMapping("/api")
public class JiraController {
    /** How many runs TeamCity's answers are kept for: each is one TeamCity call, and a finished run never changes. */
    private static final int RUNS_KEPT = 1000;

    /** How often TeamCity is asked again about a run still going, at most: it may finish or be cancelled. */
    static final long RUN_GOING_ASKED_EVERY_MS = 60_000;

    private final JiraClient jira;
    private final TcClient tc;
    private final StandingVisas standing;
    private final MergedVerdicts merged;
    private final UserDirectory users;
    private final LongSupplier clock;

    /** What TeamCity said of each run asked about, and when. */
    private final Map<Long, AskedRun> runs = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, AskedRun> eldest) {
            return size() > RUNS_KEPT;
        }
    });

    @Autowired
    public JiraController(JiraClient jira, TcClient tc, StandingVisas standing, MergedVerdicts merged,
        UserDirectory users) {
        this(jira, tc, standing, merged, users, System::currentTimeMillis);
    }

    JiraController(JiraClient jira, TcClient tc, StandingVisas standing, MergedVerdicts merged, UserDirectory users,
        LongSupplier clock) {
        this.jira = jira;
        this.tc = tc;
        this.standing = standing;
        this.merged = merged;
        this.users = users;
        this.clock = clock;
    }

    /** Where to create a Personal Access Token in ASF JIRA (profile deep link for the UI hint). */
    @GetMapping("/jira-config")
    public Map<String, String> config() {
        return Map.of("patUrl", jira.baseUrl()
            + "/secure/ViewProfile.jspa?selectedTab=com.atlassian.pats.pats-plugin:jira-user-personal-access-tokens");
    }

    /** Changes the standing options with no time zone from the browser. */
    public ResponseEntity<?> standingVisa(Boolean visa, Boolean rerun, Boolean gh, Boolean style, Boolean commands,
        Boolean suites, String tcToken, String username, String jiraToken, String ghToken) {
        return standingVisa(visa, rerun, gh, style, commands, suites, null, new OptionTokens(ghToken, jiraToken),
            tcToken, username, null);
    }

    /**
     * Changes the standing options named in the request and nothing else; a 412 names what the change
     * needs ({@code need}: jira, github or login), a 409 says the checker has no GitHub account for an option that
     * writes to GitHub, a 502 that the service could not check the token. The token an option needs comes in the body,
     * {@code jiraToken} with the visa's switch and {@code githubToken} with that of an option that commits, and goes
     * into no cookie: the server keeps it while such an option is on. {@code tz} is the browser's time zone. The
     * journal names the login and the browser of {@code req} as who made the change. Answers with the whole state,
     * like the GET.
     */
    @PostMapping("/auto-visa-all")
    public ResponseEntity<?> standingVisa(@RequestParam(required = false) Boolean visa,
        @RequestParam(required = false) Boolean rerun,
        @RequestParam(required = false) Boolean gh,
        @RequestParam(required = false) Boolean style,
        @RequestParam(required = false) Boolean commands,
        @RequestParam(required = false) Boolean suites,
        @RequestParam(required = false) String tz,
        @RequestBody(required = false) OptionTokens tokens,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        HttpServletRequest req) {
        Optional<StandingVisas.Refusal> refused = standing.change(username, tcToken,
            tokens == null ? null : pasted(tokens.jiraToken()), tokens == null ? null : pasted(tokens.githubToken()),
            new StandingVisas.OptionChange(visa, rerun, gh, style, commands, suites), tz,
            req == null ? SettingsJournal.By.of(null, null) : AuthInterceptor.by(req));
        if (refused.isPresent() && refused.get().need() == null)
            return ResponseEntity.status(502).body(Map.of("error", refused.get().error()));
        if (refused.isPresent() && refused.get().equals(StandingVisas.Refusal.UNAVAILABLE))
            return ResponseEntity.status(409).body(Map.of("error", refused.get().error()));
        if (refused.isPresent())
            return ResponseEntity.status(412)
                .body(Map.of("error", refused.get().error(), "need", refused.get().need()));

        if (standing.visaOn(username))
            users.forgetJiraInCookie(username);

        return ResponseEntity.ok(standing.settings(username));
    }

    private static String pasted(String token) {
        return token == null || token.isBlank() ? null : token.strip();
    }

    /** What a change of the standing options may bring along: the token of the option it switches on. */
    public record OptionTokens(String githubToken, String jiraToken) implements CarriesToken {
        /** The GitHub token of an option that commits. */
        public OptionTokens(String githubToken) {
            this(githubToken, null);
        }

        @Override
        public String token() {
            return githubToken != null ? githubToken : jiraToken;
        }

        @Override
        public List<String> tokens() {
            return Arrays.asList(githubToken, jiraToken);
        }

        @Override
        public String toString() {
            return "OptionTokens[githubToken=" + Secrets.masked(githubToken) + ", jiraToken="
                + Secrets.masked(jiraToken) + "]";
        }
    }

    /** The logged-in user's standing options, and whether the server holds the tokens they run on. */
    @GetMapping("/auto-visa-all")
    public StandingVisas.Settings standingVisaStatus(@RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        return standing.settings(username);
    }

    /**
     * Whether the verdict of the PR's run {@code build} is final, or auto re-runs are still settling it: the
     * page shows the waves, which only the PR comment and the visa told before.
     */
    @GetMapping("/settling")
    public StandingVisas.Phase settling(@RequestParam int pr, @RequestParam long build) {
        return standing.phase(pr, build);
    }

    /**
     * The visa row of the PR's run {@code build} in the verdict's heading: where its visa stands, and what Post to JIRA
     * can do for the viewer; see {@link PostVisaController}.
     */
    @GetMapping("/visa")
    public PostVisaController.VisaRow visaRow(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        return PostVisaController.VisaRow.of(visaLine(pr, build, tcToken, username),
            standing.visaButton(username, null, pr, build));
    }

    /**
     * Where the visa of the PR's run {@code build} stands, for the line in the verdict's heading; see
     * {@link StandingVisas#visaLine}. TeamCity is asked about the run only when the checker's own records do not
     * tell.
     */
    public StandingVisas.VisaLine visaLine(int pr, long build, String tcToken, String username) {
        return standing.visaLine(username, pr, build, () -> run(tcToken, build), merged.verdict(pr).isPresent());
    }

    /**
     * What TeamCity says of the run. A finished run, and one TeamCity no longer has, are asked about once; a run still
     * going at most every {@link #RUN_GOING_ASKED_EVERY_MS}.
     */
    StandingVisas.Run run(String tcToken, long build) {
        long now = clock.getAsLong();
        AskedRun known = runs.get(build);
        if (known != null && (known.done() || now - known.at() < RUN_GOING_ASKED_EVERY_MS))
            return known.run();

        AskedRun asked;
        try {
            asked = tc.buildEnd(tcToken, build).map(b -> new AskedRun(runOf(b), "finished".equalsIgnoreCase(b.state()),
                now)).orElse(new AskedRun(StandingVisas.Run.UNKNOWN, false, now));
        }
        catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 404)
                throw e;

            asked = new AskedRun(StandingVisas.Run.UNKNOWN, true, now);
        }
        runs.put(build, asked);

        return asked.run();
    }

    private static StandingVisas.Run runOf(TcModel.Build b) {
        return new StandingVisas.Run(TcClient.starter(b), b.canceledInfo() != null,
            TcDates.epochSeconds(b.finishDate()) * 1000);
    }

    /** TeamCity's answer about a run, asked {@code at}; {@code done}: it no longer changes. */
    private record AskedRun(StandingVisas.Run run, boolean done, long at) {
    }
}
