package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
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

    public record LoginRequest(String token) {
    }

    /** Everyone who has used the tool (names + activity): for the operator, or anyone logged in if none is named. */
    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestAttribute(AuthInterceptor.USER_ATTR) String user) {
        if (!admin.mayAdminister(user))
            return ResponseEntity.status(403).body(Map.of("error", "Only the operator can see who uses the service."));

        return ResponseEntity.ok(users.list());
    }

    /** {@code admin}: whether this user may restart, update and flush (the page shows those buttons only then). */
    public record UserResponse(String username, boolean jira, boolean admin) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest req, HttpServletRequest http) {
        if (req == null || req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));

        String token = req.token().trim();
        Duration wait = throttle.admitAttempt(LoginThrottle.clientOf(http));
        if (wait != null)
            return tooMany(wait, "Too many login attempts from your address — try again in " + minutes(wait) + ".");

        if (throttle.recentlyRejected(token))
            return rejected();

        wait = throttle.admitCheck();
        if (wait != null)
            return tooMany(wait, "Too many logins right now — try again in " + minutes(wait) + ".");

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
        warmer.offerVerifiedToken(token); // TeamCity just accepted it
        standing.tcTokenAccepted(username.get(), token);

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(codec.encode(session, issuedAt)).toString())
            .body(new UserResponse(username.get(), session.jiraToken() != null, admin.mayAdminister(username.get())));
    }

    /**
     * The session a login starts: a new TeamCity token replaces the one the browser's cookie carries for the same
     * user, and the JIRA token in it stays. Another user's cookie, or one a logout ended, gives nothing. The
     * new cookie says it was issued before this check, so a logout the check misses ends it too.
     */
    private SessionCodec.Session sessionOf(String username, String token, HttpServletRequest http) {
        return AuthInterceptor.cookie(http).flatMap(codec::decode)
            .filter(s -> username.equals(s.username()) && !users.ended(s))
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

    private static String minutes(Duration wait) {
        long min = Math.max(1, (wait.toSeconds() + 59) / 60);

        return min == 1 ? "a minute" : min + " minutes";
    }

    /**
     * Logs out: this browser's cookie goes, and with {@code everywhere}, as the Log out button asks, every session of
     * the user issued until now ends, copies of this cookie included. The page asks for this browser only when
     * TeamCity has refused the session's token: that ends no other session, which may run on another token. A cookie
     * the guard would refuse ends nothing, or a copy a logout already ended could end each later login of its user.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestParam(defaultValue = "true") boolean everywhere,
        @CookieValue(value = AuthInterceptor.COOKIE, required = false) String cookie) {
        if (everywhere) {
            codec.decode(cookie).filter(s -> !warmer.tokenRevoked(s.token()) && !users.ended(s))
                .ifPresent(s -> users.endSessions(s.username()));
        }

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, SessionCookies.cleared(props.cookieSecure()).toString())
            .build();
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@CookieValue(value = AuthInterceptor.COOKIE, required = false) String cookie) {
        Optional<SessionCodec.Session> session = codec.decode(cookie);
        if (session.isEmpty())
            return ResponseEntity.status(401).build();

        SessionCodec.Session s = session.get();
        if (warmer.tokenRevoked(s.token()))
            return ResponseEntity.status(401).body(Map.of("error", AuthInterceptor.REVOKED, "tokenRejected", true));
        long checkedAt = System.currentTimeMillis();
        if (users.ended(s))
            return ResponseEntity.status(401).body(Map.of("error", AuthInterceptor.ENDED, "sessionEnded", true));

        warmer.offerToken(s.token());
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (SessionCookies.due(s, checkedAt))
            ok.header(HttpHeaders.SET_COOKIE, sessionCookie(codec.encode(s, checkedAt)).toString());

        return ok.body(new UserResponse(s.username(), s.jiraToken() != null, admin.mayAdminister(s.username())));
    }

    private ResponseCookie sessionCookie(String value) {
        return SessionCookies.issued(value, props.cookieSecure());
    }
}
