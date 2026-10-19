package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.Caveats.Glance;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.github.PrSummary;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * The tabs Mine and Involved of the PR list show what GitHub's search finds for the user's linked GitHub account,
 * also a PR of theirs outside the 50 most recently updated. Each opening of the page asks, and the checker's token
 * has its limits, so GitHub is asked at most once a minute per account, and a failed search does not hide the last
 * answer. The PRs carry the badges of the list of all PRs, from what the checker already knows, with no call to ci2.
 */
class YourPrsTest {
    private static final String HEAD = "5be1c0d9a2f84f0b8a2d51c3e8e0f6b0c1d2e3f4";

    private static final GithubClient.PrsOf FOUND = new GithubClient.PrsOf(
        List.of(new PrSummary(12950, "IGNITE-28000 Old but mine", "u", null, null, null, HEAD, null)), List.of());

    private final GithubClient github = mock(GithubClient.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private final YourPrs yours = new YourPrs(github, standing, now::get);

    @Test
    void githubIsAskedOnceAMinutePerAccount() {
        linked();
        when(github.prsOf("alice-gh")).thenReturn(FOUND);

        YourPrs.Lists first = yours.of("alice");
        now.addAndGet(YourPrs.KEEP_MS - 1);
        yours.of("alice");
        now.addAndGet(1);
        yours.of("alice");

        assertThat(first.login()).isEqualTo("alice-gh");
        assertThat(first.mine()).extracting(PrSummary::number).containsExactly(12950);
        assertThat(first.problem()).isNull();
        verify(github, times(2)).prsOf("alice-gh");
    }

    @Test
    void aFailedSearchKeepsTheLastAnswerAndWaitsAMinute() {
        linked();
        when(github.prsOf("alice-gh")).thenReturn(FOUND).thenThrow(new IllegalStateException("GitHub answered 502"));

        yours.of("alice");
        now.addAndGet(YourPrs.KEEP_MS);
        YourPrs.Lists failed = yours.of("alice");
        now.addAndGet(YourPrs.KEEP_MS - 1);
        yours.of("alice");

        assertThat(failed.mine()).extracting(PrSummary::number).containsExactly(12950);
        assertThat(failed.problem()).isNull();
        verify(github, times(2)).prsOf("alice-gh");
    }

    @Test
    void aSearchThatNeverWorkedSaysSo() {
        linked();
        when(github.prsOf("alice-gh")).thenThrow(new IllegalStateException("GitHub answered 502"));

        YourPrs.Lists failed = yours.of("alice");
        yours.of("alice");

        assertThat(failed.mine()).isNull();
        assertThat(failed.involved()).isNull();
        assertThat(failed.problem()).isEqualTo(YourPrs.Problem.NO_ANSWER);
        verify(github, times(1)).prsOf("alice-gh");
    }

    @Test
    void withoutALinkedAccountNothingIsAsked() {
        when(github.writes()).thenReturn(true);

        YourPrs.Lists none = yours.of("alice");

        assertThat(none.login()).isNull();
        assertThat(none.mine()).isEmpty();
        assertThat(none.involved()).isEmpty();
        verify(github, never()).prsOf(any());
    }

    /** GitHub answers GraphQL only to a caller with a token, and the checker searches with its own. */
    @Test
    void aCheckerWithoutAGithubTokenSaysItCannotSearch() {
        when(standing.ghLoginOf("alice")).thenReturn("alice-gh");

        YourPrs.Lists none = yours.of("alice");

        assertThat(none.problem()).isEqualTo(YourPrs.Problem.NO_ACCOUNT);
        assertThat(none.mine()).isNull();
        verify(github, never()).prsOf(any());
    }

    /** PR 12950 is not among the 50 of the list of all PRs, and its badge comes from its last verdict all the same. */
    @Test
    void theTabsBadgeThePrsAsTheListOfAllDoes() {
        linked();
        PrSummary involved = new PrSummary(13461, "IGNITE-28972 x", "u", null, null, null, "0a1b2c3d", null);
        when(github.prsOf("alice-gh")).thenReturn(new GithubClient.PrsOf(FOUND.mine(), List.of(involved)));
        BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);
        when(analyzer.glance(12950)).thenReturn(new Glance(0, 0, true, HEAD));
        when(analyzer.glance(13461)).thenReturn(new Glance(0, 0, true, HEAD));
        when(analyzer.triggeredBy(12950)).thenReturn("alice");
        PrsController prs = new PrsController(github, analyzer, mock(AuthInterceptor.class), yours);

        YourPrs.Lists lists = prs.yourPrs("alice");

        assertThat(lists.mine()).extracting(PrSummary::number, PrSummary::triggeredBy, PrSummary::standing)
            .containsExactly(tuple(12950, "alice", "CLEAN"));
        assertThat(lists.involved()).extracting(PrSummary::number, PrSummary::standing)
            .containsExactly(tuple(13461, "OLD_CODE"));
    }

    /** The tabs need a login: the user's linked account is what they list. */
    @Test
    void theTabsAskForALogin() {
        AuthInterceptor auth = mock(AuthInterceptor.class);
        Registry registry = new Registry();
        new WebConfig(auth, mock(LiveTokenGuard.class)).addInterceptors(registry);

        assertThat(registry.interceptors().stream().map(MappedInterceptor.class::cast)
            .filter(m -> m.getInterceptor() == auth).flatMap(m -> Arrays.stream(m.getIncludePathPatterns())))
            .contains("/api/your-prs");
    }

    /** The page opens the list on Mine for a user with a linked account, so it learns of the link as it opens. */
    @Test
    void thePageLearnsOfTheLinkAsItOpens() {
        ObjectMapper mapper = Jackson2Defaults.mapper();
        SessionProperties props = new SessionProperties(false, "test-secret");
        SessionCodec codec = new SessionCodec(props, mapper);
        UserDirectory users = new UserDirectory(mapper);
        LoginController login = new LoginController(mock(TcClient.class), codec, props, mock(Warmer.class), users,
            new Logins(mapper, users), new LoginThrottle(), new AdminActions(new AdminProperties(List.of()), mapper),
            standing);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encryptString("{\"username\":\"alice\","
            + "\"token\":\"alice-tc\",\"iat\":" + System.currentTimeMillis() + ",\"v\":2}")));

        boolean before = ((LoginController.UserResponse)login.me(req).getBody()).githubLinked();
        when(standing.ghLoginOf("alice")).thenReturn("alice-gh");

        assertThat(before).isFalse();
        assertThat(((LoginController.UserResponse)login.me(req).getBody()).githubLinked()).isTrue();
    }

    /** The registry {@link WebConfig} fills, with what it holds in view. */
    private static final class Registry extends InterceptorRegistry {
        List<Object> interceptors() {
            return getInterceptors();
        }
    }

    private void linked() {
        when(standing.ghLoginOf("alice")).thenReturn("alice-gh");
        when(github.writes()).thenReturn(true);
    }
}
