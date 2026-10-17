package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The sweep, the early re-runs and the watch of running chains all borrowed the TeamCity token of
 * whichever participant the map listed first. Had that token expired, everyone's visas, re-runs and
 * comments stopped with nothing but "PR skipped" in the log, and logging in again with a fresh token
 * did not replace the dead one.
 */
class TcTokenRefusalTest {
    /** Listed first by the enrollment map, so the old code always looked builds up with this token. */
    private static final String DEAD = "nsamelchev";

    private static final String LIVE = "avinogradov";

    private static final int PR = 13335;

    private static final long RUN_ALL = 9389046L;

    private final TcClient tc = mock(TcClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final StandingVisas standing = new StandingVisas(new ObjectMapper(),
        new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()), tc, github, analyzer,
        mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
        mock(PendingCommits.class));

    @BeforeEach
    void setUp() {
        standing.change(DEAD, "dead-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        standing.change(LIVE, "live-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        when(github.openPrs()).thenReturn(List.of(new PrSummary(PR, "IGNITE-28867 Hot reload of SSL certificates",
            null, null, null, null)));
        when(tc.runningRunAllChains("dead-tc")).thenThrow(refused());
        when(tc.findRunAllBuildForPr("dead-tc", PR)).thenThrow(refused());
        when(tc.triggerBuildReplacingQueued(anyString(), anyString(), eq(PR), anyBoolean(), anyString()))
            .thenReturn(build(9389100L, null));
        when(tc.getBuildState(anyString(), anyLong())).thenReturn(new TcModel.Build(9389100L, null, "queued", null,
            null, null, null, null, null, null, "20991231T000000+0000", null, null, null, null, null, null, null));
    }

    @Test
    void oneDeadTokenDoesNotStopTheSweepForEveryoneElse() {
        runOf(LIVE);

        standing.sweep();

        verify(tc).triggerBuildReplacingQueued(eq("live-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
        assertThat(standing.tcTokenRejected(DEAD)).isTrue();
        assertThat(standing.tcTokenRejected(LIVE)).isFalse();
    }

    @Test
    void aRefusedOwnerIsPausedUntilALoginBringsAWorkingToken() {
        runOf(DEAD);

        standing.sweep();

        verify(analyzer, never()).analyzeForAction(eq("dead-tc"), anyInt());
        assertThat(standing.rerunOn(DEAD)).as("paused, not switched off").isTrue();

        standing.tcTokenAccepted(DEAD, "fresh-tc");
        when(analyzer.analyzeForAction("fresh-tc", PR)).thenReturn(Optional.of(verdict()));
        standing.sweep();

        assertThat(standing.tcTokenRejected(DEAD)).isFalse();
        verify(tc).triggerBuildReplacingQueued(eq("fresh-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
    }

    /** The lookups worked, but the run's owner's own token was refused when the verdict was computed. */
    @Test
    void aRefusalOfTheOwnersOwnCallPausesThem() {
        runOf(LIVE);
        when(analyzer.analyzeForAction("live-tc", PR)).thenThrow(refused());

        standing.sweep();
        standing.sweep();

        verify(analyzer, times(1)).analyzeForAction("live-tc", PR);
        assertThat(standing.tcTokenRejected(LIVE)).isTrue();
    }

    /** A browser still carrying an old token must not swap a working stored token for it. */
    @Test
    void aSessionTokenReplacesOnlyARefusedOne() {
        standing.tcTokenOffered(LIVE, "old-browser-tc");

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("live-tc"));

        standing.markTcRejected(LIVE);
        standing.tcTokenOffered(LIVE, "new-browser-tc");

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("new-browser-tc"));
        assertThat(standing.tcTokenRejected(LIVE)).isFalse();
    }

    /**
     * The user logged in again with a fresh token, while a tab of the old login stayed open elsewhere:
     * a click in its ⚙ or its Link button must not put the old token back in charge.
     */
    @Test
    void aSettingsClickFromAnOldTabKeepsTheLiveStoredToken() {
        standing.tcTokenAccepted(LIVE, "fresh-tc");

        standing.change(LIVE, "old-browser-tc", null, null,
            new StandingVisas.OptionChange(false, null, null, null, null));

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("fresh-tc"));

        standing.linkGhLogin(LIVE, "old-browser-tc", GhUsers.of("anton-vinogradov"));

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("fresh-tc"));

        standing.markTcRejected(LIVE);
        standing.change(LIVE, "new-browser-tc", null, null,
            new StandingVisas.OptionChange(false, null, null, null, null));

        assertThat(standing.actor(LIVE)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("new-browser-tc"));
        assertThat(standing.tcTokenRejected(LIVE)).isFalse();
    }

    /** ci2's firewall answers 403 to requests it dislikes, whatever the token. */
    @Test
    void aForbiddenAnswerIsNotARefusalOfTheToken() {
        HttpClientErrorException forbidden = HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden",
            HttpHeaders.EMPTY, new byte[0], null);
        doThrow(forbidden).when(tc).runningRunAllChains("dead-tc");
        doThrow(forbidden).when(tc).findRunAllBuildForPr("dead-tc", PR);

        standing.sweep();

        assertThat(standing.tcTokenRejected(DEAD)).isFalse();
    }

