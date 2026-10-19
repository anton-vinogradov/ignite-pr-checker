package com.github.igniteprchecker.web;

import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.session.SessionCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Links a user's GitHub account through the checker's OAuth App: PR commands take a comment for the user's command only
 * when it comes from that account, and the verdict comments mention it. The page asks for a link, and the browser goes
 * to GitHub with a one-time state the checker keeps for that session. GitHub sends the browser back to
 * {@link GithubClient#LINK_CALLBACK} with the state and a code. The account the code names is linked only to the
 * session the state was made for, within {@link #STATE_TTL}, once: a link begun in someone else's session, or a code of
 * someone else's account sent to this user, links nothing. Each answer of the callback is a redirect to the page, which
 * says how it went by {@code github}.
 */
@RestController
public class GithubLinkController {
    private static final Logger log = LoggerFactory.getLogger(GithubLinkController.class);

    /** How long a link may take at GitHub, from the click on Link GitHub to the callback. */
    static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final GithubClient github;

    private final StandingVisas standing;

    private final AuthInterceptor auth;

    private final LongSupplier clock;

    /** The links under way by their state, at most one per session. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    @Autowired
    public GithubLinkController(GithubClient github, StandingVisas standing, AuthInterceptor auth) {
        this(github, standing, auth, System::currentTimeMillis);
    }

    GithubLinkController(GithubClient github, StandingVisas standing, AuthInterceptor auth, LongSupplier clock) {
        this.github = github;
        this.standing = standing;
        this.auth = auth;
        this.clock = clock;
    }

    /**
     * Starts a link: answers with the GitHub address the page sends the browser to. {@code tz}, the browser's time
     * zone, and {@code pr}, the PR the page shows, come back with the user. A 409 when the checker has no GitHub
     * account to answer commands from, or users cannot link accounts through it ({@link GithubClient#linksAccounts}).
     */
    @PostMapping("/api/github/link")
    public ResponseEntity<?> link(@RequestParam(required = false) String tz,
        @RequestParam(required = false) Integer pr,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String username) {
        if (!github.writes())
            return ResponseEntity.status(409).body(Map.of("error", "PR commands are "
                + StandingVisas.Refusal.UNAVAILABLE.error()));
        if (!github.linksAccounts())
            return ResponseEntity.status(409).body(Map.of("error", "Link GitHub is not set up on this checker"));

        long now = clock.getAsLong();
        String session = sessionKey(username, tcToken);
        pending.values().removeIf(p -> p.session().equals(session) || p.expired(now));
        String state = newState();
        pending.put(state, new Pending(session, tz, pr != null && pr > 0 ? pr : null, now));

        return ResponseEntity.ok(Map.of("url", github.authorizeUri(state).toString()));
    }

    /**
     * Where GitHub sends the user back, with the {@code state} of the link and a {@code code}, or an {@code error}
     * when the user did not authorize the app. Not behind the login guard: a missing session must also end on the
     * page, which then asks to log in.
     */
    @GetMapping(GithubClient.LINK_CALLBACK)
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String error,
        HttpServletRequest req) {
        Pending asked = state == null ? null : pending.remove(state);
        Optional<SessionCodec.Session> session = auth.signedIn(req);
        if (session.isEmpty())
            return back("signin", null);
        if (asked == null || asked.expired(clock.getAsLong())
            || !asked.session().equals(sessionKey(session.get().username(), session.get().token())))
            return back("expired", null);
        if ("access_denied".equals(error))
            return back("denied", asked);
        if (error != null || code == null || code.isBlank()) {
            log.warn("GitHub sent {} back without a code for a GitHub account link: {}", session.get().username(),
                error == null || !error.matches("[a-z_]{1,64}") ? "no error named" : error);

            return back("failed", asked);
        }

        Optional<GithubClient.GhUser> who;
        try {
            who = github.oauthUser(code);
        }
        catch (RuntimeException e) {
            log.warn("GitHub could not name the account {} links: {}", session.get().username(), e.toString());

            return back("failed", asked);
        }
        if (who.isEmpty())
            return back("failed", asked);

        String linked = standing.linkGhLogin(session.get().username(), session.get().token(), who.get(), asked.tz(),
            SettingsJournal.By.of(session.get().login(), AuthInterceptor.browser(req)));

        return back("ok".equals(linked) ? "linked" : linked, asked);
    }

    /** Forgets the user's GitHub account; answers with the whole settings state. */
    @PostMapping("/api/github/unlink")
    public StandingVisas.Settings unlink(@RequestAttribute(AuthInterceptor.USER_ATTR) String username,
        HttpServletRequest req) {
        standing.unlinkGhLogin(username, AuthInterceptor.by(req));

        return standing.settings(username);
    }

    /** The page, told how the link went, on the PR it showed when the link began. */
    private static ResponseEntity<Void> back(String outcome, Pending asked) {
        String pr = asked == null || asked.pr() == null ? "" : "pr=" + asked.pr() + "&";

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/?" + pr + "github=" + outcome)).build();
    }

    private String newState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The session a link belongs to: its user and TeamCity token, which a renewed cookie keeps. The token is kept
     * hashed, as nothing here needs it.
     */
    private static String sessionKey(String username, String tcToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(tcToken.getBytes(StandardCharsets.UTF_8));

            return username + ":" + HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A link under way: whose session began it, the browser's time zone, the PR the page showed, and when. */
    private record Pending(String session, String tz, Integer pr, long at) {
        boolean expired(long now) {
            return now - at > STATE_TTL.toMillis();
        }
    }
}
