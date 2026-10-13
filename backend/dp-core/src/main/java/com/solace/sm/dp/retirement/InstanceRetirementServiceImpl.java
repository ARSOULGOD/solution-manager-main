package com.solace.sm.dp.retirement;

import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.RetirementOutcome;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.RetirementRefusedException.Reason;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.dp.apply.events.DeclarationsChangeSink;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Undeclare's data-plane steps (plan 012 T3; {@code ops-501.md} sections 4.3 and 4.4), each ONE transaction that takes the
 * instance's {@code apply_scope} row {@code FOR UPDATE} before it reads or writes anything else, the same lock admission
 * takes. That is the whole race story: an undeclare and an admission of one instance serialize on it, admission re-reads
 * {@code lifecycle = 'active'} under it (so it is refused once this commits {@code retiring}), and this checks what is in
 * flight under it (so it is refused once an admission committed). Exactly one wins.
 *
 * <p>Lock order, everywhere: {@code apply_scope}, then {@code instance_state} (the lifecycle UPDATE and the retirement
 * row's stamp trigger). The lifecycle is read once to decide whether the instance is one an undeclare applies to
 * ({@code NOT_FOUND} otherwise, without taking a lock or creating a scope row) and AGAIN under the lock, which is the read
 * that counts.
 *
 * <p>Nothing here reads a target or a credential, and nothing touches a host (U-C): the placements are retired as rows.
 * {@code InventoryService} is not involved: this is a separate class so the inventory's declare path is unchanged.
 */
public final class InstanceRetirementServiceImpl implements InstanceRetirementService {

    private final TransactionTemplate tx;
    private final InstanceRows instances;
    private final PlacementRows placements;
    private final RetirementRows retirements;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final DeclarationsChangeSink declarations;

    public InstanceRetirementServiceImpl(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            RetirementRows retirements, Clock clock) {
        this(tx, instances, placements, retirements, clock, UUID::randomUUID);
    }

    public InstanceRetirementServiceImpl(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            RetirementRows retirements, Clock clock, Supplier<UUID> ids) {
        this(tx, instances, placements, retirements, clock, ids, DeclarationsChangeSink.NONE);
    }

    /** As above; {@code declarations} is told, inside the transaction, when a transition changes what an agent is declared (plan 015 W4 lane D). */
    public InstanceRetirementServiceImpl(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            RetirementRows retirements, Clock clock, Supplier<UUID> ids, DeclarationsChangeSink declarations) {
        this.declarations = Objects.requireNonNull(declarations, "declarations");
        this.tx = Objects.requireNonNull(tx, "tx");
        this.instances = Objects.requireNonNull(instances, "instances");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public RetirementBegun beginRetire(UUID organizationId, UUID instanceId, UUID requestedBy, UUID correlationId,
            WorkloadDisposition workload) {
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(workload, "workload");
        return tx.execute(status -> {
            InstanceRecord seen = instances.find(organizationId, instanceId)
                    .filter(r -> r.lifecycle() == InstanceLifecycle.ACTIVE || r.lifecycle() == InstanceLifecycle.RETIRING)
                    .orElseThrow(() -> new RetirementRefusedException(Reason.NOT_FOUND));
            retirements.lockScope(organizationId, seen.instanceId());
            InstanceRecord current = instances.find(organizationId, instanceId)
                    .filter(r -> r.lifecycle() == InstanceLifecycle.ACTIVE || r.lifecycle() == InstanceLifecycle.RETIRING)
                    .orElseThrow(() -> new RetirementRefusedException(Reason.NOT_FOUND));

            if (current.lifecycle() == InstanceLifecycle.RETIRING) {
                // an authorized retry of an undeclare that stopped between its steps: its own open record, unchanged
                RetirementView open = retirements.open(organizationId, instanceId).orElseThrow(() ->
                        new IllegalStateException("a retiring instance has no open retirement record"));
                return new RetirementBegun(open.withStateVersion(current.stateVersion()), true);
            }

            if (retirements.requestInFlight(organizationId, instanceId)) {
                throw new RetirementRefusedException(Reason.REQUEST_IN_FLIGHT);
            }
            if (retirements.fleetReserves(organizationId, instanceId)) {
                throw new RetirementRefusedException(Reason.FLEET_IN_FLIGHT);
            }
            // OR-131 (W5 review W5B-5): a PENDING schedule would fire into a retiring instance and close REFUSED for good. Read
            // under the apply_scope lock taken above; schedule() takes the same lock before it inserts, so a schedule created
            // concurrently either commits first (and is seen here) or waits and finds the instance no longer active.
            if (retirements.scheduleReserves(organizationId, instanceId)) {
                throw new RetirementRefusedException(Reason.SCHEDULE_PENDING);
            }
            if (retirements.markRetiring(organizationId, instanceId) != 1) {
                throw new IllegalStateException("the instance left 'active' while it was locked for undeclare");
            }
            UUID retirementId = ids.get();
            retirements.insertOpen(organizationId, retirementId, instanceId, requestedBy, clock.instant(), correlationId,
                    workload);
            declarations.changed(new TenantId(organizationId), new InstanceId(instanceId));      // retiring: no longer declared
            RetirementView open = retirements.open(organizationId, instanceId).orElseThrow(() ->
                    new IllegalStateException("the retirement record just written is not readable"));
            return new RetirementBegun(open.withStateVersion(stateVersionOf(organizationId, instanceId)), false);
        });
    }

    @Override
    public RetirementView completeRetire(UUID organizationId, UUID instanceId) {
        return tx.execute(status -> {
            InstanceRecord seen = known(organizationId, instanceId).orElseThrow(() ->
                    new RetirementRefusedException(Reason.NOT_FOUND));
            retirements.lockScope(organizationId, seen.instanceId());
            InstanceRecord current = known(organizationId, instanceId).orElseThrow(() ->
                    new RetirementRefusedException(Reason.NOT_FOUND));

            if (current.lifecycle() == InstanceLifecycle.RETIRED) {
                // finished already (by the retry or by the sweep): the closed record, nothing written
                return retirements.latestClosed(organizationId, instanceId, RetirementOutcome.RETIRED)
                        .orElseThrow(() -> new IllegalStateException("a retired instance has no closed retirement record"))
                        .withStateVersion(current.stateVersion());
            }
            if (current.lifecycle() != InstanceLifecycle.RETIRING) {
                throw new RetirementRefusedException(Reason.NOT_RETIRING);
            }
            RetirementView open = retirements.open(organizationId, instanceId).orElseThrow(() ->
                    new IllegalStateException("a retiring instance has no open retirement record"));
            // ORDER MATTERS (sm_data V031): the placements are retired while the instance is still 'retiring' (markRetired comes after);
            // the trigger on placement.retired_at reads that lifecycle to tombstone an agent instance UNDECLARED.
            placements.retireAll(organizationId, instanceId, clock.instant());
            if (retirements.close(organizationId, open.retirementId(), RetirementOutcome.RETIRED) != 1) {
                throw new IllegalStateException("the retirement record was closed while it was locked");
            }
            if (retirements.markRetired(organizationId, instanceId) != 1) {
                throw new IllegalStateException("the instance left 'retiring' while it was locked");
            }
            declarations.changed(new TenantId(organizationId), new InstanceId(instanceId));      // its placements are retired
            RetirementView closed = retirements.latestClosed(organizationId, instanceId, RetirementOutcome.RETIRED)
                    .orElseThrow(() -> new IllegalStateException("the retirement record just closed is not readable"));
            return closed.withStateVersion(stateVersionOf(organizationId, instanceId));
        });
    }

    @Override
    public boolean rollBackRetire(UUID organizationId, UUID instanceId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Optional<InstanceRecord> seen = known(organizationId, instanceId);
            if (seen.isEmpty() || seen.get().lifecycle() != InstanceLifecycle.RETIRING) {
                return false;
            }
            retirements.lockScope(organizationId, instanceId);
            Optional<InstanceRecord> current = known(organizationId, instanceId);
            if (current.isEmpty() || current.get().lifecycle() != InstanceLifecycle.RETIRING) {
                return false;                        // finished or rolled back by someone else while this waited
            }
            RetirementView open = retirements.open(organizationId, instanceId).orElseThrow(() ->
                    new IllegalStateException("a retiring instance has no open retirement record"));
            if (retirements.close(organizationId, open.retirementId(), RetirementOutcome.ROLLED_BACK) != 1) {
                throw new IllegalStateException("the retirement record was closed while it was locked");
            }
            if (retirements.markActive(organizationId, instanceId) != 1) {
                throw new IllegalStateException("the instance left 'retiring' while it was locked");
            }
            declarations.changed(new TenantId(organizationId), new InstanceId(instanceId));      // active again: declared again
            return true;
        }));
    }

    @Override
    public List<RetiringInstance> retiring(UUID organizationId) {
        return tx.execute(status -> retirements.retiring(organizationId));
    }

    /** The instance, when it is one a retirement can concern: any lifecycle but pending and abandoned. */
    private Optional<InstanceRecord> known(UUID organizationId, UUID instanceId) {
        return instances.find(organizationId, instanceId).filter(r -> r.lifecycle() == InstanceLifecycle.ACTIVE
                || r.lifecycle() == InstanceLifecycle.RETIRING || r.lifecycle() == InstanceLifecycle.RETIRED);
    }

    private long stateVersionOf(UUID organizationId, UUID instanceId) {
        return instances.find(organizationId, instanceId).orElseThrow(() ->
                new IllegalStateException("the instance vanished inside its own transaction")).stateVersion();
    }
}
