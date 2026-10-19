package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * TeamCity and JIRA list a user's tokens without their values, so the checker cannot say which listed token it holds.
 * It names an expiry only when every listed token that still works expires at the same moment, and says it does not
 * know otherwise. GitHub names the expiry of the token itself. Each token is asked about at most once an hour, so
 * opening ⚙ again and again costs the services nothing more.
 */
class TokenExpiryAnswerTest {
    private static final String USER = "alice";

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    /** 2026-12-01 12:00 UTC, as TeamCity and JIRA write it. */
    private static final String TC_DECEMBER = "20261201T120000+0000";

    private static final String JIRA_DECEMBER = "2026-12-01T12:00:00.000+0000";

    private static final long DECEMBER = Instant.parse("2026-12-01T12:00:00Z").toEpochMilli();

    private final TcClient tc = mock(TcClient.class);

    private final JiraClient jira = mock(JiraClient.class);

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final AtomicLong clock = new AtomicLong(NOW.toEpochMilli());

    private final TokenExpiryController expiry = new TokenExpiryController(tc, jira, github, standing, clock::get);

    {
        when(standing.storedTokens(USER)).thenReturn(new StandingVisas.StoredTokens("jira-pat", "gh-pat"));
    }

    @Test
    void teamCityTokensThatAllExpireTogetherNameTheExpiry() {
        teamCity("tc", TC_DECEMBER, TC_DECEMBER);

        assertThat(answer("teamcity", "tc")).isEqualTo(new TokenExpiryController.Expiry("expires", DECEMBER, null,
            null));
    }

    @Test
    void tokensThatExpireAtDifferentTimesLeaveTheExpiryUnknown() {
        teamCity("tc", TC_DECEMBER, "20270301T120000+0000", null);

        assertThat(answer("teamcity", "tc")).isEqualTo(new TokenExpiryController.Expiry("unknown", null, "several",
            3));
    }

    /** The token the checker holds works, so a listed token that expired is not it. */
    @Test
    void anExpiredTokenIsNotTheOneTheCheckerHolds() {
        teamCity("tc", "20261001T120000+0000", TC_DECEMBER);

        assertThat(answer("teamcity", "tc").expiresAt()).isEqualTo(DECEMBER);
    }

    @Test
    void tokensWithoutAnExpiryNeverExpire() {
        teamCity("tc", null, " ");

        assertThat(answer("teamcity", "tc").state()).isEqualTo("never");
    }

    @Test
    void whatTheCheckerCannotReadIsUnknown() {
        teamCity("tc", "1 December", TC_DECEMBER);
        when(tc.ownTokens("tc-empty")).thenReturn(List.of());
        when(tc.ownTokens("tc-firewall")).thenThrow(new ResourceAccessException("connection reset"));

        assertThat(answer("teamcity", "tc").why()).isEqualTo("unreadable");
        assertThat(answer("teamcity", "tc-empty").why()).isEqualTo("unlisted");
        assertThat(answer("teamcity", "tc-firewall").why()).isEqualTo("unlisted");
    }

    @Test
    void jiraTokensAreReadTheSameWay() {
        when(jira.tokens("jira-pat")).thenReturn(List.of(new JiraClient.Pat("prc", JIRA_DECEMBER),
            new JiraClient.Pat("expired", "2026-10-01T12:00:00.000+0000")));

        assertThat(answer("jira", "tc")).isEqualTo(new TokenExpiryController.Expiry("expires", DECEMBER, null, null));
    }

    /** ASF JIRA lists a token that never expires as expiring on the last day of the year 9999. */
    @Test
    void aJiraTokenOfTheYear9999NeverExpires() {
        when(jira.tokens("jira-pat")).thenReturn(List.of(new JiraClient.Pat("Ignite PR Checker",
            "9999-12-31T00:00:00.000+00:00")));

        assertThat(answer("jira", "tc").state()).isEqualTo("never");
    }

