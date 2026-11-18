package com.github.igniteprchecker.update;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.config.UpdateProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The status reader observes the helper's atomic file without exposing arbitrary fields or local errors. */
class GithubDeployStatusTest {
    @TempDir
    Path dir;

    /** Given no helper record, when status is read, then it reports no recorded deployment. */
    @Test
    void anInstallationWithoutTheHelperHasNoRecordedDeployment() {
        assertThat(reader().snapshot()).isNull();
    }

    /** Given an atomic replacement during deployment, the next read sees completion and the previous success. */
    @Test
    void theNextReadSeesTheHelpersAtomicReplacement() throws Exception {
        GithubDeployStatus reader = reader();
        Files.writeString(dir.resolve("github-deploy.json"), "{\"state\":\"downloading\"}");
        assertThat(reader.snapshot()).containsEntry("state", "downloading");
        Path next = dir.resolve("next.json");
        Files.writeString(next, """
            {"state":"rolled_back","version":"1.52.0-7-gabcdef0","commit":"abcdef0123456789abcdef0123456789abcdef01",
             "startedAt":"2026-11-18T10:00:00Z","finishedAt":"2026-11-18T10:01:00Z",
             "lastSuccessAt":"2026-11-17T09:00:00Z","lastSuccessVersion":"1.52.0",
             "detail":"health-check-failed","localPath":"/private/deploy","token":"not-public"}
            """);
        Files.move(next, dir.resolve("github-deploy.json"), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);

        Map<String, Object> result = reader.snapshot();

        assertThat(result).containsEntry("state", "rolled_back").containsEntry("detail", "health-check-failed")
            .containsEntry("commit", "abcdef0123456789abcdef0123456789abcdef01")
            .containsEntry("startedAt", "2026-11-18T10:00:00Z")
            .containsEntry("finishedAt", "2026-11-18T10:01:00Z")
            .containsEntry("lastSuccessAt", "2026-11-17T09:00:00Z")
            .containsEntry("lastSuccessVersion", "1.52.0")
            .doesNotContainKeys("token", "localPath", "runNumber", "runAttempt");
    }

    /** Given numeric run metadata, reading status exposes the exact run and attempt within the supported range. */
    @ParameterizedTest
    @ValueSource(longs = {1, Long.MAX_VALUE})
    void positiveRunIdentifiersArePublicMetadata(long runNumber) throws Exception {
        Files.writeString(dir.resolve("github-deploy.json"), "{\"state\":\"succeeded\",\"runNumber\":"
            + runNumber + ",\"runAttempt\":2,\"token\":\"not-public\"}");

        assertThat(reader().snapshot()).isEqualTo(Map.of("state", "succeeded", "runNumber", runNumber,
            "runAttempt", 2L));
    }

    /** Given either invalid run identifier, the record is unavailable without exposing its contents. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "\"2\"", "true", "{}", "[]", "9223372036854775808"})
    void invalidRunIdentifiersDoNotBecomePublicMetadata(String literal) throws Exception {
        for (String key : List.of("runNumber", "runAttempt")) {
            Files.writeString(dir.resolve("github-deploy.json"), "{\"state\":\"succeeded\",\"" + key + "\":"
                + literal + ",\"token\":\"not-public\"}");

            assertThat(reader().snapshot()).as(key).isEqualTo(Map.of("state", "unavailable",
                "detail", "status-unreadable"));
        }
    }

    /** Given a corrupt record or unapproved detail, reading it reports unavailable without revealing its content. */
    @ParameterizedTest
    @ValueSource(strings = {"broken-json", "{\"state\":\"secret-state\"}",
        "{\"state\":\"failed\",\"detail\":\"secret token in /private/deploy\"}",
        "{\"state\":\"starting\",\"startedAt\":\"not-a-date\"}",
        "{\"state\":\"failed\",\"version\":\"/private/deploy\"}",
        "{\"state\":\"failed\",\"commit\":\"secret\"}"})
    void invalidRecordsDoNotExposeTheirContent(String content) throws Exception {
        Files.writeString(dir.resolve("github-deploy.json"), content);

        assertThat(reader().snapshot()).isEqualTo(Map.of("state", "unavailable", "detail", "status-unreadable"));
    }

    /** Given a filesystem error, reading status reports unavailable without publishing the exception or path. */
    @Test
    void aReadFailureIsPubliclyReportedWithoutItsLocalPath() throws Exception {
        Files.createDirectory(dir.resolve("github-deploy.json"));

        assertThat(reader().snapshot()).isEqualTo(Map.of("state", "unavailable", "detail", "status-unreadable"));
    }

    private GithubDeployStatus reader() {
        return new GithubDeployStatus(new UpdateProperties(false, dir.resolve("app.jar").toString()),
            Jackson2Defaults.mapper());
    }
}
