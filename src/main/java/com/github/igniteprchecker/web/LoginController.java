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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * Per-user login: the user supplies their own TeamCity token, which is validated against TeamCity and
 * then carried, encrypted, inside a stateless HttpOnly session cookie (no server-side session store).
 */
@RestController
@RequestMapping("/api")
public class LoginController {
    private final UserDirectory users;

    /** Effectively unlimited cookie lifetime; the session is ended by logout, not by time. */
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(3650);

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
    public record UserResponse(String username, boolean jira, boolean github, boolean admin) {
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
        String cookie = codec.encode(username.get(), token);
        warmer.offerVerifiedToken(token); // TeamCity just accepted it
        standing.tcTokenAccepted(username.get(), token);

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(cookie).toString())
            .body(new UserResponse(username.get(), false, false, admin.mayAdminister(username.get())));
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

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
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

        warmer.offerToken(s.token());

        return ResponseEntity.ok(new UserResponse(s.username(), s.jiraToken() != null, s.ghToken() != null,
            admin.mayAdminister(s.username())));
    }

    private ResponseCookie sessionCookie(String value) {
        // Persistent cookie so the browser keeps it across restarts; the session ends only at logout.
        return baseCookie(value).maxAge(COOKIE_MAX_AGE).build();
    }

    private ResponseCookie clearedCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(AuthInterceptor.COOKIE, value)
            .httpOnly(true)
            .secure(props.cookieSecure())
            .sameSite("Lax")
            .path("/");
    }
}
