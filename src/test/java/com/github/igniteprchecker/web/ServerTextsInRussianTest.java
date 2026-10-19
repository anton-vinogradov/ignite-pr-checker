package com.github.igniteprchecker.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BrokenRuns;
import com.github.igniteprchecker.analysis.Caveats;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.BrokenGroup;
import com.github.igniteprchecker.analysis.model.ShrunkSuite;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.github.CommitTokens;
import com.github.igniteprchecker.jira.StandingVisas;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * The server writes its texts in English: the reasons and caveats of a verdict, kept in the analysis cache and quoted
 * by PR comments and visas, and the answers of the endpoints the PR page, the login form and ⚙ use. The Russian page
 * puts each into Russian with the catalog's templates of the server's texts. Each text here comes from the server's own
 * code where the code can be called; a reason, which only an analysis makes, is written as BlockerAnalyzer puts it
 * together. A broken suite's problem in TeamCity's own words stays as TeamCity wrote it.
 */
class ServerTextsInRussianTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two or more English words in a row, a name joined by "-" or "/" one word. */
    private static final Pattern ENGLISH_PHRASE =
        Pattern.compile("(?<![\\w/.-])[A-Za-z][a-z'./-]*[a-z](?:,?\\s+[A-Za-z][a-z'./-]*[a-z])+(?![\\w/-])");

    /** The names the Russian keeps: of services, a job and a step on GitHub, and an option of TeamCity. */
    private static final List<String> NAMES = List.of("Check java code", "Run abandoned tests checks", "Bad Gateway",
        "Licenses Headers");

    @Test
    void everyTextTheServerSendsThePagesReadsInRussian() throws Exception {
        List<String> texts = new ArrayList<>(samples());
        String catalog = Files.readString(Path.of("src/main/resources/static/ru.json"), UTF_8);
        JsonNode out = PageFunctions.run("common.js", "const CATALOG = " + catalog + ";\n"
            + "const TEXTS = " + JSON.writeValueAsString(texts) + ";\n" + """
            const sections = Object.entries(CATALOG);
            RU = new Map(sections.flatMap(([, entries]) => Object.entries(entries)));
            RU_SERVER = sections.filter(([name]) => name.startsWith('server')).flatMap(([, e]) => Object.entries(e));
            LANG = 'ru';
            out(TEXTS.map(t => tr(t)));
            """);

        List<String> left = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String ru = out.get(i).asText();
            String bare = ru;
            for (String name : NAMES)
                bare = bare.replace(name, "");
            Matcher phrase = ENGLISH_PHRASE.matcher(bare);
            if (phrase.find() || !ru.matches("(?s).*\\p{IsCyrillic}.*"))
                left.add(texts.get(i) + " → " + ru);
        }

        assertThat(left).isEmpty();
        assertThat(texts).as("the texts checked").hasSizeGreaterThan(60);
    }

    /** The server's texts, from its own code where it can be called. */
    private static Set<String> samples() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (AnalysisResult r : List.of(BrokenRuns.buildFailed(false), BrokenRuns.buildFailed(true),
            BrokenRuns.buildPassedOnRerun(), BrokenRuns.artifactsGlitch(), gaps(1), gaps(3))) {
            out.addAll(Caveats.of(r, 2));
            out.addAll(Caveats.of(r, 1));
            for (BrokenGroup g : BrokenGroup.of(r)) {
                if (g.kind() == BrokenGroup.Kind.PROBLEM && g.title().startsWith("Failed to "))
                    continue;
                out.add(g.title());
                out.add(g.caveat());
            }
        }
        out.add(Caveats.headMoved());

        out.add(AuthInterceptor.REVOKED);
        out.add(AuthInterceptor.ENDED);
        out.add(LoginController.EARLIER_LOGIN);
        out.add(LoginController.LOGGED_OUT_HERE + " " + LoginController.othersGoOn(1) + " "
            + LoginController.OPTIONS_GO_ON);
        out.add(LoginController.LOGGED_OUT_HERE + " " + LoginController.othersGoOn(3));
        for (Duration wait : List.of(Duration.ofSeconds(30), Duration.ofMinutes(7))) {
            out.add(privateText(LoginController.class, "addressUsedUp", wait));
            out.add(privateText(LoginController.class, "checksUsedUp", wait));
        }
        out.add(TokenFormat.NOT_A_TOKEN);
        out.add(ApiExceptionHandler.TOKEN_REJECTED);
        out.add(ApiExceptionHandler.FORBIDDEN);
        out.add(ApiExceptionHandler.QUEUE_FORBIDDEN);
        out.add(ApiExceptionHandler.PREVIOUS_RUNALL_CANCELLED + " — try RunAll again");
        out.add(ApiExceptionHandler.PREVIOUS_RUNALL_CANCELLED + ": " + ApiExceptionHandler.FORBIDDEN);
        out.add(ApiExceptionHandler.BAD_JSON);
        out.add(String.valueOf(new ApiExceptionHandler(null, null)
            .noAnswer(new ResourceAccessException("reset")).getBody().get("error")));
        out.add(StandingVisas.Refusal.UNAVAILABLE.error());
        out.add("PR commands are " + StandingVisas.Refusal.UNAVAILABLE.error());
        String classic = "ghp_" + "a".repeat(36);
        out.add(CommitTokens.note(classic, List.of("public_repo")));
        out.add(CommitTokens.note(classic, List.of("repo")));
        out.add(CommitTokens.refusal(classic, List.of("repo", "delete_repo", "workflow")).orElseThrow());
        out.add(CommitTokens.refusal(classic, List.of("gist")).orElseThrow());
        out.addAll(List.of("token required", "TeamCity rejected this token",
            "TeamCity could not check the token (HTTP 503) — try again in a moment", "no RunAll build found for PR 13575",
            "no suites to re-run", "Link GitHub is not set up on this checker",
            "JIRA could not check the token right now — try again in a minute",
            "JIRA rejected the token — paste a new one", "Visa my runs in JIRA needs your JIRA token",
            "this token is of @ignite-pr-checker, the checker's own GitHub account — paste a token of your own account",
            "GitHub rejected the token — paste a new one", "this option needs your GitHub token for commits",
            "PR commands need your linked GitHub account"));

        for (int[] counts : new int[][] {{2, 0, 1, 0}, {0, 1, 0, 0}, {0, 0, 3, 0}, {0, 0, 0, 1}, {1, 1, 1, 1}, {3, 0, 0, 0}})
            out.add(privateText(StandingVisas.class, "suitesLabel", counts[0], counts[1], counts[2], counts[3]));

        out.addAll(List.of("GitHub could not list the PR's files", "TeamCity could not be asked how they ran",
            "the RunAll is still going; only the first 1000 runs were read",
            "the checker looked up master history for only 20 of 31 failed tests of changed classes",
            "it waits for a maintainer to approve its workflow run", "its job was cancelled",
            "its step ended as timed_out", "GitHub could not be asked",
            "GitHub shows no Check java code job for it (Ignite's workflow does not run on a PR that conflicts with master)"));

        out.addAll(List.of(
            "not seen failing in 100 master runs; failed all 3 runs on this branch",
            "not seen failing in only 4 master runs on JDK 17; failed the last 3 of 5 runs on this branch",
            "not seen failing in 1 master run; failed the only run on this branch",
            "no master history on JDK 21 (can't prove pre-existing); failed all 2 runs on commit 3fdf446 (the 2 earlier "
                + "runs were on other code)",
            "pre-existing: fails 7/33 on master",
            "rare on master: fails 1/100 on JDK 17, passed the last 60; failed all 4 runs on this branch",
            "fails 60/100 on master at TEST_SCALE_FACTOR=1.0, but 2/40 on other PR branches at 0.1; too few failures on "
                + "this code yet to outweigh other PRs",
            "flaky on other PR branches: fails 14/131 in 13 other PRs; not seen failing in 101 master runs",
            "not seen failing in 50 master runs; failed all 3 runs on this branch; fails 3/40 in 4 other PRs",
            "failed the latest run; TeamCity gave no commit for it, so the 1 earlier run does not count",
            "failed the only run on commit 3fdf446; the 3 earlier runs were on other code",
            "failed the last 2 of 3 runs, after a pass; TeamCity gave no commits to prove that was the same code",
            "flaky on branch: failed only the latest of 4 runs (passed just before, but TeamCity gave no commits to "
                + "prove that was the same code)",
            "flaky on branch: failed only the latest of 2 runs on commit 3fdf446 (an earlier run on the same code "
                + "passed; the 1 earlier run was on other code)",
            "not failing in the last finished run (passed on re-run)",
            "could not verify (TeamCity error: 502 Bad Gateway)"));

        return out;
    }

    /** A verdict whose run left gaps: suites that never ran, that ran fewer tests, unchecked tests, a newer run. */
    private static AnalysisResult gaps(int n) {
        List<TestVerdict> unchecked = new ArrayList<>();
        for (int i = 0; i < n; i++)
            unchecked.add(new TestVerdict(i, "T" + i, "S", 1, "S", null, false, false, "could not verify", "", 0));
        List<ShrunkSuite> shrunk = new ArrayList<>();
        for (int i = 0; i < n; i++)
            shrunk.add(new ShrunkSuite("S" + i, "Suite " + i, i, 10, 40, 75));

        return new AnalysisResult(13575, 9001, "pull/13575/head", 0, List.of(), List.of(), List.of(), List.of(),
            shrunk, 10, 0, true, n, true, 9002, 0, 0, 0, 0, List.of(), List.of(), unchecked, 0);
    }

    private static String privateText(Class<?> owner, String name, Object... args) throws Exception {
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return (String)m.invoke(null, args);
            }
        }

        throw new IllegalArgumentException(name);
    }
}
