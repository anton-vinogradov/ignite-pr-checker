package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * Per-user login: the user supplies their own TeamCity token, which is validated against TeamCity and
 * then carried, encrypted, inside a stateless HttpOnly session cookie (no server-side session store). A
 * logout ends the user's sessions in every browser; see {@link UserDirectory#endSessions}.
 */
@RestController
@RequestMapping("/api")
public class LoginController {
    /** What a logout tells a user whose options go on without them. */
    static final String OPTIONS_GO_ON = "You are logged out. Your options in ⚙ stay on and keep using your TeamCity "
        + "token, which the server stores for them. To stop that, log in and switch them off.";

    /** What a logout everywhere tells a user whose session's token TeamCity refused. */
    static final String OTHERS_STAY_IN = "TeamCity refused this browser's token, so only this browser is logged out: "
        + "your logins in other browsers go on. To end them, log in again and press Log out.";

    /** What the page opening on a cookie of an earlier version that is no longer taken tells its owner. */
    static final String EARLIER_LOGIN = "Your login is from an earlier version of the checker. Please log in again.";

    private final UserDirectory users;

    private final TcClient tc;
    private final SessionCodec codec;
    private final SessionProperties props;
    private final Warmer warmer;
    private final LoginThrottle throttle;
    private final AdminActions admin;
    private final StandingVisas standing;

    public LoginController(TcClient tc, SessionCodec codec, SessionProperties props, Warmer warmer, UserDirectory users,
        LoginThrottle throttle, AdminActions admin, StandingVisas standing) {
        this.users = users;
        this.tc = tc;
        this.codec = codec;
        this.props = props;
        this.warmer = warmer;
        this.throttle = throttle;
        this.admin = admin;
        this.standing = standing;
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

    /** {@code admin}: whether this user is an operator; the page shows the operator mark and Update only then. */
    public record UserResponse(String username, boolean jira, boolean admin) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest req, HttpServletRequest http) {
        if (req == null || req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));

        String token = req.token().trim();
        Duration wait = throttle.admitAttempt(LoginThrottle.clientOf(http));
        if (wait != null)
            return tooMany(wait, addressUsedUp(wait));

        if (throttle.recentlyRejected(token))
            return rejected();

        wait = throttle.admitCheck();
        if (wait != null)
            return tooMany(wait, checksUsedUp(wait));

        Optional<String> username;
        try {
            username = tc.currentUsername(token);
        }
        catch (RestClientResponseException e) {
            return ResponseEntity.status(502).body(Map.of("error",
                "TeamCity could not check the token (HTTP " + e.getStatusCode().value() + ") — try again in a moment"));
        }

        if (username.isEmpty()) {
            throttle.rejected(token);

            return rejected();
        }

