package com.github.igniteprchecker.github;

import java.util.Locale;

/** GitHub accounts of the tests: one login names one account, whatever its case, and keeps its id. */
public final class GhUsers {
    private GhUsers() {
    }

    /** The account of {@code login}, as GitHub names the author of a comment or the owner of a token. */
    public static GithubClient.GhUser of(String login) {
        return new GithubClient.GhUser(login, idOf(login));
    }

    /** The id of the account of {@code login}. */
    public static long idOf(String login) {
        return 1_000_000L + Math.floorMod(login.toLowerCase(Locale.ROOT).hashCode(), 1_000_000);
    }
}
