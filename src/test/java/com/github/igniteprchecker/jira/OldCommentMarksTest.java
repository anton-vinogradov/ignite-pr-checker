package com.github.igniteprchecker.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.TeamcityProperties;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The page, the comment's legend and the docs write "re-run" for every re-run now, and "Rerun" only on the button.
 * The server finds its own earlier comments by the lines they end with: a comment whose re-runs a newer RunAll cut
 * short, and one still saying that auto re-runs go. Those lines keep their words, so comments already in the PRs and
 * the tickets are still found.
 */
class OldCommentMarksTest {
    /** The last line of a comment whose run a newer RunAll replaced before its re-runs settled. */
    private static final String REPLACED = "**[Ignite PR Checker](https://checker.example/?pr=13427)** verdict\n\n"
        + "🛑 _Re-runs stopped: RunAll 9386000 replaced this run before they settled. The verdict above is this run's "
        + "last; the newest is on [the checker's page](https://checker.example/?pr=13427)._";

    /** The line an older release left in a comment while its auto re-runs went, in GitHub's and JIRA's markup. */
    private static final String IN_PROGRESS_GH = "\n\n⏳ _Auto re-run **#1** (of up to 2) in progress — 2 blocker "
        + "suite(s) re-queued. This comment updates when they settle._";

    private static final String IN_PROGRESS_JIRA = "\r\n\r\n⏳ _Auto re-run *#1* (of up to 2) in progress — 2 blocker "
        + "suite(s) re-queued. This comment updates when they settle._";

    @Test
    void aCommentWhoseReRunsANewerRunCutShortIsStillFound() {
        VisaService visas = new VisaService(new TeamcityProperties("https://ci2.example/"), "https://checker.example");

        assertThat(visas.saysSuperseded(REPLACED)).isTrue();
    }

    @Test
    void aCommentLeftSayingReRunsGoIsStillFound() throws Exception {
        Method pendingAt = StandingVisas.class.getDeclaredMethod("pendingAt", String.class);
        pendingAt.setAccessible(true);

        for (String line : new String[] {IN_PROGRESS_GH, IN_PROGRESS_JIRA}) {
            String body = "verdict" + line;
            assertThat(pendingAt.invoke(null, body)).as(line).isEqualTo(body.indexOf('⏳'));
        }
    }
}
