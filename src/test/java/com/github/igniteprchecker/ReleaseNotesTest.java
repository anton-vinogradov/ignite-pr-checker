package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On 06.10 v1.20.1 to v1.20.10 came out within an hour, each with one PR title for notes. Four of them changed the
 * verdict rules: every cached verdict was computed again and blocker counts changed, and neither the users nor whoever
 * pressed Update could tell. Runs .github/release-notes.sh in a scratch repository with a stub gh that serves PR
 * descriptions.
 */
class ReleaseNotesTest {
    private static final String RULES_FILE = "src/main/java/com/github/igniteprchecker/analysis/model/TestVerdict.java";

    @TempDir
    Path repo;

    @TempDir
    Path bin;

    private void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertThat(p.waitFor()).as(out).isZero();
    }

    private void commit(String subject, int rules) throws Exception {
        Path verdict = repo.resolve(RULES_FILE);
        Files.createDirectories(verdict.getParent());
        Files.writeString(verdict, "public record TestVerdict() {\n    public static final int RULES = " + rules
            + ";\n}\n");
        Files.writeString(repo.resolve("log.txt"), subject + "\n", StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        exec("git", "add", "-A");
        exec("git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", subject);
    }

    private void pr(int number, String body) throws IOException {
        Files.writeString(bin.resolve("pr-" + number + ".md"), body);
    }

    @BeforeEach
    void setUp() throws Exception {
        Path gh = bin.resolve("gh");
        Files.writeString(gh, "#!/usr/bin/env bash\n[ \"$1 $2\" = \"pr view\" ] || exit 1\ncat \"" + bin
            + "/pr-$3.md\" 2>/dev/null || exit 1\n");
        gh.toFile().setExecutable(true);

        exec("git", "init", "-q");
        commit("Count only real runs in a test's master history (#232)", 8);
        exec("git", "tag", "v1.20.0");
    }

    private Result notes(String tag) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", Path.of(".github/release-notes.sh").toAbsolutePath().toString(),
            tag).directory(repo.toFile());
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.get("PATH"));
        Path err = Files.createTempFile(bin, "err", ".txt");
        pb.redirectError(err.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertThat(p.waitFor()).as(Files.readString(err)).isZero();

        return new Result(out, Files.readString(err));
    }

    @Test
    void theNotesSayWhatChangesForUsersAndThatVerdictsAreRecomputed() throws Exception {
        commit("Judge PR failures against comparable master runs (#242)", 9);
        pr(242, """
            ## What changes for users

            <!-- Goes into the release notes as is. -->
            A test that fails on master only on another JDK is a blocker again.

            ## Why

            The nightly JDK 21 run made 50 tests pre-existing for every PR.
            """);
        commit("Fix a typo in the status page (#243)", 9);
        pr(243, "## What changes for users\n\nNothing.\n");
        commit("Bump jackson (#244)", 9);
        exec("git", "tag", "v1.21.0");

        Result r = notes("v1.21.0");

        assertThat(r.out()).startsWith("## What changes for users\n\n> **Verdict rules changed** (RULES 8 → 9)")
            .contains("every PR's verdict is computed\n> again")
            .contains("### Judge PR failures against comparable master runs\n\n"
                + "A test that fails on master only on another JDK is a blocker again.\n")
            .doesNotContain("nightly JDK 21", "Goes into the release notes", "typo", "Bump jackson");
        assertThat(r.err()).doesNotContain("::warning::");
    }

    @Test
    void aRuleChangeInAPatchReleaseIsFlagged() throws Exception {
        commit("Count master failures per JDK (#245)", 9);
        exec("git", "tag", "v1.20.1");

        Result r = notes("v1.20.1");

        assertThat(r.out()).contains("Verdict rules changed").contains("_None of the PRs below says what changes for"
            + " users._");
        assertThat(r.err()).contains("::warning::v1.20.1 changes the verdict rules in a patch release");
    }

    @Test
    void sameRulesNoWarning() throws Exception {
        commit("Keep PR commands and narration accurate (#246)", 8);
        pr(246, "## What changes for users\n\n- /run-all with a typo gets a reply.\n");
        exec("git", "tag", "v1.20.1");

        assertThat(notes("v1.20.1").out()).doesNotContain("Verdict rules").contains("- /run-all with a typo gets a reply.");
    }

    private record Result(String out, String err) {
    }
}
