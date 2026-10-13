package com.solace.sm.dp.schedule;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.DueSchedule;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.ScheduleAcceptedView;
import com.solace.sm.dp.api.apply.ScheduleCommand;
import com.solace.sm.dp.api.apply.ScheduleCommand.Operation;
import com.solace.sm.dp.api.apply.ScheduleFireResult;
import com.solace.sm.dp.api.apply.ScheduleReason;
import com.solace.sm.dp.api.apply.ScheduleRefusedException;
import com.solace.sm.dp.api.apply.ScheduleView;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.schedule.ScheduleRecord.State;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled applies and rollbacks (plan 012 T7; {@code OPS-Q9} Sch-2, {@code OPS-Q10} (c), {@code BE-D52}): a
 * {@code sm_data.apply_schedule} row that waits for its window and is then admitted as an ORDINARY apply.
 *
 * <h2>Nothing is held while a schedule waits</h2>
 * {@link #schedule} validates NOW (the instance is active, the placement live, an apply's generation staged, a rollback's
 * target eligible, the window in the future and both its ends within {@code max-lead}, itself at most 7 days) and records a row. It admits nothing and takes no
 * fence, so a hand fix at 23:00 can still be applied (W5) and the schedule then refuses at fire. This is what Sch-1's
 * waiting {@code apply_request} could not do.
 *
 * <h2>Fire: the gateway decides who may, this class decides what runs</h2>
 * {@code console-gateway}'s {@code ScheduleFirer} enumerates through {@link #tenantsWithDueSchedules} (the ONE
 * cross-organization read, {@code OR-122} (2)), re-requires the submitter's verbs through {@code cp-api} (which this plane
 * may not call, {@code I7}) and then calls {@link #admit}. Under the row's lock - so two firers, or a firer and a cancel,
 * take turns - {@link #admit} calls the ordinary {@link ApplyService}: same admission, fences, watermark, journal, ladder and
 * events, with the SUBMITTER as the apply's actor. A scheduled rollback is resolved and copied forward HERE, at fire
 * (design 6.3, finding F12), through the same {@code rollbackApply}; the row then records the target resolved and the new
 * generation minted (W6).
 *
 * <h2>The window is the bound</h2>
 * Inside {@code [notBefore, notAfter)} a busy fence ({@code SCOPE_BUSY}, {@code STOP_IN_FLIGHT}, a lost admission race)
 * leaves the row PENDING and the next tick tries again; a refusal that will not change (the monotonicity fence, a
 * generation or target that is gone, an instance that is no longer active) closes it REFUSED with a reason from the closed
 * set; at {@code notAfter} it closes MISSED and nothing ran. {@code V025}'s CHECKs hold the same line.
 *
 * <h2>The two commits of a fire, and a crash between them ({@code OR-125} (5))</h2>
 * The engine's admission commits in its own transaction and the row's move to {@code ADMITTED} in the caller's, so a crash
 * between them used to leave a schedule {@code PENDING} for an apply that ran, which the retry's ordinary admission then
 * met as {@code SCOPE_BUSY} or a watermark refusal, closing the row {@code REFUSED} or {@code MISSED}. Now the fire PRE-MINTS
 * the apply's id ({@link ScheduleApplyIds}: a pure function of the schedule's id) and hands it to the engine, which answers an
 * admission under an id it already holds as "already admitted"; and every call that would move a {@code PENDING} row
 * ({@link #admit}, {@link #refuse}, {@link #miss}, {@link #cancel}) first asks the journal whether an apply exists under that
 * id, and if one does it RECONCILES: the row is marked {@code ADMITTED} with that apply, stamped with the time the engine
 * admitted it (clamped into the window, which the apply provably ran inside), and nothing is admitted a second time. A
 * rollback's recorded target is the one the schedule named, else the generation its copy-forward was derived from, read back
 * from the journal; only when the copy-forward minted nothing ({@code OR-33} guard 1) and the schedule named an apply is it
 * re-resolved, and never thrown over ({@link #rollbackTarget}, W5 review W5A-1).
 *
 * <p>{@code I5}: every method names the organization; a schedule, instance or placement of another is
 * {@code NOT_FOUND}, indistinguishable from an absent one.
 */
public final class ApplyScheduleServiceImpl implements ApplySchedulePort {

    private final TransactionTemplate transactions;
    private final ScheduleRows rows;
    private final ScheduleTargets targets;
    private final ApplyService apply;
    private final RollbackTargets rollbackTargets;
    private final ApplyScheduleProperties properties;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ApplyScheduleServiceImpl(TransactionTemplate transactions, ScheduleRows rows, ScheduleTargets targets,
            ApplyService apply, RollbackTargets rollbackTargets, ApplyScheduleProperties properties, Clock clock,
            Supplier<UUID> ids) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.rollbackTargets = Objects.requireNonNull(rollbackTargets, "rollbackTargets");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    // ---- schedule / list / cancel ------------------------------------------------------------------------------

    @Override
    public ScheduleAcceptedView schedule(UUID organizationId, UUID instanceId, ScheduleCommand command) {
        if (!targets.instanceActive(organizationId, instanceId)
                || !targets.placementLive(organizationId, instanceId, command.placementId())) {
            throw new ApplyRefusedException(Reason.NOT_FOUND);
        }
        Instant now = clock.instant();
        if (!command.notBefore().isAfter(now)) {
            throw new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_BEFORE_NOT_IN_THE_FUTURE);
        }
        Instant horizon = now.plus(properties.maxLead());
        if (command.notBefore().isAfter(horizon)) {
            throw new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_BEFORE_TOO_FAR_AHEAD);
        }
        // The window's far end is bounded by the same horizon (OR-122 (2): "at most 7 days ahead"). An unbounded notAfter is
        // a schedule that can fire weeks late after an outage instead of being MISSED, under authority that was last asked
        // long before.
        if (command.notAfter().isAfter(horizon)) {
            throw new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_AFTER_TOO_FAR_AHEAD);
        }
        if (command.operation() == Operation.APPLY) {
            if (!targets.generationStaged(organizationId, instanceId, command.generation())) {
                throw new ApplyRefusedException(Reason.GENERATION_NOT_STAGED);
            }
        } else {
            // Validation only: the target is resolved to see that it is eligible NOW. Nothing is copied forward here.
            rollbackTargets.resolve(new TenantId(organizationId), new InstanceId(instanceId),
                    new PlacementId(command.placementId()), command.rollbackApplyId(), command.rollbackGeneration());
        }
        UUID scheduleId = ids.get();
        UUID confirmedBy = command.outageConfirmedBy() == null ? null : UUID.fromString(command.outageConfirmedBy());
        ScheduleRecord row = ScheduleRecord.pending(organizationId, scheduleId, instanceId, command.placementId(),
                command.operation(), command.generation(), command.rollbackApplyId(), command.rollbackGeneration(),
                command.strategy(), confirmedBy, command.notBefore(), command.notAfter(), command.actor(), now);
        long stateVersion = Objects.requireNonNull(transactions.execute(status -> {
            // OR-131: serialise with beginRetire on the apply_scope lock and re-check, so a schedule cannot be created on an
            // instance an undeclare has just taken (or is taking) out of 'active'.
            // The placement is re-read here too (FF-1): the outside read is advisory, and a retirePlacement that committed
            // between it and this lock must not leave a PENDING schedule naming a retired placement.
            if (!targets.lockInstanceScopeAndCheckActive(organizationId, instanceId)
                    || !targets.placementLive(organizationId, instanceId, command.placementId())) {
                throw new ApplyRefusedException(Reason.NOT_FOUND);
            }
            return rows.insert(row);
        }));
        return new ScheduleAcceptedView(scheduleId, stateVersion);
    }

    @Override
    public List<ScheduleView> list(UUID organizationId, UUID instanceId, int limit) {
        return rows.list(organizationId, instanceId, limit).stream().map(ApplyScheduleServiceImpl::view).toList();
    }

    @Override
    public ScheduleView cancel(UUID organizationId, UUID instanceId, UUID scheduleId, UUID actor) {
        // An empty result means "an earlier fire's apply was found and recorded": that reconciliation is COMMITTED here and
        // the cancel is then refused as not pending, outside the transaction (a refusal thrown inside it would roll the
        // reconciliation back with it).
        Optional<ScheduleView> cancelled = transactions.execute(status -> {
            ScheduleRecord row = rows.lockOfInstance(organizationId, instanceId, scheduleId)
                    .orElseThrow(() -> new ApplyRefusedException(Reason.NOT_FOUND));
            if (row.state() != State.PENDING) {
                throw new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_PENDING);
            }
            if (reconcile(row).isPresent()) {
                return Optional.empty();
            }
            Instant at = clock.instant();
            if (rows.cancel(organizationId, scheduleId, actor, at) != 1) {
                throw new IllegalStateException("a locked PENDING schedule could not be cancelled");
            }
            return Optional.of(view(rows.find(organizationId, instanceId, scheduleId)
                    .orElseThrow(() -> new IllegalStateException("a cancelled schedule is not readable"))));
        });
        return Objects.requireNonNull(cancelled, "transaction").orElseThrow(
                () -> new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_PENDING));
    }

    // ---- the firer's calls --------------------------------------------------------------------------------------

    @Override
    public List<UUID> tenantsWithDueSchedules(Instant now) {
        return rows.dueTenants(now);
    }

    @Override
    public List<DueSchedule> due(UUID organizationId, Instant now) {
        return rows.due(organizationId, now, properties.dueBatch()).stream()
                .map(r -> new DueSchedule(r.scheduleId(), r.instanceId(), r.requestedBy(), r.operation(), r.strategy(),
                        r.notBefore(), r.notAfter()))
                .toList();
    }

    @Override
    public ScheduleFireResult admit(UUID organizationId, UUID scheduleId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Optional<ScheduleRecord> locked = rows.lock(organizationId, scheduleId);
            if (locked.isEmpty() || locked.get().state() != State.PENDING) {
                return ScheduleFireResult.notPending();
            }
            // Before anything is decided: an earlier fire of THIS schedule may already have admitted its apply (OR-125 (5)).
            Optional<ScheduleFireResult> reconciled = reconcile(locked.get());
            if (reconciled.isPresent()) {
                return reconciled.get();
            }
            // Read AFTER the lock: the window is decided, and resolved_at recorded, at the moment of admission - not against a
            // reading the caller took before a control-plane round trip or a wait behind a cancel (W3 review W3B-1).
            Instant now = clock.instant();
            ScheduleRecord s = locked.get();
            if (!now.isBefore(s.notAfter())) {
                return closeMissed(s, now);
            }
            if (now.isBefore(s.notBefore())) {
                return ScheduleFireResult.deferred();
            }
            try {
                return s.operation() == Operation.APPLY ? fireApply(s, now) : fireRollback(s, now);
            } catch (ApplyRefusedException refused) {
                return switch (refused.reason()) {
                    case SCOPE_BUSY, STOP_IN_FLIGHT, CONCURRENT_ADMISSION -> ScheduleFireResult.deferred();
                    case NOT_FOUND -> closeRefused(s, ScheduleReason.TARGET_GONE, now);
                    case GENERATION_NOT_STAGED -> closeRefused(s, ScheduleReason.GENERATION_NOT_STAGED, now);
                    case GENERATION_NOT_ABOVE_WATERMARK -> closeRefused(s, ScheduleReason.GENERATION_SUPERSEDED, now);
                    case ROLLBACK_TARGET_NOT_ELIGIBLE -> closeRefused(s, ScheduleReason.ROLLBACK_TARGET_NOT_ELIGIBLE, now);
                    // not a refusal an apply or a rollback can raise: surface it rather than close a schedule on a guess
                    case UNAPPLIED_DRAFT_PENDING -> throw new IllegalStateException(
                            "an apply or a rollback answered a refusal only a log-level change raises");
                };
            }
        }));
    }

    @Override
    public ScheduleFireResult refuse(UUID organizationId, UUID scheduleId, ScheduleReason reason) {
        if (reason == ScheduleReason.WINDOW_MISSED) {
            throw new IllegalArgumentException("a schedule that missed its window is closed by miss(), as MISSED");
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            Optional<ScheduleRecord> locked = rows.lock(organizationId, scheduleId);
            if (locked.isEmpty() || locked.get().state() != State.PENDING) {
                return ScheduleFireResult.notPending();
            }
            // A revocation found AFTER the apply ran does not rewrite history: the submitter held the verbs when it was admitted.
            Optional<ScheduleFireResult> reconciled = reconcile(locked.get());
            if (reconciled.isPresent()) {
                return reconciled.get();
            }
            return closeRefused(locked.get(), reason, clock.instant());
        }));
    }

    @Override
    public ScheduleFireResult miss(UUID organizationId, UUID scheduleId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Optional<ScheduleRecord> locked = rows.lock(organizationId, scheduleId);
            if (locked.isEmpty() || locked.get().state() != State.PENDING) {
                return ScheduleFireResult.notPending();
            }
            // The firer found the window closed, but the apply may have run inside it before a crash (OR-125 (5)): record that.
            Optional<ScheduleFireResult> reconciled = reconcile(locked.get());
            if (reconciled.isPresent()) {
                return reconciled.get();
            }
            Instant now = clock.instant();
            if (now.isBefore(locked.get().notAfter())) {
                throw new IllegalStateException("a schedule whose window is still open is not missed");
            }
            return closeMissed(locked.get(), now);
        }));
    }

    // ---- fire ---------------------------------------------------------------------------------------------------

    private ScheduleFireResult fireApply(ScheduleRecord s, Instant now) {
        ApplyAcceptedView accepted = apply.submitApply(s.tenantId(), s.instanceId(),
                new SubmitApplyCommand(s.placementId(), s.generation(), s.strategy(), s.outageConfirmation(),
                        s.requestedBy()), ScheduleApplyIds.forSchedule(s.scheduleId()));
        markAdmitted(s, accepted, null, s.generation(), now);
        return ScheduleFireResult.admitted(accepted.applyId());
    }

    private ScheduleFireResult fireRollback(ScheduleRecord s, Instant now) {
        // Resolved NOW, not at submit: eligibility is re-decided at the moment the rollback runs (OR-42), and the row records
        // what was resolved. The resolved generation is what is copied forward; by-apply and by-generation targets alike.
        long target = rollbackTargets.resolve(new TenantId(s.tenantId()), new InstanceId(s.instanceId()),
                new PlacementId(s.placementId()), s.rollbackApplyId(), s.rollbackGeneration());
        ApplyAcceptedView accepted = apply.rollbackApply(s.tenantId(), s.instanceId(),
                new RollbackCommand(s.placementId(), null, target, s.strategy(), s.outageConfirmation(), s.requestedBy()),
                ScheduleApplyIds.forSchedule(s.scheduleId()));
        long minted = apply.observeApply(s.tenantId(), s.instanceId(), new ObserveQuery(accepted.applyId(), null, 1))
                .stream().findFirst().map(ApplyObservationView::generation)
                .orElseThrow(() -> new IllegalStateException("an admitted rollback is not in the journal"));
        markAdmitted(s, accepted, target, minted, now);
        return ScheduleFireResult.admitted(accepted.applyId());
    }

    /**
     * If an apply already exists under this schedule's pre-minted id, an earlier fire got as far as the engine's commit and
     * died before it could record that: marks the schedule {@code ADMITTED} with that apply and answers so. Empty when the
     * journal holds nothing under the id (the ordinary case). Must be called with the row locked and PENDING.
     *
     * <p>A request under the id that is NOT this schedule's (another placement, another generation) cannot be reached by
     * construction (the id is derived from the schedule's own). Were it reached, throwing would wedge the row forever: every
     * later {@code admit}, {@code miss} and {@code cancel} reconciles first. It is therefore closed {@code REFUSED} (Fable F-8)
     * with the closest reason in the closed vocabulary (V025's CHECK is frozen, so a dedicated {@code reconcile.mismatch}
     * would need a new {@code sm_data} migration): {@code schedule.target.gone} for another placement,
     * {@code schedule.generation.superseded} for another generation. The pre-minted apply is NEVER recorded as this
     * schedule's.
     */
    private Optional<ScheduleFireResult> reconcile(ScheduleRecord s) {
        UUID applyId = ScheduleApplyIds.forSchedule(s.scheduleId());
        Optional<ScheduleTargets.AdmittedRequest> found = targets.admittedUnder(s.tenantId(), s.instanceId(), applyId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ScheduleTargets.AdmittedRequest request = found.get();
        if (!request.placementId().equals(s.placementId())) {
            return Optional.of(closeRefused(s, ScheduleReason.TARGET_GONE, clock.instant()));
        }
        Long target = null;
        if (s.operation() == Operation.APPLY) {
            if (request.generation() != s.generation()) {
                return Optional.of(closeRefused(s, ScheduleReason.GENERATION_SUPERSEDED, clock.instant()));
            }
        } else {
            target = rollbackTarget(s, request);
        }
        // Stamped when the engine admitted it: it provably ran inside the window (the firer decided that before it asked),
        // and V025 holds an ADMITTED row's stamp inside [notBefore, notAfter).
        Instant at = request.requestedAt();
        if (at.isBefore(s.notBefore())) {
            at = s.notBefore();
        } else if (!at.isBefore(s.notAfter())) {
            at = s.notAfter().minus(java.time.Duration.ofNanos(1_000));
        }
        if (rows.admitted(s.tenantId(), s.scheduleId(), applyId, target, request.generation(), at) != 1) {
            throw new IllegalStateException("a locked PENDING schedule could not be marked admitted");
        }
        return Optional.of(ScheduleFireResult.admitted(applyId));
    }

    /**
     * The target a reconciled rollback records (W5 review W5A-1). The copy-forward does NOT always mint a copy: when the
     * latest generation already carries the target's content (OR-33 guard 1) it returns THAT generation, which may be a plain
     * edit ({@code derived_from_generation} NULL) or a copy of some other generation. So the rule never throws for a
     * rollback; in order:
     * <ol>
     *   <li>the generation the schedule named, when it named one;</li>
     *   <li>the generation the admitted copy was derived from;</li>
     *   <li>the target re-resolved by apply id (stable: eligibility only grows, and pruning never deletes a generation that
     *       has an {@code apply_request}); should even that be refused, the generation the request applied, whose content is
     *       the target's by construction.</li>
     * </ol>
     * Throwing here would wedge the row: every later {@code admit}, {@code miss} and {@code cancel} reconciles first.
     */
    private long rollbackTarget(ScheduleRecord s, ScheduleTargets.AdmittedRequest request) {
        if (s.rollbackGeneration() != null) {
            return s.rollbackGeneration();
        }
        if (request.derivedFromGeneration() != null) {
            return request.derivedFromGeneration();
        }
        try {
            return rollbackTargets.resolve(new TenantId(s.tenantId()), new InstanceId(s.instanceId()),
                    new PlacementId(s.placementId()), s.rollbackApplyId(), null);
        } catch (ApplyRefusedException refused) {
            return request.generation();
        }
    }

    private void markAdmitted(ScheduleRecord s, ApplyAcceptedView accepted, Long target, long admittedGeneration, Instant now) {
        if (rows.admitted(s.tenantId(), s.scheduleId(), accepted.applyId(), target, admittedGeneration, now) != 1) {
            throw new IllegalStateException("a locked PENDING schedule could not be marked admitted");
        }
    }

    private ScheduleFireResult closeMissed(ScheduleRecord s, Instant now) {
        if (rows.close(s.tenantId(), s.scheduleId(), State.MISSED, ScheduleReason.WINDOW_MISSED, now) != 1) {
            throw new IllegalStateException("a locked PENDING schedule could not be closed MISSED");
        }
        return ScheduleFireResult.missed();
    }

    private ScheduleFireResult closeRefused(ScheduleRecord s, ScheduleReason reason, Instant now) {
        if (rows.close(s.tenantId(), s.scheduleId(), State.REFUSED, reason, now) != 1) {
            throw new IllegalStateException("a locked PENDING schedule could not be closed REFUSED");
        }
        return ScheduleFireResult.refused(reason);
    }

    private static ScheduleView view(ScheduleRecord r) {
        return new ScheduleView(r.scheduleId(), r.placementId(), r.operation().wire(), r.generation(),
                r.rollbackApplyId(), r.rollbackGeneration(), r.strategy(), r.notBefore(), r.notAfter(), r.requestedAt(),
                r.state().name(), r.applyId(), r.targetGeneration(), r.admittedGeneration(),
                r.reason() == null ? null : r.reason().wire(), r.resolvedAt(), r.stateVersion());
    }
}
