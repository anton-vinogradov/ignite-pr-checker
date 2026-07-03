package com.github.igniteprchecker.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.igniteprchecker.config.SessionProperties;
import org.junit.jupiter.api.Test;

class SessionStoreTest {
    @Test
    void createLookupAndRemove() {
        SessionStore store = new SessionStore(new SessionProperties(60, false));

        UserSession s = store.create("bob", "secret-token");

        assertThat(store.get(s.id())).contains(s);
        assertThat(s.username()).isEqualTo("bob");
        assertThat(s.token()).isEqualTo("secret-token");

        store.remove(s.id());
        assertThat(store.get(s.id())).isEmpty();
    }

    @Test
    void expiredSessionIsEvicted() {
        SessionStore store = new SessionStore(new SessionProperties(-1, false));

        UserSession s = store.create("bob", "tok");

        assertThat(store.get(s.id())).isEmpty();
    }

    @Test
    void unknownOrNullId() {
        SessionStore store = new SessionStore(new SessionProperties(60, false));

        assertThat(store.get(null)).isEmpty();
        assertThat(store.get("does-not-exist")).isEmpty();
    }
}
