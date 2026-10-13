package com.solace.sm.dp.schedule;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What a schedule asks of the registry, the config of record and the apply journal (plan 012 T7): three yes/no questions
 * at submit and, since {@code OR-125} (5), one lookup at fire, each scoped to the tenant ({@code I5}). Narrow on purpose, so the service is tested without faking the
 * three wide row ports it is implemented over ({@link ApplyScheduleConfiguration}).
 */
public interface ScheduleTargets {

    /** The instance exists and is {@code active}: a pending, abandoned, retiring, retired or unknown one is not a target. */
    boolean instanceActive(UUID tenantId, UUID instanceId);

    /**
     * Takes the instance's {@code apply_scope} lock (the first lock of the order {@code apply_scope -> instance_state}, the one
     * {@code beginRetire} holds) and then answers {@link #instanceActive}. Call it inside the transaction that inserts the
     * schedule: an undeclare that committed first is seen as not active, and one that comes later sees the new row (OR-131).
     */
    boolean lockInstanceScopeAndCheckActive(UUID tenantId, UUID instanceId);

    /** The placement is a LIVE placement of THIS instance of THIS tenant. */
    boolean placementLive(UUID tenantId, UUID instanceId, UUID placementId);

    /** The generation exists for this instance (staged and not retired). */
    boolean generationStaged(UUID tenantId, UUID instanceId, long generation);

    /**
     * The ordinary apply that ALREADY exists under {@code applyId} for this instance of this tenant, if any ({@code OR-125}
     * (5)): the committed {@code apply_request} row. The schedule's fire pre-mints the apply's id, so a retry can ask whether
     * an earlier attempt got as far as the engine's commit before it died, instead of admitting a second time (which the
     * fences the first admission raised would refuse).
     */
    Optional<AdmittedRequest> admittedUnder(UUID tenantId, UUID instanceId, UUID applyId);

    /**
     * An apply request as the schedule needs it to record that it was admitted.
     *
     * @param placementId           the placement the request names (the instance-wide sentinel is not a schedule's)
     * @param generation            the generation the request applies (a rollback's is the copy-forward it minted)
     * @param requestedAt           when the engine admitted it: the moment the schedule was in fact fired
     * @param derivedFromGeneration the generation the request's generation was copied from, for a rollback's copy-forward;
     *                              {@code null} for a generation staged by an edit
     */
    record AdmittedRequest(UUID applyId, UUID placementId, long generation, Instant requestedAt,
            Long derivedFromGeneration) {
    }
}
