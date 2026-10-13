package com.solace.sm.dp.retirement;

import com.solace.sm.dp.api.inventory.RetirementOutcome;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port of undeclare ({@code instance_retirement}, and the lifecycle moves of {@code instance_state} that
 * only it makes). The service in this package imports no JDBC type ({@code jdbcIsConfinedToPersistencePackages});
 * {@code JdbcRetirementRepository} implements it. Every method names the tenant ({@code I5}) and binds its parameters, and
 * every method is called inside the service's one transaction.
 *
 * <p>The lifecycle moves are CONDITIONAL on the row's current lifecycle and return the rows they changed, so a late writer
 * meets zero rows rather than the database's forward-only trigger (which stays the second enforcer).
 */
public interface RetirementRows {

    /**
     * Takes the lock admission is serialized by: ensures the instance's {@code apply_scope} row and takes it
     * {@code FOR UPDATE}. The instance must exist ({@code scope_has_instance} would refuse otherwise). The first lock of the
     * order {@code apply_scope -> instance_state}, everywhere.
     */
    void lockScope(UUID tenantId, UUID instanceId);

    /** Whether an {@code apply_request} of this instance is {@code REQUESTED}, {@code RUNNING} or {@code APPLY_INDETERMINATE}. */
    boolean requestInFlight(UUID tenantId, UUID instanceId);

    /** Whether a {@code RUNNING} fleet holds a live reservation (a pending or unsettled admitted member) on this instance. */
    boolean fleetReserves(UUID tenantId, UUID instanceId);

    /** Whether an {@code apply_schedule} of this instance is {@code PENDING} (OR-131). */
    boolean scheduleReserves(UUID tenantId, UUID instanceId);

    /** {@code active -> retiring}; returns rows changed. */
    int markRetiring(UUID tenantId, UUID instanceId);

    /** {@code retiring -> retired}; returns rows changed. The database refuses it while a live placement remains. */
    int markRetired(UUID tenantId, UUID instanceId);

    /** {@code retiring -> active}; returns rows changed. The database refuses it unless the record was just closed rolled-back. */
    int markActive(UUID tenantId, UUID instanceId);

    /**
     * Inserts the OPEN whole-instance record. Each live placement's last persisted health is read by the same statement that
     * inserts the row, so the record names exactly the placements that were live and the state they were last known in.
     */
    void insertOpen(UUID tenantId, UUID retirementId, UUID instanceId, UUID requestedBy, Instant requestedAt,
            UUID correlationId, WorkloadDisposition workload);

    /** The instance's open whole-instance record, if any (at most one: {@code instance_retirement_open_once}). */
    Optional<RetirementView> open(UUID tenantId, UUID instanceId);

    /** The instance's most recent whole-instance record closed with {@code outcome}, if any. */
    Optional<RetirementView> latestClosed(UUID tenantId, UUID instanceId, RetirementOutcome outcome);

    /** Closes an open record, once: {@code completed_at = now()} (the transaction's own timestamp) and the outcome. Returns rows changed. */
    int close(UUID tenantId, UUID retirementId, RetirementOutcome outcome);

    /** This tenant's {@code retiring} instances that have an open record, oldest first. */
    List<RetiringInstance> retiring(UUID tenantId);
}
