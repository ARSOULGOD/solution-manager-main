package com.solace.sm.dp.api.agw;

import java.util.List;

/**
 * A read-only, allow-listed view of a {@code host-local} managed integration's configuration file, asked of the agent that serves
 * the placement (plan 017). The gateway implements it; the data plane calls it and never sees a gRPC type (AGW-D2).
 *
 * <p>The caller resolves {@code agentId} from its own tenant-scoped view of the placement (I5). The gateway holds no tenant scope
 * (AGW-D3): {@code tenantId} is required, scopes nothing in the gateway and is never echoed to the agent. The binding the gateway
 * does enforce is the agent's: the request goes to that agent's live session, and only a reply from that same session can answer it.
 *
 * <p>A request is held in memory only, at most one per agent at a time. It is not a command and is not journalled.
 */
public interface AgentConfigViewPort {

    /**
     * Asks the agent for the scalar settings of the placement's configuration file whose dotted paths match {@code allow}, bounded
     * by {@code maxBytes} of output. Blocks until the reply, the gateway's deadline, or the end of the session.
     *
     * <p>Never throws for a refusal: a placement outside the lease, an agent refusal, a busy agent, a missing session, a missed
     * deadline or a malformed reply is an {@link AgentConfigView} with its {@link AgentConfigView.Status status}. Bad arguments (a
     * null or blank id, an id that is not a UUID, a null {@code allow}, a non-positive {@code maxBytes}) throw synchronously
     * ({@code NullPointerException} or {@code IllegalArgumentException}).
     */
    AgentConfigView read(String tenantId, String agentId, String placementId, List<String> allow, int maxBytes);
}
