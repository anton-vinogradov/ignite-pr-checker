package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The README and the tour told an operator to roll back with a cp over the running jar, which cost the last snapshot,
 * listed users.json among the state files written within a second, and told users of an open PR list without its
 * limit of 50 and of auto re-runs without the suites TeamCity cancelled. Each fact is checked against the code or the
 * command it describes, in both languages.
 */
class DocFactsTest {
    private static final Path README = Path.of("README.md");

    private static final Path README_RU = Path.of("README.ru.md");

    private static final Path TOUR = Path.of("docs/features.md");

    private static final Path TOUR_RU = Path.of("docs/features.ru.md");

    private static final Pattern FILE_NAME = Pattern.compile("String fileName\\(\\)\\s*\\{\\s*return \"([^\"]+)\";");

    private static final Pattern DURABLE = Pattern.compile("boolean durable\\(\\)\\s*\\{\\s*return true;");

    private static final Pattern QUOTED_FILE = Pattern.compile("`([a-z-]+\\.json)`");

    private static final Pattern OPEN_PRS_PAGE = Pattern.compile("pulls\\?state=open[^\"]*per_page=(\\d+)");

    @Test
    void theCacheDirectoryTableTellsStateFromCaches() throws IOException {
        Map<String, Boolean> durable = snapshotFiles();
        assertThat(durable).containsEntry("standing-visas.json", true).containsEntry("users.json", false)
            .hasSizeGreaterThanOrEqualTo(13);

        assertTable(README, "### The cache directory", "Caches:", durable);
        assertTable(README_RU, "### Каталог кэша", "Кэши:", durable);
    }

