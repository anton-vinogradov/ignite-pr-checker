package com.github.igniteprchecker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.igniteprchecker.analysis.BlockerAnalyzer;
import com.github.igniteprchecker.analysis.CauseClusters;
import com.github.igniteprchecker.analysis.FailureDetails;
import com.github.igniteprchecker.analysis.model.AnalysisResult;
import com.github.igniteprchecker.analysis.model.TestVerdict;
import com.github.igniteprchecker.tc.TcClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Every PR page with two blockers or more asked /api/causes for its header count, and the server fetched up to
 * 80 failure messages from TeamCity for it, whether or not anyone opened the Root causes tab.
 */
class CauseCountHeaderTest {
    private final TcClient tc = mock(TcClient.class);

    private final BlockerAnalyzer analyzer = mock(BlockerAnalyzer.class);

    private final CausesController causes = new CausesController(analyzer,
        new CauseClusters(tc, new FailureDetails(tc), Executors.newFixedThreadPool(2)));

    @Test
    void headerGetsTheCountOnlyOnceTheTabGroupedTheBlockers() {
        when(analyzer.analyze("t", 13575)).thenReturn(Optional.of(new AnalysisResult(13575, 9001L, "pull/13575/head",
            0L, List.of(blocker(1, "o1"), blocker(2, "o2")), List.of(), List.of(), List.of(), List.of(), 0, 0, false, 0,
            false, 0, 0, 0, 0, 0)));

        ResponseEntity<?> header = causes.causes(13575, true, "t");
        verifyNoInteractions(tc);
        when(tc.testDetails("t", "o1")).thenReturn("Partition 1 lost");
        when(tc.testDetails("t", "o2")).thenReturn("Lock was not released");
        ResponseEntity<?> tab = causes.causes(13575, false, "t");
        ResponseEntity<?> headerAfterTab = causes.causes(13575, true, "t");

        assertThat(header.getStatusCode().value()).isEqualTo(204);
        assertThat(tab.getStatusCode().value()).isEqualTo(200);
        assertThat(headerAfterTab.getStatusCode().value()).isEqualTo(200);
        assertThat(headerAfterTab.getBody()).isEqualTo(tab.getBody());
    }

    private static TestVerdict blocker(long testId, String occurrenceId) {
        return new TestVerdict(testId, "Cache2: T.test" + testId, "IgniteTests24Java8_Cache2", 9002L, "Cache 2",
            occurrenceId, true, false, "blocker", "FFF", 3);
    }
}
