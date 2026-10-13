package com.solace.sm.dp.api.config;

import java.util.UUID;

/**
 * The data plane's read side for one instance's observed state (plan 010 T5.3). Every call is tenant-scoped by
 * {@code organizationId}, which is {@code Session.organizationId()}, never a request field (I5). The caller has
 * already authorized the instance; an instance that is not this organization's reads as one with nothing observed,
 * so the answer discloses nothing.
 *
 * <p>The {@code persisted} and {@code restart-observed} rungs are derived from the apply journal (plan 010 T6.5); the
 * {@code effective-verified} rung is declared absent for every package and the ladder says so
 * ({@link ConfigLadderView#degradedReason()}).
 */
public interface ObservedStateService {

    /** What {@code getDesigner} and {@code stageConfig} project against. */
    ObservedInstance observed(UUID organizationId, UUID instanceId);

    /** What {@code getConfigLadder} reports. */
    ConfigLadderView ladder(UUID organizationId, UUID instanceId);
}
