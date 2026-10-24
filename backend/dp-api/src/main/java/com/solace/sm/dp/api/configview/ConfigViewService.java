package com.solace.sm.dp.api.configview;

import java.util.UUID;

/**
 * A read-only, allow-listed view of an agent placement's managed-integration configuration file (plan 017 task 5).
 *
 * <p>The placement is resolved under {@code (organizationId, instanceId)} from the data plane's own tables (I5): one that is not
 * that instance's in that organization, or is retired, is {@link ConfigViewResult.Reason#NOT_FOUND}, and no agent is asked. The
 * instance is part of the key because the console authorizes at instance scope; a placement of another instance must never be
 * readable through this one.
 *
 * <p>Never throws for a refusal; bad arguments (a null id) throw {@code NullPointerException}. The call may block for up to the
 * configured {@code sm.dp.config-view.timeout} plus a short grace.
 */
public interface ConfigViewService {

    ConfigViewResult view(UUID organizationId, UUID instanceId, UUID placementId);
}
