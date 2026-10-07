package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.tc.dto.TcModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import org.junit.jupiter.api.Test;

/**
 * Three recomputes of PR 13583 a quarter of an hour apart, while the checker re-ran Queries 5: what each one
 * may take from the one before. An answer about the branch holds as long as no build of its suite finished
 * since it was fetched, and only TeamCity can say that, from the moment the branch was last checked.
 */
class BuildFactsTest {
    private static final int PR = 13583;

    private static final String CACHE1 = "IgniteTests24Java8_Cache1";

    private static final String QUERIES5 = "IgniteTests24Java8_Queries5";

    private final BuildFacts facts = new BuildFacts();

    /** The three computes' watermarks, in epoch seconds. */
    private final long w1 = System.currentTimeMillis() / 1000 - 3600;

    private final long w2 = w1 + 900;

    private final long w3 = w2 + 900;

    /** Where each check started, in order. */
    private final List<Long> checkedSince = new ArrayList<>();

    private final AtomicInteger fetches = new AtomicInteger();

    @Test
    void theFirstComputeFetchesWithoutAsking() {
        runs(facts.branch(PR, w1, finished()), 1L, CACHE1);

        assertThat(checkedSince).isEmpty();
        assertThat(fetches).hasValue(1);
    }

    @Test
    void anAnswerReusedIsCheckedFromTheLatestCheckOn() {
        runs(facts.branch(PR, w1, finished()), 1L, CACHE1);
        runs(facts.branch(PR, w2, finished()), 1L, CACHE1);
        runs(facts.branch(PR, w3, finished()), 1L, CACHE1);

        assertThat(checkedSince).containsExactly(w1, w2);
        assertThat(fetches).as("nothing finished on the branch since the first fetch").hasValue(1);
    }

    @Test
    void anAnswerAboutASuiteThatFinishedABuildIsFetchedAgain() {
        runs(facts.branch(PR, w1, finished()), 1L, CACHE1);
        runs(facts.branch(PR, w1, finished()), 2L, QUERIES5);

        runs(facts.branch(PR, w2, finished(QUERIES5)), 1L, CACHE1);
        runs(facts.branch(PR, w2, finished(QUERIES5)), 2L, QUERIES5);

        assertThat(fetches).hasValue(3);
    }

    /**
     * The second compute did not look at Queries 5, so its answer was not checked when its re-run finished. The
     * third check starts after that re-run: the answer must not pass it as current.
     */
    @Test
    void anAnswerTheLastComputeDidNotCheckIsFetchedAgain() {
        BuildFacts.Branch first = facts.branch(PR, w1, finished());
        runs(first, 1L, CACHE1);
        runs(first, 2L, QUERIES5);
        runs(facts.branch(PR, w2, finished(QUERIES5)), 1L, CACHE1);

        runs(facts.branch(PR, w3, finished()), 2L, QUERIES5);

        assertThat(fetches).hasValue(3);
    }

    @Test
    void whenTeamCityCannotSayEverythingIsFetchedAgain() {
        runs(facts.branch(PR, w1, finished()), 1L, CACHE1);
        runs(facts.branch(PR, w2, since -> Optional.empty()), 1L, CACHE1);
        runs(facts.branch(PR, w3, since -> {
            throw new IllegalStateException("502 Bad Gateway");
        }), 1L, CACHE1);

        assertThat(fetches).hasValue(3);
    }

    /** Past three hours every answer has been fetched again anyway: TeamCity is not asked about that long. */
    @Test
    void aCheckOlderThanTheAnswersKeptIsNotAsked() {
        long old = System.currentTimeMillis() / 1000 - BuildFacts.TTL_MS / 1000 - 60;
        runs(facts.branch(PR, old, finished()), 1L, CACHE1);

        runs(facts.branch(PR, w1, finished()), 1L, CACHE1);

        assertThat(checkedSince).isEmpty();
    }

    /** Each branch is its own: another PR's check says nothing about this one. */
    @Test
    void aCheckOfAnotherPrDoesNotCount() {
        runs(facts.branch(PR, w1, finished()), 1L, CACHE1);
        runs(facts.branch(13584, w2, finished()), 1L, CACHE1);

        runs(facts.branch(PR, w3, finished()), 1L, CACHE1);

        assertThat(checkedSince).containsExactly(w1);
        assertThat(fetches).hasValue(2);
    }

    /** The latest run of a suite that has none on the branch is an answer too. */
    @Test
    void noLatestRunIsKeptLikeAnyAnswer() {
        TcModel.Build none = facts.branch(PR, w1, finished()).latestRun(CACHE1, this::fetchNone);
        TcModel.Build again = facts.branch(PR, w2, finished()).latestRun(CACHE1, this::fetchNone);

        assertThat(none).isNull();
        assertThat(again).isNull();
        assertThat(fetches).hasValue(1);
    }

    private void runs(BuildFacts.Branch branch, long testId, String suite) {
        branch.testRuns(testId, suite, () -> {
            fetches.incrementAndGet();

            return List.of();
        });
    }

    private Optional<TcModel.Build> fetchNone() {
        fetches.incrementAndGet();

        return Optional.empty();
    }

    /** TeamCity's answer to "which suites finished a build since", naming {@code suites}. */
    private LongFunction<Optional<Set<String>>> finished(String... suites) {
        return since -> {
            checkedSince.add(since);

            return Optional.of(Set.of(suites));
        };
    }
}
