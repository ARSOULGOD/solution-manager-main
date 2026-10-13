package com.solace.sm.dp.api.home;

import java.util.List;
import java.util.UUID;

/**
 * The data plane's facts for {@code getHomeSummary} (plan 010 T10.5, {@code OR-45}, {@code OR-61}): one row per ACTIVE
 * instance of the organization, with its health already rolled up. The data plane cannot know what the caller may see
 * (authorization is the control plane's, {@code I7}), so it returns every instance of the organization and the gateway
 * filters per instance by the caller's reach - this port never counts anything for a caller.
 *
 * <p>Reads persisted rows only and touches no host, so it answers when a host is down. Every call is scoped to
 * {@code organizationId}, which is {@code Session.organizationId()}, never a request field ({@code I5}).
 */
public interface InventorySummaryService {

    List<InstanceHealthSummary> summarize(UUID organizationId);
}
