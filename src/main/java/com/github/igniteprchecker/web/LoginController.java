package com.github.igniteprchecker.web;

import com.github.igniteprchecker.analysis.Warmer;
import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionCodec;
import com.github.igniteprchecker.tc.TcClient;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public LoginController(TcClient tc, SessionCodec codec, SessionProperties props, Warmer warmer, UserDirectory users) {
        this.users = users;
        this.tc = tc;
        this.codec = codec;
        this.props = props;
        this.warmer = warmer;
    }

    public record LoginRequest(String token) {
    }

    /** Everyone who has used the tool (names + activity; auth-guarded — not for anonymous eyes). */
    @org.springframework.web.bind.annotation.GetMapping("/users")
    public List<UserDirectory.UserView> users() {
        return users.list();
    }

    public record UserResponse(String username, boolean jira, boolean github) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest req) {
        if (req == null || req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));

        Optional<String> username = tc.currentUsername(req.token().trim());
        if (username.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "TeamCity rejected this token"));

        users.touchLogin(username.get());
        String cookie = codec.encode(username.get(), req.token().trim());
        warmer.offerVerifiedToken(req.token().trim()); // TeamCity just accepted it

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(cookie).toString())
            .body(new UserResponse(username.get(), false, false));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
            .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@CookieValue(value = AuthInterceptor.COOKIE, required = false) String cookie) {
        return codec.decode(cookie)
            .map(s -> {
                warmer.offerToken(s.token());
                return ResponseEntity.ok(new UserResponse(s.username(), s.jiraToken() != null, s.ghToken() != null));
            })
            .orElseGet(() -> ResponseEntity.status(401).build());
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
