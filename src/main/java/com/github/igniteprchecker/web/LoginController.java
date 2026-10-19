package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.i18n.Lang;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * Per-user login: the user supplies their own TeamCity token, which is validated against TeamCity and then carried,
 * encrypted, inside an HttpOnly session cookie. The cookie names its login, a record of {@link Logins} without the
 * token. Log out ends this browser's login; ⚙ lists the user's logins and can end any of the others. The options in
 * ⚙ are the account's: they are the same in every login, and no logout changes them.
 */
@RestController
@RequestMapping("/api")
public class LoginController {
    /** How a logout's note starts: the other logins go on. */
    static final String LOGGED_OUT_HERE = "You are logged out of this browser.";

    /** What a logout tells a user whose options go on without them. */
    static final String OPTIONS_GO_ON = "Your options in ⚙ stay on and keep using your TeamCity token, which the "
        + "server stores for them. To stop that, log in and switch them off.";

    /** What the page opening on a cookie of an earlier version that is no longer taken tells its owner. */
    static final String EARLIER_LOGIN = "Your login is from an earlier version of the checker. Please log in again.";

    private final UserDirectory users;

    private final Logins logins;

    private final TcClient tc;
    private final SessionCodec codec;
    private final SessionProperties props;
    private final Warmer warmer;
    private final LoginThrottle throttle;
    private final AdminActions admin;
    private final StandingVisas standing;

    public LoginController(TcClient tc, SessionCodec codec, SessionProperties props, Warmer warmer, UserDirectory users,
        Logins logins, LoginThrottle throttle, AdminActions admin, StandingVisas standing) {
        this.users = users;
        this.logins = logins;
        this.tc = tc;
        this.codec = codec;
        this.props = props;
        this.warmer = warmer;
        this.throttle = throttle;
        this.admin = admin;
        this.standing = standing;
        logins.whenEnded(this::outOfPool);
    }

    /** The TeamCity token the user pasted into the login form. */
    public record LoginRequest(String token) implements CarriesToken {
        @Override
        public String toString() {
            return "LoginRequest[token=" + Secrets.masked(token) + "]";
        }
    }

