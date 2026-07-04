package com.github.igniteprchecker.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateServiceTest {
    @Test
    void offersStrictlyNewerReleases() {
        assertTrue(UpdateService.isNewer("0.1.1", "0.1.0"));
        assertTrue(UpdateService.isNewer("0.2.0", "0.1.9"));
        assertTrue(UpdateService.isNewer("1.0", "0.9.9"));
    }

    @Test
    void doesNotOfferSameOrOlderReleases() {
        assertFalse(UpdateService.isNewer("0.1.1", "0.1.1"));
        assertFalse(UpdateService.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateService.isNewer("0.1.1", "0.1.2")); // dev build already ahead of the last release
    }
}
