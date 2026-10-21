package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post to JIRA on the PR page: the viewer posts the visa of the run the page shows to the PR's IGNITE ticket now, with
 * the JIRA token they saved in ⚙ for Visa my runs in JIRA, as their own comment in JIRA. The visa has the text the
 * run's automatic visa has, and a last line that says who posted it. A second press for the same run edits that
 * comment. The visa goes only for a final verdict: no run of the PR is going, no auto re-run settles it, TeamCity
 * errors left no part of it to check again, and a merged PR's verdict is not waiting for runs that went at the merge.
 * It goes only to the ticket the viewer confirmed, which the PR title names. A PR of the Python thin client gets the
 * visa of its newest run of the client's suite. See {@link StandingVisas#postVisaFromPage}.
 */
@RestController
@RequestMapping("/api")
public class PostVisaController {
    private final StandingVisas standing;

    private final BlockerAnalyzer analyzer;

    private final MergedVerdicts merged;

    private final TcClient tc;

    private final PrTitleController titles;

    private final ClientVerdicts clients;

    private final PythonClientRuns python;

    private final PrCommands commands;

    private final ClientPrsController clientPrs;

    public PostVisaController(StandingVisas standing, BlockerAnalyzer analyzer, MergedVerdicts merged, TcClient tc,
        PrTitleController titles, ClientVerdicts clients, PythonClientRuns python, PrCommands commands,
        ClientPrsController clientPrs) {
        this.standing = standing;
        this.analyzer = analyzer;
        this.merged = merged;
        this.tc = tc;
        this.titles = titles;
        this.clients = clients;
        this.python = python;
        this.commands = commands;
        this.clientPrs = clientPrs;
    }

    /**
     * Posts the visa of the RunAll {@code build} of PR {@code pr} to {@code issue}. 412 with {@code need} when the
     * viewer has no JIRA token stored, or JIRA refused it; 409 when the verdict is not final ({@code going}), is not of
     * the run the page shows ({@code stale}), or the PR title names another ticket or none ({@code ticket}); 502 when
     * JIRA did not take the visa. Answers with the visa row of the run.
     */
    @PostMapping("/visa")
    public ResponseEntity<?> post(@RequestParam int pr, @RequestParam long build, @RequestParam String issue,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username, HttpServletRequest req) {
        if (!standing.visaButton(username, null, pr, build).jiraReady())
            return noToken();

        Final verdict = igniteVerdict(tcToken, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), "no RunAll build found for PR " + pr);

        Optional<ResponseEntity<?>> wrongTicket = wrongTicket(() -> titles.titleNow(pr), issue);
        if (wrongTicket.isPresent())
            return wrongTicket.get();

        StandingVisas.PagePost posted = standing.postVisaFromPage(username, AuthInterceptor.by(req), tcToken, pr,
            build, issue, verdict.res());