        users.touchLogin(username.get());
        long issuedAt = System.currentTimeMillis();
        SessionCodec.Session session = sessionOf(username.get(), token, http);
        warmer.offerVerifiedToken(username.get(), token); // TeamCity just accepted it
        standing.tcTokenAccepted(username.get(), token);

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies(http, sessionCookie(codec.encode(session, issuedAt))))
            .body(new UserResponse(username.get(), session.jiraToken() != null, admin.mayAdminister(username.get())));
    }

    /**
     * The session a login starts: a new TeamCity token replaces the one the browser's cookie carries for the same
     * user, and the JIRA token in it stays. Another user's cookie, or one a logout ended, gives nothing. The
     * new cookie says it was issued before this check, so a logout the check misses ends it too.
     */
    private SessionCodec.Session sessionOf(String username, String token, HttpServletRequest http) {
        return carried(http).filter(s -> username.equals(s.username()) && !users.ended(s))
            .map(s -> s.withToken(token)).orElseGet(() -> new SessionCodec.Session(username, token, null));
    }

    private static ResponseEntity<?> rejected() {
        return ResponseEntity.status(401).body(Map.of("error", "TeamCity rejected this token"));
    }

    private static ResponseEntity<?> tooMany(Duration wait, String message) {
        return ResponseEntity.status(429)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, wait.toSeconds())))
            .body(Map.of("error", message));
    }

    /** The answer to a login from an address that has used its attempts: the limit and how long to wait. */
    private static String addressUsedUp(Duration wait) {
        return "One network address gets " + LoginThrottle.ATTEMPTS_PER_CLIENT + " login attempts in "
            + LoginThrottle.CLIENT_WINDOW.toMinutes() + " minutes, and yours has used them. Try again in "
            + minutes(wait) + ".";
    }

    /** The answer to a login when all logins together have used the TeamCity checks of the last minute. */
    private static String checksUsedUp(Duration wait) {
        return "The checker checks at most " + LoginThrottle.CHECKS_PER_MINUTE + " tokens a minute with TeamCity, for"
            + " all users together. Logins in the last minute used all " + LoginThrottle.CHECKS_PER_MINUTE
            + ": try again in " + minutes(wait) + ".";
    }

    private static String minutes(Duration wait) {
        long min = Math.max(1, (wait.toSeconds() + 59) / 60);

        return min == 1 ? "a minute" : min + " minutes";
    }

    /**
     * Logs out: this browser's cookie goes, and with {@code everywhere}, as the Log out button asks, every session of
     * the user issued until now ends, copies of this cookie included. The page asks for this browser only when
     * TeamCity has refused the session's token: that ends no other session, which may run on another token. A cookie
     * the guard would refuse ends nothing, or a copy a logout already ended could end each later login of its user.
     * Before ending every session, TeamCity is asked whether this session's token still works: a session on a token
     * TeamCity refuses ends no other, and the answer says so. The tokens of the sessions that end leave the warming
     * pool at once, unless the user's standing options lend them there; when options of theirs work on a stored token,
     * {@code note} says so.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestParam(defaultValue = "true") boolean everywhere,
        HttpServletRequest http) {
        String[] cleared = cookies(http, SessionCookies.cleared(props.cookieSecure()));
        Optional<SessionCodec.Session> live = carried(http)
            .filter(s -> !warmer.tokenRevoked(s.token()) && !users.ended(s));
        if (everywhere && live.filter(s -> LiveTokenGuard.refusedNow(tc, warmer, s.token())).isPresent()) {
            return ResponseEntity.status(401).header(HttpHeaders.SET_COOKIE, cleared)
                .body(Map.of("error", OTHERS_STAY_IN, "tokenRejected", true));
        }

        live.ifPresent(s -> {
            Predicate<String> lent = t -> standing.lendsTcToken(s.username(), t);
            if (everywhere) {
                users.endSessions(s.username());
                warmer.forgetAllOf(s.username(), lent);
            }
            else if (!lent.test(s.token()))
                warmer.forget(s.token());
        });
        boolean optionsGoOn = live.filter(s -> standing.keepsTcToken(s.username())).isPresent();

        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cleared)
            .body(optionsGoOn ? Map.of("note", OPTIONS_GO_ON) : Map.of());
    }

    /**
     * Who the session is, for the page as it opens. A cookie old enough, or under the name earlier versions gave it, is
     * issued again, as the login guard does. A cookie under that name that is no longer taken is removed, and when an
     * earlier version issued it, the answer says why its owner is at the login form.
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
        if (users.ended(s))
            return ResponseEntity.status(401).body(Map.of("error", AuthInterceptor.ENDED, "sessionEnded", true));

        warmer.offerToken(s.username(), s.token());
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (carried.get().earlier() || SessionCookies.due(s, checkedAt))
            ok.header(HttpHeaders.SET_COOKIE, cookies(http, sessionCookie(codec.encode(s, checkedAt))));
        else if (SessionCookies.earlierLeft(http, props.cookieSecure()))
            ok.header(HttpHeaders.SET_COOKIE, SessionCookies.clearedEarlier().toString());

        return ok.body(new UserResponse(s.username(), s.jiraToken() != null, admin.mayAdminister(s.username())));
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
