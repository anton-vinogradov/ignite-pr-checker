package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

/**
 * The checkstyle autofix runs only from a PR command, yet ⚙ let it be switched on with PR commands off, where it
 * never ran, and kept it on when PR commands went off. The server held the GitHub token for it all the same.
 */
class AutofixNeedsCommandsTest {
    private static final String USER = "avinogradov";

    private final GithubClient github = AppWriting.github();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final StandingVisas standing = standing();

    private final JiraController controller = new JiraController(mock(JiraClient.class), mock(TcClient.class),
        standing, mock(MergedVerdicts.class), mock(UserDirectory.class));

    @BeforeEach
    void setUp() {
        when(github.tokenOwner(anyString())).thenReturn(Optional.of(new GithubClient.TokenOwner(
            GhUsers.of("anton-vinogradov"), null)));
    }

    @Test
    void theAutofixDoesNotGoOnWithoutPrCommands() {
        ResponseEntity<?> res = post(null, true, null, "gh-pat");

        assertThat(res.getStatusCode().value()).isEqualTo(412);
        assertThat(((Map<?, ?>)res.getBody()).get("need")).isEqualTo("commands");
        assertThat(((Map<?, ?>)res.getBody()).get("error"))
            .isEqualTo("Fix checkstyle before my commanded runs needs PR commands: switch them on first");
        assertThat(standing.settings(USER).style()).isFalse();
        assertThat(standing.settings(USER).ghStored()).isFalse();
        verify(github, never()).tokenOwner(anyString());
    }

    @Test
    void theAutofixGoesOnTogetherWithPrCommands() {
        ResponseEntity<?> res = post(null, true, true, "gh-pat");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.styleFixOn(USER)).isTrue();
        assertThat(standing.commandsOn(USER)).isTrue();
    }

    @Test
    void prCommandsOffTakeTheAutofixAndItsTokenAlong() {
        post(null, true, true, "gh-pat");
        clearInvocations(github);

        ResponseEntity<?> res = post(null, null, false, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(standing.settings(USER).style()).isFalse();
        assertThat(standing.settings(USER).ghStored()).as("nothing else needs the GitHub token").isFalse();
    }

    @Test
    void prCommandsOffKeepTheTokenAnotherOptionNeeds() {
        controller.standingVisa(null, null, true, true, true, true, "tc", USER, null, "gh-pat");

        post(null, null, false, null);

        assertThat(standing.settings(USER).style()).isFalse();
        assertThat(standing.settings(USER).gh()).isTrue();
        assertThat(standing.settings(USER).suites()).isTrue();
        assertThat(standing.settings(USER).ghStored()).as("tests to suites commit with it").isTrue();
    }

    /** Auto re-run keeps the options on, yet nothing left needs the GitHub token. */
    @Test
    void prCommandsOffDropTheAutofixTokenWhileAutoRerunStaysOn() {
        controller.standingVisa(null, true, null, true, true, null, "tc", USER, null, "gh-pat");

        post(null, null, false, null);

        assertThat(standing.settings(USER).rerun()).isTrue();
        assertThat(standing.settings(USER).style()).isFalse();
        assertThat(standing.settings(USER).ghStored()).isFalse();
    }

    /**
     * Another checker user proved the same GitHub account with a token: the claimant's PR commands went off with the
     * login, and the autofix stayed on with its token, though it runs only from a command.
     */
    @Test
    void aLoginProvenByAnotherTakesTheAutofixAndItsTokenAlong() {
        controller.standingVisa(null, true, null, true, true, null, "tc", "claimant", null, "claimant-pat");

        controller.standingVisa(null, null, null, null, null, true, "tc", USER, null, "gh-pat");

        assertThat(standing.settings("claimant").login()).isNull();
        assertThat(standing.settings("claimant").commands()).isFalse();
        assertThat(standing.settings("claimant").style()).isFalse();
        assertThat(standing.settings("claimant").ghStored()).isFalse();
        assertThat(standing.settings("claimant").rerun()).isTrue();
        assertThat(standing.settings(USER).login()).isEqualTo("anton-vinogradov");
    }

    /** A snapshot of the version that let the autofix stay on without PR commands. */
    @Test
    void anOlderSnapshotWithTheAutofixAloneLoadsWithItOff(@TempDir Path dir) throws Exception {
        post(null, true, true, "gh-pat");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.withArray("enrollments").get(0)).put("commands", false);
        mapper.writeValue(file.toFile(), snap);

        StandingVisas loaded = standing();
        loaded.loadFrom(file);

        assertThat(loaded.styleFixOn(USER)).isFalse();
        assertThat(loaded.settings(USER).ghStored()).isFalse();
        assertThat(loaded.settings(USER).login()).isEqualTo("anton-vinogradov");
    }

    @Test
    void anOlderSnapshotWithTheAutofixAndPrCommandsLoadsWithBoth(@TempDir Path dir) throws Exception {
        post(null, true, true, "gh-pat");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);

        StandingVisas loaded = standing();
        loaded.loadFrom(file);

        assertThat(loaded.styleFixOn(USER)).isTrue();
        assertThat(loaded.commandsOn(USER)).isTrue();
        assertThat(loaded.settings(USER).ghStored()).isTrue();
    }

    private StandingVisas standing() {
        return new StandingVisas(mapper, codec, mock(TcClient.class), github, mock(BlockerAnalyzer.class),
            mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class),
            mock(PendingCommits.class));
    }

    private ResponseEntity<?> post(Boolean gh, Boolean style, Boolean commands, String ghToken) {
        return controller.standingVisa(null, null, gh, style, commands, null, "tc", USER, null, ghToken);
    }
}
