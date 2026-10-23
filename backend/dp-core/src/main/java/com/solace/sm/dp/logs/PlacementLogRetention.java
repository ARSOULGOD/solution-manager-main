package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-placement on-disk log cap of a {@code host-local} agent (plan 016 task 9, spec 5 and 6). INTERNAL: there is no REST
 * write and no permission verb for it; which existing verb will authorize one is an open question for the project owner
 * (spec 6), so nothing here is reachable from the console.
 *
 * <p><b>Order.</b> The cap is stored FIRST and pushed second. A push that fails or finds no session loses nothing: the
 * gateway re-sends the stored value whenever the agent's session opens or its lease is renewed ({@code AgentLogRetentionSource}),
 * and the outcome tells the caller which of the three happened.
 *
 * <p><b>Tenant (I5).</b> The organization is the caller's authenticated one, but the placement is resolved here from the DP's
 * own tables ({@link PlacementLogRetentionRows#isLiveAgentPlacement}); another organization's, a retired and a non-agent
 * placement are all the same {@link UnresolvablePlacementException}, and nothing is stored or sent.
 */
public final class PlacementLogRetention {

    private static final Logger log = LoggerFactory.getLogger(PlacementLogRetention.class);

    /** What happened to a cap that was stored. */
    public enum Outcome {
        /** Stored, and handed to the agent's session. */
        STORED_AND_PUSHED,
        /** Stored; the placement's agent has no session now, so it receives the value when one opens. */
        STORED_AGENT_NOT_CONNECTED,
        /** Stored; the push failed. The stored value is sent on the agent's next session event. */
        STORED_PUSH_FAILED
    }

    /** The placement is not a live agent placement of the organization (the same answer for every reason, I5). */
    public static final class UnresolvablePlacementException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnresolvablePlacementException() {
            super("the placement is not available");
        }
    }

    private final PlacementLogRetentionRows rows;
    private final AgentLogPort port;
    private final Clock clock;
    private final long maxCapBytes;

    public PlacementLogRetention(PlacementLogRetentionRows rows, AgentLogPort port, Clock clock, long maxCapBytes) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxCapBytes < 1) {
            throw new IllegalArgumentException("the maximum cap must be positive");
        }
        this.maxCapBytes = maxCapBytes;
    }

    /**
     * @param maxBytes the cap in bytes; {@code 0} turns collection off
     * @throws IllegalArgumentException when {@code maxBytes} is negative or above the configured maximum (nothing stored)
     * @throws UnresolvablePlacementException when the placement is not a live agent placement of the organization (nothing stored)
     */
    public Outcome setCap(UUID organizationId, UUID placementId, long maxBytes) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(placementId, "placementId");
        if (maxBytes < 0 || maxBytes > maxCapBytes) {
            throw new IllegalArgumentException("the cap must be between 0 and " + maxCapBytes + " bytes");
        }
        if (!rows.isLiveAgentPlacement(organizationId, placementId)) {
            throw new UnresolvablePlacementException();
        }
        rows.upsert(organizationId, placementId, maxBytes, clock.instant());
        try {
            Optional<AgentSessionRef> session = port.sessionFor(organizationId, placementId);
            if (session.isEmpty()) {
                return Outcome.STORED_AGENT_NOT_CONNECTED;
            }
            port.sendRetention(session.get(), placementId, maxBytes);
            return Outcome.STORED_AND_PUSHED;
        } catch (RuntimeException failure) {
            log.warn("the stored log cap of placement {} could not be pushed (it is re-sent at the next session event): {}",
                    placementId, failure.getClass().getSimpleName());
            return Outcome.STORED_PUSH_FAILED;
        }
    }
}
