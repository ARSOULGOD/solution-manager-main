package com.solace.sm.dp.apply.service;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.ApplyStepView;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RestoreRuntimeCommand;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.SetLogLevelCommand;
import com.solace.sm.dp.api.apply.StopCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.GenerationBinding;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.inventory.PlacementRows;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code submitApply}, {@code stopInstance} and {@code observeApply} (plan 010 T6.3).
 *
 * <p><b>Submission is admission, nothing more.</b> The one place this class reaches the engine is
 * {@link ApplyExecutor#admit}, which commits a {@code REQUESTED} row and returns - it never calls an adapter. The
 * durable worker (nudged afterwards, for latency only) claims the row. There is no other mutating path from here, and
 * no {@code RuntimeAdapter} type is imported.
 *
 * <p><b>Observation is the journal.</b> {@link ApplyObservationRows} reads {@code apply_request},
 * {@code apply_step_intent} and {@code apply_attempt}; there is nothing behind it that could reach a host, so it
 * answers when the host is unreachable.
 *
 * <p>{@code I5}: the organization is the caller's argument and part of every key; a placement or request id of
 * another instance or tenant is {@code NOT_FOUND}, indistinguishable from an absent one.
 */
public final class ApplyServiceImpl implements ApplyService {

    private static final Logger LOG = LoggerFactory.getLogger(ApplyServiceImpl.class);

    /**
     * The strategies the API serves. Plan 012 T2 ({@code OR-121}) widened it to the two ungated ones, which the ENGINE
     * already ran; {@code scheduled} answers {@code 501} until plan 012 T7 builds it. The verbs and the confirmation are
     * the gateway's ({@code instance.restart.overrideSafety}; the confirming principal arrives in the command).
     */
    private static final Set<Strategy> SERVED = EnumSet.of(Strategy.MANUAL, Strategy.ROLLING_HEALTH_GATED,
            Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL);

    private final Supplier<ApplyExecutor> executor;
    private final PlacementRows placements;
    private final ApplyRequestRepository requests;
    private final ApplyObservationRows observations;
    private final ConfigRecordService configRecords;
    private final RollbackTargets rollbackTargets;
    private final Runnable afterAdmission;

    /**
     * @param executor       absent (null) when no executor is configured: a submission then fails loudly rather than
     *                       leaving an unclaimable row
     * @param configRecords  the config of record: a rollback copies a generation forward through it (OR-42)
     * @param rollbackTargets which generations a rollback may copy forward (OR-42)
     * @param afterAdmission latency only (the worker's nudge); a failure here never fails the submission
     */
    public ApplyServiceImpl(Supplier<ApplyExecutor> executor, PlacementRows placements,
            ApplyRequestRepository requests, ApplyObservationRows observations, ConfigRecordService configRecords,
            RollbackTargets rollbackTargets, Runnable afterAdmission) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.configRecords = Objects.requireNonNull(configRecords, "configRecords");
        this.rollbackTargets = Objects.requireNonNull(rollbackTargets, "rollbackTargets");
        this.afterAdmission = Objects.requireNonNull(afterAdmission, "afterAdmission");
    }

    @Override
    public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command) {
        return submit(organizationId, instanceId, command, null);
    }

    @Override
    public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command,
            UUID reservedApplyId) {
        return submit(organizationId, instanceId, command, new ApplyId(Objects.requireNonNull(reservedApplyId,
                "reservedApplyId")));
    }

    private ApplyAcceptedView submit(UUID organizationId, UUID instanceId, SubmitApplyCommand command,
            ApplyId reserved) {
        Strategy strategy = Strategy.fromSql(command.strategy());
        if (!SERVED.contains(strategy)) {
            throw new NotImplementedStrategyException(strategy.sql());
        }
        requireLivePlacement(organizationId, instanceId, command.placementId());
        ApplyCommand apply = new ApplyCommand(
                scope(organizationId, instanceId, new PlacementId(command.placementId())), Operation.APPLY,
                command.generation(), strategy, command.outageConfirmedBy(), command.actor().toString());
        return accepted(organizationId, executor().admit(apply, GenerationBinding.MUST_BE_STAGED, reserved));
    }

    @Override
    public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command) {
        return rollback(organizationId, instanceId, command, null);
    }

    @Override
    public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command,
            UUID reservedApplyId) {
        return rollback(organizationId, instanceId, command, new ApplyId(Objects.requireNonNull(reservedApplyId,
                "reservedApplyId")));
    }

    private ApplyAcceptedView rollback(UUID organizationId, UUID instanceId, RollbackCommand command, ApplyId reserved) {
        Strategy strategy = Strategy.fromSql(command.strategy());
        if (!SERVED.contains(strategy)) {
            throw new NotImplementedStrategyException(strategy.sql());
        }
        requireLivePlacement(organizationId, instanceId, command.placementId());
        PlacementId placement = new PlacementId(command.placementId());
        // OR-125 (5): a request that already exists under the pre-minted id IS this rollback, admitted by an earlier attempt
        // that died before its caller recorded it. Answered BEFORE the scope pre-check (which would refuse it as SCOPE_BUSY
        // while it is in flight) and before anything is resolved or copied forward a second time.
        if (reserved != null) {
            Optional<ApplyRequestRow> existing = requests.find(new TenantId(organizationId), reserved)
                    .filter(row -> row.scope().equals(scope(organizationId, instanceId, placement)));
            if (existing.isPresent()) {
                return new ApplyAcceptedView(reserved.value(), existing.get().stateVersion());
            }
        }
        // Fail loudly BEFORE minting anything: with no executor the new generation could never be applied.
        ApplyExecutor engine = executor();
        requireScopeFree(organizationId, instanceId, placement);
        long target = rollbackTargets.resolve(new TenantId(organizationId), new InstanceId(instanceId), placement,
                command.applyId(), command.generation());
        // Transaction 1 (its own, REQUIRES_NEW, holding instance_state only): a NEW, higher generation with the
        // target's content. The target's own number is never re-admitted - the only-goes-up fence is untouched.
        long copied = configRecords.copyForward(organizationId, instanceId, target, GenerationOrigin.ROLLBACK,
                command.actor()).generation().generation();
        // Transaction 2 (the engine's, REQUIRES_NEW): a normal apply of the copy, apply_scope then instance_state.
        // A crash (or a race lost between the scope pre-check and admission) between the two leaves a rollback
        // generation with no apply; the operator's retry copies the same content, which OR-33 guard 1 returns as
        // unchanged, so it never mints a second one. An ordinary refusal never gets here: requireScopeFree answered it.
        ApplyCommand apply = new ApplyCommand(scope(organizationId, instanceId, placement), Operation.APPLY, copied,
                strategy, command.outageConfirmedBy(), command.actor().toString());
        return accepted(organizationId, engine.admit(apply, GenerationBinding.MUST_BE_STAGED, reserved));
    }

    @Override
    public ApplyAcceptedView restoreRuntime(UUID organizationId, UUID instanceId, RestoreRuntimeCommand command) {
        Strategy strategy = Strategy.fromSql(command.strategy());
        if (!SERVED.contains(strategy)) {
            throw new NotImplementedStrategyException(strategy.sql());
        }
        requireLivePlacement(organizationId, instanceId, command.placementId());
        PlacementId placement = new PlacementId(command.placementId());
        // Fail loudly BEFORE minting anything: with no executor the new generation could never be applied.
        ApplyExecutor engine = executor();
        requireScopeFree(organizationId, instanceId, placement);
        // Transaction 1 (its own, REQUIRES_NEW, holding instance_state only): a NEW generation with the CURRENT desired
        // content, resolved under the row lock so a stage landing meanwhile cannot make the copy stale. Not the
        // desired generation's own number: the placement's watermark is at or above it, so admission would refuse it.
        long copied = configRecords.copyLatestForward(organizationId, instanceId, GenerationOrigin.RESTORE_RUNTIME,
                command.actor())
                .orElseThrow(() -> new ApplyRefusedException(ApplyRefusedException.Reason.GENERATION_NOT_STAGED))
                .generation().generation();
        // Transaction 2 (the engine's, REQUIRES_NEW): a normal apply of the copy, apply_scope then instance_state.
        ApplyCommand apply = new ApplyCommand(scope(organizationId, instanceId, placement), Operation.APPLY, copied,
                strategy, command.outageConfirmedBy(), command.actor().toString());
        return accepted(organizationId, engine.admit(apply, GenerationBinding.MUST_BE_STAGED));
    }

    @Override
    public ApplyAcceptedView setLogLevel(UUID organizationId, UUID instanceId, SetLogLevelCommand command) {
        // A level change restarts nothing: the file is written, and the level takes effect at the next restart (P-8).
        Strategy strategy = Strategy.MANUAL;
        requireLivePlacement(organizationId, instanceId, command.placementId());
        PlacementId placement = new PlacementId(command.placementId());
        // Fail loudly BEFORE minting anything: with no executor the new generation could never be applied.
        ApplyExecutor engine = executor();
        requireScopeFree(organizationId, instanceId, placement);
        // Transaction 1 (its own, REQUIRES_NEW, holding instance_state only): a NEW generation, origin edit, with the
        // latest content and the one SM-owned level entry. The entry is built by the overlay's one function from the
        // package-declared logger, never from a request field (OR-103).
        long copied = configRecords.copyLatestWithLogLevel(organizationId, instanceId, command.placementId(), command.loggerPrefix(),
                command.level(), command.actor())
                .orElseThrow(() -> new ApplyRefusedException(ApplyRefusedException.Reason.GENERATION_NOT_STAGED))
                .generation().generation();
        // Transaction 2 (the engine's, REQUIRES_NEW): a normal apply of the copy. Content equal to the latest's (the
        // level is already configured) minted nothing (OR-33 guard 1) and this admits that generation, which the
        // watermark refuses when it was already applied - the same answer a re-submit gets.
        ApplyCommand apply = new ApplyCommand(scope(organizationId, instanceId, placement), Operation.APPLY, copied,
                strategy, null, command.actor().toString());
        return accepted(organizationId, engine.admit(apply, GenerationBinding.MUST_BE_STAGED));
    }

    @Override
    public ApplyAcceptedView stopInstance(UUID organizationId, UUID instanceId, StopCommand command) {
        PlacementId placement = PlacementId.INSTANCE_WIDE;
        if (command.placementId() != null) {
            requireLivePlacement(organizationId, instanceId, command.placementId());
            placement = new PlacementId(command.placementId());
        }
        ApplyCommand stop = new ApplyCommand(scope(organizationId, instanceId, placement), Operation.STOP, null, null,
                null, command.actor().toString());
        return accepted(organizationId, executor().admit(stop));
    }

    @Override
    public List<ApplyObservationView> observeApply(UUID organizationId, UUID instanceId, ObserveQuery query) {
        TenantId tenant = new TenantId(organizationId);
        InstanceId instance = new InstanceId(instanceId);
        ApplyId applyId = query.applyId() == null ? null : new ApplyId(query.applyId());
        List<ApplyObservationRows.ObservedRequest> observed = observations.observe(tenant, instance, applyId,
                query.sinceStateVersion(), query.limit());
        if (applyId != null && observed.isEmpty() && requests.find(tenant, applyId)
                .filter(row -> row.scope().instance().equals(instance)).isEmpty()) {
            throw new ApplyRefusedException(ApplyRefusedException.Reason.NOT_FOUND);
        }
        return observed.stream().map(this::view).toList();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private ApplyExecutor executor() {
        ApplyExecutor resolved = executor.get();
        if (resolved == null) {
            throw new IllegalStateException("no ApplyExecutor is configured: an admitted request could never be claimed");
        }
        return resolved;
    }

    private static ScopeKey scope(UUID organizationId, UUID instanceId, PlacementId placement) {
        return new ScopeKey(new TenantId(organizationId), new InstanceId(instanceId), placement);
    }

    /**
     * A read-only pre-check, BEFORE a copy-forward mints anything (S811-2): a rollback or restore the engine would
     * refuse for an ordinary reason (an apply or restart holding the scope, a stop in flight) must not first replace the
     * head of the instance's desired configuration with old content. Admission stays the authority - this narrows the
     * window to a true race between the check and the admission, which the plan accepts, as it does a crash between the
     * two transactions (a retry then reuses the unapplied copy, OR-33 guard 1).
     */
    private void requireScopeFree(UUID organizationId, UUID instanceId, PlacementId placement) {
        TenantId tenant = new TenantId(organizationId);
        InstanceId instance = new InstanceId(instanceId);
        ScopeKey scope = new ScopeKey(tenant, instance, placement);
        if (requests.blockingRequests(tenant, instance).stream().anyMatch(r -> r.scope().overlaps(scope))) {
            throw new ApplyRefusedException(ApplyRefusedException.Reason.SCOPE_BUSY);
        }
        if (requests.stopInFlight(tenant, instance, placement)) {
            throw new ApplyRefusedException(ApplyRefusedException.Reason.STOP_IN_FLIGHT);
        }
    }

    /** The placement must be a live one of THIS instance of THIS tenant; anything else is an indistinguishable 404. */
    private void requireLivePlacement(UUID organizationId, UUID instanceId, UUID placementId) {
        if (placements.findLive(organizationId, instanceId, placementId).isEmpty()) {
            throw new ApplyRefusedException(ApplyRefusedException.Reason.NOT_FOUND);
        }
    }

    private ApplyAcceptedView accepted(UUID organizationId, AdmissionResult result) {
        if (result instanceof AdmissionResult.Admitted admitted) {
            ApplyRequestRow row = requests.find(new TenantId(organizationId), admitted.id())
                    .orElseThrow(() -> new IllegalStateException("an admitted request is not in the journal"));
            try {
                afterAdmission.run();
            } catch (RuntimeException e) {
                LOG.warn("the worker nudge failed; the poll will claim the request", e);
            }
            return new ApplyAcceptedView(admitted.id().value(), row.stateVersion());
        }
        if (result instanceof AdmissionResult.AlreadyAdmitted existing) {
            // A request already exists under the id the caller pre-minted (OR-125 (5)): nothing was written, and the worker
            // was nudged when it was admitted. Answered with the request as it stands, not as a new admission.
            ApplyRequestRow row = requests.find(new TenantId(organizationId), existing.id())
                    .orElseThrow(() -> new IllegalStateException("an already-admitted request is not in the journal"));
            return new ApplyAcceptedView(existing.id().value(), row.stateVersion());
        }
        if (result instanceof AdmissionResult.StrategyUnsupported unsupported) {
            throw new NotImplementedStrategyException(unsupported.s().sql());
        }
        if (result instanceof AdmissionResult.NotAdmitted refused) {
            throw new ApplyRefusedException(switch (refused.reason()) {
                case SCOPE_BUSY -> ApplyRefusedException.Reason.SCOPE_BUSY;
                case STOP_IN_FLIGHT -> ApplyRefusedException.Reason.STOP_IN_FLIGHT;
                case GENERATION_NOT_ABOVE_WATERMARK -> ApplyRefusedException.Reason.GENERATION_NOT_ABOVE_WATERMARK;
                case FENCE_RACE_LOST -> ApplyRefusedException.Reason.CONCURRENT_ADMISSION;
                case INSTANCE_NOT_DECLARED -> ApplyRefusedException.Reason.NOT_FOUND;
                case GENERATION_NOT_STAGED -> ApplyRefusedException.Reason.GENERATION_NOT_STAGED;
            });
        }
        // OperationUnsupported (restart) and ScopeUnsupported (instance-wide apply) are unreachable from here: this
        // service never builds a restart, and an apply always names a placement.
        throw new IllegalStateException("admission answered something this service cannot have asked for");
    }

    private ApplyObservationView view(ApplyObservationRows.ObservedRequest observed) {
        ApplyRequestRow row = observed.row();
        UUID placement = row.scope().placement().isInstanceWide() ? null : row.scope().placement().value();
        String strength = gatedStrength(row, placement);
        List<ApplyStepView> steps = observed.steps().stream()
                .map(step -> step(step, step.placementId() != null ? step.placementId() : placement, strength))
                .toList();
        // Present only while the request is still indeterminate: once an operator resolves it, the give-up is history.
        String reconcileReason = row.state() == com.solace.sm.dp.apply.model.RequestState.APPLY_INDETERMINATE
                ? observed.reconcileClass() : null;
        return new ApplyObservationView(row.applyId().value(), row.operation().sql(), placement, row.generation(),
                row.requestedAt(), row.state().name(), row.state().isTerminal(), row.stateVersion(), steps,
                reconcileReason);
    }

    /**
     * The strength a passed gate of this request could have proved (T6.9 point 4): the placement's declared probe
     * strength, and only when the request is a {@code rolling-health-gated} apply of a placement that declares a probe
     * SM executes. Read from the immutable placement row (retired or not). By the executor's no-fallback rule that probe
     * was then the gate's one signal, so the declaration is the proved strength. {@code null} otherwise - a placement
     * with no executable probe has none, and nothing is ever inferred or upgraded.
     */
    private String gatedStrength(ApplyRequestRow row, UUID placement) {
        if (row.operation() != com.solace.sm.dp.apply.model.Operation.APPLY
                || row.strategy() != Strategy.ROLLING_HEALTH_GATED || placement == null) {
            return null;
        }
        return placements.find(row.scope().tenant().value(), row.scope().instance().value(), placement)
                .flatMap(p -> p.healthStrength()).map(s -> s.wire()).orElse(null);
    }

    private static ApplyStepView step(ApplyObservationRows.ObservedStep step, UUID placement, String gatedStrength) {
        String outcome;
        if (!step.attempted()) {
            outcome = "pending";
        } else if (step.outcome() == null) {
            outcome = "in-progress";
        } else {
            outcome = switch (step.outcome()) {
                case SUCCEEDED -> "succeeded";
                case FAILED -> "failed";
                case TIMED_OUT -> "timed-out";
            };
        }
        boolean failed = step.outcome() == AttemptOutcome.FAILED || step.outcome() == AttemptOutcome.TIMED_OUT;
        // Under rolling-health-gated the LIFECYCLE attempt closes SUCCEEDED only once its replica's gate has passed
        // (ApplyExecutor.restartAndGate), so a succeeded lifecycle step IS a passed gate.
        String gateProved = step.step() == com.solace.sm.dp.apply.model.Step.LIFECYCLE
                && step.outcome() == AttemptOutcome.SUCCEEDED ? gatedStrength : null;
        var changed = step.changed();
        return new ApplyStepView(step.step().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), placement,
                outcome, failed ? step.errorClass() : null, gateProved,
                changed == null ? null : changed.paths(), changed == null ? null : changed.pathsTruncated(),
                changed == null ? null : changed.otherContentChanged());
    }
}
