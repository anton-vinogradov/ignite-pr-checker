package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.Jackson2Defaults;
import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.MergedVerdicts;
import com.github.igniteprchecker.analysis.PendingCommits;
import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.AdminProperties;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.github.AppWriting;
import com.github.igniteprchecker.github.GhUsers;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.jira.VisaService;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.RerunTracker;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Someone at a browser alice forgot to log out of, or with a copy of her cookie, could switch her options, replace her
 * JIRA token or link their own GitHub account to her ci2 account, and nothing in ⚙ showed it. Each such change now goes
 * into her journal with the browser it came from, and the line of her GitHub account says when and from where it was
 * linked. What the checker changes itself, a token a service refused or an account another user linked, is there too,
 * as the checker's.
 */
class JournalOfChangesTest {
    private static final String USER = "alice";

    private static final String FIREFOX_LINUX = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101"
        + " Firefox/131.0";

    private static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML,"
        + " like Gecko) Chrome/130.0.0.0 Safari/537.36";

    private final ObjectMapper mapper = Jackson2Defaults.mapper();

    private final GithubClient github = AppWriting.github();

    private final JiraClient jira = mock(JiraClient.class);

    private final TcClient tc = mock(TcClient.class);

    private final SessionCodec codec = new SessionCodec(new SessionProperties(false, "test-secret"), mapper);

    private final SettingsJournal journal = new SettingsJournal(mapper);

