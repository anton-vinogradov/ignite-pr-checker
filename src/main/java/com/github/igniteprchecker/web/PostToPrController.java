package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.jira.StandingVisas;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Post to PR on the PR page: the viewer puts the verdict of the run the page shows into the PR now, as a comment of
 * the checker's account, which writes every comment of the checker. The comment has the text of the run's automatic
 * verdict comment, and a last line that names who pressed. A run has one verdict comment: a press edits the one the
 * checker knows of the run, posted by Post my runs' verdicts in the PR or by an earlier press, and a new comment goes
 * in only when there is none. The verdict goes only when it is final, as for Post to JIRA ({@link
 * PostVisaController#igniteVerdict}), and only while the checker's account can write to GitHub. A PR of the Python
 * thin client gets the verdict of its newest run of the client's suite. See {@link
 * StandingVisas#postCommentFromPage}.
 */
@RestController
@RequestMapping("/api")
public class PostToPrController {
    private static final Logger log = LoggerFactory.getLogger(PostToPrController.class);

    private final StandingVisas standing;

    private final GithubClient github;

    private final PostVisaController verdicts;

    private final JiraController runs;

    private final PythonClientRuns python;

    private final ClientVerdicts clients;

    private final String publicUrl;

    public PostToPrController(StandingVisas standing, GithubClient github, PostVisaController verdicts,
        JiraController runs, PythonClientRuns python, ClientVerdicts clients,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl) {
        this.standing = standing;
        this.github = github;
        this.verdicts = verdicts;
        this.runs = runs;
        this.python = python;
        this.clients = clients;
        this.publicUrl = publicUrl;
    }

    /** What Post to PR can do on the RunAll {@code build} of PR {@code pr}; see {@link PrPost}. */
    @GetMapping("/pr-comment")
    public PrPost row(@RequestParam int pr, @RequestParam long build) {
        return new PrPost(bot(), standing.runCommentUrl(null, pr, build, 0).orElse(null), null);
    }

    /**
     * What Post to PR can do on the run {@code build} of the client's suite for PR {@code pr} of the Python thin
     * client. The verdict kept for the PR names the later run of the same commits it counts too, whose story may have
     * posted the comment.
     */
    @GetMapping("/client/pr-comment")
    public PrPost clientRow(@RequestParam int pr, @RequestParam long build) {
        long rerunId = clients.kept(pr).filter(v -> v.run().buildId() == build).map(v -> v.run().rerunId())
            .orElse(0L);

        return new PrPost(bot(), standing.runCommentUrl(python.repo(), pr, build, rerunId).orElse(null), null);
    }

    /**
     * Puts the verdict of the RunAll {@code build} of PR {@code pr} into the PR. 409 when the checker's account cannot
     * write ({@code bot}), the verdict is not final ({@code going}) or is not of the run the page shows ({@code
     * stale}); 503 when its writes wait; 502 when GitHub did not take the comment. Answers with what Post to PR can do
     * now, the comment's address, and whether the press edited the run's comment.
     */
    @PostMapping("/pr-comment")
    public ResponseEntity<?> post(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username, HttpServletRequest req) {
        String bot = bot();
        if (!PrPost.READY.equals(bot))
            return noBot(bot);

        PostVisaController.Final verdict = verdicts.igniteVerdict(tcToken, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), "no RunAll build found for PR " + pr);

        StandingVisas.CommentPost posted = standing.postCommentFromPage(username, AuthInterceptor.by(req), tcToken, pr,
            build, verdict.res(), starter(tcToken, build));

        return answer(posted, pr);
    }

    /**
     * Puts the verdict of the run {@code build} of the client's suite for PR {@code pr} of the Python thin client into
     * the PR, as {@link #post} puts a RunAll's. The comment mentions who started the newest run of the verdict, as the
     * run's own verdict comment does.
     */
    @PostMapping("/client/pr-comment")
    public ResponseEntity<?> postClient(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username, HttpServletRequest req) {
        String bot = bot();
        if (!PrPost.READY.equals(bot))
            return noBot(bot);

        PostVisaController.Final verdict = verdicts.clientVerdict(tcToken, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), ClientPrsController.NO_RUN);

        ClientVerdicts.Run run = verdict.run();
        String login = run.starter() == null ? null : standing.provenGhLogin(run.starter());
        StandingVisas.CommentPost posted = standing.postClientCommentFromPage(username, AuthInterceptor.by(req),
            python.repo(), pr, build, run.rerunId(), fromPage -> python.pageCommentBody(login, pr, run.buildId(),
                run.rerunId(), run.suiteName(), run.clientSha(), run.igniteSha(), verdict.res(), fromPage,
                PythonClientRuns.pageUrl(publicUrl, pr)));

        return answer(posted, pr);
    }

    /** Whether the checker's account can write to GitHub now; see {@link PrPost#bot}. */
    private String bot() {
        if (!github.writes())
            return PrPost.NONE;
        if (github.canWrite())
            return PrPost.READY;

        return github.writeState().pausedUntil() > 0 ? PrPost.PAUSED : PrPost.REFUSED;
    }

    /**
     * Who started the RunAll, as TeamCity names them; null when it does not. Their verdict comment mentions them, and
     * the comment Post to PR puts in does too. A TeamCity error only costs the mention.
     */
    private String starter(String tcToken, long build) {
        try {
            return runs.run(tcToken, build).starter();
        }
        catch (RuntimeException e) {
            log.warn("who started build {} is not known, its verdict from the page mentions nobody: {}", build,
                e.toString());

            return null;
        }
    }

    private static ResponseEntity<?> answer(StandingVisas.CommentPost posted, int pr) {
        return switch (posted.outcome()) {
            case StandingVisas.CommentPost.POSTED, StandingVisas.CommentPost.EDITED -> ResponseEntity.ok(new PrPost(
                PrPost.READY, posted.url(), StandingVisas.CommentPost.EDITED.equals(posted.outcome())));
            case StandingVisas.CommentPost.NO_BOT -> noBot(PrPost.NONE);
            case StandingVisas.CommentPost.DEFERRED -> ResponseEntity.status(503).body(Map.of("error", "The checker's"
                + " GitHub account cannot write to this PR now: it reached its limit of writes for an hour, or GitHub"
                + " asked it to wait. Try again later."));
            default -> notTaken(posted.status(), pr);
        };
    }

    /** What GitHub answered a comment it did not take, as far as its HTTP status tells. */
    private static ResponseEntity<?> notTaken(int status, int pr) {
        if (status == 0)
            return ResponseEntity.status(502).body(Map.of("error", "GitHub did not answer — try again in a moment"));
        if (status == 401) {
            return ResponseEntity.status(502).body(Map.of("error", "GitHub refused the token the checker writes with"
                + " (GITHUB_TOKEN), so the comment did not go in.", "bot", PrPost.REFUSED));
        }
        if (status == 403 || status == 404) {
            return ResponseEntity.status(502).body(Map.of("error", "GitHub did not let the checker's account comment"
                + " on PR #" + pr + " (HTTP " + status + ")."));
        }

        return ResponseEntity.status(502).body(Map.of("error", "GitHub did not take the comment (HTTP " + status
            + "). Try again later."));
    }

    /** The answer to a press while the checker's account cannot write to GitHub, {@code bot} saying why. */
    private static ResponseEntity<?> noBot(String bot) {
        String why = switch (bot) {
            case PrPost.NONE -> "This checker has no GitHub account to write with (GITHUB_TOKEN), so it cannot post"
                + " to the PR. Its operator can add one.";
            case PrPost.PAUSED -> "GitHub asked the checker to wait before it writes again. Try again later.";
            default -> "GitHub refuses the token the checker writes with (GITHUB_TOKEN), so the checker cannot post"
                + " to the PR now.";
        };

        return ResponseEntity.status(409).body(Map.of("error", why, "bot", bot));
    }

    /** The answer to a press whose verdict may not go, by why; {@code none} says the PR has no run to post. */
    private static ResponseEntity<?> refused(String why, String none) {
        return switch (why) {
            case PostVisaController.Final.NONE -> ResponseEntity.status(404).body(Map.of("error", none));
            case PostVisaController.Final.STALE -> ResponseEntity.status(409).body(Map.of("error", "The checker has a"
                + " newer verdict of this PR than the page shows. Reload the page, then post the verdict.", "stale",
                true));
            case PostVisaController.Final.GOING -> ResponseEntity.status(409).body(Map.of("error", "Post to PR waits"
                + " for the final verdict: runs of this PR can still change it.", "going", true));
            default -> ResponseEntity.status(409).body(Map.of("error", "TeamCity errors left part of this verdict"
                + " unchecked. The checker checks that part again, and Post to PR waits up to 20 minutes for that.",
                "going", true));
        };
    }

    /**
     * What Post to PR can do on a run: whether the checker's account can write to GitHub ({@code bot}: {@link
     * #READY}; {@link #NONE}, no GITHUB_TOKEN is set; {@link #REFUSED}, GitHub refuses it; {@link #PAUSED}, GitHub
     * asked the account to wait), and the address of the run's verdict comment, which a press edits; null when the
     * checker knows none. {@code edited}: the press just made edited the run's comment; null in any other answer.
     */
    public record PrPost(String bot, String url, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean edited) {
        static final String READY = "ready";

        static final String NONE = "none";

        static final String REFUSED = "refused";

        static final String PAUSED = "paused";
    }
}
