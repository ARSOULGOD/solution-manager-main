package com.solace.sm.dp.api.agw;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What an agent is told to collect, per placement of its active lease; implemented by dp-core's
 * {@code AgentTelemetryDeclarationsService} (plan 015 T3.4). The gateway reads it at session open and at every lease renewal or
 * re-issue, and (plan 015 W4 lane D) whenever the data plane signals a change through {@link #onDeclarationsChanged}.
 */
public interface AgentTelemetryDeclarationsPort {
    List<TelemetryDeclaration> forAgent(AgentSessionRef ref);

    /**
     * The declarations-change signal (plan 015 W4 notes (a) and nit 10; an HF18 addition authorised by the integrator). Registers
     * {@code affectedAgents}: the data plane calls it with the id of an agent enrolled on a target where an instance's declared set
     * MAY have changed (a metric activation, an instance activated, retired or rolled back, a placement added or retired),
     * AFTER the change committed. The gateway then asks the agent to renew when its lease no longer names the live placement set
     * (a lease's placement set is a snapshot, so a placement added since is declared only after the renewal), and re-reads and
     * sends only what differs.
     *
     * <p>A notice carries an agent id and nothing else: it is a prompt to re-read, never data, so a lost or spurious one costs
     * one read, and the lease renewal remains the backstop. Registering the same consumer instance twice is one registration. The
     * consumer runs on the committing thread and must not block or throw. The default is a no-op that never calls back, which is
     * the pre-W4 behaviour (the delay of up to one renewal interval) for an implementation that does not signal.
     */
    default void onDeclarationsChanged(Consumer<UUID> affectedAgents) {
    }
}
