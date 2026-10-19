package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.i18n.Lang;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * The owner asked for Russian in ⚙. The language is the account's: chosen in one browser, it holds in every browser the
 * user logs in from, survives a restart, and goes into the journal with the browser it came from, as any change in ⚙
 * does. Nothing changes for a user who never chose: the file of an earlier version reads as English.
 */
class LanguageChoiceTest {
    private static final String USER = "alice";

    private static final String FIREFOX_LINUX = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101"
        + " Firefox/131.0";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final UserDirectory users = new UserDirectory(mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private final LanguageController language = new LanguageController(users, journal, mock(AuthInterceptor.class));

    @Test
    void russianIsTheAccountsAndGoesIntoTheJournal() throws Exception {
        ResponseEntity<?> chosen = language.choose(new LanguageController.Choice("ru"), USER, request("laptop"));
        language.choose(new LanguageController.Choice("ru"), USER, request("laptop"));

        assertThat(chosen.getStatusCode().value()).isEqualTo(200);
        assertThat(chosen.getBody()).isEqualTo(Map.of("lang", "ru"));
        assertThat(users.language(USER)).isEqualTo(Lang.RU);
        assertThat(journal.views(USER, "laptop", "Firefox on Linux")).as("the second choice of the same changed nothing")
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly(tuple("Switched the language to Russian.", "from this browser"));
        assertThat(journal.views(USER, "desk", "Chrome on Windows", Lang.RU))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly(tuple("Язык переключён на русский.", "из Firefox на Linux"));

        language.choose(new LanguageController.Choice("en"), USER, request("laptop"));

        assertThat(users.language(USER)).isEqualTo(Lang.EN);
        assertThat(journal.views(USER, "laptop", "Firefox on Linux").get(0).text())
            .isEqualTo("Switched the language to English.");
    }

    /**
     * The journal is the one text the server writes in its reader's language: ⚙ asks /api/journal, and alice, who chose
     * Russian, reads her entries in Russian, while bob reads his in English.
     */
    @Test
    void theJournalComesInItsReadersLanguage() {
        JournalController controller = new JournalController(journal, users);
        language.choose(new LanguageController.Choice("ru"), USER, request("laptop"));
        journal.add("bob", SettingsJournal.Entry.language(SettingsJournal.By.of("desk", "Firefox on Linux"), Lang.EN));

        assertThat(controller.journal(USER, "laptop", request("laptop")).entries())
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly(tuple("Язык переключён на русский.", "из этого браузера"));
        assertThat(controller.journal("bob", "desk", request("desk")).entries())
            .extracting(SettingsJournal.View::text).containsExactly("Switched the language to English.");
    }

    /** A login in another browser learns the language with the user's name, and the page opens in it. */
    @Test
    void aLoginAnywhereAnswersWithTheAccountsLanguage() throws Exception {
        users.language(USER, Lang.RU);
        TcClient tc = mock(TcClient.class);
        when(tc.currentUsername("tc")).thenReturn(Optional.of(USER));
        SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);
        StandingVisas standing = mock(StandingVisas.class);
        LoginController login = new LoginController(tc, codec, new SessionProperties(false, "test-secret"),
            mock(Warmer.class), users, new Logins(mapper, users), new LoginThrottle(),
            new AdminActions(new AdminProperties(null), mapper), standing);

        ResponseEntity<?> answer = login.login(new LoginController.LoginRequest("tc"), request(null));

        assertThat(mapper.readTree(mapper.writeValueAsString(answer.getBody())).get("lang").asText()).isEqualTo("ru");
    }

    @Test
    void onlyEnglishAndRussianAreTaken() {
        for (String lang : new String[] {"de", "RU", "", null}) {
            assertThat(language.choose(new LanguageController.Choice(lang), USER, request("laptop")).getStatusCode()
                .value()).as(String.valueOf(lang)).isEqualTo(400);
        }
        assertThat(language.choose(null, USER, request("laptop")).getStatusCode().value()).isEqualTo(400);

        assertThat(users.language(USER)).isEqualTo(Lang.EN);
        assertThat(journal.of(USER)).isEmpty();
    }

    @Test
    void theLanguageSurvivesARestartAndAnEarlierFileReadsAsEnglish(@TempDir Path dir) throws Exception {
        Path earlier = dir.resolve("earlier.json");
        Files.writeString(earlier, "[{\"name\":\"bob\",\"firstSeen\":1,\"lastSeen\":2,\"logins\":3,"
            + "\"sessionsEndedAt\":0,\"jiraInCookieAt\":0}]");
        UserDirectory read = new UserDirectory(mapper);
        read.loadFrom(earlier);

        assertThat(read.language("bob")).isEqualTo(Lang.EN);

        read.language(USER, Lang.RU);
        Path saved = dir.resolve("users.json");
        read.saveTo(saved);
        UserDirectory restarted = new UserDirectory(mapper);
        restarted.loadFrom(saved);

        assertThat(restarted.language(USER)).isEqualTo(Lang.RU);
        assertThat(restarted.language("bob")).isEqualTo(Lang.EN);
        assertThat(mapper.readTree(saved.toFile()).findValues("language")).as("only the user who chose has one")
            .extracting(n -> n.asText()).containsExactly("ru");
    }

    private static MockHttpServletRequest request(String login) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HttpHeaders.USER_AGENT, FIREFOX_LINUX);
        if (login != null)
            req.setAttribute(AuthInterceptor.LOGIN_ATTR, login);

        return req;
    }
}