    /**
     * Alice's saved JIRA token expired yesterday, and ⚙ said in grey that JIRA did not list her tokens. When the list
     * names no token that works, the checker asks JIRA whether it still takes the token.
     */
    @Test
    void aJiraTokenJiraRefusesIsToldApart() {
        when(jira.tokens("jira-pat")).thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));
        when(jira.tokenRejected("jira-pat")).thenReturn(true);
        when(jira.tokens("jira-empty")).thenReturn(List.of());
        when(jira.tokenRejected("jira-empty")).thenReturn(true);
        when(jira.tokens("jira-unlisted")).thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        assertThat(jiraAnswer("jira-pat").state()).isEqualTo("refused");
        assertThat(jiraAnswer("jira-empty").state()).as("a list without a token that works")
            .isEqualTo("refused");
        assertThat(jiraAnswer("jira-unlisted").why()).isEqualTo("unlisted");
        verify(jira, times(1)).tokenRejected("jira-unlisted");
    }

    @Test
    void githubNamesTheExpiryOfTheTokenItself() {
        owner("gh-pat", "2026-12-01 12:00:00 UTC");
        owner("gh-forever", null);
        when(github.tokenOwner("gh-dead")).thenReturn(Optional.empty());
        when(github.tokenOwner("gh-down")).thenThrow(new ResourceAccessException("timed out"));
        owner("gh-odd", "soon");
        owner("gh-february", "2026-02-30 12:00:00 UTC");

        assertThat(answer("github", "tc").expiresAt()).isEqualTo(DECEMBER);
        assertThat(githubAnswer("gh-forever").state()).isEqualTo("never");
        assertThat(githubAnswer("gh-dead").state()).isEqualTo("refused");
        assertThat(githubAnswer("gh-down").why()).isEqualTo("no-answer");
        assertThat(githubAnswer("gh-odd").why()).isEqualTo("unreadable");
        assertThat(githubAnswer("gh-february").why()).as("GitHub answered, with a day that does not exist")
            .isEqualTo("unreadable");
    }

    /** No JIRA or GitHub token on the server: nothing to say, and nobody to ask. */
    @Test
    void aTokenTheServerDoesNotHoldIsNotAskedAbout() {
        when(standing.storedTokens(USER)).thenReturn(new StandingVisas.StoredTokens(null, null));

        assertThat(answer("jira", "tc").state()).isEqualTo("none");
        assertThat(answer("github", "tc").state()).isEqualTo("none");
        verify(jira, never()).tokens(anyString());
        verify(github, never()).tokenOwner(anyString());
    }

    @Test
    void eachTokenIsAskedAboutAtMostOnceAnHour() {
        teamCity("tc", TC_DECEMBER);
        teamCity("tc-other", TC_DECEMBER);
        when(jira.tokens("jira-pat")).thenThrow(new ResourceAccessException("JIRA is down"));

        answer("teamcity", "tc");
        answer("jira", "tc");
        clock.addAndGet(TokenExpiryController.KEPT_MS - 1);
        answer("teamcity", "tc");
        answer("teamcity", "tc-other");
        assertThat(answer("jira", "tc").why()).as("a failure is kept too").isEqualTo("unlisted");
        verify(tc, times(1)).ownTokens("tc");
        verify(tc, times(1)).ownTokens("tc-other");
        verify(jira, times(1)).tokens("jira-pat");

        clock.incrementAndGet();
        answer("teamcity", "tc");
        verify(tc, times(2)).ownTokens("tc");
    }

    /** Two tabs open ⚙ at once: TeamCity hears about the token once, and both tabs get its answer. */
    @Test
    void twoOpeningsAtOnceAskOnce() throws Exception {
        CountDownLatch asking = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        when(tc.ownTokens("tc")).thenAnswer(inv -> {
            asking.countDown();
            answered.await();

            return List.of(new TcModel.Token("t", TC_DECEMBER));
        });
        AtomicReference<TokenExpiryController.Expiry> firstTab = new AtomicReference<>();
        AtomicReference<TokenExpiryController.Expiry> secondTab = new AtomicReference<>();
        Thread first = new Thread(() -> firstTab.set(answer("teamcity", "tc")));
        Thread second = new Thread(() -> secondTab.set(answer("teamcity", "tc")));
        try {
            first.start();
            assertThat(asking.await(10, TimeUnit.SECONDS)).isTrue();
            second.start();
            waitUntilWaiting(second);
        }
        finally {
            answered.countDown();
            first.join(10_000);
            second.join(10_000);
        }

        assertThat(firstTab.get().expiresAt()).isEqualTo(DECEMBER);
        assertThat(secondTab.get()).isEqualTo(firstTab.get());
        verify(tc, times(1)).ownTokens("tc");
    }

    @Test
    void anUnknownServiceIsRefused() {
        assertThat(expiry.expiry("ci2", USER, "tc").getStatusCode().value()).isEqualTo(400);
    }

    private void teamCity(String token, String... expiries) {
        when(tc.ownTokens(token)).thenReturn(Arrays.stream(expiries).map(e -> new TcModel.Token("t", e)).toList());
    }

    private void owner(String token, String expiration) {
        when(github.tokenOwner(token)).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("alice-gh"),
            null, expiration)));
    }

    private static void waitUntilWaiting(Thread t) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < until)
            Thread.sleep(5);

        assertThat(t.getState()).isEqualTo(Thread.State.WAITING);
    }

    private TokenExpiryController.Expiry jiraAnswer(String token) {
        when(standing.storedTokens(USER)).thenReturn(new StandingVisas.StoredTokens(token, "gh-pat"));

        return answer("jira", "tc");
    }

    private TokenExpiryController.Expiry githubAnswer(String token) {
        when(standing.storedTokens(USER)).thenReturn(new StandingVisas.StoredTokens("jira-pat", token));

        return answer("github", "tc");
    }

    private TokenExpiryController.Expiry answer(String service, String tcToken) {
        ResponseEntity<?> res = expiry.expiry(service, USER, tcToken);

        return (TokenExpiryController.Expiry)res.getBody();
    }
}
