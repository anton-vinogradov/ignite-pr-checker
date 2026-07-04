package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TokenPoolTest {
    @Test
    void offerReportsWhenPoolWasEmpty() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));

        assertThat(pool.offer("a")).isTrue();   // was empty
        assertThat(pool.offer("b")).isFalse();  // already had "a"
        assertThat(pool.offer("a")).isFalse();  // refresh, still non-empty
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    void nextCyclesThroughAllTokens() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("a");
        pool.offer("b");
        pool.offer("c");

        // Three consecutive picks must cover every token (round-robin over a stable set of 3).
        Set<String> firstRound = Set.of(pool.next(), pool.next(), pool.next());
        assertThat(firstRound).containsExactlyInAnyOrder("a", "b", "c");

        // And the cursor keeps cycling, not sticking on one.
        Set<String> secondRound = Set.of(pool.next(), pool.next(), pool.next());
        assertThat(secondRound).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void removedTokenIsNotHandedOut() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("a");
        pool.offer("b");

        pool.remove("a");

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.next()).isEqualTo("b");
        assertThat(pool.next()).isEqualTo("b");
    }

    @Test
    void emptyPoolHandsOutNull() {
        assertThat(new TokenPool(TimeUnit.MINUTES.toMillis(60)).next()).isNull();
    }

    @Test
    void staleTokensAreEvicted() throws Exception {
        TokenPool pool = new TokenPool(40); // 40 ms TTL
        pool.offer("old");

        Thread.sleep(70);
        pool.offer("fresh"); // "old" is now past its TTL

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.next()).isEqualTo("fresh");
    }
}
