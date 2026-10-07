package com.github.igniteprchecker.analysis;

import com.github.igniteprchecker.tc.TcClient;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Failure output of test occurrences, fetched from TeamCity once and kept cut to size (see
 * {@link FailureOutput#trim}). An occurrence's output never changes, yet "why?", "ai", the root-cause
 * groups and a cause's "ai" each fetched it again, up to 700 KB a time, to read a few lines.
 */
@Component
public class FailureDetails {
    /**
     * Outputs of at most 32 000 characters each, so 6 to 13 MB at most: enough for the root causes of a few big
     * runs and the messages people open.
     */
    static final int CAPACITY = 200;

    private final TcClient tc;

    private final Map<String, String> byOccurrence = Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > CAPACITY;
            }
        });

    public FailureDetails(TcClient tc) {
        this.tc = tc;
    }

    /** The occurrence's failure output, cut to size; null when TeamCity has none for it. */
    public String of(String token, String occurrenceId) {
        String kept = byOccurrence.get(occurrenceId);
        if (kept != null)
            return kept;

        String details = FailureOutput.trim(tc.testDetails(token, occurrenceId));
        if (details != null)
            byOccurrence.put(occurrenceId, details);

        return details;
    }
}
