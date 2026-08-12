package com.github.igniteprchecker.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The checker's own comments are read back by its command poll, so nothing it posts may look like a
 * command. Taking a narration over from a dead PAT re-posted the author's comment verbatim — first
 * line "/runall" included — and the poll obligingly triggered another RunAll on two PRs.
 */
class CommandLoopTest {
    @Test
    void aTakenOverNarrationCarriesTheStatusNotTheCommand() {
        String authored = "/runall\n\n---\n🚀 **RunAll queued** — build 42.\n⏱ _~2h left._";

        String status = PrCommands.statusOf(authored);

        assertThat(status).doesNotContain("/runall").startsWith("🚀 **RunAll queued**");
        assertThat(PrCommands.readsAsCommand("@someone " + status)).isFalse();
    }

    @Test
    void theCommandItselfStillParses() {
        assertThat(PrCommands.readsAsCommand("/runall")).isTrue();
        assertThat(PrCommands.readsAsCommand("/run-all top")).isTrue();
        assertThat(PrCommands.readsAsCommand("/top")).isTrue();
        assertThat(PrCommands.readsAsCommand("please /runall")).isFalse();
    }
}
