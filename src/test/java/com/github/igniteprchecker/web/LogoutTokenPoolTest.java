package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.config.WarmProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * After Log out the user's TeamCity token stayed in the pool the background reads borrow from, and went on reading
 * ci2 for everyone for up to an hour. A token the user's standing options lend the pool stays: the options run on it,
 * and the logout says so.
 */
class LogoutTokenPoolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private final SessionProperties props = new SessionProperties(false, "test-secret");

    private final SessionCodec codec = new SessionCodec(props, mapper);

    private final GithubClient github = mock(GithubClient.class);

    private final Warmer warmer = new Warmer(mock(BlockerAnalyzer.class), github,
        new WarmProperties(false, null, null, null), mock(ExecutorService.class));

    private final StandingVisas standing = new StandingVisas(mapper, codec, mock(TcClient.class), github,
        mock(BlockerAnalyzer.class), mock(JiraClient.class), mock(VisaService.class), mock(RerunTracker.class), warmer,
        mock(PendingCommits.class));

    private final LoginController login = new LoginController(mock(TcClient.class), codec, props, warmer,
        new UserDirectory(mapper), new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper), standing);

    @Test
    void theTokenOfAUserWithoutOptionsLeavesThePool() {
        warmer.offerToken("alice-tc");

        ResponseEntity<Map<String, Object>> out = logout("alice", "alice-tc");

        assertThat(warmer.pooledTokens()).isZero();
        assertThat(warmer.tokenRevoked("alice-tc")).isFalse();
        assertThat(out.getBody()).isEmpty();
    }

    @Test
    void aTokenTheOptionsLendStaysAndTheLogoutSaysSo() {
        standing.change("bob", "bob-tc", null, null, new StandingVisas.OptionChange(false, true, false, false, null));
        warmer.offerToken("bob-tc");

        ResponseEntity<Map<String, Object>> out = logout("bob", "bob-tc");

        assertThat(warmer.borrowToken()).isEqualTo("bob-tc");
        assertThat(out.getBody()).containsEntry("note", LoginController.OPTIONS_GO_ON);
    }

    /** PR commands alone lend the token to nothing: it leaves the pool, and the commands go on with it. */
    @Test
    void theTokenOfPrCommandsAloneLeavesThePool() {
        when(github.writes()).thenReturn(true);
        standing.linkGhLogin("carol", "carol-tc", new GithubClient.GhUser("carol-gh", 4242));
        warmer.offerToken("carol-tc");

        ResponseEntity<Map<String, Object>> out = logout("carol", "carol-tc");

        assertThat(warmer.pooledTokens()).isZero();
        assertThat(out.getBody()).containsEntry("note", LoginController.OPTIONS_GO_ON);
    }

    private ResponseEntity<Map<String, Object>> logout(String user, String token) {
        return login.logout(true, codec.encode(new SessionCodec.Session(user, token, null)));
    }
}