    /** Everyone who has used the tool (names + activity): for the operators only. */
    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestAttribute(AuthInterceptor.USER_ATTR) String user) {
        if (!admin.mayAdminister(user))
            return ResponseEntity.status(403).body(Map.of("error", "Only the operator can see who uses the service."));

        return ResponseEntity.ok(users.list());
    }

    /**
     * {@code admin}: whether this user is an operator; the page shows the operator mark and Update only then.
     * {@code jiraInCookie}: a cookie of theirs carried a JIRA token lately, and Visa my runs in JIRA is off, so ⚙ tells
     * them how to get visas; see {@link UserDirectory#jiraInCookie}. {@code lang}: the code of the language they read
     * the pages in.
     */
    public record UserResponse(String username, boolean admin, boolean jiraInCookie, Lang lang) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest req, HttpServletRequest http) {
        if (req == null || req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));

        String token = req.token().trim();
        String client = LoginThrottle.clientOf(http);
        if (throttle.recentlyRejected(token)) {
            Duration wait = throttle.admitAttempt(client);

            return wait != null ? tooMany(wait, addressUsedUp(wait)) : rejected();
        }

        LoginThrottle.Wait wait = throttle.admitCheck(client);
        if (wait != null)
            return tooMany(wait.time(), wait.shared() ? checksUsedUp(wait.time()) : addressUsedUp(wait.time()));

        Optional<String> username;
        try {
            username = tc.currentUsername(token);
        }
        catch (RestClientResponseException e) {
            throttle.refund(client);

            return ResponseEntity.status(502).body(Map.of("error",
                "TeamCity could not check the token (HTTP " + e.getStatusCode().value() + ") — try again in a moment"));
        }

        if (username.isEmpty()) {
            throttle.rejected(token);

            return rejected();
        }

        throttle.refund(client);
        users.touchLogin(username.get());
        long issuedAt = System.currentTimeMillis();
        SessionCodec.Session session = sessionOf(username.get(), token, http);
        warmer.offerVerifiedToken(username.get(), token, session.login()); // TeamCity just accepted it
        standing.tcTokenAccepted(username.get(), token,
            SettingsJournal.By.of(session.login(), AuthInterceptor.browser(http)));

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies(http, sessionCookie(codec.encode(session, issuedAt))))
            .body(userResponse(session));
    }

    /**
     * The session a login starts, in a new login. The login the browser's cookie had for the same user ends: its
     * cookie is replaced, and a copy of it must not go on. Another user's login in this browser stays in their ⚙.
     */
    private SessionCodec.Session sessionOf(String username, String token, HttpServletRequest http) {
        Optional<SessionCodec.Session> before = carried(http).filter(s -> username.equals(s.username()));
        boolean carriesJira = before.filter(SessionCodec.Session::carriesJira).isPresent();
        before.flatMap(s -> loginOf(s, http)).ifPresent(l -> logins.end(username, Set.of(l.id())));
        Logins.Login started = logins.start(username, AuthInterceptor.browser(http));

        return new SessionCodec.Session(username, token, 0, carriesJira, started.id());
    }

    /**
     * Who the session is, for the page. A cookie with a JIRA token loses it when it is issued again, so the user is
     * marked here first: ⚙ goes on telling them that visas need Visa my runs in JIRA.
     */
    private UserResponse userResponse(SessionCodec.Session s) {
        boolean visaOn = standing.visaOn(s.username());
        if (s.carriesJira() && !visaOn)
            users.sawJiraInCookie(s.username());

        return new UserResponse(s.username(), admin.mayAdminister(s.username()),
            !visaOn && users.jiraInCookie(s.username()), users.language(s.username()));
    }

    private static ResponseEntity<?> rejected() {
        return ResponseEntity.status(401).body(Map.of("error", "TeamCity rejected this token"));
    }

    private static ResponseEntity<?> tooMany(Duration wait, String message) {
        return ResponseEntity.status(429)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, wait.toSeconds())))
            .body(Map.of("error", message));
    }

    /** The answer to a login from an address whose tokens TeamCity rejected too often: the limit and the wait. */
    private static String addressUsedUp(Duration wait) {
        return "TeamCity rejected " + LoginThrottle.ATTEMPTS_PER_CLIENT + " tokens from your network address in the"
            + " last " + LoginThrottle.CLIENT_WINDOW.toMinutes() + " minutes. Try again in " + minutes(wait) + ".";
    }

    /** The answer to a login when all logins together have used the TeamCity checks of the last minute. */
    private static String checksUsedUp(Duration wait) {
        return "The checker checks the tokens of at most " + LoginThrottle.CHECKS_PER_MINUTE + " logins a minute with"
            + " TeamCity, for all users together. Logins in the last minute used all " + LoginThrottle.CHECKS_PER_MINUTE
            + ": try again in " + minutes(wait) + ".";
    }

    private static String minutes(Duration wait) {
        long min = Math.max(1, (wait.toSeconds() + 59) / 60);

        return min == 1 ? "a minute" : min + " minutes";
    }

    /**
     * Logs out of this browser: its cookie goes, and its login ends, copies of the cookie included, whatever TeamCity
     * says of the token. The user's other logins go on. The token leaves the warming pool at once, unless the user's
     * standing options lend it there. {@code note} says what goes on: the other logins, and the options that work on a
     * stored token.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest http) {
        String[] cleared = cookies(http, SessionCookies.cleared(props.cookieSecure()));
        Optional<Logins.Login> ended = carried(http).flatMap(s -> loginOf(s, http));
        ended.ifPresent(l -> logins.end(l.user(), Set.of(l.id())));
        String note = ended.map(l -> logoutNote(l.user())).orElse("");

        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleared)
            .body(note.isEmpty() ? Map.of() : Map.of("note", note));
    }

    /**
     * The login of a cookie that is about to go. A cookie of an earlier version gets its login first, unless it is no
     * longer taken: that login's end then reaches every copy of the cookie.
     */
    private Optional<Logins.Login> loginOf(SessionCodec.Session session, HttpServletRequest http) {
        return logins.enter(session, AuthInterceptor.browser(http));
    }

    /** What goes on after a logout of this browser; nothing to say when nothing does. */
    private String logoutNote(String user) {
        int others = logins.of(user).size();
        boolean optionsGoOn = standing.keepsTcToken(user);
        if (others == 0 && !optionsGoOn)
            return "";

        String note = LOGGED_OUT_HERE;
        if (others > 0)
            note += " " + othersGoOn(others);

        return optionsGoOn ? note + " " + OPTIONS_GO_ON : note;
    }

    /** The logout note's word on the user's logins in other browsers. */
    static String othersGoOn(int others) {
        return others == 1 ? "You are still logged in on 1 other browser." : "You are still logged in on " + others
            + " other browsers.";
    }

    /**
     * The user's logins, this browser's first, then the one seen last. {@code ended} counts the logins the request
     * ended.
     */
    public record LoginList(List<LoginView> logins, int ended) {
    }

    /**
     * A login as ⚙ lists it: the label of its browser and system, when it logged in, when it was last seen, and
     * whether it is this browser's. {@code earlier}: it began in an earlier version, which did not say when; then
     * {@code createdAt} is when this version first saw it.
     */
    public record LoginView(String id, String browser, long createdAt, boolean earlier, long lastSeen,
        boolean current) {
    }

    /** The ids of the logins to end. */
    public record EndRequest(List<String> ids) {
    }

    @GetMapping("/logins")
    public LoginList logins(@RequestAttribute(AuthInterceptor.USER_ATTR) String user,
        @RequestAttribute(AuthInterceptor.LOGIN_ATTR) String login) {
        return loginList(user, login, 0);
    }

    /** Ends the user's logins the request names; this browser's ends only with Log out. */
    @PostMapping("/logins/end")
    public LoginList endLogins(@RequestBody(required = false) EndRequest req,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String user,
        @RequestAttribute(AuthInterceptor.LOGIN_ATTR) String login) {
        Set<String> ids = new HashSet<>(req == null || req.ids() == null ? List.of() : req.ids());
        ids.remove(login);
        ids.remove(null);

        return loginList(user, login, logins.end(user, ids).size());
    }

    /**
     * Ends every login of the user but this browser's, the cookies of earlier versions that this version has not seen
     * yet included.
     */
    @PostMapping("/logins/end-others")
    public LoginList endOtherLogins(@RequestAttribute(AuthInterceptor.USER_ATTR) String user,
        @RequestAttribute(AuthInterceptor.LOGIN_ATTR) String login) {
        return loginList(user, login, logins.endAllBut(user, login).size());
    }

    /** The tokens of the logins that ended leave the warming pool, but those the user's options lend. */
    private void outOfPool(String user, Set<String> ended) {
        warmer.forgetLogins(ended, t -> standing.lendsTcToken(user, t));
    }

    private LoginList loginList(String user, String current, int ended) {
        List<LoginView> list = logins.of(user).stream()
            .map(l -> new LoginView(l.id(), l.browser(), l.createdAt(), l.earlierIssuedAt() != null, l.lastSeen(),
                l.id().equals(current)))
            .sorted(Comparator.comparing(LoginView::current).reversed())
            .toList();

        return new LoginList(list, ended);
    }

    /**
     * Who the session is, for the page as it opens. A cookie old enough, under the name earlier versions gave it, or
     * naming no login, is issued again, as the login guard does. A cookie under that name that is no longer taken is
     * removed, and when an earlier version issued it, the answer says why its owner is at the login form.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest http) {
        Optional<SessionCookies.Carried> carried = SessionCookies.carried(http, codec, props.cookieSecure());
        if (carried.isEmpty() && SessionCookies.earlierLeft(http, props.cookieSecure())) {
            ResponseEntity.BodyBuilder refused = ResponseEntity.status(401)
                .header(HttpHeaders.SET_COOKIE, SessionCookies.clearedEarlier().toString());

            return SessionCookies.earlierIssued(http, codec, props.cookieSecure())
                ? refused.body(Map.of("error", EARLIER_LOGIN, "sessionEnded", true)) : refused.build();
        }
        if (carried.isEmpty())
            return ResponseEntity.status(401).build();

        SessionCodec.Session s = carried.get().session();
        if (warmer.tokenRevoked(s.token()))
            return ResponseEntity.status(401).body(Map.of("error", AuthInterceptor.REVOKED, "tokenRejected", true));
        long checkedAt = System.currentTimeMillis();
        Optional<Logins.Login> login = logins.enter(s, AuthInterceptor.browser(http));
        if (login.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", AuthInterceptor.ENDED, "sessionEnded", true));

        warmer.offerToken(s.username(), s.token(), login.get().id());
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (carried.get().earlier() || SessionCookies.due(s, checkedAt)) {
            ok.header(HttpHeaders.SET_COOKIE,
                cookies(http, sessionCookie(codec.encode(s.withLogin(login.get().id()), checkedAt))));
        }
        else if (SessionCookies.earlierLeft(http, props.cookieSecure()))
            ok.header(HttpHeaders.SET_COOKIE, SessionCookies.clearedEarlier().toString());

        return ok.body(userResponse(s));
    }

    private Optional<SessionCodec.Session> carried(HttpServletRequest http) {
        return SessionCookies.carried(http, codec, props.cookieSecure()).map(SessionCookies.Carried::session);
    }

    /** The cookie to set, and the removal of one under the name earlier versions gave it, if the browser has one. */
    private String[] cookies(HttpServletRequest http, ResponseCookie cookie) {
        return SessionCookies.earlierLeft(http, props.cookieSecure())
            ? new String[] {cookie.toString(), SessionCookies.clearedEarlier().toString()}
            : new String[] {cookie.toString()};
    }

    private ResponseCookie sessionCookie(String value) {
        return SessionCookies.issued(value, props.cookieSecure());
    }
}