    private final StandingVisas standing = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class),
        jira, mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class),
        journal);

    private final UserDirectory users = new UserDirectory(mapper);

    private final Logins logins = new Logins(mapper, users);

    private final JiraController options = new JiraController(jira, tc, standing, mock(MergedVerdicts.class), users);

    private final GithubLinkController links = new GithubLinkController(github, standing,
        new AuthInterceptor(codec, mock(Warmer.class), users, logins, standing));

    private final JournalController journalApi = new JournalController(journal, users);

    private String laptop;

    @BeforeEach
    void setUp() {
        when(jira.myself(anyString())).thenReturn(Optional.of("Alice"));
        when(github.linksAccounts()).thenReturn(true);
        when(github.authorizeUri(anyString())).thenAnswer(inv -> UriComponentsBuilder
            .fromUriString("https://github.com/login/oauth/authorize").queryParam("state", (String)inv.getArgument(0))
            .build().toUri());
        laptop = logins.start(USER, "Firefox on Linux").id();
    }

    @Test
    void eachSwitchAndTokenGoesInWithTheBrowserItCameFrom() {
        switchOn(true, null, "jira-pat", laptop, FIREFOX_LINUX);
        switchOn(true, null, "jira-pat-2", laptop, FIREFOX_LINUX);
        switchOn(null, true, null, "desk", CHROME_WINDOWS);
        switchOn(false, null, null, "desk", CHROME_WINDOWS);

        JournalController.Journal seen = journalApi.journal(USER, laptop, request(laptop, FIREFOX_LINUX));

        assertThat(seen.full()).isFalse();
        assertThat(seen.entries()).extracting(SettingsJournal.View::text, SettingsJournal.View::from).containsExactly(
            tuple("Switched off Visa my runs in JIRA.", "from Chrome on Windows"),
            tuple("Switched on Re-run failed suites of my runs.", "from Chrome on Windows"),
            tuple("Saved a new JIRA token.", "from this browser"),
            tuple("Switched on Visa my runs in JIRA with a new JIRA token.", "from this browser"));
    }

    @Test
    void noTokenGoesIntoTheJournal(@TempDir Path dir) throws Exception {
        switchOn(true, null, "jira-pat-secret", laptop, FIREFOX_LINUX);
        standing.markTcRejected(USER);
        standing.tcTokenAccepted(USER, "tc-fresh-secret", SettingsJournal.By.of(laptop, "Firefox on Linux"));
        Path file = dir.resolve("journal.json");

        journal.saveTo(file);

        assertThat(Files.readString(file)).doesNotContain("secret", "jira-pat", "tc-fresh");
        assertThat(mapper.writeValueAsString(journalApi.journal(USER, laptop, request(laptop, FIREFOX_LINUX))))
            .doesNotContain("secret");
    }

    /** alice links her GitHub account from her laptop: ⚙ says when and from where, and so does the journal. */
    @Test
    void aLinkSaysWhenAndFromWhichBrowser() {
        long before = System.currentTimeMillis();

        assertThat(link(GhUsers.of("alice-gh"), FIREFOX_LINUX)).isEqualTo("linked");

        StandingVisas.Settings st = standing.settings(USER);
        assertThat(st.linkedAt()).isBetween(before, System.currentTimeMillis());
        assertThat(st.linkedFrom()).isEqualTo("Firefox on Linux");
        assertThat(journalApi.journal(USER, laptop, request(laptop, FIREFOX_LINUX)).entries()).singleElement()
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly("Linked GitHub account @alice-gh and switched on PR commands.", "from this browser");
    }

    /** Someone with a copy of alice's laptop cookie links their own account from another browser. */
    @Test
    void aLinkFromACopyOfTheCookieNamesTheOtherBrowser() {
        link(GhUsers.of("mallory-gh"), CHROME_WINDOWS);

        assertThat(standing.settings(USER).linkedFrom()).isEqualTo("Chrome on Windows");
        assertThat(journalApi.journal(USER, laptop, request(laptop, FIREFOX_LINUX)).entries()).singleElement()
            .extracting(SettingsJournal.View::from).isEqualTo("from Chrome on Windows, in the login of this browser");
    }

    @Test
    void anUnlinkSaysWhatWentOffWithIt() {
        link(GhUsers.of("alice-gh"), FIREFOX_LINUX);

        links.unlink(USER, request("desk", CHROME_WINDOWS));

        assertThat(journalApi.journal(USER, laptop, request(laptop, FIREFOX_LINUX)).entries().get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly("Unlinked GitHub account @alice-gh. PR commands went off.", "from Chrome on Windows");
    }

    /**
     * alice had only PR commands on and switched them off. The checker keeps nothing for a user with every option off,
     * so her GitHub account went too, and the journal says so: her next command is answered as from an account nobody
     * linked.
     */
    @Test
    void theLastOptionOffUnlinksTheAccountAndSaysSo() {
        link(GhUsers.of("alice-gh"), FIREFOX_LINUX);

        assertThat(options.standingVisa(null, null, null, null, false, null, null, null, "tc", USER,
            request(laptop, FIREFOX_LINUX)).getStatusCode().value()).isEqualTo(200);

        assertThat(standing.settings(USER).login()).isNull();
        assertThat(journalApi.journal(USER, laptop, request(laptop, FIREFOX_LINUX)).entries())
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from).containsExactly(
                tuple("Unlinked GitHub account @alice-gh, as all your options are off.", "from this browser"),
                tuple("Switched off PR commands.", "from this browser"),
                tuple("Linked GitHub account @alice-gh and switched on PR commands.", "from this browser"));
    }

    /**
     * alice linked her account from the laptop, then saved a GitHub token for commits of the same account at her desk.
     * That is no new link: ⚙ keeps saying when and from where she linked it, as the journal does.
     */
    @Test
    void aTokenOfTheLinkedAccountKeepsWhenAndWhereItWasLinked() {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("alice-gh"),
            null)));
        link(GhUsers.of("alice-gh"), FIREFOX_LINUX);
        StandingVisas.Settings linked = standing.settings(USER);

        assertThat(standing.change(USER, "tc", null, "gh-pat", new StandingVisas.OptionChange(null, null, null, true,
            null), null, SettingsJournal.By.of("desk", "Chrome on Windows"))).isEmpty();

        StandingVisas.Settings st = standing.settings(USER);
        assertThat(st.style()).isTrue();
        assertThat(st.linkedAt()).isEqualTo(linked.linkedAt());
        assertThat(st.linkedFrom()).isEqualTo("Firefox on Linux");
        assertThat(journal.of(USER)).extracting(SettingsJournal.Entry::kind).containsExactly("on", "linked");
    }

    /** alice logged in again on her laptop: the TeamCity token of that login is this browser's entry. */
    @Test
    void aLoginInThisBrowserIsThisBrowsersEntry() {
        switchOn(null, true, null, laptop, FIREFOX_LINUX);
        standing.markTcRejected(USER);
        when(tc.currentUsername("tc-fresh")).thenReturn(Optional.of(USER));

        loginController().login(new LoginController.LoginRequest("tc-fresh"), request(null, FIREFOX_LINUX));

        String fresh = logins.of(USER).stream().map(Logins.Login::id).filter(id -> !id.equals(laptop)).findFirst()
            .orElseThrow();
        assertThat(journal.views(USER, fresh, "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly("Saved a new TeamCity token for your options.", "from this browser");
    }

    /**
     * TeamCity refused the token alice's options use, and a request from a copy of her laptop's cookie brought a token
     * that works. The options took it, and the journal names the browser of that request.
     */
    @Test
    void aSessionsTokenInPlaceOfARefusedOneNamesItsBrowser() throws Exception {
        switchOn(null, true, null, laptop, FIREFOX_LINUX);
        standing.markTcRejected(USER);
        MockHttpServletRequest copy = request(null, CHROME_WINDOWS);
        copy.setCookies(new Cookie(AuthInterceptor.COOKIE,
            codec.encode(new SessionCodec.Session(USER, "tc-other").withLogin(laptop))));

        assertThat(new AuthInterceptor(codec, mock(Warmer.class), users, logins, standing)
            .preHandle(copy, new MockHttpServletResponse(), null)).isTrue();

        assertThat(standing.tcTokenRejected(USER)).isFalse();
        assertThat(journal.views(USER, laptop, "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly("Saved a new TeamCity token for your options.",
                "from Chrome on Windows, in the login of this browser");
    }

    /** bob links the account alice linked: alice learns that it left her settings, and with it her PR commands. */
    @Test
    void anAccountAnotherUserLinksLeavesAnEntryForItsFormerHolder() {
        link(GhUsers.of("shared-gh"), FIREFOX_LINUX);

        standing.linkGhLogin("bob", "bob-tc", GhUsers.of("shared-gh"), null, SettingsJournal.By.of("b1", "Safari on"
            + " macOS"));

        assertThat(standing.commandsOn(USER)).isFalse();
        assertThat(journal.views(USER, laptop, "Firefox on Linux").get(0))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .containsExactly("Another ci2 user linked GitHub account @shared-gh, so it is no longer linked to you. PR"
                + " commands went off.", "by the checker");
        assertThat(journal.views("bob", "b1", "Safari on macOS")).singleElement().extracting(SettingsJournal.View::text)
            .isEqualTo("Linked GitHub account @shared-gh and switched on PR commands.");
    }

    /**
     * JIRA refused alice's token, then TeamCity the one her options use, and she logged in again with a new one: the
     * refusals are the checker's entries, the login's token is the login's.
     */
    @Test
    void whatTheCheckerChangesIsTheCheckersEntry() {
        switchOn(true, true, "jira-pat", laptop, FIREFOX_LINUX);
        standing.dropJiraToken(USER);
        standing.markTcRejected(USER);
        when(tc.currentUsername("tc-fresh")).thenReturn(Optional.of(USER));

        loginController().login(new LoginController.LoginRequest("tc-fresh"), request(null, CHROME_WINDOWS));

        assertThat(journal.views(USER, laptop, "Firefox on Linux"))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from)
            .startsWith(tuple("Saved a new TeamCity token for your options.", "from Chrome on Windows"),
                tuple("TeamCity refused the token your options use. They wait until you log in again.",
                    "by the checker"),
                tuple("JIRA refused your JIRA token, so the checker dropped it. Visa my runs in JIRA went off.",
                    "by the checker"));
    }

    /**
     * An earlier version lost alice's JIRA and GitHub tokens and kept her options on. The command poll switches off
     * what no token is left for, and her journal says so as the checker's entries.
     */
    @Test
    void optionsWithoutTokensGoOffWithAnEntry(@TempDir Path dir) throws Exception {
        when(github.tokenOwner("gh-pat")).thenReturn(Optional.of(new GithubClient.TokenOwner(GhUsers.of("alice-gh"),
            null)));
        standing.change(USER, "tc", "jira-pat", "gh-pat", new StandingVisas.OptionChange(true, null, null, null, true,
            true));
        Path file = dir.resolve("standing-visas.json");
        standing.saveTo(file);
        ObjectNode snap = (ObjectNode)mapper.readTree(file.toFile());
        ((ObjectNode)snap.get("enrollments").get(0)).remove(List.of("jiraToken", "ghToken"));
        mapper.writeValue(file.toFile(), snap);
        SettingsJournal restartedJournal = new SettingsJournal(mapper);
        StandingVisas restarted = new StandingVisas(mapper, codec, tc, github, mock(BlockerAnalyzer.class), jira,
            mock(VisaService.class), mock(RerunTracker.class), mock(Warmer.class), mock(PendingCommits.class),
            restartedJournal);
        restarted.loadFrom(file);

        restarted.ensureGhLogins();

        assertThat(restarted.settings(USER).visa()).isFalse();
        assertThat(restartedJournal.views(USER, laptop, "Firefox on Linux"))
            .extracting(SettingsJournal.View::text, SettingsJournal.View::from).containsExactly(
                tuple("The checker had no JIRA token, so Visa my runs in JIRA went off.", "by the checker"),
                tuple("The checker had no GitHub token for commits, so Add my PR's tests that no suite runs went off.",
                    "by the checker"));
    }

    /** A login with the token the options already use changes nothing, so it adds nothing. */
    @Test
    void aLoginWithTheSameTokenAddsNothing() {
        switchOn(null, true, null, laptop, FIREFOX_LINUX);

        standing.tcTokenAccepted(USER, "tc", SettingsJournal.By.of(laptop, "Firefox on Linux"));

        assertThat(journal.of(USER)).hasSize(1);
    }

    private LoginController loginController() {
        return new LoginController(tc, codec, new SessionProperties(false, "test-secret"), mock(Warmer.class), users,
            logins, new LoginThrottle(), new AdminActions(new AdminProperties(null), mapper), standing);
    }

    private void switchOn(Boolean visa, Boolean rerun, String jiraToken, String login, String userAgent) {
        assertThat(options.standingVisa(visa, rerun, null, null, null, null, null,
            new JiraController.OptionTokens(null, jiraToken), "tc", USER, request(login, userAgent))
            .getStatusCode().value()).isEqualTo(200);
    }

    private static MockHttpServletRequest request(String login, String userAgent) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(HttpHeaders.USER_AGENT, userAgent);
        if (login != null)
            req.setAttribute(AuthInterceptor.LOGIN_ATTR, login);

        return req;
    }

    /** The outcome of a link GitHub confirmed as {@code account}, from a browser with the cookie of alice's laptop. */
    private String link(GithubClient.GhUser account, String userAgent) {
        URI authorize = URI.create(String.valueOf(((Map<?, ?>)links.link(null, null, "tc", USER).getBody())
            .get("url")));
        String state = UriComponentsBuilder.fromUri(authorize).build().getQueryParams().getFirst("state");
        when(github.oauthUser("code")).thenReturn(Optional.of(account));
        MockHttpServletRequest browser = request(null, userAgent);
        browser.setCookies(new Cookie(AuthInterceptor.COOKIE,
            codec.encode(new SessionCodec.Session(USER, "tc").withLogin(laptop))));

        String back = String.valueOf(links.callback("code", state, null, browser).getHeaders().getLocation());

        return back.substring(back.indexOf("github=") + "github=".length());
    }
}
