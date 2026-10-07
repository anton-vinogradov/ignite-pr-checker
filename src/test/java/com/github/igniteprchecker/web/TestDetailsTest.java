package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.FailureDetails;
import com.github.igniteprchecker.tc.TcClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * testGroupReservation's 38 KB of output, fetched from ci2 again at each "why?" and "ai", and labelled
 * "environment/timing" by the page because of its stdout. The endpoint answers with what the message and
 * the stack trace say, and reads the occurrence from ci2 once.
 */
class TestDetailsTest {
    private final TcClient tc = mock(TcClient.class);

    private final TestDetailsController details = new TestDetailsController(new FailureDetails(tc));

    @Test
    void sameOccurrenceIsReadOnceAndNamedByItsStackTrace() throws IOException {
        String occ = "build:(id:9388909),id:2000000230";
        when(tc.testDetails("t", occ))
            .thenReturn(Files.readString(Path.of("src/test/resources/details/assertion-testGroupReservation.txt")));

        Map<String, String> why = details.details(occ, "t");
        Map<String, String> ai = details.details(occ, "t");

        assertThat(why).containsEntry("kind", "assertion").isEqualTo(ai);
        assertThat(why.get("details")).startsWith("expected:<false> but was:<true>\n------- Stdout: -------\n")
            .endsWith("characters kept; the whole output is in TeamCity]");
        verify(tc, times(1)).testDetails(anyString(), anyString());
    }

    @Test
    void occurrenceWithoutAMessageAnswersEmpty() {
        assertThat(details.details("build:(id:1),id:2", "t")).containsEntry("details", "").containsEntry("kind", "");
    }
}
