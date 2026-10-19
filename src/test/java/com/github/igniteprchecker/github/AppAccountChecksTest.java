package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.github.GithubClient.AppAccount.Check;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * The check of GITHUB_TOKEN named its account and whether it may push to apache/ignite, and showed the account as fine
 * when GitHub did not say. A fine-grained token, which cannot write to another owner's repo, or a classic one without
 * public_repo would have failed every reply unseen, an expiry came without warning, and push to the checker's own repo,
 * where its releases come from, was not looked at.
 */
class AppAccountChecksTest {
    private static final long NOW = Instant.parse("2026-10-14T12:00:00Z").toEpochMilli();

    private static final String CLASSIC = "ghp_0123456789abcdefghijABCDEFGHIJ012345";

    private static final Check CLASSIC_OK = new Check("ok", "classic token, scopes: public_repo");

    private static final Check NEVER_EXPIRES = new Check("warn", "GITHUB_TOKEN does not expire: a copy of it works until"
        + " you revoke it on GitHub — make a new one with an expiry, say 90 days");

    private static final Check NO_PUSH = new Check("ok", "cannot push to apache/ignite");

    private static final Check NO_PUSH_SELF = new Check("ok", "cannot push to anton-vinogradov/ignite-pr-checker");

    /** What the viewers who are not operators read of an expiry more than two weeks ahead. */
    private static final String FINE = "expires in more than 14 days";

    /** What they read of an expiry two weeks ahead or sooner. */
    private static final String SOON = "GITHUB_TOKEN expires in 14 days or less: an operator has to replace it, or the"
        + " app account stops replying";

    private static final String UNREADABLE = "GitHub named an expiry of GITHUB_TOKEN the checker cannot read";

    @Test
    void aClassicPublicRepoTokenOfAnAccountWithoutPushIsFine() {
        assertThat(checks(CLASSIC, headers("public_repo", "2027-01-12 00:00:00 UTC"), false, false))
            .containsExactly(CLASSIC_OK, new Check("ok", "expires on 2027-01-12, in 89 days", FINE), NO_PUSH,
                NO_PUSH_SELF);
    }

    /**
     * The token of prod's app account never expires, and the check showed that as fine: a copy taken from the server
     * writes as the app account until someone notices and revokes it.
     */
    @Test
    void aTokenThatNeverExpiresIsWarnedOf() {
        assertThat(checks(CLASSIC, headers("public_repo", null), false, false).get(1)).isEqualTo(NEVER_EXPIRES);
    }

    @Test
    void aFineGrainedTokenCannotWriteToTheRepo() {
        assertThat(checks("github_pat_11ABCDEFG0123456789_abcdefghij", headers(null, null), false, false).get(0))
            .isEqualTo(new Check("error", "GITHUB_TOKEN is a fine-grained token, which cannot write to apache/ignite,"
                + " a repository of another owner: no reply, reaction or story of the app account goes out — use a"
                + " classic token with the public_repo scope"));
    }

    @Test
    void aClassicTokenWithoutPublicRepoCannotWrite() {
        assertThat(checks(CLASSIC, headers("read:user, user:email", null), false, false).get(0))
            .isEqualTo(new Check("error", "GITHUB_TOKEN lacks the public_repo scope (it has: read:user, user:email):"
                + " no reply, reaction or story of the app account goes out"));
        assertThat(checks(CLASSIC, headers("", null), false, false).get(0).text()).contains("(it has: none)");
    }

    @Test
    void scopesBeyondPublicRepoAreWorthTellingOf() {
        assertThat(checks(CLASSIC, headers("repo, workflow, read:org", null), false, false).get(0))
            .isEqualTo(new Check("warn", "GITHUB_TOKEN has scopes beyond public_repo (repo, workflow): anyone who"
                + " reads the server's token gets them too"));
    }

    @Test
    void anExpiryTwoWeeksAheadIsWarnedOf() {
        assertThat(checks(CLASSIC, headers("public_repo", "2026-10-20 09:30:00 UTC"), false, false).get(1))
            .isEqualTo(new Check("warn", "GITHUB_TOKEN expires on 2026-10-20, in 5 days: make a new one and replace"
                + " it in the service's environment, or the app account stops replying", SOON));
        assertThat(checks(CLASSIC, headers("public_repo", "2026-10-14 20:00:00 -0800"), false, false).get(1).text())
            .startsWith("GITHUB_TOKEN expires on 2026-10-15, in less than a day:");
        assertThat(checks(CLASSIC, headers("public_repo", "2027-01-12 00:00:00 UTC"), false, false).get(1))
            .isEqualTo(new Check("ok", "expires on 2027-01-12, in 89 days", FINE));
    }

