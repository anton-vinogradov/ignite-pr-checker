package com.github.igniteprchecker.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenPoolTest {
    @Test
    void offerReportsWhenPoolWasEmpty() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));

        assertThat(pool.offer("alice", "a", false)).isTrue();   // was empty
        assertThat(pool.offer("alice", "b", false)).isFalse();  // already had "a"
        assertThat(pool.offer("alice", "a", false)).isFalse();  // refresh, still non-empty
        assertThat(pool.size()).isEqualTo(2);
    }

    /**
     * A logout takes the token out of the pool. Through remove(), as a refusal, the same token lent again by the
     * user's standing options would have stayed out for an hour.
     */
    @Test
    void aForgottenTokenComesBackWithItsNextOffer() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("alice", "a", false);

        pool.forget("a");

        assertThat(pool.size()).isZero();
        assertThat(pool.revoked("a")).isFalse();
        assertThat(pool.offer("alice", "a", false)).isTrue();
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    void nextCyclesThroughAllTokens() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("alice", "a", false);
        pool.offer("alice", "b", false);
        pool.offer("alice", "c", false);

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
        pool.offer("alice", "a", false);
        pool.offer("alice", "b", false);

        pool.remove("a");

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.next()).isEqualTo("b");
        assertThat(pool.next()).isEqualTo("b");
    }

    @Test
    void rejectedTokenIsNotReDonatedUntilItCoolsDown() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("alice", "dead", false);
        pool.remove("dead"); // TeamCity said 401

        // A standing enrollment keeps re-donating it every sweep — that must not resurrect it,
        // or every warm cycle would hammer ci2 with doomed calls.
        assertThat(pool.offer("alice", "dead", false)).isFalse();
        assertThat(pool.size()).isZero();

        // A fresh login proves the token works again (re-issued, or TeamCity had a hiccup).
        assertThat(pool.offer("alice", "dead", true)).isTrue();
        assertThat(pool.next()).isEqualTo("dead");
    }

    @Test
    void rejectionExpiresWithTheTtl() {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        TokenPool pool = new TokenPool(40, now::get); // 40 ms TTL and cooldown
        pool.offer("alice", "t", false);
        pool.remove("t");

        now.addAndGet(70);

        assertThat(pool.offer("alice", "t", false)).isTrue(); // cooled down: worth one more try
        assertThat(pool.size()).isEqualTo(1);
    }

    /** The session cookie never expires, so a session must not outlive TeamCity's 401 on its token. */
    @Test
    void a401KeepsTheTokenRevokedPastTheCooldownUntilALogin() {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        TokenPool pool = new TokenPool(40, now::get);
        pool.offer("alice", "dead", false);
        pool.remove("dead", true);

        now.addAndGet(70);
        pool.offer("alice", "dead", false); // an enrollment re-donates it after the cooldown

        assertThat(pool.revoked("dead")).isTrue();

        pool.offer("alice", "dead", true);

        assertThat(pool.revoked("dead")).isFalse();
    }

    /** The ci2 WAF answers 403 to valid requests too, so a 403 drops the token from the pool only. */
    @Test
    void a403DoesNotRevokeTheToken() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("alice", "t", false);
        pool.remove("t", false);

        assertThat(pool.revoked("t")).isFalse();
        assertThat(pool.size()).isZero();
    }

    @Test
    void emptyPoolHandsOutNull() {
        assertThat(new TokenPool(TimeUnit.MINUTES.toMillis(60)).next()).isNull();
    }

    /**
     * A heap dump of the service held every TeamCity token ever refused, in the clear and for good: a revoked token
     * is someone's account, and some come back to life when TeamCity had only a hiccup.
     */
    @Test
    void aRefusedTokenIsKeptByItsHashOnly() throws Exception {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        pool.offer("alice", "revoked-tc-token", false);
        pool.offer("alice", "blocked-tc-token", false);

        pool.remove("revoked-tc-token", true);
        pool.remove("blocked-tc-token", false);

        assertThat(held(pool)).doesNotContain("revoked-tc-token", "blocked-tc-token");
        assertThat(pool.revoked("revoked-tc-token")).isTrue();
        assertThat(pool.offer("alice", "blocked-tc-token", false)).as("still cooling down").isFalse();
    }

    @Test
    void aRevokedTokenIsForgottenAfterAMonth() {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60), now::get);
        pool.remove("dead", true);

        now.addAndGet(TokenPool.REVOKED_MEMORY.toMillis());
        assertThat(pool.revoked("dead")).isTrue();

        now.incrementAndGet();
        assertThat(pool.revoked("dead")).isFalse();
    }

    @Test
    void theMemoryOfRevokedTokensIsBounded() {
        TokenPool pool = new TokenPool(TimeUnit.MINUTES.toMillis(60));
        for (int i = 0; i <= TokenPool.REVOKED_MAX; i++)
            pool.remove("dead-" + i, true);

        assertThat(pool.revoked("dead-0")).isFalse();
        assertThat(pool.revoked("dead-1")).isTrue();
        assertThat(pool.revoked("dead-" + TokenPool.REVOKED_MAX)).isTrue();
    }

    @Test
    void staleTokensAreEvicted() {
        AtomicLong now = new AtomicLong(1_800_000_000_000L);
        TokenPool pool = new TokenPool(40, now::get); // 40 ms TTL
        pool.offer("alice", "old", false);

        now.addAndGet(70);
        pool.offer("alice", "fresh", false); // "old" is now past its TTL

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.next()).isEqualTo("fresh");
    }

    /** Everything the pool's maps and sets hold, keys and values, as text. */
    private static String held(TokenPool pool) throws IllegalAccessException {
        StringBuilder all = new StringBuilder();
        for (Field f : TokenPool.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object v = f.get(pool);
            if (v instanceof Map<?, ?> m)
                all.append(m.keySet()).append(m.values());
            else if (v instanceof Collection<?> c)
                all.append(c);
        }

        return all.toString();
    }
}
