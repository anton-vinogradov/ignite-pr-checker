package com.github.igniteprchecker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spring Boot 3.5 and Spring Framework 6.2 lost free support on 2026-06-30. The security review of 16.10 found it;
 * no release said so. Runs .github/dependency-notes.sh in a scratch repository with a stub curl that serves saved
 * endoflife.date answers and a stub gh that serves a list of Dependabot PRs.
 */
class DependencyNotesTest {
    private static final Path SAMPLES = Path.of("src/test/resources/release/endoflife");

    private static final String JAR = "build/libs/ignite-pr-checker.jar";

    @TempDir
    Path repo;

    @TempDir
    Path bin;

    @BeforeEach
    void setUp() throws IOException {
        stub("curl", "echo \"$*\" >> \"" + bin + "/curl.log\"\n"
            + "[ -f \"" + bin + "/offline\" ] && exit 6\n"
            + "url=\"${@: -1}\"\n"
            + "cat \"" + SAMPLES.toAbsolutePath() + "/${url##*/}.json\" 2>/dev/null || exit 22\n");
        stub("gh", "echo \"$*\" >> \"" + bin + "/gh.log\"\n"
            + "[ \"$1 $2\" = \"pr list\" ] && [ -f \"" + bin + "/prs.json\" ] || exit 1\n"
            + "while [ $# -gt 0 ]; do [ \"$1\" = --jq ] && exec jq -r \"$2\" \"" + bin + "/prs.json\"; shift; done\n"
            + "cat \"" + bin + "/prs.json\"\n");
        Files.writeString(bin.resolve("prs.json"), """
            [{"number": 241, "title": "Bump org.springframework.boot from 3.5.16 to 4.0.0"},
             {"number": 252, "title": "Bump actions/upload-artifact from 4 to 5"}]
            """);

        build("3.5.16", "9.8.0", "17");
        jar(Map.of("spring-core", "6.2.19", "tomcat-embed-core", "10.1.60", "log4j-api", "2.25.5"));
    }

    @Test
    void theNotesNameWhatLostFreeSupportAndTheOpenDependabotPrs() throws Exception {
        String out = notes("2026-10-18");

        assertThat(out).isEqualTo("""
            ## Dependencies to update

            End of free support, as [endoflife.date](https://endoflife.date) lists it on 2026-10-18:

            - **Spring Boot 3.5** (3.5.16 in build.gradle): free support ended on 2026-06-30.
            - **Spring Framework 6.2** (6.2.19 in the jar): free support ended on 2026-06-30.
            - Gradle 9.8.0, Eclipse Temurin 17, Apache Tomcat 10.1.60, Apache Log4j 2.25.5: \
            no end of free support before 2027-01-16.

            Open Dependabot PRs:

            - #241 Bump org.springframework.boot from 3.5.16 to 4.0.0
            - #252 Bump actions/upload-artifact from 4 to 5

            """);
        assertThat(Files.readString(bin.resolve("gh.log"))).contains("pr list --state open --author app/dependabot");
        assertThat(Files.readAllLines(bin.resolve("curl.log"))).extracting(l -> l.substring(l.lastIndexOf('/') + 1))
            .containsExactly("spring-boot", "gradle", "eclipse-temurin", "spring-framework", "tomcat", "log4j");
    }

    @Test
    void anEndWithin90DaysIsNamedWithTheDaysLeft() throws Exception {
        assertThat(notes("2026-04-01"))
            .contains("- **Spring Boot 3.5** (3.5.16 in build.gradle): free support ends on 2026-06-30, in 90 days.\n");
        assertThat(notes("2026-03-31")).doesNotContain("**Spring Boot")
            .contains("- Spring Boot 3.5.16, Gradle 9.8.0, Eclipse Temurin 17, Spring Framework 6.2.19, Apache Tomcat "
                + "10.1.60, Apache Log4j 2.25.5: no end of free support before 2026-06-29.\n");
    }

    @Test
    void aVersionEndoflifeDateDoesNotListIsNotTakenForSupported() throws Exception {
        build("5.0.0", "9.8.0", "17");
        Files.delete(repo.resolve(JAR));

        String out = notes("2026-10-18");

        assertThat(out).contains("- Spring Boot 5.0.0: could not check, endoflife.date lists no release of it.\n")
            .contains("- Spring Framework: could not check, no version found in the jar.\n")
            .doesNotContain("Spring Boot 5.0.0,", "Spring Framework,");
    }

