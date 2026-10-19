package com.github.igniteprchecker.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.igniteprchecker.config.Secrets;
import com.github.igniteprchecker.github.GithubClient;
import com.github.igniteprchecker.jira.JiraClient;
import com.github.igniteprchecker.jira.SettingsJournal;
import com.github.igniteprchecker.jira.StandingVisas;
import com.github.igniteprchecker.tc.TcClient;
import com.github.igniteprchecker.tc.TcDates;
import com.github.igniteprchecker.tc.dto.TcModel;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientResponseException;

/**
 * How long each token the checker uses for a user has left, for ⚙: the TeamCity token of the login, and the JIRA and
 * GitHub tokens the server stores for the options. TeamCity and JIRA list a user's tokens without their values, so the
 * expiry of the token the checker holds is known only when every listed token that still works expires at the same
 * moment; else it is unknown. GitHub names the expiry in each answer to the token. ⚙ asks when it opens, and each
 * service is asked about a token at most once an hour.
 */
@RestController
@RequestMapping("/api")
public class TokenExpiryController {
    private static final Logger log = LoggerFactory.getLogger(TokenExpiryController.class);

    /** How long an answer about a token is kept, and so how often a service is asked about it at most. */
    static final long KEPT_MS = 60 * 60_000L;

    /** A token that never expires, among the moments the listed tokens expire at. */
    private static final long NEVER = Long.MAX_VALUE;

    private final TcClient tc;

    private final JiraClient jira;

    private final GithubClient github;

    private final StandingVisas standing;

    private final LongSupplier clock;

    /** The answers by a hash of the service and the token: no token is kept here. */
    private final Map<String, Asked> asked = new ConcurrentHashMap<>();

    @Autowired
    public TokenExpiryController(TcClient tc, JiraClient jira, GithubClient github, StandingVisas standing) {
        this(tc, jira, github, standing, System::currentTimeMillis);
    }

    TokenExpiryController(TcClient tc, JiraClient jira, GithubClient github, StandingVisas standing,
        LongSupplier clock) {
        this.tc = tc;
        this.jira = jira;
        this.github = github;
        this.standing = standing;
        this.clock = clock;
    }

    /**
     * How long the user's token of the {@code service} has left: {@code teamcity} for the token of this login,
     * {@code jira} and {@code github} for the tokens the server stores for the options.
     */
    @GetMapping("/token-expiry")
    public ResponseEntity<?> expiry(@RequestParam String service,
        @RequestAttribute(AuthInterceptor.USER_ATTR) String user,
        @RequestAttribute(AuthInterceptor.TOKEN_ATTR) String tcToken) {
        return switch (service) {
            case SettingsJournal.Entry.TEAMCITY -> ResponseEntity.ok(known(service, user, tcToken, Expiry.UNLISTED,
                t -> sameForAll(tc.ownTokens(t).stream().map(TcModel.Token::expirationTime).toList(),
                    TcDates::instant)));
            case SettingsJournal.Entry.JIRA -> ResponseEntity.ok(known(service, user,
                standing.storedTokens(user).jira(), Expiry.UNLISTED, this::fromJira));
            case SettingsJournal.Entry.GITHUB -> ResponseEntity.ok(known(service, user,
                standing.storedTokens(user).github(), Expiry.NO_ANSWER, this::fromGithub));
            default -> ResponseEntity.badRequest().body(Map.of("error", "no such service: " + service));
        };
    }

    /**
     * What the service said of the token within the last hour, else what it says now; {@code failed} when it could not
     * say. {@link Expiry#NONE} when there is no token.
     */
    private Expiry known(String service, String user, String token, Expiry failed, Function<String, Expiry> ask) {
        if (token == null)
            return Expiry.NONE;

        long now = clock.getAsLong();
        CompletableFuture<Expiry> mine = new CompletableFuture<>();
        Asked a = asked.compute(Secrets.sha256(service + ":" + token),
            (k, cur) -> cur != null && now - cur.at() < KEPT_MS ? cur : new Asked(now, mine));
        if (a.answer() != mine)
            return a.answer().join();

        asked.values().removeIf(old -> now - old.at() >= KEPT_MS);
        Expiry answer = failed;
        try {
            answer = ask.apply(token);
        }
        catch (RuntimeException e) {
            log.info("{} did not say when a token of {} expires ({}): asked again in an hour at the earliest", service,
                user, e instanceof RestClientResponseException r ? "HTTP " + r.getStatusCode().value()
                    : e.getClass().getSimpleName());
        }
        finally {
            mine.complete(answer);
        }

        return answer;
    }