    /** 14 days and an hour ahead the check said "in 14 days" and called it fine. */
    @Test
    void aCheckThatSaysFourteenDaysWarns() {
        assertThat(checks(CLASSIC, headers("public_repo", "2026-10-28 13:00:00 UTC"), false, false).get(1))
            .isEqualTo(new Check("warn", "GITHUB_TOKEN expires on 2026-10-28, in 14 days: make a new one and replace"
                + " it in the service's environment, or the app account stops replying", SOON));
        assertThat(checks(CLASSIC, headers("public_repo", "2026-10-29 13:00:00 UTC"), false, false).get(1))
            .isEqualTo(new Check("ok", "expires on 2026-10-29, in 15 days", FINE));
    }

    @Test
    void anExpiryTheCheckerCannotReadIsUnknown() {
        assertThat(checks(CLASSIC, headers("public_repo", "soon"), false, false).get(1))
            .isEqualTo(new Check("unknown", UNREADABLE + ": soon", UNREADABLE));
        assertThat(checks(CLASSIC, headers("public_repo", "2026-02-30 12:00:00 UTC"), false, false).get(1))
            .as("a day that does not exist").isEqualTo(new Check("unknown", UNREADABLE + ": 2026-02-30 12:00:00 UTC",
                UNREADABLE));
    }

    /**
     * The status page showed every signed-in viewer the day GITHUB_TOKEN expires and the days it had left, and anyone
     * may register on ci2. That day is for the operators, who replace the token: the others read only whether it is
     * two weeks away or less, and the rest of the check as it is.
     */
    @Test
    void onlyTheOperatorsReadTheDayTheTokenExpires() {
        List<Check> soon = checks(CLASSIC, headers("public_repo", "2026-10-20 09:30:00 UTC"), false, false);
        List<Check> fine = checks(CLASSIC, headers("public_repo", "2027-01-12 00:00:00 UTC"), false, false);

        assertThat(account(soon).withoutDates().checks())
            .containsExactly(CLASSIC_OK, new Check("warn", SOON), NO_PUSH, NO_PUSH_SELF);
        assertThat(account(fine).withoutDates().checks()).extracting(Check::text)
            .containsExactly(CLASSIC_OK.text(), FINE, NO_PUSH.text(), NO_PUSH_SELF.text());
        assertThat(account(checks(CLASSIC, headers("public_repo", null), false, false)).withoutDates().checks().get(1))
            .as("names no day").isEqualTo(NEVER_EXPIRES);
        assertThat(account(soon).withoutDates())
            .extracting(GithubClient.AppAccount::login, GithubClient.AppAccount::canPush)
            .containsExactly("ignite-pr-checker", false);
    }

    @Test
    void pushToTheRepoOrTheCheckersOwnIsWarnedOf() {
        assertThat(checks(CLASSIC, headers("public_repo", null), true, true)).endsWith(
            new Check("warn", "the app account can push to apache/ignite: anyone who reads the server's token can too"
                + " — use an account without write access"),
            new Check("warn", "the app account can push to anton-vinogradov/ignite-pr-checker, where the checker's"
                + " releases come from: anyone who reads the server's token could publish one"));
    }

    /** GitHub gives the permissions only when it knows the caller; without them nothing says the account is fine. */
    @Test
    void whatGithubDidNotSayIsUnknown() {
        assertThat(checks("gho_0123456789", headers(null, null), null, null)).containsExactly(
            new Check("unknown", "GitHub did not name the scopes of GITHUB_TOKEN"), NEVER_EXPIRES,
            new Check("unknown", "GitHub did not say whether the app account can push to apache/ignite"),
            new Check("unknown", "GitHub did not say whether the app account can push to "
                + "anton-vinogradov/ignite-pr-checker"));
    }

    private static GithubClient.AppAccount account(List<Check> checks) {
        return new GithubClient.AppAccount("ok", "ignite-pr-checker", false, 234567L, checks);
    }

    private static List<Check> checks(String token, HttpHeaders user, Boolean canPush, Boolean canPushSelf) {
        return AppAccountChecks.of(token, user, "apache/ignite", canPush, canPushSelf, NOW);
    }

    private static HttpHeaders headers(String scopes, String expiration) {
        HttpHeaders h = new HttpHeaders();
        if (scopes != null)
            h.add(AppAccountChecks.SCOPES, scopes);
        if (expiration != null)
            h.add(AppAccountChecks.EXPIRATION, expiration);

        return h;
    }
}
