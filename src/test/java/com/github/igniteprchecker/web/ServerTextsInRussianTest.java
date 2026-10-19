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
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.github.CommitTokens;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.health.ServiceHealth;
import com.github.igniteprchecker.jira.StandingVisas;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * The server writes its texts in English: the reasons and caveats of a verdict, kept in the analysis cache and quoted
 * by PR comments and visas, the answers of the endpoints the PR page, the login form and ⚙ use, and what the status
 * page shows of the service's health, of GITHUB_TOKEN, of the snapshots and of the settings. The Russian page puts
 * each into Russian with the catalog's templates of the server's texts. Each text here comes from the server's own
 * code: called where it can be, read from the source where it is made of literals, and written out with its values
 * where it has some, in a sample that must still carry each fixed part of the source. A reason, which only an analysis
 * makes, is written as BlockerAnalyzer puts it together. A broken suite's problem in TeamCity's own words stays as
 * TeamCity wrote it.
 */
class ServerTextsInRussianTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Two or more English words in a row, a name joined by "-" or "/" one word. */
    private static final Pattern ENGLISH_PHRASE =
        Pattern.compile("(?<![\\w/.-])[A-Za-z][a-z'./-]*[a-z](?:,?\\s+[A-Za-z][a-z'./-]*[a-z])+(?![\\w/-])");

    /** The names the Russian keeps: of services, a job and a step on GitHub, an option of TeamCity, GitHub scopes. */
    private static final List<String> NAMES = List.of("Check java code", "Run abandoned tests checks", "Bad Gateway",
        "Licenses Headers", "repo, workflow");

    /** The server's code that answers the PR page, the login form and ⚙. */
    private static final List<Path> SERVER_CODE = List.of(Path.of("src/main/java/com/github/igniteprchecker/web"),
        Path.of("src/main/java/com/github/igniteprchecker/jira"));

    /** Where a text for a page starts: the error of an answer, or why an option or an action was refused. */
    private static final Pattern TEXT_STARTS =
        Pattern.compile("Map\\.of\\(\"error\",|new Refusal\\((?:\"\\w+\"|null|\\d+),");

    /** The server's code that writes the texts of the status page. */
    private static final List<Path> STATUS_CODE = Stream.of("health/ServiceHealth.java",
        "github/AppAccountChecks.java", "github/AppWrites.java", "config/EffectiveConfig.java",
        "persist/CacheStore.java").map(f -> Path.of("src/main/java/com/github/igniteprchecker", f)).toList();

    /**
     * Where a text of the status page starts: a problem of the service's health, what a check of GITHUB_TOKEN found
     * (with its text for viewers who are not operators after it), a problem or a value of a setting, why snapshots are
     * off, and what GitHub answered a refused write.
     */
    private static final Pattern STATUS_TEXT_STARTS = Pattern.compile("Problem\\.(?:warn|error)\\("
        + "|Check\\.(?:ok|unknown|warn|error)\\(|(new Check\\()(?:Check\\.\\w+|\"\\w+\"),"
        + "|\\bproblem\\((?!String)|\\bshow\\(\"\\w+\",|\\boff = |\\brefused = ");

    /** One Java string literal and nothing else. */
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The texts in the server's code that none of these pages shows, and why. */
    private static final Map<String, String> NOT_SHOWN = Map.of(
        "Only the operator can see who uses the service.", "the Users tab answers a refusal in its own words",
        "no such language: use en or ru", "⚙ sends en or ru only",
        "no such service: ", "⚙ asks about TeamCity, JIRA and GitHub only",
        "no title known for PR ", "the PR page shows no failed title");

    @Test
    void everyTextTheServerSendsThePagesReadsInRussian() throws Exception {
        List<String> texts = new ArrayList<>(samples());
        for (CodeText t : textsInCode()) {
            if (t.whole() != null && !NOT_SHOWN.containsKey(t.whole()) && !texts.contains(t.whole()))
                texts.add(t.whole());
        }
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

    /**
     * A text with values in it is checked on a sample written out here. Reworded in the code, "TeamCity could not check
     * the token (HTTP …)" must not stay green on its old sample: the sample must still carry each fixed part.
     */
    @Test
    void eachSampleStillMatchesItsTextInTheCode() throws Exception {
        Set<String> samples = samples();
        List<CodeText> inCode = textsInCode();
        List<String> lost = new ArrayList<>();
        for (CodeText t : inCode) {
            for (String part : t.parts()) {
                if (part.chars().anyMatch(Character::isLetter) && !NOT_SHOWN.containsKey(part)
                    && samples.stream().noneMatch(sample -> sample.contains(part)))
                    lost.add(t.where() + ": " + part);
            }
        }

        assertThat(lost).isEmpty();
        assertThat(inCode).extracting(CodeText::whole).as("the texts read from the code")
            .contains("no suites to re-run", "token required", "GitHub rejected the token — paste a new one",
                "GitHub did not name the scopes of GITHUB_TOKEN", "disabled (persist.enabled=false)",
                "not set (all interfaces)");
        assertThat(inCode).flatExtracting(CodeText::parts).as("the fixed parts of the status page's texts")
            .contains("GitHub answered 403 to a write: ", "expires in more than ", "warm cycle running for ");
        assertThat(inCode).filteredOn(t -> !t.parts().isEmpty()).as("the texts with values").hasSizeGreaterThan(5);
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
        out.addAll(List.of("TeamCity could not check the token (HTTP 503) — try again in a moment",
            "no RunAll build found for PR 13575", "JIRA could not check the token right now — try again in a minute",
            "this token is of @ignite-pr-checker, the checker's own GitHub account — paste a token of your own account",
            "TeamCity answered 500 — try again in a moment", "update failed: HTTP 404"));

        for (int[] counts : new int[][] {{2, 0, 1, 0}, {0, 1, 0, 0}, {0, 0, 3, 0}, {0, 0, 0, 1}, {1, 1, 1, 1}, {3, 0, 0, 0}})
            out.add(privateText(StandingVisas.class, "suitesLabel", counts[0], counts[1], counts[2], counts[3]));

        for (ServiceHealth.Problem p : StatusTexts.problems())
            out.add(p.text());
        for (ServiceHealth.Problem p : StatusTexts.problemsWithoutDates())
            out.add(p.text());
        for (GithubClient.AppAccount.Check c : StatusTexts.checks()) {
            out.add(c.text());
            if (c.dateless() != null)
                out.add(c.dateless());
        }
        out.addAll(StatusTexts.SETTING_WORDS);
        out.addAll(StatusTexts.OPERATORS_UNNAMED);
        out.addAll(StatusTexts.SNAPSHOTS_OFF);
        out.addAll(List.of(StatusTexts.WRITE_REFUSED, "user alice", "a user"));
        AdminActions admin = new AdminActions(new AdminProperties(null), JSON);
        for (AdminActions.Action action : AdminActions.Action.values())
            out.add(admin.claim("bob", action).orElseThrow().message());

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

    /**
     * A text for a page in the server's code, at {@code where}: the {@code whole} of it when it is made of literals
     * alone, else the literal {@code parts} between its values.
     */
    private record CodeText(String where, String whole, List<String> parts) {
    }

    /** The texts for the pages in the server's code, each branch of a {@code ?:} a text of its own. */
    private static List<CodeText> textsInCode() throws IOException {
        List<CodeText> out = new ArrayList<>();
        for (Path dir : SERVER_CODE) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(f -> f.toString().endsWith(".java")).sorted().toList();
            }
            for (Path f : files)
                collectAll(f, TEXT_STARTS, out);
        }
        for (Path f : STATUS_CODE)
            collectAll(f, STATUS_TEXT_STARTS, out);

        return out;
    }

    /** The texts in a file that start where {@code starts} finds; a check's text for others follows its own text. */
    private static void collectAll(Path file, Pattern starts, List<CodeText> out) throws IOException {
        String code = Files.readString(file, UTF_8);
        Matcher m = starts.matcher(code);
        while (m.find()) {
            String text = argument(code, m.end());
            collect(file.getFileName().toString(), text, out);
            int after = m.end() + text.length();
            if (m.groupCount() > 0 && m.group(1) != null && code.charAt(after) == ',')
                collect(file.getFileName().toString(), argument(code, after + 1), out);
        }
    }

    private static void collect(String where, String expr, List<CodeText> out) {
        List<Integer> asks = topLevel(expr, "?");
        if (!asks.isEmpty()) {
            int colon = elseOf(expr, asks.get(0));
            collect(where, expr.substring(asks.get(0) + 1, colon), out);
            collect(where, expr.substring(colon + 1), out);
            return;
        }

        List<String> operands = new ArrayList<>();
        int from = 0;
        for (int plus : topLevel(expr, "+")) {
            operands.add(expr.substring(from, plus).strip());
            from = plus + 1;
        }
        operands.add(expr.substring(from).strip());
        List<String> literals = operands.stream().filter(o -> LITERAL.matcher(o).matches())
            .map(o -> o.substring(1, o.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\")).toList();
        if (literals.isEmpty())
            return;

        out.add(literals.size() == operands.size() ? new CodeText(where, String.join("", literals), List.of())
            : new CodeText(where, null, literals));
    }

    /**
     * The source of the argument that starts at {@code from}: up to the comma or the bracket that ends it, or the end
     * of the statement for the value of an assignment.
     */
    private static String argument(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'')
                i = endOfLiteral(code, i);
            else if ("([{".indexOf(c) >= 0)
                depth++;
            else if (")]}".indexOf(c) >= 0 && depth-- == 0 || (c == ',' || c == ';') && depth == 0)
                return code.substring(from, i);
        }

        throw new IllegalStateException("no end of the argument at " + from);
    }

    /** Where the {@code :} of the {@code ?} at {@code ask} stands. */
    private static int elseOf(String expr, int ask) {
        int open = 0;
        for (int at : topLevel(expr, "?:")) {
            if (at <= ask)
                continue;
            if (expr.charAt(at) == '?')
                open++;
            else if (open-- == 0)
                return at;
        }

        throw new IllegalStateException("no : for the ? in " + expr);
    }

    /** Where the signs of {@code signs} stand in {@code expr} outside literals and brackets. */
    private static List<Integer> topLevel(String expr, String signs) {
        List<Integer> out = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"' || c == '\'')
                i = endOfLiteral(expr, i);
            else if ("([{".indexOf(c) >= 0)
                depth++;
            else if (")]}".indexOf(c) >= 0)
                depth--;
            else if (depth == 0 && signs.indexOf(c) >= 0)
                out.add(i);
        }

        return out;
    }

    private static int endOfLiteral(String code, int start) {
        for (int i = start + 1; ; i++) {
            if (code.charAt(i) == '\\')
                i++;
            else if (code.charAt(i) == code.charAt(start))
                return i;
        }
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
