package com.solace.sm.dp.api.inventory;

import java.util.List;
import java.util.UUID;

/**
 * The data plane's half of undeclare (plan 012 T3, {@code ops-501.md} section 4.4). Undeclare writes two planes and
 * {@code I7} forbids a distributed transaction, so the gateway runs it as three steps and this port is steps 3 and 5 and
 * the sweep's two moves: {@link #beginRetire} records the intent, the Control Plane releases its governance node, then
 * {@link #completeRetire} finishes. A crash between them is recovered by an authorized retry of the same call (which
 * {@linkplain RetirementBegun#resumed() resumes}) or by the sweep. Every call is scoped to {@code organizationId} ({@code I5}),
 * and none of them touches a host: the workload is left running (U-C).
 *
 * <p>An {@code instance_state} read by every other service sees {@code retiring} as gone from the first step: admission,
 * pull collection (the health poller and the metrics loop, through {@code instances.list(ACTIVE)}), attention and
 * {@code getInstance} all serve {@code active} only. Push ingest is the exception (W2 review W2C-5): it resolves a placement
 * by placement liveness ({@code retired_at IS NULL}), not by lifecycle, so samples pushed for a retiring instance keep
 * landing until {@link #completeRetire} retires its placements. Whether ingest should also require {@code active} is plan
 * 012 T5's to decide.
 */
public interface InstanceRetirementService {

    /**
     * Step 3, one transaction, under the instance's {@code apply_scope} row lock (the lock admission takes, so an undeclare
     * and an admission cannot race: exactly one wins): refuses while anything is in flight, then {@code active -> retiring}
     * and the open {@code instance_retirement} record carrying {@code correlationId} and each placement's last health. On an
     * instance that is already {@code retiring} with an open record it writes nothing and answers that record,
     * {@code resumed}; the record's {@code requestedBy} stays the original requester.
     *
     * @throws RetirementRefusedException {@code NOT_FOUND}, {@code REQUEST_IN_FLIGHT} or {@code FLEET_IN_FLIGHT}
     */
    RetirementBegun beginRetire(UUID organizationId, UUID instanceId, UUID requestedBy, UUID correlationId,
            WorkloadDisposition workload);

    /**
     * Step 5, one transaction under the same lock: every live placement is retired, the open record is closed
     * {@code retired}, and {@code retiring -> retired}. Idempotent: on an instance that is already {@code retired} it
     * writes nothing and answers the closed record, so a retry and the sweep may both finish the same undeclare.
     *
     * @throws RetirementRefusedException {@code NOT_FOUND}, or {@code NOT_RETIRING} when no undeclare is in progress
     */
    RetirementView completeRetire(UUID organizationId, UUID instanceId);

    /**
     * The sweep's roll-back, one transaction under the same lock: the open record is closed {@code rolled-back} and
     * {@code retiring -> active}. The caller (never this method) decides it is time: the Control Plane node is live and
     * the record is older than the window.
     *
     * @return {@code true} when it rolled one back, {@code false} when the instance is not {@code retiring} (nothing written)
     */
    boolean rollBackRetire(UUID organizationId, UUID instanceId);

    /**
     * This tenant's {@code retiring} instances, oldest first, for the sweep. Tenant-bound; no tenant enumeration is added
     * ({@code OR-44}, {@code OR-49} are untouched).
     */
    List<RetiringInstance> retiring(UUID organizationId);
}