    @Test
    void aRefusalSurvivesARestart(@TempDir Path dir) throws Exception {
        standing.markTcRejected(DEAD);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        StandingVisas restarted = new StandingVisas(new ObjectMapper(),
            new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()), tc, github, analyzer,
            mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
            mock(PendingCommits.class));

        restarted.loadFrom(file);

        assertThat(restarted.tcTokenRejected(DEAD)).isTrue();
        assertThat(restarted.tcTokenRejected(LIVE)).isFalse();
    }

    /**
     * A refusal kept the dead token's ciphertext in standing-visas.json, and with it in every backup, though only a
     * comparison with the token a session brings needed it.
     */
    @Test
    void ofARefusedTokenOnlyItsHashIsKept(@TempDir Path dir) throws Exception {
        standing.markTcRejected(DEAD);
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);

        JsonNode dead = enrollmentOf(new ObjectMapper().readTree(file.toFile()), DEAD);
        assertThat(dead.get("tcToken").isNull()).isTrue();
        assertThat(dead.get("tcRefusedHash").asText()).isEqualTo(Secrets.sha256("dead-tc"));
        assertThat(dead.get("tcRejectedAt").asLong()).isPositive();

        StandingVisas restarted = restarted(file);
        restarted.tcTokenOffered(DEAD, "dead-tc");
        assertThat(restarted.tcTokenRejected(DEAD)).as("the same dead token does not resume the options").isTrue();
        restarted.tcTokenOffered(DEAD, "new-browser-tc");
        assertThat(restarted.tcTokenRejected(DEAD)).isFalse();
        assertThat(restarted.actor(DEAD)).hasValueSatisfying(a -> assertThat(a.tcToken()).isEqualTo("new-browser-tc"));
    }

    /**
     * Without the ciphertext the user's commands found no actor, and the reply said the checker could no longer read
     * the token instead of that TeamCity refused it; the story of their /run-all ended "options switched off".
     */
    @Test
    void aUserWhoseTokenWasRefusedIsStillTheActorOfTheirCommands() {
        when(github.writes()).thenReturn(true);
        assertThat(standing.linkGhLogin(DEAD, "dead-tc", new GithubClient.GhUser("NSAmelchev", 4242))).isEqualTo("ok");
        standing.markTcRejected(DEAD);

        assertThat(standing.actorByGhUser(new GithubClient.GhUser("NSAmelchev", 4242))).hasValueSatisfying(a -> {
            assertThat(a.username()).isEqualTo(DEAD);
            assertThat(a.tcToken()).isNull();
        });
        assertThat(standing.actor(DEAD)).isPresent();
        assertThat(standing.tcTokenRejected(DEAD)).isTrue();
    }

    /** The ciphertext of a refused token an earlier version kept goes at the first load; its hash stays. */
    @Test
    void aRefusedTokenAnEarlierVersionKeptIsHashedAtLoad(@TempDir Path dir) throws Exception {
        SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper());
        Path file = dir.resolve("standing-visas.json");
        Files.writeString(file, "{\"enrollments\":[{\"username\":\"nsamelchev\",\"tcToken\":\""
            + codec.encryptString("dead-tc") + "\",\"tz\":\"Europe/Moscow\",\"enabledAt\":1760000000000,"
            + "\"autoVisa\":false,\"autoRerun\":true,\"tcRejectedAt\":1760400000000,\"commands\":false}]}");

        StandingVisas loaded = restarted(file);
        Path saved = dir.resolve("saved.json");
        loaded.saveTo(saved);

        JsonNode dead = enrollmentOf(new ObjectMapper().readTree(saved.toFile()), DEAD);
        assertThat(dead.get("tcToken").isNull()).isTrue();
        assertThat(dead.get("tcRefusedHash").asText()).isEqualTo(Secrets.sha256("dead-tc"));
        assertThat(dead.get("tcRejectedAt").asLong()).isEqualTo(1760400000000L);
        assertThat(loaded.rerunOn(DEAD)).isTrue();
        loaded.tcTokenOffered(DEAD, "dead-tc");
        assertThat(loaded.tcTokenRejected(DEAD)).isTrue();
        loaded.tcTokenOffered(DEAD, "fresh-tc");
        assertThat(loaded.tcTokenRejected(DEAD)).isFalse();
    }

    @Test
    void anEarlyRerunFindsTheChainOwnerWithAnotherToken() {
        when(tc.buildTriggeredBy("dead-tc", RUN_ALL)).thenThrow(refused());
        when(tc.buildTriggeredBy("live-tc", RUN_ALL)).thenReturn(Optional.of(LIVE));
        when(analyzer.analyze("live-tc", PR)).thenReturn(Optional.of(verdict()));

        standing.earlyRerun(new RerunTracker.SuiteFailedMidRun(PR, RUN_ALL, "Cache1", 9389012L, "Cache 1"));

        verify(tc).triggerBuildReplacingQueued(eq("live-tc"), eq("Cache1"), eq(PR), eq(true), anyString());
    }

    private StandingVisas restarted(Path file) throws Exception {
        StandingVisas restarted = new StandingVisas(new ObjectMapper(),
            new SessionCodec(new SessionProperties(false, "test-secret"), new ObjectMapper()), tc, github, analyzer,
            mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
            mock(PendingCommits.class));
        restarted.loadFrom(file);

        return restarted;
    }

    private static JsonNode enrollmentOf(JsonNode snapshot, String username) {
        for (JsonNode e : snapshot.get("enrollments")) {
            if (e.get("username").asText().equals(username))
                return e;
        }

        throw new AssertionError("no enrollment of " + username);
    }

    private void runOf(String user) {
        when(tc.findRunAllBuildForPr("live-tc", PR)).thenReturn(Optional.of(build(RUN_ALL, user)));
        when(tc.findRunAllBuildForPr("fresh-tc", PR)).thenReturn(Optional.of(build(RUN_ALL, user)));
        when(analyzer.analyzeForAction("live-tc", PR)).thenReturn(Optional.of(verdict()));
    }

    private static TcModel.Build build(long id, String triggeredBy) {
        return new TcModel.Build(id, "FAILURE", triggeredBy == null ? "queued" : "finished", "pull/13335/head",
            null, null, null, null, null, null, null, null,
            triggeredBy == null ? null : new TcModel.Triggered("user", new TcModel.User(triggeredBy)),
            null, null, null, null, null);
    }

    private static AnalysisResult verdict() {
        TestVerdict blocker = new TestVerdict(1L, "GridCacheTest.testPut", "Cache1", 9389012L, "Cache 1", "o1",
            true, false, "not seen failing in 100 master runs", "F", 1);

        return new AnalysisResult(PR, RUN_ALL, "pull/13335/head", System.currentTimeMillis(), List.of(blocker),
            List.of(), List.of(), List.of(), List.of(), 140, 1, false, 0, false, 0, 0, 0, 0, 0);
    }

    private static HttpClientErrorException refused() {
        return HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
            new byte[0], null);
    }
}
