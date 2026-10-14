package com.github.igniteprchecker.github;

import java.util.List;
import java.util.Optional;

/**
 * Which GitHub tokens of a user the checker keeps to commit with: the checkstyle autofix and tests added to suites
 * push to the branch of the user's PR, and nothing else of theirs is done with their token. The checker took any
 * token GitHub put a name to, an {@code admin:org} or {@code delete_repo} one too. A fine-grained token given only the
 * user's fork is what it asks for; a classic one is taken only with {@code public_repo}, or {@code repo}, and never
 * with scopes that administer, delete repositories or change workflows.
 */
public final class CommitTokens {
    /** What a fine-grained token starts with. */
    private static final String FINE_GRAINED = "github_pat_";

    private CommitTokens() {
    }

    /**
     * Why the checker does not keep the token, the {@code scopes} GitHub named for it; empty when it does. Null
     * {@code scopes}: GitHub named none, as for a fine-grained token, which can do only what it was given.
     */
    public static Optional<String> refusal(String token, List<String> scopes) {
        if (fineGrained(token, scopes))
            return Optional.empty();

        List<String> tooMuch = scopes.stream()
            .filter(s -> s.startsWith("admin:") || s.equals("delete_repo") || s.equals("workflow")).toList();
        if (!tooMuch.isEmpty()) {
            return Optional.of("this classic token has " + String.join(", ", tooMuch) + ": with it, anyone who reads "
                + "it could administer, delete repositories or change workflows, and the checker only commits. Create "
                + "one without them: a fine-grained token for your fork, or a classic one with public_repo");
        }
        if (!scopes.contains("public_repo") && !scopes.contains("repo")) {
            return Optional.of("this classic token has no public_repo scope, so it cannot commit to your PR's branch: "
                + "create a fine-grained token for your fork, or a classic one with public_repo");
        }

        return Optional.empty();
    }

    /** What the user is told of a token the checker keeps; null when nothing. */
    public static String note(String token, List<String> scopes) {
        if (fineGrained(token, scopes))
            return null;

        String repo = scopes.contains("repo") ? ", and with the repo scope to your private ones as well; public_repo is"
            + " enough" : "";

        return "Your saved token is a classic one: it can push to every public repository you can push to,"
            + " apache/ignite too if you are a committer" + repo + ". A fine-grained token for your fork can push there"
            + " only.";
    }

    private static boolean fineGrained(String token, List<String> scopes) {
        return scopes == null || token.startsWith(FINE_GRAINED);
    }
}
