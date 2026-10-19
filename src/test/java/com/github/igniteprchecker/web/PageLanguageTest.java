package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.i18n.Lang;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * The status page and the flaky board have no login form, so they did not know a reader had chosen Russian. They ask
 * /api/page-language: the language of the signed-in reader, else English. Asking changes nothing on the server: it
 * enters no login, so the reader does not show as active on the Users tab, and it offers no token to the pool, as
 * /api/me does when the PR page opens.
 */
class PageLanguageTest {
    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Logins logins = new Logins(mapper, users);

    private final Warmer warmer = mock(Warmer.class);

    private final StandingVisas standing = mock(StandingVisas.class);

    private final LanguageController language = new LanguageController(users, new SettingsJournal(mapper),
        new AuthInterceptor(codec, warmer, users, logins, standing));

    @Test
    void aSignedInReaderGetsTheLanguageTheyChose() {
        users.language("alice", Lang.RU);

        assertThat(language.pageLanguage(signedIn("alice"))).isEqualTo(Map.of("lang", "ru"));
        assertThat(language.pageLanguage(signedIn("bob"))).isEqualTo(Map.of("lang", "en"));
    }

    /** Without a login that goes on, the page is English, as for anyone not signed in. */
    @Test
    void aReaderWithoutALiveLoginGetsEnglish() {
        users.language("alice", Lang.RU);
        MockHttpServletRequest ended = signedIn("alice");
        logins.end("alice", Set.of(logins.of("alice").get(0).id()));
        MockHttpServletRequest revoked = signedIn("alice");
        when(warmer.tokenRevoked("tok-alice")).thenReturn(true);

        assertThat(language.pageLanguage(new MockHttpServletRequest())).isEqualTo(Map.of("lang", "en"));
        assertThat(language.pageLanguage(ended)).isEqualTo(Map.of("lang", "en"));
        assertThat(language.pageLanguage(revoked)).isEqualTo(Map.of("lang", "en"));
    }

    @Test
    void askingChangesNothing() {
        users.language("alice", Lang.RU);
        MockHttpServletRequest req = signedIn("alice");
        String before = users.list().toString() + logins.of("alice");

        language.pageLanguage(req);

        assertThat(users.list().toString() + logins.of("alice")).isEqualTo(before);
        verify(warmer, never()).offerToken(any(), any());
        verify(warmer, never()).offerToken(any(), any(), any());
        verify(standing, never()).tcTokenOffered(any(), any());
        verify(standing, never()).tcTokenOffered(any(), any(), any());
    }

    /** A request with the cookie of a new login of {@code user}, made in a browser just now. */
    private MockHttpServletRequest signedIn(String user) {
        Logins.Login login = logins.start(user, "Firefox on Linux");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie(AuthInterceptor.COOKIE, codec.encode(new SessionCodec.Session(user, "tok-" + user,
            System.currentTimeMillis(), false, login.id()))));

        return req;
    }
}
