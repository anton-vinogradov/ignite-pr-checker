package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.ClientVerdicts;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrCommands;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.github.PythonClientRuns;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcResponseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the checker's page shows and does for a PR of the Python thin client, whose numbers overlap Ignite's: the list
 * of the client's open PRs, the verdict of a PR's newest run of the client's suite (see {@link ClientVerdicts}), its
 * runs on ci2, and the buttons that queue and cancel them. Run queues what {@code /run-all} in the PR queues: the
 * suite with the PR's head and Ignite's master (see {@link PythonClientRuns#queue}). The starter's options act on the
 * run as on the run of a {@code /run-all}.
 */
@RestController
@RequestMapping("/api/client")
public class ClientPrsController {
    /** Why a run was not queued on a PR with merge conflicts, and how to queue it all the same. */
    static final String CONFLICTS = "GitHub reports merge conflicts between this PR and its base branch. Resolve them"
        + " and run the suite again, or press Run anyway to queue it on the PR as it is";

    /** Why the page has no verdict of the PR. */
    static final String NO_RUN = "no run of the suite tested this PR's own commit";

    /** Why a new run was not queued after the user's runs of the suite on the PR were cancelled. */
    static final String PREVIOUS_RUN_CANCELLED = "Your previous run of the suite was cancelled, but queuing the new one"
        + " failed";

    /** How long a title is kept, and how many PRs GitHub is asked about in an hour, as for Ignite's PRs. */
    private static final long TITLE_KEPT_MS = PrTitleController.KEPT_MS;

    private static final long HOUR_MS = 60 * 60_000L;

    private final ClientVerdicts clients;

    private final PythonClientRuns python;

    private final TcClient tc;

    private final GithubClient github;

    private final PrHeads heads;

    private final YourPrs yours;

    private final StandingVisas standing;

    private final PrCommands commands;

    private final AuthInterceptor auth;

    private final LongSupplier clock;

    private final Map<Integer, Title> titles = new ConcurrentHashMap<>();

    /** When GitHub was asked for a title, over the last hour. */
    private final Deque<Long> askedTitles = new ArrayDeque<>();

    @Autowired
    public ClientPrsController(ClientVerdicts clients, PythonClientRuns python, TcClient tc, GithubClient github,
        PrHeads heads, YourPrs yours, StandingVisas standing, PrCommands commands, AuthInterceptor auth) {
        this(clients, python, tc, github, heads, yours, standing, commands, auth, System::currentTimeMillis);
    }

    ClientPrsController(ClientVerdicts clients, PythonClientRuns python, TcClient tc, GithubClient github,
        PrHeads heads, YourPrs yours, StandingVisas standing, PrCommands commands, AuthInterceptor auth,
        LongSupplier clock) {
        this.clients = clients;
        this.python = python;
        this.tc = tc;
        this.github = github;
        this.heads = heads;
        this.yours = yours;
        this.standing = standing;
        this.commands = commands;
        this.auth = auth;
        this.clock = clock;
    }

    /** The client's open PRs, badged as {@code /api/prs} badges Ignite's; who started a run only to a signed-in user. */
    @GetMapping("/prs")
    public List<PrSummary> prs(HttpServletRequest req) {
        boolean signedIn = auth.signedIn(req).isPresent();

        return clients.openPrs().stream().map(p -> listed(p, signedIn)).toList();
    }

    /** The client's PRs of the tabs Mine and Involved. */
    @GetMapping("/your-prs")
    public YourPrs.Lists yourPrs(@RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        YourPrs.Lists found = yours.of(username, clients.repo());

        return new YourPrs.Lists(found.login(), badged(found.mine()), badged(found.involved()), found.problem());
    }

    private List<PrSummary> badged(List<PrSummary> prs) {
        return prs == null ? null : prs.stream().map(p -> listed(p, true)).toList();
    }

    private PrSummary listed(PrSummary p, boolean signedIn) {
        String starter = signedIn ? clients.starter(p.number()) : null;
        Caveats.Glance g = clients.glance(p.number());
        if (g == null)
            return new PrSummary(p.number(), p.title(), p.url(), starter, null, null, p.headSha(), null);

        Caveats.Standing s = g.against(p.headSha());

        return new PrSummary(p.number(), p.title(), p.url(), starter, g.blockers(), s == Caveats.Standing.CLEAN,
            p.headSha(), s.name());
    }

    /** The verdict of the PR's newest run that tested its own commit; 404 with {@code noRun} when it has none. */
    @GetMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return served(pr, clients.verdict(token, pr));
    }

    /** The same, with the newest run asked of TeamCity and the verdict computed now: the page's ↻. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token) {
        return served(pr, clients.fresh(token, pr));
    }

    private ResponseEntity<?> served(int pr, Optional<ClientVerdicts.Verdict> verdict) {
        return verdict.<ResponseEntity<?>>map(v -> ResponseEntity.ok(Served.of(v, clients.head(pr), python.repo(),
                github.repo())))
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", NO_RUN, "noRun", true)));
    }

    /**
     * The runs of the client's suite people launched for the PR that are queued or running, each with who started it
     * ({@code by}) and whether that is the viewer ({@code mine}). Each is pinned to its commits when queued.
     */
    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam int pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        return tc.currentUserBuilds(token, PythonClientRuns.branch(pr)).stream()
            .filter(b -> python.suite().equals(b.buildTypeId()))
            .map(b -> {
                Map<String, Object> m = TriggerController.brief(b);
                String by = TcClient.starter(b);
                m.put("by", by == null ? "" : by);
                m.put("mine", username.equals(by));
                m.put("runAll", true);
                m.put("pinned", true);

                return m;
            }).toList();
    }

    /**
     * Queues the client's suite for the PR as {@code /run-all} does, and the user's options act on the run as on the
     * run of a {@code /run-all} (see {@link PrCommands#queueFromPage}). {@code replace} first cancels the user's own
     * runs of the suite on the PR. A PR that GitHub reports merge conflicts for gets 409 with {@code conflicts}, and
     * nothing is cancelled or queued, unless {@code force}. A run that cannot be put together gets 409 with why.
     */
    @PostMapping("/trigger")
    public ResponseEntity<?> trigger(@RequestParam int pr, @RequestParam(defaultValue = "false") boolean top,
        @RequestParam(defaultValue = "false") boolean replace, @RequestParam(defaultValue = "false") boolean force,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        if (!force && heads.spare() && github.reportsConflicts(python.repo(), pr))
            return ResponseEntity.status(409).body(Map.of("error", CONFLICTS, "conflicts", true));

        int[] replaced = {0};
        boolean[] queuing = {false};
        PythonClientRuns.Queued queued;
        try {
            queued = commands.queueFromPage(username, pr, replace, () -> {
                replaced[0] = replace ? python.cancelOwn(token, username, pr) : 0;
                queuing[0] = true;

                return python.queue(token, pr, top, "Ignite PR Checker: Run on the checker's page for"
                    + " https://github.com/" + python.repo() + "/pull/" + pr);
            });
        }
        catch (PythonClientRuns.NotQueued e) {
            return ResponseEntity.status(409).body(replaced[0] == 0 ? Map.of("error", e.getMessage())
                : Map.of("error", PREVIOUS_RUN_CANCELLED + ": " + e.getMessage(), "replaced", replaced[0]));
        }
        catch (RuntimeException e) {
            if (!queuing[0])
                throw e;

            RuntimeException refused = e instanceof TcResponseException tcError && tcError.getStatusCode().value() == 403
                ? new QueueRefusedException(tcError) : e;
            if (replaced[0] == 0)
                throw refused;

            throw new ReplacementNotQueuedException(replaced[0], refused, PREVIOUS_RUN_CANCELLED);
        }

        Map<String, Object> m = TriggerController.brief(queued.build());
        if (queued.belowTop() != 0) {
            m.put("belowTop", true);
            m.put("topRefusal", queued.belowTop());
        }

        return ResponseEntity.ok(Map.of("triggered", List.of(m), "replaced", replaced[0]));
    }

    /** Cancels the user's own runs of the suite on the PR, only those in {@code ids} when given. */
    @PostMapping("/cancel-all")
    public ResponseEntity<?> cancelAll(@RequestParam int pr, @RequestParam(required = false) List<Long> ids,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String token,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        return ResponseEntity.ok(Map.of("cancelled", tc.cancelOwnBuilds(token, PythonClientRuns.branch(pr), username,
            b -> python.suite().equals(b.buildTypeId()) && (ids == null || ids.contains(b.id())))));
    }

    /** Whether the verdict of the run {@code build} is final, or the story of its {@code /run-all} still settles it. */
    @GetMapping("/settling")
    public StandingVisas.Phase settling(@RequestParam int pr, @RequestParam long build) {
        return commands.clientPhase(pr, build);
    }

    /**
     * The visa row of the run {@code build}: where its visa stands, as far as the checker's records tell, see the
     * verdict kept, and what Post to JIRA can do for the viewer; see {@link PostVisaController}.
     */
    @GetMapping("/visa")
    public PostVisaController.VisaRow visaLine(@RequestParam int pr, @RequestParam long build,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        String revision = clients.kept(pr).map(ClientVerdicts.Verdict::result)
            .filter(r -> r.buildId() == build).map(AnalysisResult::revision).orElse(null);

        return PostVisaController.VisaRow.of(standing.clientVisaLine(username, python.repo(), pr, build, revision),
            standing.visaButton(username, python.repo(), pr, build));
    }

    /**
     * {@code title}: the PR's title, from the list of the client's open PRs, else from GitHub; 404 when GitHub has no
     * such PR or could not be asked. A merged or closed PR also gets when and at which commit, as Ignite's PRs get it
     * (see {@link PrTitleController#answer}). GitHub's answer is kept 5 minutes, and GitHub is asked about 60 PRs an
     * hour at most.
     */
    @GetMapping("/pr-title")
    public ResponseEntity<?> title(@RequestParam int pr) {
        return known(pr).filter(p -> p.title() != null)
            .<ResponseEntity<?>>map(p -> ResponseEntity.ok(PrTitleController.answer(p)))
            .orElseGet(() -> noTitle(pr));
    }

    /**
     * The PR's title as it is now, for the visa that goes to the ticket it names: from the list of the client's open
     * PRs, which follows a renamed title, for an open PR, else as the page gets it.
     */
    Optional<String> titleNow(int pr) {
        Optional<String> listed = listed(pr);

        return listed.isPresent() ? listed : knownTitle(pr);
    }

    /** The PR's title as the page gets it; empty when GitHub has no such PR or could not be asked. */
    Optional<String> knownTitle(int pr) {
        return known(pr).map(GithubClient.PullState::title);
    }

    /** The PR as the page gets it; empty when GitHub has no such PR or could not be asked. */
    private Optional<GithubClient.PullState> known(int pr) {
        long now = clock.getAsLong();
        Title t = titles.get(pr);
        if (t == null || now - t.at() >= TITLE_KEPT_MS) {
            t = titleOf(pr, now);
            if (t == null)
                return Optional.empty();

            titles.values().removeIf(old -> now - old.at() >= TITLE_KEPT_MS);
            titles.put(pr, t);
        }

        return Optional.ofNullable(t.pull());
    }

    private Title titleOf(int pr, long now) {
        Optional<String> listed = listed(pr);
        if (listed.isPresent())
            return new Title(new GithubClient.PullState(listed.get(), true, false), now);
        if (!mayAskGithub(now))
            return null;

        try {
            return new Title(python.pullState(pr).orElse(null), now);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** The title of the PR in the list of the client's open PRs; empty when the list does not hold it. */
    private Optional<String> listed(int pr) {
        return clients.openPrs().stream().filter(p -> p.number() == pr).map(PrSummary::title).filter(Objects::nonNull)
            .findFirst();
    }

    private synchronized boolean mayAskGithub(long now) {
        while (!askedTitles.isEmpty() && now - askedTitles.peekFirst() >= HOUR_MS)
            askedTitles.pollFirst();
        if (askedTitles.size() >= PrTitleController.ASKED_PER_HOUR)
            return false;

        askedTitles.addLast(now);

        return true;
    }

    private static ResponseEntity<?> noTitle(int pr) {
        return ResponseEntity.status(404).body(Map.of("error", "no title known for PR " + pr));
    }

    /** A PR, null for one GitHub does not have, and when it was learned. */
    private record Title(GithubClient.PullState pull, long at) {
    }

    /**
     * The verdict as the page gets it, as {@link AnalyzeController.Served} serves Ignite's: with how it stands and the
     * caveats behind that, and {@code pushed}, whether the PR's head moved past the client's commit the run tested.
     * The page's address names the client's repo with {@code repo}. {@code rerunId}: the later run of the same commits
     * whose failed tests the verdict counts too, 0 when none. {@code clientSha} and {@code igniteSha}: the commits the
     * run tested, null when TeamCity did not name them.
     */
    public record Served(@JsonUnwrapped AnalysisResult result, Caveats.Standing standing, List<String> caveats,
        Map<String, Object> pushed, String repo, long rerunId, String suiteName, String clientSha, String igniteSha,
        String clientRepo, String igniteRepo) {
        static Served of(ClientVerdicts.Verdict v, String head, String clientRepo, String igniteRepo) {
            AnalysisResult r = v.result();
            ClientVerdicts.Run run = v.run();

            return new Served(r, Caveats.standing(r, null), Caveats.of(r, null), pushed(run.clientSha(), head),
                PythonClientRuns.PAGE_REPO, run.rerunId(), run.suiteName(), run.clientSha(), run.igniteSha(),
                clientRepo, igniteRepo);
        }

        /**
         * Whether the PR's head moved past the client's commit the run tested, in the words of {@code /api/pending}:
         * not checked when either is not known.
         */
        private static Map<String, Object> pushed(String tested, String head) {
            Map<String, Object> out = new LinkedHashMap<>();
            boolean known = tested != null && head != null;
            boolean moved = known && !tested.equalsIgnoreCase(head);
            out.put("pending", moved);
            out.put("checked", known);
            if (moved) {
                out.put("ahead", 0);
                out.put("caveat", Caveats.headMoved());
                out.put("builtSha", shortSha(tested));
                out.put("headSha", shortSha(head));
                out.put("rewritten", false);
            }

            return out;
        }

        private static String shortSha(String sha) {
            return sha.substring(0, Math.min(7, sha.length()));
        }
    }
}
