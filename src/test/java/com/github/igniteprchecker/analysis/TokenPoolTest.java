package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TokenPoolTest {
    @Test
    void offerReportsWhenPoolWasEmpty() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));

        assertThat(pool.offer("a", false)).isTrue();   // was empty
        assertThat(pool.offer("b", false)).isFalse();  // already had "a"
        assertThat(pool.offer("a", false)).isFalse();  // refresh, still non-empty
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    void nextCyclesThroughAllTokens() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("a", false);
        pool.offer("b", false);
        pool.offer("c", false);

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
        pool.offer("a", false);
        pool.offer("b", false);

        pool.remove("a");

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.next()).isEqualTo("b");
        assertThat(pool.next()).isEqualTo("b");
    }

    @Test
    void rejectedTokenIsNotReDonatedUntilItCoolsDown() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("dead", false);
        pool.remove("dead"); // TeamCity said 401

        // A standing enrollment keeps re-donating it every sweep — that must not resurrect it,
        // or every warm cycle would hammer ci2 with doomed calls.
        assertThat(pool.offer("dead", false)).isFalse();
        assertThat(pool.size()).isZero();

        // A fresh login proves the token works again (re-issued, or TeamCity had a hiccup).
        assertThat(pool.offer("dead", true)).isTrue();
        assertThat(pool.next()).isEqualTo("dead");
    }

    @Test
    void rejectionExpiresWithTheTtl() throws Exception {
        TokenPool pool = new TokenPool(40); // 40 ms TTL and cooldown
        pool.offer("t", false);
        pool.remove("t");

        Thread.sleep(70);

        assertThat(pool.offer("t", false)).isTrue(); // cooled down: worth one more try
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    void emptyPoolHandsOutNull() {
        assertThat(new TokenPool(TimeUnit.MINUTES.toMillis(60)).next()).isNull();
    }

    @Test
    void staleTokensAreEvicted() throws Exception {
        TokenPool pool = new TokenPool(40); // 40 ms TTL
        pool.offer("old", false);

        Thread.sleep(70);
        pool.offer("fresh", false); // "old" is now past its TTL

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.next()).isEqualTo("fresh");
    }
}