    /**
     * The expiry of the token the checker holds, from the expiries of all the user's tokens, null for one that never
     * expires: known only when every token that still works expires at the same moment. A token that expired is not
     * the one the checker holds, as that one works.
     */
    private Expiry sameForAll(List<String> expiries, Function<String, Instant> parse) {
        long now = clock.getAsLong();
        Set<Long> moments = new HashSet<>();
        int working = 0;
        for (String expiry : expiries) {
            long at;
            try {
                at = expiry == null || expiry.isBlank() ? NEVER : parse.apply(expiry.strip()).toEpochMilli();
            }
            catch (DateTimeException e) {
                return Expiry.UNREADABLE;
            }
            if (at <= now)
                continue;

            working++;
            moments.add(at);
        }

        if (moments.isEmpty())
            return Expiry.UNLISTED;
        if (moments.size() > 1)
            return Expiry.several(working);

        long at = moments.iterator().next();

        return at == NEVER ? Expiry.NEVER_EXPIRES : Expiry.at(at);
    }

    /**
     * The expiry of the saved JIRA token from the user's token list. A token JIRA no longer takes gets no list that
     * names a working token, so then JIRA is asked whether it still takes the token.
     */
    private Expiry fromJira(String token) {
        List<JiraClient.Pat> pats;
        try {
            pats = jira.tokens(token);
        }
        catch (RuntimeException e) {
            if (jira.tokenRejected(token))
                return Expiry.REFUSED;

            throw e;
        }
        Expiry listed = sameForAll(pats.stream().map(JiraClient.Pat::expiry).toList(), JiraClient::instant);

        return listed == Expiry.UNLISTED && jira.tokenRejected(token) ? Expiry.REFUSED : listed;
    }

    private Expiry fromGithub(String token) {
        Optional<GithubClient.TokenOwner> owner = github.tokenOwner(token);
        if (owner.isEmpty())
            return Expiry.REFUSED;
        if (owner.get().expiration() == null)
            return Expiry.NEVER_EXPIRES;

        return owner.get().expiresAt().map(at -> Expiry.at(at.toEpochMilli())).orElse(Expiry.UNREADABLE);
    }

    /**
     * How long a token has left. {@code state}: {@code expires} at {@code expiresAt}, epoch ms; {@code never};
     * {@code refused}, JIRA or GitHub refused the token when asked; {@code none}, the server stores no such token; or
     * {@code unknown}, and {@code why}: {@code unlisted}, the service did not list a token that works;
     * {@code several}, the user's {@code tokens} that work expire at different moments; {@code unreadable}, the
     * service wrote a date the checker cannot read; {@code no-answer}, GitHub did not answer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Expiry(String state, Long expiresAt, String why, Integer tokens) {
        static final Expiry NONE = new Expiry("none", null, null, null);

        static final Expiry NEVER_EXPIRES = new Expiry("never", null, null, null);

        static final Expiry REFUSED = new Expiry("refused", null, null, null);

        static final Expiry UNLISTED = new Expiry("unknown", null, "unlisted", null);

        static final Expiry UNREADABLE = new Expiry("unknown", null, "unreadable", null);

        static final Expiry NO_ANSWER = new Expiry("unknown", null, "no-answer", null);

        static Expiry at(long epochMs) {
            return new Expiry("expires", epochMs, null, null);
        }

        static Expiry several(int tokens) {
            return new Expiry("unknown", null, "several", tokens);
        }
    }

    /** An answer about a token, asked {@code at}; it is done once the service answered. */
    private record Asked(long at, CompletableFuture<Expiry> answer) {
    }
}