        return answer(posted, username, null, pr, build, issue);
    }

    /**
     * Posts the visa of the run {@code build} of the client's suite for PR {@code pr} of the Python thin client to
     * {@code issue}, as {@link #post} posts a RunAll's. The run is the PR's newest run that tested its own commit, as
     * the page shows it.
     */
    @PostMapping("/client/visa")
    public ResponseEntity<?> postClient(@RequestParam int pr, @RequestParam long build, @RequestParam String issue,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username, HttpServletRequest req) {
        if (!standing.visaButton(username, python.repo(), pr, build).jiraReady())
            return noToken();

        Final verdict = clientVerdict(tcToken, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), ClientPrsController.NO_RUN);

        Optional<ResponseEntity<?>> wrongTicket = wrongTicket(() -> clientPrs.titleNow(pr), issue);
        if (wrongTicket.isPresent())
            return wrongTicket.get();

        AnalysisResult res = verdict.res();
        ClientVerdicts.Run run = verdict.run();
        String body = python.pageVisaBody(pr, run.buildId(), run.rerunId(), run.suiteName(), run.clientSha(),
            run.igniteSha(), res);
        StandingVisas.PagePost posted = standing.postClientVisaFromPage(username, AuthInterceptor.by(req),
            python.repo(), pr, build, issue, res, body);

        return answer(posted, username, python.repo(), pr, build, issue);
    }

    /**
     * The verdict of the RunAll {@code build} of PR {@code pr} the page shows, for a button that posts it, or why it
     * may not go now; see {@link Final}. A merged PR's verdict is the one kept at the merge, which only the runs that
     * went at the merge may still change. Any other verdict is final when no run of the PR is going on ci2 and no auto
     * re-run settles it.
     */
    Final igniteVerdict(String tcToken, int pr, long build) {
        Optional<AnalysisResult> atMerge = merged.verdict(pr);
        Optional<AnalysisResult> res = atMerge.isPresent() ? atMerge : analyzer.analyzeForAction(tcToken, pr);
        if (res.isEmpty())
            return Final.refused(Final.NONE);
        if (res.get().buildId() != build)
            return Final.refused(Final.STALE);

        boolean going = atMerge.isPresent() ? analyzer.runsAtMergeDue(pr)
            : res.get().live() || res.get().finishedAt() == 0
                || !StandingVisas.Phase.FINAL.equals(standing.phase(pr, build).phase())
                || !tc.currentUserBuilds(tcToken, pr).isEmpty();

        return finalOrNot(res.get(), null, going);
    }

    /**
     * The verdict of the run {@code build} of the client's suite for PR {@code pr} of the Python thin client the page
     * shows, as {@link #igniteVerdict} has a RunAll's: the PR's newest run that tested its own commit. It is final
     * when its story settled it and no run of the suite on the PR's branch is going.
     */
    Final clientVerdict(String tcToken, int pr, long build) {
        Optional<ClientVerdicts.Verdict> verdict = clients.verdict(tcToken, pr);
        if (verdict.isEmpty())
            return Final.refused(Final.NONE);
        if (verdict.get().result().buildId() != build)
            return Final.refused(Final.STALE);

        boolean going = !StandingVisas.Phase.FINAL.equals(commands.clientPhase(pr, build).phase())
            || tc.currentUserBuilds(tcToken, PythonClientRuns.branch(pr)).stream()
                .anyMatch(b -> python.suite().equals(b.buildTypeId()));

        return finalOrNot(verdict.get().result(), verdict.get().run(), going);
    }

    /** The verdict, or why it may not go: runs can still change it ({@code going}), or TeamCity errors left a part. */
    private Final finalOrNot(AnalysisResult res, ClientVerdicts.Run run, boolean going) {
        String refused = going ? Final.GOING : analyzer.stillRetrying(res) ? Final.RETRYING : null;

        return new Final(res, run, refused);
    }

    /** The answer to a press whose verdict may not go, by why; {@code none} says the PR has no run to post. */
    private static ResponseEntity<?> refused(String why, String none) {
        return switch (why) {
            case Final.NONE -> ResponseEntity.status(404).body(Map.of("error", none));
            case Final.STALE -> stale();
            case Final.GOING -> ResponseEntity.status(409).body(Map.of("error", "Post to JIRA waits for the final"
                + " verdict: runs of this PR can still change it.", "going", true));
            default -> ResponseEntity.status(409).body(Map.of("error", "TeamCity errors left part of this verdict"
                + " unchecked. The checker checks that part again, and Post to JIRA waits up to 20 minutes for that.",
                "going", true));
        };
    }

    /**
     * Why the visa does not go to {@code issue}, the ticket the viewer confirmed: the PR's {@code title} is not known,
     * or it names another ticket or none. Empty when it goes.
     */
    private static Optional<ResponseEntity<?>> wrongTicket(Supplier<Optional<String>> title, String issue) {
        Optional<String> known = title.get();
        if (known.isEmpty()) {
            return Optional.of(ResponseEntity.status(502).body(Map.of("error", "The checker could not get the PR's"
                + " title from GitHub, so it does not know the ticket. Try again in a minute.")));
        }

        Optional<String> ticket = StandingVisas.ticketIn(known.get());
        if (ticket.isEmpty()) {
            return Optional.of(ResponseEntity.status(409).body(Map.of("error", "The PR title names no IGNITE ticket,"
                + " so there is no ticket to post the visa to. Add the ticket key to the title.", "ticket", true)));
        }
        if (!ticket.get().equals(issue)) {
            return Optional.of(ResponseEntity.status(409).body(Map.of("error", "The PR title now names "
                + ticket.get() + ", not " + issue + ". Reload the page, then post the visa.", "ticket", true)));
        }

        return Optional.empty();
    }

    private ResponseEntity<?> answer(StandingVisas.PagePost posted, String username, String repo, int pr, long build,
        String issue) {
        return switch (posted.outcome()) {
            case StandingVisas.PagePost.POSTED, StandingVisas.PagePost.EDITED -> ResponseEntity.ok(new VisaRow(
                new StandingVisas.VisaLine(StandingVisas.VisaLine.POSTED, username, true, posted.issue(),
                    posted.url(), false), standing.visaButton(username, repo, pr, build),
                StandingVisas.PagePost.EDITED.equals(posted.outcome())));
            case StandingVisas.PagePost.REFUSED -> ResponseEntity.status(412).body(Map.of("error", "JIRA rejected your"
                + " JIRA token, so the checker dropped it and switched off Visa my runs in JIRA. Save a new token in"
                + " ⚙.", "need", "jira"));
            case StandingVisas.PagePost.NO_TOKEN -> noToken();
            default -> notTaken(posted.status(), issue);
        };
    }

    /** What JIRA answered a visa it did not take, as far as its HTTP status tells. */
    private static ResponseEntity<?> notTaken(int status, String issue) {
        if (status == 0)
            return ResponseEntity.status(502).body(Map.of("error", "JIRA did not answer — try again in a moment"));
        if (status == 403) {
            return ResponseEntity.status(502).body(Map.of("error", "JIRA refused the comment (HTTP 403). Check that"
                + " your JIRA account can comment on " + issue + "."));
        }
        if (status == 404) {
            return ResponseEntity.status(502).body(Map.of("error", "JIRA has no ticket " + issue + ", or your JIRA"
                + " account cannot see it (HTTP 404)."));
        }

        return ResponseEntity.status(502).body(Map.of("error", "JIRA did not take the visa (HTTP " + status
            + "). Try again later."));
    }

    private static ResponseEntity<?> noToken() {
        return ResponseEntity.status(412).body(Map.of("error", "Post to JIRA uses the JIRA token you save in ⚙, and you"
            + " have none there. Saving one also switches on Visa my runs in JIRA.", "need", "jira"));
    }

    private static ResponseEntity<?> stale() {
        return ResponseEntity.status(409).body(Map.of("error", "The checker has a newer verdict of this PR than the"
            + " page shows. Reload the page, then post the visa.", "stale", true));
    }

    /**
     * The verdict {@code res} of the run the PR page shows, and for a client's PR the {@code run} it is of, null for a
     * RunAll. {@code refused}: why nothing may be posted of it now, null when it may: the PR has no such run ({@link
     * #NONE}), the checker has a newer verdict than the page ({@link #STALE}), runs of the PR can still change it
     * ({@link #GOING}), or TeamCity errors left part of it to check again ({@link #RETRYING}).
     */
    record Final(AnalysisResult res, ClientVerdicts.Run run, String refused) {
        static final String NONE = "none";

        static final String STALE = "stale";

        static final String GOING = "going";

        static final String RETRYING = "retrying";

        static Final refused(String why) {
            return new Final(null, null, why);
        }
    }

    /**
     * The visa row of a run on the PR page: where its visa stands, see {@link StandingVisas#visaLine}, and what Post
     * to JIRA can do for the viewer. {@code edited}: the visa Post to JIRA just put in edited the viewer's own visa of
     * the run; null in any other answer.
     */
    public record VisaRow(@JsonUnwrapped StandingVisas.VisaLine line, @JsonUnwrapped StandingVisas.VisaButton button,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean edited) {
        /** The row the page polls for. */
        public static VisaRow of(StandingVisas.VisaLine line, StandingVisas.VisaButton button) {
            return new VisaRow(line, button, null);
        }
    }
}
