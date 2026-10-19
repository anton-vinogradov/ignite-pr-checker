package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * alice had only Visa my runs in JIRA on. 90 days later JIRA refused her token, and the visa went off with it, the last
 * option she had. Her TeamCity token stayed in standing-visas.json and in the daily backups, every sweep lent it to the
 * background reads, and Log out told her that her options keep using it, though ⚙ showed them all off. The token lent
 * before the refusal stayed in the pool of the background reads for up to an hour. The same happened when GitHub
 * refused the token of the only option that commits, and when a token went missing.
 */
class NoOptionNoTeamCityTokenTest {
    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final GithubClient github = AppWriting.github();

    private final JiraClient jira = mock(JiraClient.class);

    private final Warmer warmer = new Warmer(mock(BlockerAnalyzer.class), github,
        new WarmProperties(false, null, null, null), mock(ExecutorService.class));

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), jira, mock(VisaService.class), mock(RerunTracker.class), warmer,
        mock(PendingCommits.class));

    @Test
    void aRefusedJiraTokenTakesTheTeamCityTokenWithTheLastOption(@TempDir Path dir) throws IOException {
        when(jira.myself(anyString())).thenReturn(Optional.of("alice"));
        standing.change("alice", "alice-tc", "alice-jira", null,
            new StandingVisas.OptionChange(true, false, false, false, false));
        lentBySweep();

        standing.dropJiraToken("alice");

        assertKeepsNoTeamCityToken(dir);
        assertThat(standing.jiraTokenRejected("alice")).as("⚙ still says JIRA refused the token").isTrue();
    }

    @Test
    void aRefusedGithubTokenTakesTheTeamCityTokenWithTheLastOption(@TempDir Path dir) throws IOException {
        when(github.tokenOwner(anyString())).thenReturn(Optional.of(new GithubClient.TokenOwner(
            new GithubClient.GhUser("alice-gh", 77), null)));
        standing.change("alice", "alice-tc", null, "github_pat_alice",
            new StandingVisas.OptionChange(false, false, false, false, false, true));
        lentBySweep();

        standing.dropGhToken("alice");

        assertKeepsNoTeamCityToken(dir);
    }

    /** An earlier build lost alice's JIRA token and left the visa on: the poll switches the visa off. */
    @Test
    void aMissingTokenTakesTheTeamCityTokenWithTheLastOption(@TempDir Path dir) throws IOException {
        when(jira.myself(anyString())).thenReturn(Optional.of("alice"));
        standing.change("alice", "alice-tc", "alice-jira", null,
            new StandingVisas.OptionChange(true, false, false, false, false));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode state = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)state.get("enrollments").get(0)).remove("jiraToken");
        mapper.writeValue(file.toFile(), state);
        StandingVisas restarted = restarted(file);
        restarted.donateOnStartup();
        assertThat(warmer.borrowToken()).isEqualTo("alice-tc");

        restarted.ensureGhLogins();

        assertThat(warmer.pooledTokens()).isZero();
        assertThat(restarted.visaOn("alice")).isFalse();
        assertThat(restarted.keepsTcToken("alice")).isFalse();
        assertThat(restarted.lendsTcToken("alice", "alice-tc")).isFalse();
        restarted.saveTo(file);
        assertThat(enrollment(file).path("tcToken").isString()).isFalse();
    }

    /** A file of an earlier version kept the TeamCity token of a user with every option off: it goes at the start. */
    @Test
    void theStartDropsTheTeamCityTokenOfAUserWithNoOption(@TempDir Path dir) throws IOException {
        when(jira.myself(anyString())).thenReturn(Optional.of("alice"));
        standing.change("alice", "alice-tc", "alice-jira", null,
            new StandingVisas.OptionChange(true, false, false, false, false));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode state = (ObjectNode)mapper.readTree(file.toFile());
        ObjectNode alice = (ObjectNode)state.get("enrollments").get(0);
        alice.put("autoVisa", false).remove("jiraToken");
        alice.put("jiraRejectedAt", System.currentTimeMillis());
        mapper.writeValue(file.toFile(), state);

        StandingVisas restarted = restarted(file);

        assertThat(restarted.enabled("alice")).isTrue();
        assertThat(restarted.keepsTcToken("alice")).isFalse();
        restarted.saveTo(file);
        assertThat(enrollment(file).path("tcToken").isString()).isFalse();
    }

    /** The re-run option stays on after JIRA refused the token of the visa: it keeps the TeamCity token. */
    @Test
    void anOptionLeftOnKeepsTheTeamCityToken() {
        when(jira.myself(anyString())).thenReturn(Optional.of("alice"));
        standing.change("alice", "alice-tc", "alice-jira", null,
            new StandingVisas.OptionChange(true, true, false, false, false));
        lentBySweep();

        standing.dropJiraToken("alice");

        assertThat(standing.rerunOn("alice")).isTrue();
        assertThat(standing.keepsTcToken("alice")).isTrue();
        assertThat(standing.lendsTcToken("alice", "alice-tc")).isTrue();
        assertThat(warmer.borrowToken()).isEqualTo("alice-tc");
    }

    /** PR commands lend their token to nothing: once the refusal leaves them alone on, the token leaves the pool. */
    @Test
    void theTokenLeavesThePoolWhenPrCommandsAloneStayOn() {
        when(jira.myself(anyString())).thenReturn(Optional.of("alice"));
        standing.linkGhLogin("alice", "alice-tc", new GithubClient.GhUser("alice-gh", 77));
        standing.change("alice", "alice-tc", "alice-jira", null,
            new StandingVisas.OptionChange(true, false, false, false, true));
        lentBySweep();

        standing.dropJiraToken("alice");

        assertThat(standing.settings("alice").commands()).isTrue();
        assertThat(standing.keepsTcToken("alice")).isTrue();
        assertThat(warmer.pooledTokens()).isZero();
    }

    /** alice's browser sent the same TeamCity token: it stays in the pool for her login after the refusal. */
    @Test
    void theTokenALoginOfferedStaysAfterTheRefusal() {
        when(jira.myself(anyString())).thenReturn(Optional.of("alice"));
        standing.change("alice", "alice-tc", "alice-jira", null,
            new StandingVisas.OptionChange(true, false, false, false, false));
        lentBySweep();
        warmer.offerToken("alice", "alice-tc", "alice-laptop");

        standing.dropJiraToken("alice");

        assertThat(warmer.borrowToken()).isEqualTo("alice-tc");
    }

    /** A sweep lends the token of alice's options to the background reads. */
    private void lentBySweep() {
        standing.donateOnStartup();
        assertThat(warmer.borrowToken()).isEqualTo("alice-tc");
    }

    /**
     * Every option is off and no TeamCity token is stored, nor lent to the background reads, nor taken from a request
     * of a login; switching an option on stores the token of the session again.
     */
    private void assertKeepsNoTeamCityToken(Path dir) throws IOException {
        StandingVisas.Settings s = standing.settings("alice");
        assertThat(s.visa() || s.rerun() || s.gh() || s.style() || s.commands() || s.suites()).isFalse();
        assertThat(standing.keepsTcToken("alice")).isFalse();
        assertThat(standing.lendsTcToken("alice", "alice-tc")).isFalse();
        assertThat(warmer.pooledTokens()).as("the background reads borrow it no more").isZero();
        standing.donateOnStartup();
        assertThat(warmer.pooledTokens()).isZero();

        standing.tcTokenOffered("alice", "alice-tc");
        standing.tcTokenAccepted("alice", "alice-tc");
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        assertThat(enrollment(file).path("tcToken").isString()).isFalse();

        standing.change("alice", "alice-tc", null, null, new StandingVisas.OptionChange(null, true, null, null, null));
        assertThat(standing.keepsTcToken("alice")).isTrue();
    }

    private StandingVisas restarted(Path file) throws IOException {
        StandingVisas s = new StandingVisas(mapper, codec, mock(TcClient.class), github, mock(BlockerAnalyzer.class),
            jira, mock(VisaService.class), mock(RerunTracker.class), warmer, mock(PendingCommits.class));
        s.loadFrom(file);

        return s;
    }

    private JsonNode enrollment(Path file) throws IOException {
        assertThat(Files.exists(file)).isTrue();

        return mapper.readTree(file.toFile()).get("enrollments").get(0);
    }
}