    @Test
    void withoutTheNetworkTheNotesSayCouldNotCheckAndTheReleaseGoesOn() throws Exception {
        Files.writeString(bin.resolve("offline"), "");
        Files.delete(bin.resolve("prs.json"));

        String out = notes("2026-10-18");

        assertThat(out).contains("- Spring Boot 3.5.16: could not check, endoflife.date did not answer.\n",
            "- Apache Log4j 2.25.5: could not check, endoflife.date did not answer.\n",
            "Open Dependabot PRs: could not check, `gh pr list` failed.\n").doesNotContain("no end of free support");
        assertThat(Files.readAllLines(bin.resolve("curl.log"))).as("one call, not one timeout per product").hasSize(1);
    }

    /** The checker's own build.gradle, Gradle wrapper and release workflow, as the release runs the script on them. */
    @Test
    void theVersionsComeFromTheBuild() throws Exception {
        Files.writeString(bin.resolve("offline"), "");

        String out = run(Path.of("").toAbsolutePath(), "2026-10-18", repo.resolve(JAR).toString());

        assertThat(out).containsPattern("- Spring Boot \\d+\\.\\d+\\.\\d+: could not check")
            .containsPattern("- Gradle \\d+\\.\\d+(\\.\\d+)?: could not check")
            .containsPattern("- Eclipse Temurin \\d+: could not check")
            .doesNotContain("no version found");
    }

    @Test
    void theReleaseWorkflowAppendsTheSectionToTheNotes() throws IOException {
        assertThat(Files.readString(Path.of(".github/workflows/release.yml")))
            .contains("bash .github/dependency-notes.sh " + JAR + " >> release-notes.md");
    }

    /** checkstyle stays on the version Ignite's own build runs; a Dependabot PR for its next major is noise. */
    @Test
    void dependabotSkipsCheckstyleMajors() throws IOException {
        assertThat(Files.readString(Path.of(".github/dependabot.yml")).replaceAll("#.*", ""))
            .containsPattern("ignore:\\s+- dependency-name: com\\.puppycrawl\\.tools:checkstyle\\s+update-types:\\s+"
                + "- version-update:semver-major");
    }

    private void stub(String name, String body) throws IOException {
        Path f = bin.resolve(name);
        Files.writeString(f, "#!/usr/bin/env bash\n" + body);
        f.toFile().setExecutable(true);
    }

    private void build(String boot, String gradle, String java) throws IOException {
        Files.writeString(repo.resolve("build.gradle"), "plugins {\n    id 'java'\n    id 'org.springframework.boot' "
            + "version '" + boot + "'\n}\n");
        Path wrapper = repo.resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(wrapper.getParent());
        Files.writeString(wrapper, "distributionUrl=https\\://services.gradle.org/distributions/gradle-" + gradle
            + "-bin.zip\n");
        Path workflow = repo.resolve(".github/workflows/release.yml");
        Files.createDirectories(workflow.getParent());
        Files.writeString(workflow, "      - uses: actions/setup-java@v6\n        with:\n          distribution: temurin\n"
            + "          java-version: '" + java + "'\n");
    }

    /** A jar that holds empty libraries, by name and version. */
    private void jar(Map<String, String> libs) throws IOException {
        Path jar = repo.resolve(JAR);
        Files.createDirectories(jar.getParent());
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (String entry : List.of("BOOT-INF/classes/application.yml", "BOOT-INF/lib/spring-boot-3.5.16.jar"))
                zip.putNextEntry(new ZipEntry(entry));
            for (Map.Entry<String, String> lib : libs.entrySet())
                zip.putNextEntry(new ZipEntry("BOOT-INF/lib/" + lib.getKey() + "-" + lib.getValue() + ".jar"));
        }
    }

    private String notes(String today) throws Exception {
        return run(repo, today, JAR);
    }

    private String run(Path dir, String today, String jar) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", Path.of(".github/dependency-notes.sh").toAbsolutePath()
            .toString(), jar).directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.put("PATH", bin + ":" + env.get("PATH"));
        env.put("TODAY", today);
        Path err = Files.createTempFile(bin, "err", ".txt");
        pb.redirectError(err.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertThat(p.waitFor()).as(Files.readString(err)).isZero();

        return out;
    }
}
