package com.github.igniteprchecker.web;

import com.github.igniteprchecker.config.SessionProperties;
import com.github.igniteprchecker.session.SessionCodec;
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
 * then carried, encrypted, inside a stateless HttpOnly session cookie (no server-side session store).
 */
@RestController
@RequestMapping("/api")
public class LoginController {
    private final TcClient tc;
    private final SessionCodec codec;
    private final SessionProperties props;

    public LoginController(TcClient tc, SessionCodec codec, SessionProperties props) {
        this.tc = tc;
        this.codec = codec;
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

        String cookie = codec.encode(username.get(), req.token().trim());

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(cookie).toString())
            .body(new UserResponse(username.get()));
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
            .map(s -> ResponseEntity.ok(new UserResponse(s.username())))
            .orElseGet(() -> ResponseEntity.status(401).build());
    }

    private ResponseCookie sessionCookie(String value) {
        return baseCookie(value).maxAge(Duration.ofMinutes(props.ttlMinutes())).build();
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
