package com.github.igniteprchecker.github;

import com.github.igniteprchecker.github.GithubClient.AppAccount.Check;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/**
 * What GitHub tells of {@code GITHUB_TOKEN} and its account, for the operator. The startup check named the account and
 * whether it may push to the repo, and showed it as fine when GitHub did not say. A token that cannot write to the
 * repo (a fine-grained one, or a classic one without {@code public_repo}), one about to expire or that never expires,
 * and an account that may push to the repo or to the checker's own, where its releases come from, are now told; what
 * GitHub does not say is unknown.
 */
final class AppAccountChecks {
    /** How long before the token expires the operator is warned. */
    static final Duration EXPIRY_WARNING = Duration.ofDays(14);

    /** The scopes of a classic token, which GitHub sends with every answer to it. */
    static final String SCOPES = "X-OAuth-Scopes";

    /** When the token expires; GitHub sends it with every answer to a token that does. */
    static final String EXPIRATION = "github-authentication-token-expiration";

    /** What a fine-grained token starts with. */
    private static final String FINE_GRAINED = "github_pat_";

    /** The scope the app account writes to a public repo with. */
    private static final String PUBLIC_REPO = "public_repo";

    /** "2026-11-30 12:00:00 UTC", or with an offset such as "-0800" in place of UTC. */
    private static final Pattern WHEN = Pattern.compile(
        "(\\d{4}-\\d\\d-\\d\\d)[ T](\\d\\d:\\d\\d:\\d\\d)\\s*(UTC|Z|[+-]\\d\\d:?\\d\\d)?");

    private AppAccountChecks() {
    }

    /**
     * The checks of the token, from the headers of GitHub's answer to {@code GET /user} under it, and of its account:
     * whether it may push to {@code repo} and to the checker's own repo, null when GitHub did not say.
     */
    static List<Check> of(String token, HttpHeaders user, String repo, Boolean canPush, Boolean canPushSelf,
        long nowMs) {
        return List.of(scopes(token, user.getFirst(SCOPES), repo), expiry(user.getFirst(EXPIRATION), nowMs),
            push(canPush, repo, ": anyone who reads the server's token can too — use an account without write "
                + "access"),
            push(canPushSelf, GithubClient.SELF_REPO, ", where the checker's releases come from: anyone who reads the "
                + "server's token could publish one"));
    }

    private static Check scopes(String token, String header, String repo) {
        if (token.startsWith(FINE_GRAINED)) {
            return Check.error("GITHUB_TOKEN is a fine-grained token, which cannot write to " + repo + ", a repository "
                + "of another owner: no reply, reaction or story of the app account goes out — use a classic token "
                + "with the " + PUBLIC_REPO + " scope");
        }
        if (header == null)
            return Check.unknown("GitHub did not name the scopes of GITHUB_TOKEN");

        List<String> scopes = Arrays.stream(header.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        String named = scopes.isEmpty() ? "none" : String.join(", ", scopes);
        if (!scopes.contains(PUBLIC_REPO) && !scopes.contains("repo")) {
            return Check.error("GITHUB_TOKEN lacks the " + PUBLIC_REPO + " scope (it has: " + named + "): no reply, "
                + "reaction or story of the app account goes out");
        }

        List<String> beyond = scopes.stream().filter(s -> !s.equals(PUBLIC_REPO) && !s.startsWith("read:")).toList();
        if (!beyond.isEmpty()) {
            return Check.warn("GITHUB_TOKEN has scopes beyond " + PUBLIC_REPO + " (" + String.join(", ", beyond)
                + "): anyone who reads the server's token gets them too");
        }

        return Check.ok("classic token, scopes: " + named);
    }

    private static Check expiry(String header, long nowMs) {
        if (header == null) {
            return Check.warn("GITHUB_TOKEN does not expire: a copy of it works until you revoke it on GitHub — make "
                + "a new one with an expiry, say 90 days");
        }

        Matcher m = WHEN.matcher(header.strip());
        if (!m.matches())
            return Check.unknown("GitHub named an expiry of GITHUB_TOKEN the checker cannot read: " + header.strip());

        String zone = m.group(3) == null || m.group(3).equals("UTC") ? "Z" : m.group(3);
        Instant at = LocalDateTime.parse(m.group(1) + "T" + m.group(2)).toInstant(ZoneOffset.of(zone));
        LocalDate day = at.atOffset(ZoneOffset.UTC).toLocalDate();
        Duration left = Duration.between(Instant.ofEpochMilli(nowMs), at);
        if (left.compareTo(EXPIRY_WARNING) > 0)
            return Check.ok("expires on " + day);

        long days = Math.max(0, left.toDays());
        String when = days == 0 ? "in less than a day" : "in " + days + (days == 1 ? " day" : " days");

        return Check.warn("GITHUB_TOKEN expires on " + day + ", " + when + ": make a new one and replace it in the "
            + "service's environment, or the app account stops replying");
    }

    private static Check push(Boolean can, String repo, String risk) {
        if (can == null)
            return Check.unknown("GitHub did not say whether the app account can push to " + repo);

        return can ? Check.warn("the app account can push to " + repo + risk)
            : Check.ok("cannot push to " + repo);
    }
}
