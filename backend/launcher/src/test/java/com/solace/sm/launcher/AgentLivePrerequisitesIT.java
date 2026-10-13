package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Under {@code -Pagent-live} the prerequisites are checked once, first, with one clear failure (plan 015 T4.4 step 2). Excluded from the
 * ordinary build with the other live classes.
 */
class AgentLivePrerequisitesIT {

    @Test
    void goDockerAlpineAndTheCacheImageArePresent() {
        AgentLive.require();
        assertTrue(AgentLive.missing().isEmpty());
    }
}