    /** The jar a JVM runs from must stay readable to it until it exits: its shutdown hook still loads classes. */
    @Test
    void theRollbackLeavesTheRunningJarAlone(@TempDir Path dir) throws Exception {
        List<String> commands = new ArrayList<>();
        for (Path doc : List.of(README, README_RU, Path.of("deploy.sh"))) {
            Files.readString(doc, UTF_8).lines().filter(l -> l.contains("app.jar.prev app.jar"))
                .map(DocFactsTest::jarStep).forEach(commands::add);
        }
        assertThat(commands).as("README, README.ru and deploy.sh's comment and message").hasSize(4);

        for (String cmd : commands) {
            Files.writeString(dir.resolve("app.jar"), "running jar");
            Files.writeString(dir.resolve("app.jar.prev"), "previous jar");
            try (InputStream running = Files.newInputStream(dir.resolve("app.jar"))) {
                Process p = new ProcessBuilder("bash", "-c", cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .start();
                assertThat(p.waitFor()).as(cmd + ": " + new String(p.getInputStream().readAllBytes(), UTF_8)).isZero();

                assertThat(dir.resolve("app.jar")).as(cmd).hasContent("previous jar");
                assertThat(new String(running.readAllBytes(), UTF_8)).as(cmd).isEqualTo("running jar");
            }
        }
    }

    @Test
    void theTourSaysHowManyOpenPrsTheListHolds() throws IOException {
        Matcher page = OPEN_PRS_PAGE.matcher(Files.readString(
            Path.of("src/main/java/com/github/igniteprchecker/github/GithubClient.java"), UTF_8));
        assertThat(page.find()).isTrue();

        assertThat(collapsed(TOUR)).contains("lists the " + page.group(1) + " most recently updated open PRs");
        assertThat(collapsed(TOUR_RU)).contains(page.group(1) + " последних обновлённых открытых PR");
    }

    /** StandingVisas.WaveSuites re-runs a suite TeamCity cancelled by itself, and counts it towards the 30. */
    @Test
    void theTourSaysAutoRerunAlsoRerunsWhatTeamCityCancelled() throws IOException {
        assertThat(collapsed(TOUR))
            .contains("broken suites, and also the suites TeamCity cancelled by itself, not those a person cancelled.");
        assertThat(collapsed(TOUR_RU))
            .contains("битые сьюты, а ещё сьюты, которые TeamCity отменил сам, но не те, что отменил человек.");
    }

    /** The settle line, the tested revision and Next: are appended after the verdict's glossary footer. */
    @Test
    void theTourDoesNotSayTheGlossaryLinkEndsTheVerdict() throws IOException {
        assertThat(collapsed(TOUR)).doesNotContain("Every verdict ends with a link")
            .contains("the lines about the tested revision and the **Next:** line come after it.");
        assertThat(collapsed(TOUR_RU)).doesNotContain("Каждый вердикт кончается ссылкой")
            .contains("строки о проверенной ревизии и строка **Next:** идут после неё.");
    }

    /** An anonymous status page gets the app account without canPush, so it shows no warning. */
    @Test
    void thePushWarningIsForSignedInViewers() throws IOException {
        assertThat(collapsed(TOUR))
            .contains("Signed-in viewers also see a warning if that account can push to the repo");
        assertThat(collapsed(TOUR_RU)).contains("Вошедшие видят ещё предупреждение, если этот аккаунт может пушить");
        assertThat(collapsed(README)).contains("warns signed-in viewers if that account can push to the repo");
        assertThat(collapsed(README_RU)).contains("предупреждает вошедших, если этот аккаунт может пушить");
    }

    /**
     * On a PR's first RunAll one failure is a blocker; Recently started failing takes a failure that follows a pass:
     * a failure on newly pushed code after a pass on older code, or two or more after a pass on the same code.
     */
    @Test
    void theReadmesSayWhatGoesToRecentlyStartedFailing() throws IOException {
        assertThat(collapsed(README)).doesNotContain("A first failure on new code")
            .contains("when it failed the only run of newly pushed code after passing on the branch before, or its "
                + "last two or more runs on the same code after passing on that code.");
        assertThat(collapsed(README_RU)).doesNotContain("Первое падение на новом коде")
            .contains("когда он упал в единственном прогоне свежезапушенного кода, а до этого проходил на ветке, или "
                + "в двух и больше последних прогонах того же кода после прохода на этом коде.");
    }

    /** BlockerAnalyzer lets a rare and old master failure, or one at another scale factor, leave a blocker. */
    @Test
    void theHistoryDepthCommentsDoNotStateTheOldBlockerRule() throws IOException {
        assertThat(collapsed(Path.of("install.sh"))).doesNotContain("unless the test also fails at least once")
            .contains("but not always: the README says when it does not.");
        assertThat(collapsed(Path.of("src/main/resources/application.yml")))
            .doesNotContain("unless it also fails at least once")
            .contains("except a rare and old one and one at another test scale factor");
    }

    /** The files of the snapshot caches in the main code, each with whether it is durable state. */
    private static Map<String, Boolean> snapshotFiles() throws IOException {
        Map<String, Boolean> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, UTF_8);
                Matcher name = FILE_NAME.matcher(src);
                if (src.contains("implements SnapshotCache") && name.find())
                    out.put(name.group(1), DURABLE.matcher(src).find());
            }
        }

        return out;
    }

    /** Each state file has a row of its own; the caches share the row that begins with {@code cachesLabel}. */
    private static void assertTable(Path readme, String heading, String cachesLabel, Map<String, Boolean> durable)
        throws IOException {
        String text = Files.readString(readme, UTF_8);
        int start = text.indexOf("\n" + heading + "\n");
        assertThat(start).as(readme + ": " + heading).isNotNegative();
        String section = text.substring(start + 1, text.indexOf("\n#", start + 1));

        List<String> caches = new ArrayList<>();
        for (String row : section.lines().filter(l -> l.startsWith("| `")).toList()) {
            String[] cells = row.split(" \\| ");
            List<String> files = QUOTED_FILE.matcher(cells[0]).results().map(m -> m.group(1)).toList();
            if (cells[1].startsWith(cachesLabel))
                caches.addAll(files);
            else if (files.size() == 1)
                assertThat(durable.get(files.get(0))).as(readme + ": " + files.get(0) + " is state").isTrue();
        }

        assertThat(caches).as(readme + ": the caches' row").containsExactlyInAnyOrderElementsOf(
            durable.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
        durable.keySet().forEach(file -> assertThat(section).as(readme.toString()).contains("`" + file + "`"));
    }

    /** The step of a documented rollback command that puts app.jar.prev in place, as a shell runs it. */
    private static String jarStep(String line) {
        return Stream.of(line.split("&&")).filter(s -> s.contains("app.jar.prev app.jar")).findFirst().orElseThrow()
            .strip().replaceFirst("^sudo ", "");
    }

    private static String collapsed(Path file) throws IOException {
        return Files.readString(file, UTF_8).replaceAll("\\s+", " ");
    }
}
