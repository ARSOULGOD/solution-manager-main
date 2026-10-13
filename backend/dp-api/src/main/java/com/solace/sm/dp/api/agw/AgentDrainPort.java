package com.solace.sm.dp.api.agw;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Asks the agent gateway to drain ONE agent's live session (plan 015 T4.3, {@code drainAgent}; AGW-D18). Called by the console,
 * implemented by the gateway. An HF18-scope addition authorised for lane B in W4 (it was first written as
 * {@code AgentDrainRequester} outside this package and moved here by the W4 lane B review, MAJOR 1); its contract kit is
 * {@code AbstractAgentDrainPortContractTest}.
 *
 * <p><b>Single-replica limit (AGW-D32, {@code OR-44}).</b> The gateway holds live sessions in memory, so this port reaches only the
 * sessions held by THIS process. With a second gateway replica, or once the gateway leaves the process (AGW-D14), an agent connected
 * elsewhere is indistinguishable from one that is not connected, and the console answers {@code AGENT_NOT_CONNECTED}. A remote
 * adapter must pass the kit and route to the replica that holds the session.
 *
 * <p>The port takes no tenant by design: the caller resolves {@code agentId} inside the principal's organization first (I5), and the
 * gateway never sees a tenant (AGW-D3). Only the composition root supplies an implementation, and only when the agent listener is
 * composed.
 */
public interface AgentDrainPort {

    /**
     * Sends {@code Drain} to the agent's live session and arms the force-close at the end of the drain window.
     *
     * @param reason a short lower-case token ({@code [a-z][a-z0-9-]{0,31}}, for example {@code operator-drain}) carried in the
     *               {@code Drain} and in the {@code agent_event}; free text is refused with {@link IllegalArgumentException}
     * @return the {@code reconnect_after} the agent was told; EMPTY when nothing was sent: the agent has no live session here, or its
     *         session is already draining (a repeated request sends nothing). Nothing is ever queued.
     */
    Optional<Duration> drain(UUID agentId, String reason);
}
