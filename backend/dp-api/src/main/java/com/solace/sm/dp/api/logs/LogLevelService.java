package com.solace.sm.dp.api.logs;

import java.util.UUID;

/**
 * The live log-level READ (plan 010 T9.3, OR-103 option Rd). Reads the running process's level over SSH from the
 * placement's loopback; there is no live write here or anywhere in v1: every level change is configuration (T9.4).
 */
public interface LogLevelService {

    /**
     * A bounded read on the request thread. Never throws for a host that cannot answer: that is an availability, with a
     * reason, not an error.
     *
     * @throws java.util.NoSuchElementException the placement is not a live placement of this instance of this organization
     */
    LogLevelView read(UUID organizationId, UUID instanceId, UUID placementId, LoggerTarget target);
}
