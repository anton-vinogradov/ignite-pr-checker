package com.github.igniteprchecker.jira;

/**
 * What became of the visa of a run of the Python thin client's suite, for the comment that brings the run's verdict:
 * its {@code state}, and the ticket and the JIRA comment it went to ({@code issue}, {@code url}). {@code why}: for
 * {@link #WAITS}, why the token is gone, {@link StandingVisas#TOKEN_REMOVED}, {@link SettingsJournal.Entry#KEY_CHANGED}
 * or {@link SettingsJournal.Entry#DAMAGED}; for {@link #FAILED}, what failed, in words for the commander. Kept with the
 * run in pr-commands.json, so a restart never posts it twice.
 */
public record ClientVisa(String state, String issue, String url, String why) {
    /** The commander's "Visa my runs in JIRA" does not act on the run: off, or switched on after the run finished. */
    public static final ClientVisa NONE = new ClientVisa("none", null, null, null);

    /** The visa is in the ticket. */
    public static final String POSTED = "posted";

    /** The commander's last visa of the PR in that ticket has the same verdict for the same commits: none went out. */
    public static final String SAME = "same";

    /** The PR title names no IGNITE ticket. */
    public static final String NO_TICKET = "no-ticket";

    /** The PR was closed or merged before the run's verdict. */
    public static final String CLOSED = "closed";

    /** The option waits for a JIRA token the checker no longer has; {@code why} says why it is gone. */
    public static final String WAITS = "waits";

    /** JIRA refused the stored token: the checker dropped it, and the option went off. */
    public static final String REFUSED = "refused";

    /** JIRA or GitHub failed; {@code why} says what failed. */
    public static final String FAILED = "failed";

    static ClientVisa posted(String issue, String url) {
        return new ClientVisa(POSTED, issue, url, null);
    }

    static ClientVisa same(String issue, String url) {
        return new ClientVisa(SAME, issue, url, null);
    }

    static ClientVisa of(String state, String why) {
        return new ClientVisa(state, null, null, why);
    }

    /** Whether a later try may post it: JIRA or GitHub failed this time. */
    public boolean failed() {
        return FAILED.equals(state);
    }
}
