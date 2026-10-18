package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The JVM ran out of memory, wrote a heap dump with decrypted tokens in it and restarted, and the status page showed
 * none of it: the service's log has no word of an OOM. The page shows when the JVM ran out of memory while its dump is
 * on disk, and when the dump goes.
 */
class OutOfMemoryCardTest {
    private static final String[] FUNCTIONS = {"fmtDur", "card", "agoShort", "heapDumpCard"};

    @Test
    void aDumpOnDiskShowsWhenTheJvmRanOutOfMemoryAndWhenTheDumpGoes() throws Exception {
        String card = run("const now = Date.now();"
            + " out(heapDumpCard({ writtenAt: now - 5 * 3600e3, deletedAt: now + 43 * 3600e3 }));").asText();

        assertThat(card).contains("Out of memory", "5h ago", "heap dump deleted in 1d 19h 0m", "class=\"v warn\"",
            "Its heap dump holds decrypted tokens, so the server deletes it two days after the JVM wrote it.");
    }

    @Test
    void aDumpPastItsTimeGoesAtTheNextSweep() throws Exception {
        String card = run("const now = Date.now();"
            + " out(heapDumpCard({ writtenAt: now - 48 * 3600e3, deletedAt: now - 1000 }));").asText();

        assertThat(card).contains("heap dump deleted within 10 min");
    }

    @Test
    void noDumpNoCard() throws Exception {
        assertThat(run("out(heapDumpCard(null));").asText()).isEmpty();
    }

    private static JsonNode run(String script) throws Exception {
        return PageFunctions.run("status.js", script, FUNCTIONS);
    }
}
