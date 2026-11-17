package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.github.ClientRuns;
import com.github.igniteprchecker.github.ClientSpec;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.ThinClients;
import com.github.igniteprchecker.jira.StandingVisas;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
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
 * PostVisaController#igniteVerdict}), and only while the checker's account can write to GitHub. A thin client's PR
 * gets the verdict of its newest run of that client's suite. See {@link
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

    private final ClientRuns clientRuns;

    private final ThinClients projects;

    private final ClientVerdicts clients;

    private final String publicUrl;

    public PostToPrController(StandingVisas standing, GithubClient github, PostVisaController verdicts,
        JiraController runs, ClientRuns clientRuns, ClientVerdicts clients, ThinClients projects,
        @Value("${app.public-url:https://ignite-pr-checker.is-a.dev}") String publicUrl) {
        this.standing = standing;
        this.github = github;
        this.verdicts = verdicts;
        this.runs = runs;
        this.clientRuns = clientRuns;
        this.projects = projects;
        this.clients = clients;
        this.publicUrl = publicUrl;
    }

    /**
     * What Post to PR can do on the RunAll {@code build} of PR {@code pr}; see {@link PrPost}. TeamCity is asked who
     * started the run only while the run has no verdict comment and the checker has an account to post one from; when
     * it does not say, the row answers without the starter's option.
     */
    @GetMapping("/pr-comment")
    public PrPost row(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        String bot = bot();
        String url = standing.runCommentUrl(null, pr, build, 0).orElse(null);

        return new PrPost(bot, url, null,
            url != null || PrPost.NONE.equals(bot) ? null : option(pr, build, tcToken, username),
            url == null ? null : runs.commentCurrent(pr, build));
    }

    /**
     * What Post to PR can do on the run {@code build} of the selected client's suite for PR {@code pr}.
     * The verdict kept for the PR names the later run of the same commits it counts too, whose story may have
     * posted the comment. The row says nothing of the starter's option: the story of a run posts a client's verdict
     * comment, and the checker's records of options do not tell whether the run has one.
     */
    @GetMapping("/client/pr-comment")
    public PrPost clientRow(@RequestParam String repo, @RequestParam int pr, @RequestParam long build) {
        ClientSpec spec = projects.require(repo);
        Optional<ClientVerdicts.Verdict> kept = clients.kept(spec, pr).filter(v -> v.run().buildId() == build);
        long rerunId = kept.map(v -> v.run().rerunId()).orElse(0L);

        return new PrPost(bot(), standing.runCommentUrl(spec.repo(), pr, build, rerunId).orElse(null), null, null,
            standing.commentCurrent(spec.repo(), pr, build, rerunId, kept.map(ClientVerdicts.Verdict::result)));
    }

    /**
     * The comment a press of Post to PR would put into PR {@code pr} for the RunAll {@code build} now, as {@code text},
     * for the page to show before the press, and the {@code key} of that text for the press to send back. Nothing is
     * posted, and the press is not counted. Refused as {@link #post} is while the checker's account cannot write, or
     * the verdict is not final or not of the run the page shows.
     */
    @GetMapping("/pr-comment/preview")
    public ResponseEntity<?> preview(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        String bot = bot();
        if (!PrPost.READY.equals(bot))
            return noBot(bot);

        PostVisaController.Final verdict = verdicts.igniteVerdict(tcToken, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), "no RunAll build found for PR " + pr);

        return previewed(standing.commentPreview(username, tcToken, pr, build, verdict.res(), starter(tcToken, build)));
    }

    /** The comment a press would put in for the run of the selected client's suite, as {@link #preview} has it. */
    @GetMapping("/client/pr-comment/preview")
    public ResponseEntity<?> previewClient(@RequestParam String repo, @RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        ClientSpec spec = projects.require(repo);
        String bot = bot();
        if (!PrPost.READY.equals(bot))
            return noBot(bot);

        PostVisaController.Final verdict = verdicts.clientVerdict(tcToken, spec, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), ClientPrsController.NO_RUN);

        return previewed(standing.clientCommentPreview(username, spec.repo(), pr, build, verdict.run().rerunId(),
            clientBody(spec, pr, verdict)));
    }

    /** The answer with the comment's text and its key. */
    private static ResponseEntity<?> previewed(String text) {
        return ResponseEntity.ok(Map.of("text", text, "key", StandingVisas.textKey(text)));
    }

    /**
     * Puts the verdict of the RunAll {@code build} of PR {@code pr} into the PR. 409 when the checker's account cannot
     * write ({@code bot}), the verdict is not final ({@code going}) or is not of the run the page shows ({@code
     * stale}), or the comment is no longer the text of {@code key}, which {@link #preview} gave with the text the page
     * showed ({@code changed}); 503 when its writes wait; 429 when the PR had {@link
     * StandingVisas#PRESSES_PER_PR_HOUR} presses in the last hour; 502 when GitHub did not take the comment. Answers
     * with what Post to PR can do now, the comment's address, and whether the press edited the run's comment.
     */
    @PostMapping("/pr-comment")
    public ResponseEntity<?> post(@RequestParam int pr, @RequestParam long build,
        @RequestParam(required = false) String key,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username, HttpServletRequest req) {
        String bot = bot();
        if (!PrPost.READY.equals(bot))
            return noBot(bot);

        PostVisaController.Final verdict = verdicts.igniteVerdict(tcToken, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), "no RunAll build found for PR " + pr);

        StandingVisas.CommentPost posted = standing.postCommentFromPage(username, AuthInterceptor.by(req), tcToken, pr,
            build, verdict.res(), starter(tcToken, build), key);

        return answer(posted, pr);
    }

    /**
     * Puts the verdict of the run {@code build} of the selected client's suite for PR {@code pr} into
     * the PR, as {@link #post} puts a RunAll's. The comment mentions who started the newest run of the verdict, as the
     * run's own verdict comment does.
     */
    @PostMapping("/client/pr-comment")
    public ResponseEntity<?> postClient(@RequestParam String repo, @RequestParam int pr, @RequestParam long build,
        @RequestParam(required = false) String key,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username, HttpServletRequest req) {
        ClientSpec spec = projects.require(repo);
        String bot = bot();
        if (!PrPost.READY.equals(bot))
            return noBot(bot);

        PostVisaController.Final verdict = verdicts.clientVerdict(tcToken, spec, pr, build);
        if (verdict.refused() != null)
            return refused(verdict.refused(), ClientPrsController.NO_RUN);

        StandingVisas.CommentPost posted = standing.postClientCommentFromPage(username, AuthInterceptor.by(req),
            spec.repo(), pr, build, verdict.run().rerunId(), verdict.res(), clientBody(spec, pr, verdict), key);

        return answer(posted, pr);
    }

    /** The verdict comment of a client's run by how the run was queued, without the last line a press adds. */
    private Function<Boolean, String> clientBody(ClientSpec spec, int pr, PostVisaController.Final verdict) {
        ClientVerdicts.Run run = verdict.run();
        String login = run.starter() == null ? null : standing.provenGhLogin(run.starter());

        return fromPage -> clientRuns.pageCommentBody(spec, login, pr, run.buildId(), run.rerunId(), run.suiteName(),
            run.clientSha(), run.igniteSha(), verdict.res(), fromPage, ClientRuns.pageUrl(publicUrl, spec, pr));
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
     * Whose option may still post the run's verdict comment, see {@link StandingVisas#commentLine}; null when TeamCity
     * could not be asked who started the run. The row is what enables Post to PR, so a TeamCity error only costs the
     * word on the option.
     */
    private StandingVisas.CommentLine option(int pr, long build, String tcToken, String username) {
        try {
            return runs.commentLine(pr, build, tcToken, username);
        }
        catch (RuntimeException e) {
            log.warn("who started build {} is not known, the row of Post to PR tells nothing of their option: {}",
                build, e.toString());

            return null;
        }
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
                PrPost.READY, posted.url(), StandingVisas.CommentPost.EDITED.equals(posted.outcome()), null, true));
            case StandingVisas.CommentPost.NO_BOT -> noBot(PrPost.NONE);
            case StandingVisas.CommentPost.DEFERRED -> ResponseEntity.status(503).body(Map.of("error", "The checker's"
                + " GitHub account cannot write to this PR now: it reached an hourly limit of writes, or GitHub asked it"
                + " to wait. Try again later."));
            case StandingVisas.CommentPost.CAPPED -> ResponseEntity.status(429).body(Map.of("error", "The checker took "
                + StandingVisas.PRESSES_PER_PR_HOUR + " presses of Post to PR on this PR in the last hour, the most it"
                + " allows. Try again later."));
            case StandingVisas.CommentPost.CHANGED -> PostVisaController.changed();
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
     * {@code option}: whose option may still post the comment, see {@link StandingVisas#commentLine}; null where
     * the row does not tell. {@code current}: whether the run's comment still says what the verdict says, see {@link
     * StandingVisas#commentCurrent}; null when the checker cannot tell.
     */
    public record PrPost(String bot, String url, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean edited,
        @JsonUnwrapped StandingVisas.CommentLine option, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean current) {
        static final String READY = "ready";

        static final String NONE = "none";

        static final String REFUSED = "refused";

        static final String PAUSED = "paused";
    }
}
