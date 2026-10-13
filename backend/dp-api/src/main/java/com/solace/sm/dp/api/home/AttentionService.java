package com.solace.sm.dp.api.home;

import java.util.List;
import java.util.UUID;

/**
 * The data plane's facts for {@code getHomeAttention} (plan 010 T10.5, {@code OR-45}, {@code OR-60}, {@code OR-61}):
 * every reason an ACTIVE instance of the organization needs attention, unfiltered. The gateway filters each candidate
 * by the verb that guards its source, at the instance's own scope; this port knows nothing of the caller.
 *
 * <p>Reads persisted rows only and touches no host. Every call is scoped to {@code organizationId}
 * ({@code Session.organizationId()}, never a request field - {@code I5}).
 */
public interface AttentionService {

    List<AttentionCandidate> attention(UUID organizationId);
}
