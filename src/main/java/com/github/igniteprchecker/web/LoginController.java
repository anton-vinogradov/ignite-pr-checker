package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionStore;
import com.github.igniteprchecker.session.UserSession;
import com.github.igniteprchecker.tc.TcClient;
import java.time.Duration;
import java.util.Map;
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
 * kept only in a server-side session referenced by an HttpOnly cookie.
 */
@RestController
@RequestMapping("/api")
public class LoginController {
    private final TcClient tc;
    private final SessionStore store;
    private final SessionProperties props;

    public LoginController(TcClient tc, SessionStore store, SessionProperties props) {
        this.tc = tc;
        this.store = store;
        this.props = props;
    }

    public record LoginRequest(String token) {
    }

    public record UserResponse(String username) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest req) {
        if (req == null || req.token() == null || req.token().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));

        Optional<String> username = tc.currentUsername(req.token().trim());
        if (username.isEmpty())
            return ResponseEntity.status(401).body(Map.of("error", "TeamCity rejected this token"));

        UserSession session = store.create(username.get(), req.token().trim());

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(session.id()).toString())
            .body(new UserResponse(username.get()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = AuthInterceptor.COOKIE, required = false) String sid) {
        store.remove(sid);

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
            .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@CookieValue(value = AuthInterceptor.COOKIE, required = false) String sid) {
        return store.get(sid)
            .map(s -> ResponseEntity.ok(new UserResponse(s.username())))
            .orElseGet(() -> ResponseEntity.status(401).build());
    }

    private ResponseCookie sessionCookie(String id) {
        return baseCookie(id).maxAge(Duration.ofMinutes(props.ttlMinutes())).build();
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
