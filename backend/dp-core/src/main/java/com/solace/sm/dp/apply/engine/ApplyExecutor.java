package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.events.RolloutStep;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ChangedContent;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreImageChange;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.WriteBaselinePolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The apply step loop — {@code BE-D12} §2.2's safety properties, driven over plan 004's persistence
 * and one {@link RuntimeAdapter}.
 *
 * <p><b>Write before act</b> (rule 0): the intent row and the attempt row for the try about to run
 * are committed before the adapter is invoked, so an intent with no attempt row provably issued
 * nothing. Every service this class calls runs {@code REQUIRES_NEW} already, so "committed" is a
 * fact about the call returning rather than a hope about an enclosing transaction.
 *
 * <p><b>One placement per apply</b> ({@code BE-D45}): an instance-wide APPLY is refused before admission, by policy
 * (a rollout across placements is a fleet of placement-scoped applies). Until {@code V021} this was a limit of the
 * journal ({@code BE-D23}); {@code apply_step_intent} is placement-aware now ({@code BE-D44}), and an instance-wide STOP
 * journals each replica under the placement it addressed.
 *
 * <p><b>Under {@code manual} the core never calls {@code startOrUpdate}.</b> It stages and lands
 * {@code STAGED}, which {@code BE-D16} makes exclusively the manual strategy's terminal state.
 *
 * <p><b>Stage everywhere, then act</b> ({@code SM-D15}): every {@code MATERIALIZE} intent predates
 * every {@code LIFECYCLE} intent, because a rolling restart that wrote replica 2's config only after
 * replica 1 was already serving the new generation would be a rollout of two different generations
 * wearing one apply id.
 */
public final class ApplyExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ApplyExecutor.class);

    private final AdmissionService admission;
    private final StepIntentService intents;
    private final AttemptService attempts;
    private final OutcomeService outcomes;
    private final RuntimeAdapter adapter;
    private final RenderedConfigSource configs;
    private final HealthGate gate;
    private final ResumePlanner planner;
    private final ApplyEngineProperties props;
    private final ApplyEventEmitter events;
    private final LivePlacements livePlacements;

    /**
     * The strategies this build executes. {@code SCHEDULED} is absent for the whole of plan 005:
     * it needs a scheduler, and there is no application class to host one.
     */
    private static final Set<Strategy> SUPPORTED = EnumSet.of(Strategy.MANUAL,
            Strategy.ROLLING_HEALTH_GATED, Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL);

    /** An executor that publishes no rollout notices: the journal-only suites. Production uses the emitter form. */
    public ApplyExecutor(AdmissionService admission, StepIntentService intents,
            AttemptService attempts, OutcomeService outcomes, RuntimeAdapter adapter,
            RenderedConfigSource configs, HealthGate gate, ResumePlanner planner,
            ApplyEngineProperties props) {
        this(admission, intents, attempts, outcomes, adapter, configs, gate, planner, props,
                ApplyEventEmitter.silent());
    }

    public ApplyExecutor(AdmissionService admission, StepIntentService intents,
            AttemptService attempts, OutcomeService outcomes, RuntimeAdapter adapter,
            RenderedConfigSource configs, HealthGate gate, ResumePlanner planner,
            ApplyEngineProperties props, ApplyEventEmitter events) {
        this(admission, intents, attempts, outcomes, adapter, configs, gate, planner, props, events,
                LivePlacements.UNAVAILABLE);
    }

    /**
     * The canonical form (plan 008 S3): the two above build an executor whose instance-wide stop fails loudly
     * ({@link LivePlacements#UNAVAILABLE}). Production supplies the registry's source.
     */
    public ApplyExecutor(AdmissionService admission, StepIntentService intents,
            AttemptService attempts, OutcomeService outcomes, RuntimeAdapter adapter,
            RenderedConfigSource configs, HealthGate gate, ResumePlanner planner,
            ApplyEngineProperties props, ApplyEventEmitter events, LivePlacements livePlacements) {
        this.livePlacements = Objects.requireNonNull(livePlacements, "livePlacements");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.gate = Objects.requireNonNull(gate, "health gate");
        this.planner = Objects.requireNonNull(planner, "resume planner");
        this.props = Objects.requireNonNull(props, "props");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * One {@code instance.rollout} notice (design section 3.5), published AFTER the journal write it announces has
     * committed - every service call above returned from its own {@code REQUIRES_NEW} transaction - so a notice never
     * outruns the row. The request is read back for the stamp it holds now ({@code B/D15}); a failure to build or
     * publish the notice never reaches the step loop.
     */
    private void rollout(TenantId t, ApplyId id, ReplicaRef replica, RolloutStep step) {
        ApplyEventEmitter.bestEffort("rollout", () -> events.onRollout(outcomes.requestOf(t, id), replica, step));
    }

    /**
     * A placement's pre-flight result: the replicas to address, the bytes to write to them, and
     * <b>which of them were running when the apply began</b>.
     *
     * <p>That last field is what tells obligation (r) apart from an ordinary start. A replica that
     * was running at pre-flight and is not running at its lifecycle step was taken down underneath
     * this apply; one that was already down is the state the operator asked SM to apply from.
     */
    private record Plan(List<TargetReplica> replicas, RenderedConfig config,
            Set<ReplicaRef> runningAtPreflight) {
    }

    /** A step's result, or the classifier that ended it. Never both. */
    private record StepOutcome<T>(T value, ErrorClass errorClass) {

        static <T> StepOutcome<T> of(T v) {
            return new StepOutcome<>(v, null);
        }

        static <T> StepOutcome<T> failed(ErrorClass e) {
            return new StepOutcome<>(null, e);
        }

        boolean failed() {
            return errorClass != null;
        }
    }

    /**
     * Admission only — Task 4's split of plan 005's {@code execute()}. Validation, the fences, the
     * {@code REQUESTED} row. <b>No adapter call, and no {@code REQUESTED → RUNNING} transition</b>:
     * that transition is Task 4a's claim, absorbed into the durable worker, not into admission.
     *
     * <p>Every pre-admission refusal plan 005's {@code execute()} used to return inline is preserved
     * here, unchanged in order and in meaning — only the return type changed, from a bare
     * {@code ApplyOutcome} to {@link AdmissionResult}, so a caller that only needs to know whether a
     * row now exists is not forced to interpret an execution-shaped answer.
     */
    public AdmissionResult admit(ApplyCommand command) {
        return admit(command, GenerationBinding.UNCHECKED);
    }

    /**
     * As {@link #admit(ApplyCommand)}, with the generation binding the caller requires. Every API submission passes
     * {@link GenerationBinding#MUST_BE_STAGED}; the one-argument form is plan 005's behaviour for the engine's own
     * suites.
     */
    public AdmissionResult admit(ApplyCommand command, GenerationBinding binding) {
        return admit(command, binding, null);
    }

    /**
     * As {@link #admit(ApplyCommand)} for a caller that PRE-MINTED the request's id (plan 010 T11.2): the fleet, whose
     * members are admitted batch by batch and must survive a crash between an admission and its record
     * ({@code fleet-apply.md} section 4.2). A request that already exists under {@code id} answers
     * {@link AdmissionResult.AlreadyAdmitted}, and nothing is written.
     */
    public AdmissionResult admit(ApplyCommand command, ApplyId id) {
        return admit(command, GenerationBinding.UNCHECKED, id);
    }

    /** As {@link #admit(ApplyCommand, ApplyId)}, with the generation binding the caller requires. */
    public AdmissionResult admit(ApplyCommand command, GenerationBinding binding, ApplyId id) {
        // A restart REQUEST is a different slice: no generation to bind, so no MATERIALIZE step and
        // a lifecycle journal with nothing to compare a hash against. Plan 005 does not open it.
        if (command.operation() == Operation.RESTART) {
            return new AdmissionResult.OperationUnsupported(command.operation());
        }
        if (command.operation() == Operation.APPLY) {
            // SM-D15: "SM offers only strategies the solution's declared capabilities support."
            // Refused BEFORE admission, so no row is written for a request that was never going
            // to run.
            if (!SUPPORTED.contains(command.strategy())) {
                return new AdmissionResult.StrategyUnsupported(command.strategy());
            }
            // BE-D45: an instance-wide APPLY is refused before admission, by policy. (Until V021 this was a limit of the
            // journal, BE-D23: apply_step_intent's identity was placement-blind, so a fan-out would have journalled
            // placement B's pre-flight as a retry of placement A's. The journal is placement-aware now, BE-D44, and
            // the refusal stays because OR-53, OR-4 and OR-42 are all per placement: a rollout across an instance's
            // placements is a fleet of placement-scoped applies.)
            //
            // A STOP is a different case and is not refused here: BE-D26 -- it is scope-granular by design.
            if (command.scope().placement().isInstanceWide()) {
                return new AdmissionResult.ScopeUnsupported(command.scope());
            }
        }

        AdmissionOutcome admitted = admission.admit(command, binding, id);
        if (admitted instanceof AdmissionOutcome.Refused r) {
            return new AdmissionResult.NotAdmitted(r.reason(), r.detail());
        }
        if (admitted instanceof AdmissionOutcome.AlreadyAdmitted existing) {
            // Mapped to its OWN result, never folded into Admitted: execute() runs whatever Admitted names, so folding
            // would let it start an existing request a second time. An unconditional cast here would instead throw
            // ClassCastException on exactly the crash-recovery path this variant exists to make safe.
            return new AdmissionResult.AlreadyAdmitted(existing.applyId());
        }
        return new AdmissionResult.Admitted(((AdmissionOutcome.Admitted) admitted).applyId());
    }

    /**
     * Everything plan 005's {@code execute()} did AFTER admission — called ONLY by the durable
     * worker, on a row the worker's own claim ({@code ApplyClaimRepository.claimNextAsStarted}) has
     * ALREADY moved to {@code RUNNING}. <b>This method must not call {@code outcomes.start} —
     * winning the claim already performed that one {@code REQUESTED → RUNNING} transition</b>; a
     * second call here would either refuse (the row is no longer {@code REQUESTED}) or, if
     * {@code start} were ever weakened to tolerate {@code RUNNING}, would delete plan 004's fence
     * instead of merely being redundant. See the class javadoc's crash-window table.
     *
     * <p>The command is reconstructed from the persisted row rather than threaded through from
     * {@code admit} — the worker calls this on a row it discovered by polling, with no in-memory
     * {@link ApplyCommand} to hand back.
     *
     * @throws IllegalStateException if the row is not {@code RUNNING} — this method's one
     *                                precondition, and the mutation this correction exists for: an
     *                                earlier draft re-ran {@code start()} here, which this guard would
     *                                also have caught, since {@code start()} itself refuses a row that
     *                                is not {@code REQUESTED}
     */
    public ApplyOutcome runAdmitted(TenantId tenant, ApplyId id) {
        ApplyRequestRow row = outcomes.requestOf(tenant, id);
        if (row.state() != RequestState.RUNNING) {
            throw new IllegalStateException("runAdmitted expects a row the claim already moved to "
                    + "RUNNING; " + id.value() + " is " + row.state());
        }
        ApplyCommand command = new ApplyCommand(row.scope(), row.operation(), row.generation(),
                row.strategy(), row.outageConfirmedBy(), row.requestedBy());
        return command.operation() == Operation.STOP
                ? runStop(tenant, id, command.scope())
                : run(tenant, id, command);
    }

    /**
     * {@code admit()} followed by {@code runAdmitted()} — plan 005's original, single-call shape,
     * kept as a <b>test-only convenience</b> so the existing engine test suite (written against one
     * synchronous call) did not have to be rewritten for a refactor that changes no engine semantics.
     * No production caller uses this: the real path is the HTTP submission (Task 4's other half,
     * {@code admit} only) and the durable worker (Task 4a's claim, then {@code runAdmitted}).
     *
     * <p>Simulates the worker's claim with a direct {@code outcomes.start()} call, since this
     * convenience has no worker polling to perform it — {@code runAdmitted} itself never does.
     */
    public ApplyOutcome execute(ApplyCommand command) {
        AdmissionResult result = admit(command);
        if (result instanceof AdmissionResult.NotAdmitted na) {
            return new ApplyOutcome.NotAdmitted(na.reason(), na.detail());
        }
        if (result instanceof AdmissionResult.OperationUnsupported ou) {
            return new ApplyOutcome.OperationUnsupported(ou.op(),
                    "this build's executor drives apply and stop requests");
        }
        if (result instanceof AdmissionResult.StrategyUnsupported su) {
            return new ApplyOutcome.StrategyUnsupported(su.s(),
                    "this build executes " + SUPPORTED + ", and scheduled applies need a scheduler "
                            + "no deployable exists to host");
        }
        if (result instanceof AdmissionResult.ScopeUnsupported scu) {
            return new ApplyOutcome.ScopeUnsupported(scu.scope(),
                    "an apply names one placement (BE-D45); roll a generation across an instance's placements as a fleet");
        }
        if (result instanceof AdmissionResult.AlreadyAdmitted) {
            // execute() supplies no id, so admission cannot answer this; a sentence, not a ClassCastException
            throw new IllegalStateException("execute() never supplies a pre-minted id, so admission cannot have answered "
                    + "AlreadyAdmitted");
        }
        ApplyId id = ((AdmissionResult.Admitted) result).id();
        TenantId t = command.scope().tenant();
        outcomes.start(t, id);
        return runAdmitted(t, id);
    }

    /**
     * {@code BE-D26}'s stop: scope-granular, one {@code STOP} intent per replica.
     *
     * <p><b>Instance-wide is admissible here and not for an apply</b>, by policy ({@code BE-D45}) rather than by a
     * limit of the journal: {@code apply_step_intent}'s identity is {@code (tenant_id, apply_id, placement_id, step,
     * replica_ref)} ({@code BE-D44}, {@code V021}), so one {@code apply_id} holds one {@code STOP} intent per replica
     * of every placement it addresses, even when two placements on two hosts name their container alike. Each intent
     * carries its own placement and, under an instance-wide request, a key that names it
     * ({@link #idempotencyKey(ApplyId, ScopeKey, PlacementId, Step, ReplicaRef)}).
     *
     * <p>No pre-flight, no generation, no rendering. The replica set comes from
     * {@link RuntimeAdapter#replicas} ({@code BE-D25}), which exists because {@code prepare} is
     * generation-bearing and a stop has no generation.
     */
    private ApplyOutcome runStop(TenantId t, ApplyId id, ScopeKey scope) {
        // BE-D46: an instance-wide stop addresses the placements the instance HAS (the registry), never the ones SM
        // has ever been asked to act on (request history), which would include a retired placement and fail the stop.
        List<PlacementId> placements = scope.placement().isInstanceWide()
                ? livePlacements.of(scope.tenant(), scope.instance())
                : List.of(scope.placement());
        if (placements.isEmpty()) {
            // An instance with no live placement: nothing was addressed, so nothing is
            // known to be down. STOP_FAILED, not STOPPED -- reporting STOPPED would tell an
            // operator the instance is down when SM never looked at anything.
            //
            // MEASURED, not chosen freely: state_fits_operation admits only REQUESTED, RUNNING,
            // STOPPED and STOP_FAILED for a stop, so FAILED_NO_EFFECT -- the apply path's answer
            // for "nothing was asked of anything" -- is refused for this operation. The database
            // settled the vocabulary; between the two it leaves, this is the honest one.
            return land(t, id, RequestState.STOP_FAILED,
                    new ErrorClass("stop.no.known.placement"));
        }

        // ENUMERATE EVERYTHING FIRST, then act. BE-D23's second clause used to forbid two placements whose adapter
        // yields the same replica label under one apply_id (the SSH adapter's label is the configured container
        // name, so `cache-engine` on host A and host B is the ordinary shape), and this method refused the whole
        // stop with `stop.replica.label.collision` before stopping anything. V021 closed that clause in the DDL: the
        // intent's identity includes its placement, so the colliding stop is journalled, both replicas are stopped,
        // and the refusal is gone. Encoding the placement into `replica_ref` is still what BE-D23 forbids, and still
        // not done: the placement is a column of its own.
        ErrorClass failure = null;
        List<TargetReplica> targets = new ArrayList<>();
        for (PlacementId placementId : placements) {
            PlacementRef placement =
                    new PlacementRef(scope.tenant(), scope.instance(), placementId);
            List<TargetReplica> found;
            try {
                found = adapter.replicas(placement);
            } catch (RuntimeAdapterException e) {
                // One unreachable placement must not hide the others, and it cannot be journalled:
                // a STOP intent must carry a real replica_ref (placement_scoped_steps), and there
                // is no replica to name. Reported in the outcome only -- a stated limit.
                failure = failure == null ? e.errorClass() : failure;
                continue;
            }
            targets.addAll(found);
        }

        for (TargetReplica replica : targets) {
            ErrorClass stopped = stopOne(t, id, scope, replica);
            // FIRST failure wins, not the last: a classifier that depends on iteration order is
            // not a classifier.
            failure = failure == null ? stopped : failure;
        }
        // state_fits_operation admits only REQUESTED/RUNNING/STOPPED/STOP_FAILED for a stop, so
        // APPLY_INDETERMINATE is not reachable here -- a stop says whether it worked.
        return failure == null
                ? land(t, id, RequestState.STOPPED, null)
                : land(t, id, RequestState.STOP_FAILED, failure);
    }

    /** One replica's stop. {@code null} on success, or the classifier that ended it. */
    private ErrorClass stopOne(TenantId t, ApplyId id, ScopeKey scope, TargetReplica replica) {
        PlacementId placement = replica.placement().placement();
        StepIntentRow intent = intents.ensureIntent(t, id, placement, Step.STOP, replica.ref(),
                idempotencyKey(id, scope, placement, Step.STOP, replica.ref()), null);
        AttemptRow attempt;
        try {
            attempt = attempts.open(t, id, intent, null, props.stepBudget());
        } catch (AttemptService.RetryCapExceeded | AttemptService.ConcurrentAttempt e) {
            // Neither may escape: a stop is the emergency lever, and state_fits_operation gives it
            // no APPLY_INDETERMINATE to be stranded in, so an exception out of here would leave
            // the request RUNNING with stop_inflight_fence blocking every retry and no operator
            // exit at all.
            return new ErrorClass("stop.attempt.unavailable");
        }
        try {
            LifecycleResult result = adapter.stop(replica, context(intent, attempt));
            // issued=false is the idempotent case §2.2 rule 2 requires an adapter to report as
            // success: a replica already at the desired state. Recorded, not hidden -- the
            // read-back says what the runtime holds now.
            attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, readBack(replica),
                    null, result.runtimeRef());
            return null;
        } catch (RuntimeAdapterException e) {
            attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica),
                    e.errorClass(), null);
            return e.errorClass();
        }
    }

    private ApplyOutcome run(TenantId t, ApplyId id, ApplyCommand command) {
        long generation = command.generation();
        ScopeKey scope = command.scope();
        PlacementRef placement =
                new PlacementRef(scope.tenant(), scope.instance(), scope.placement());

        // ---- Phase 1: PREFLIGHT. Nothing here mutates the target: rendering writes nothing and
        // prepare() never mutates by contract. So a failure here is provably FAILED_NO_EFFECT -- a
        // measurement ("no MATERIALIZE intent exists"), not a guess.
        StepOutcome<Plan> preflight = preflight(t, id, placement, generation, command.strategy());
        if (preflight.failed()) {
            // "No MATERIALIZE intent exists" holds on a first run only. A RESUME re-enters here with the journal of a request that
            // already wrote and may have restarted (an agent that is not connected yet fails preflight fast), and FAILED_NO_EFFECT
            // would then be a false statement: such a request is APPLY_INDETERMINATE, which the reconciler settles from a read
            // (OR-146 (2), the grace-expiry leg). THE RULE (final review MINOR-1): the attempt ledger decides. Every adapter call of
            // a non-PREFLIGHT step is preceded by attempts.open on its intent, so a non-PREFLIGHT intent with NO attempt opened
            // proves nothing was written, restarted or dispatched for it (a crash between the intent insert and the attempt) and
            // stays FAILED_NO_EFFECT. An intent WITH an attempt, however it closed, does not prove that (a FAILED close can follow a
            // write that landed and was not acknowledged), so it is APPLY_INDETERMINATE.
            boolean mayHaveEffect = this.intents.intentsOf(t, id).stream()
                    .anyMatch(i -> i.step() != Step.PREFLIGHT && this.attempts.latest(t, i.intentId()).isPresent());
            return land(t, id, mayHaveEffect ? RequestState.APPLY_INDETERMINATE : RequestState.FAILED_NO_EFFECT,
                    preflight.errorClass());
        }
        Plan plan = preflight.value();

        // ---- Phase 2: MATERIALIZE every replica (SM-D15's stage-everywhere ordering). From the
        // first successful write onward FAILED_NO_EFFECT would be a false statement, so every
        // failure here routes to BE-D22's exhaustion journal.
        for (TargetReplica replica : plan.replicas()) {
            ErrorClass failure = materialize(t, id, replica, generation, plan.config());
            if (failure != null) {
                return exhausted(t, id, placement, plan.replicas(), generation,
                        plan.config().hash(), failure);
            }
        }

        // ---- Phase 3. Under manual the operator restarts and SM observes: BE-D16 makes STAGED
        // exclusively the manual strategy's terminal state, so this is the only branch that reaches
        // it. Every other supported strategy activates the generation itself.
        if (command.strategy() == Strategy.MANUAL) {
            return land(t, id, RequestState.STAGED, null);
        }
        return restartAndGate(t, id, command, plan);
    }

    /**
     * {@code SM-D15}'s restart strategies, over the one placement ({@code BE-D23}), one replica at a
     * time and in {@code prepare}'s order.
     *
     * <p><b>{@code LIFECYCLE} is the one step that carries a {@code pre_baseline}</b>, which is both
     * what {@code baseline_only_for_lifecycle} permits and what the step needs: restart-observed is
     * a comparison against the baseline captured <b>before</b> the verb was issued, and a baseline
     * read afterwards would compare the new value with itself.
     *
     * <p><b>Under {@code rolling-health-gated} replica N+1 is not touched until replica N's gate
     * passes.</b> That ordering is the whole content of the strategy; a loop that restarted
     * everything and gated once would be {@code restart-all} with a longer name.
     */
    private ApplyOutcome restartAndGate(TenantId t, ApplyId id, ApplyCommand command, Plan plan) {
        return restartAndGate(t, id, command.strategy() == Strategy.ROLLING_HEALTH_GATED, command.generation(),
                plan.replicas(), plan.runningAtPreflight(), plan.config().hash());
    }

    /**
     * The step loop proper, over an explicit replica list: the forward path passes every replica of the placement, and a
     * re-drive (plan 010 T6.8) passes only the replicas the fresh observation found to need a restart. The loop itself is
     * the same code, so a re-drive is gated, journaled and verified exactly as the original apply would have been.
     *
     * @param intendedHash the keyed hash the {@code VERIFY} step compares the post-restart read-back with
     */
    private ApplyOutcome restartAndGate(TenantId t, ApplyId id, boolean gated, long generation,
            List<TargetReplica> replicas, Set<ReplicaRef> runningAtPreflight, String intendedHash) {
        for (TargetReplica replica : replicas) {
            // NOTE ON OBLIGATION (r), and why there is no stop check here.
            //
            // BE-D13's apply fence carries `operation <> 'stop'`, so an emergency stop IS
            // admissible by the database while this apply is in flight. Design §3.3 then says what
            // must follow: "the stop executes immediately at the adapter -- it does not queue
            // behind the apply and THE APPLY IS NOT CANCELLED IN PLACE; the running apply's next
            // step observes the stopped replica through status and its health gate, and terminates
            // through its normal failure paths".
            //
            // An earlier version of this loop polled apply_request for an admitted stop and
            // returned CONFIG_DIVERGED. That cancels the apply in place, on the strength of a row
            // rather than an observation -- the opposite of what §3.3 specifies -- so it was
            // removed rather than kept as a quiet local invention. The mechanism §3.3 names cannot
            // be built here either: driving a stop needs the replica set, and
            // prepare(placement, generation) is the seam's only enumerator while
            // apply_has_generation makes a stop's generation NULL. Obligation (r) is therefore
            // UNPROVEN in this build and routed to a BE-D* decision; StopDuringApplyIT pins what
            // is true today, gap included.
            RuntimeStatus observed;
            try {
                observed = adapter.status(replica);
            } catch (RuntimeAdapterException e) {
                return land(t, id, RequestState.APPLY_INDETERMINATE, e.errorClass());
            }
            boolean wasRunning = runningAtPreflight.contains(replica.ref());
            if (wasRunning && observed.liveness() != Liveness.RUNNING) {
                // OBLIGATION (r), reached the way §3.3 says it must be: by OBSERVATION. Somebody
                // -- an executed stop, an operator, the runtime -- took this replica down while
                // the apply was in flight. The apply is not cancelled in place and reads no
                // request row; its next step simply looks, finds a replica that was running when
                // the apply began and is not running now, and terminates through a normal failure
                // path with per-replica evidence.
                //
                // Conditional on wasRunning, and that condition is the whole difference between
                // (r) and an ordinary start: an apply issued AFTER an operator stopped the
                // instance finds every replica down at pre-flight, and must bring them up rather
                // than refuse. An unconditional check made `stop, then apply under restart-all`
                // impossible, which a review caught.
                //
                // Distinct from an unreadable baseline below: this is a KNOWN state, not an
                // unknown one, so it is CONFIG_DIVERGED and not APPLY_INDETERMINATE. Journalled
                // before landing, so the evidence is per replica.
                ErrorClass why = new ErrorClass("lifecycle.replica.not.running");
                StepIntentRow halted = intents.ensureIntent(t, id, replica.placement().placement(),
                        Step.LIFECYCLE, replica.ref(),
                        idempotencyKey(id, Step.LIFECYCLE, replica.ref()), null);
                AttemptRow record;
                try {
                    record = attempts.open(t, id, halted, null, props.stepBudget());
                } catch (AttemptService.RetryCapExceeded | AttemptService.ConcurrentAttempt e) {
                    return land(t, id, RequestState.CONFIG_DIVERGED, why);
                }
                attempts.close(t, record.attemptId(), AttemptOutcome.FAILED,
                        observed.observedHash(), why, observed.runtimeRef());
                return land(t, id, RequestState.CONFIG_DIVERGED, why);
            }
            // A replica that was already down when the apply began has no baseline to compare
            // against, and needs none: the lifecycle verb starts it, and "it is now running and
            // healthy" is the observation that matters. §2.2 rule 3's baseline-missing rule is
            // about RESUME -- re-issuing a restart nobody authorized twice -- and this is the
            // forward path, where the operator's own request is the authorization.
            PreBaseline before = observed.baseline();
            if (wasRunning && before == null) {
                // §2.2 rule 3: "baseline missing or unreadable -> APPLY_INDETERMINATE, because
                // re-issuing a restart the operator may not have authorized twice is exactly the
                // guess this state exists to refuse." Refused BEFORE the verb, not after it.
                return land(t, id, RequestState.APPLY_INDETERMINATE,
                        new ErrorClass("lifecycle.baseline.unreadable"));
            }

            StepIntentRow intent = intents.ensureIntent(t, id, replica.placement().placement(),
                    Step.LIFECYCLE, replica.ref(),
                    idempotencyKey(id, Step.LIFECYCLE, replica.ref()), null);
            AttemptRow attempt = attempts.open(t, id, intent, before, props.stepBudget());

            LifecycleResult issued;
            try {
                issued = adapter.startOrUpdate(replica, generation, context(intent, attempt));
            } catch (RuntimeAdapterException e) {
                // OR-146 (1), the rule: WHETHER THE RESTART WAS DISPATCHED decides. An exception the adapter raised after
                // handing the verb to the runtime (afterDispatch(): the agent adapter's wait ended with the command sent and no
                // terminal answer - a lost session, a timeout, an interrupt) leaves the replica in an UNKNOWN state: it may well
                // have restarted. That is what APPLY_INDETERMINATE is for, and the reconciler resolves it from a fresh read
                // (the adapter's own contract: "the command stays as it is for the engine's retry"). An exception with nothing
                // dispatched (no session, unresolvable placement, a refused or failed answer from the runtime) is a KNOWN
                // outcome - the replica was not restarted by this step - and stays the terminal CONFIG_DIVERGED.
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica),
                        e.errorClass(), null);
                return land(t, id, e.afterDispatch() ? RequestState.APPLY_INDETERMINATE : RequestState.CONFIG_DIVERGED,
                        e.errorClass());
            }

            // The verb was issued: "restart-requested". Not "restart-observed" - nothing has looked yet.
            rollout(t, id, replica.ref(), RolloutStep.RESTART_REQUESTED);

            if (!gated) {
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, readBack(replica),
                        null, issued.runtimeRef());
                ApplyOutcome unverified = verifyAfterRestart(t, id, replica, intendedHash);
                if (unverified != null) {
                    return unverified;
                }
                continue;
            }

            GateVerdict verdict = gate.await(adapter, replica, before);
            if (verdict instanceof GateVerdict.Unavailable u) {
                // Pre-flight refused a health-gated strategy against a runtime that publishes no
                // health signal, so the ORDINARY route here is closed. It is NOT unreachable: the
                // signal is observed per poll, and a replica whose health stops being readable
                // between pre-flight and the gate arrives here. An earlier version threw an
                // IllegalStateException, which left the request RUNNING -- scope blocked, no
                // terminal state, no operator exit. SM restarted this replica and then lost sight
                // of it, which is what APPLY_INDETERMINATE is for.
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, u.why(), null);
                return land(t, id, RequestState.APPLY_INDETERMINATE, u.why());
            }
            if (verdict instanceof GateVerdict.Failed failed) {
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica),
                        failed.why(), null);
                // NOT FAILED_ROLLED_BACK. SM-D15 names divergence explicitly for this case, and
                // rolling back a replica already serving the new generation is a second outage.
                return land(t, id, RequestState.CONFIG_DIVERGED, failed.why());
            }
            attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, readBack(replica),
                    null, issued.runtimeRef());
            // OR-53: the gate passed; now read the file back. The next replica is not touched until this one's file
            // is verified, so a bad write stops the roll at the first replica.
            ApplyOutcome unverified = verifyAfterRestart(t, id, replica, intendedHash);
            if (unverified != null) {
                return unverified;
            }
            // Only the gate OBSERVES a restart (its baseline comparison, with the runtime saying healthy), and the
            // ladder's restart-observed rung also needs the read-back to have matched (T6.5). The ungated strategies
            // issue the verb and move on, so they publish "restart-requested" and never claim "restart-observed" - a
            // rollout notice must not say more than the engine saw.
            rollout(t, id, replica.ref(), RolloutStep.RESTART_OBSERVED);
        }
        return land(t, id, RequestState.APPLIED, null);
    }

    /**
     * OR-53, Q-G2 (a): after a replica's restart (and, when gated, its gate) SM reads the file back and verifies it
     * against the {@code MATERIALIZE} intent's bound keyed hash, in one {@code VERIFY} intent of its own. Write before
     * act: the intent and attempt are committed before the adapter is asked.
     *
     * <p>A match closes the attempt {@code SUCCEEDED} with the read-back hash as evidence. A mismatch - a different
     * hash, or no file at all - closes it {@code FAILED} with {@code verify.hash.mismatch} and lands
     * {@code CONFIG_DIVERGED}, the same as a failed gate: <b>no automatic rollback</b>, because restoring a replica that
     * is already serving is a second outage ({@code SM-D15}). A read-back that cannot be taken is not a mismatch - SM
     * restarted the replica and lost sight of it - so it lands {@code APPLY_INDETERMINATE}, as an unreadable status does
     * elsewhere in this loop.
     *
     * <p>The only {@code VERIFY} on a forward path. {@link #verifyRestore} is the exhaustion tail's, and the two never
     * meet in one apply: exhaustion happens before any restart, and a divergence here lands without a rollback.
     *
     * @return {@code null} to continue, or the landed outcome that ends the apply
     */
    private ApplyOutcome verifyAfterRestart(TenantId t, ApplyId id, TargetReplica replica, String intendedHash) {
        StepIntentRow intent = intents.ensureIntent(t, id, replica.placement().placement(),
                Step.VERIFY, replica.ref(),
                idempotencyKey(id, Step.VERIFY, replica.ref()), intendedHash);
        AttemptRow attempt;
        try {
            attempt = attempts.open(t, id, intent, null, props.stepBudget());
        } catch (AttemptService.RetryCapExceeded | AttemptService.ConcurrentAttempt e) {
            return land(t, id, RequestState.APPLY_INDETERMINATE, new ErrorClass("verify.attempt.unavailable"));
        }
        String observed;
        try {
            observed = adapter.status(replica).observedHash();
        } catch (RuntimeAdapterException e) {
            attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, e.errorClass(), null);
            return land(t, id, RequestState.APPLY_INDETERMINATE, e.errorClass());
        }
        if (intendedHash.equals(observed)) {
            attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, observed, null, null);
            return null;
        }
        ErrorClass mismatch = new ErrorClass("verify.hash.mismatch");
        attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, observed, mismatch, null);
        return land(t, id, RequestState.CONFIG_DIVERGED, mismatch);
    }

    /**
     * Pre-flight: render, then ask the adapter. <b>Rendering first is deliberate and load-bearing</b>
     * — it is pure and cannot touch the target, so a render failure means the runtime was asked
     * nothing at all, which is the strongest form of "no effect" available.
     *
     * <p>{@code pre_baseline} is {@code null}, as {@code baseline_only_for_lifecycle} requires of
     * every step that is not {@code LIFECYCLE}.
     */
    private StepOutcome<Plan> preflight(TenantId t, ApplyId id, PlacementRef placement,
            long generation, Strategy strategy) {
        StepIntentRow intent = intents.ensureIntent(t, id, placement.placement(),
                Step.PREFLIGHT, ReplicaRef.PLACEMENT,
                idempotencyKey(id, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null);
        AttemptRow attempt = attempts.open(t, id, intent, null, props.stepBudget());

        RenderedConfig config;
        try {
            config = configs.render(placement, generation);
        } catch (RuntimeException e) {
            // I6. The renderer's own message is NEVER read -- not getMessage(), not toString(), not
            // even the exception's class name. A file-backed renderer's message carries the path it
            // could not read; a template renderer's could carry the value it choked on. ONE fixed
            // classifier, chosen here rather than supplied by the throwing code, so a badly-behaved
            // RenderedConfigSource cannot widen it. ErrorClass's own constructor is the second line
            // of defence: it refuses any token outside ^[a-z][a-z0-9]*(\.[a-z0-9]+)*$.
            return closeFailed(t, attempt, new ErrorClass("render.failed"));
        }

        PreflightResult result;
        try {
            result = adapter.prepare(placement, generation, context(intent, attempt));
        } catch (RuntimeAdapterException e) {
            return closeFailed(t, attempt, e.errorClass());
        }
        if (result instanceof PreflightResult.Refused refused) {
            attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, refused.reason(),
                    refused.runtimeRef());
            return StepOutcome.failed(refused.reason());
        }
        PreflightResult.Ready ready = (PreflightResult.Ready) result;

        // SM-D15's strategy availability, enforced where it is KNOWABLE. healthSignalDeclared is an
        // observation, not a configuration flag, so this cannot be the pre-admission
        // StrategyUnsupported refusal -- nothing before pre-flight knows the answer. It is still
        // refused before a single byte is written, which is what makes FAILED_NO_EFFECT a
        // measurement here. Treating it as a pass would make rolling-health-gated
        // indistinguishable from rolling-no-validation while keeping its name (L7).
        if (strategy == Strategy.ROLLING_HEALTH_GATED && !ready.healthSignalDeclared()) {
            return closeFailed(t, attempt, new ErrorClass("strategy.health.signal.absent"));
        }
        Set<ReplicaRef> running = new LinkedHashSet<>();
        for (TargetReplica replica : ready.replicas()) {
            try {
                if (adapter.status(replica).liveness() == Liveness.RUNNING) {
                    running.add(replica.ref());
                }
            } catch (RuntimeAdapterException e) {
                return closeFailed(t, attempt, e.errorClass());
            }
        }
        attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null,
                ready.runtimeRef());
        return StepOutcome.of(new Plan(ready.replicas(), config, running));
    }

    /**
     * One replica's config write, with §2.2 rule 4's bounded retry.
     *
     * @return {@code null} on success, or the last classifier seen once the cap is spent. The bound
     *         is {@link AttemptService}'s, which reads the database's own count of attempts on this
     *         intent — so it survives a crash, which a counter held here would not.
     */
    private ErrorClass materialize(TenantId t, ApplyId id, TargetReplica replica, long generation,
            RenderedConfig config) {
        StepIntentRow intent = intents.ensureIntent(t, id, replica.placement().placement(),
                Step.MATERIALIZE, replica.ref(),
                idempotencyKey(id, Step.MATERIALIZE, replica.ref()), config.hash());
        ErrorClass last = null;
        while (true) {
            AttemptRow attempt;
            try {
                attempt = attempts.open(t, id, intent, null, props.stepBudget());
            } catch (AttemptService.RetryCapExceeded e) {
                return last == null ? new ErrorClass("retry.cap.spent") : last;
            }
            try {
                MaterializeResult result = adapter.materializeConfig(replica, generation, config,
                        context(intent, attempt));
                RuntimeStatus status;
                try {
                    status = adapter.status(replica);
                } catch (RuntimeAdapterException e) {
                    last = e.errorClass();
                    attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, last,
                            result.runtimeRef());
                    continue;   // bounded retry; exhaustion routes to BE-D22's journal
                }

                // The intent BOUND a hash before the adapter was invoked (§2.2 rule 2), but a
                // return value is not evidence. Only a read-back from the runtime may close this
                // attempt successfully. This catches both an adapter that reports a different hash
                // and one that writes different bytes while falsely reporting the expected value.
                String observed = status.observedHash();
                if (!intent.configHash().equals(result.writtenHash())
                        || !intent.configHash().equals(observed)) {
                    last = observed == null
                            ? new ErrorClass("materialize.readback.unavailable")
                            : new ErrorClass("materialize.hash.mismatch");
                    attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, observed, last,
                            result.runtimeRef());
                    continue;   // bounded retry; exhaustion routes to BE-D22's journal
                }
                // The container start the host reports at this read-back is kept with the write (OR-112 (1)): a start the
                // data plane later observes is "after the write" only if it differs from, and is later than, this one -
                // both values are the host's own clock, so no skew between the host and the data plane can misjudge it.
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, observed, null,
                        result.runtimeRef(), WriteBaselinePolicy.of(adapter, replica.placement(), intent.intentId(), status));
                // The replica's config is written AND read back: that is "staged" (design section 3.5), per replica.
                rollout(t, id, replica.ref(), RolloutStep.STAGED);
                return null;
            } catch (RuntimeAdapterException e) {
                last = e.errorClass();
                // Record what is ACTUALLY on this replica's disk after the failed try. This row is
                // the per-replica evidence §3.2's CONFIG_DIVERGED row points at, and observed_hash
                // is "read-back EVIDENCE, never intent" -- a failed attempt has evidence too.
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica),
                        last, null);
            }
        }
    }

    private static String firstHash(List<RuntimeStatus> observed) {
        return observed.isEmpty() ? null : observed.get(0).observedHash();
    }

    /** Best-effort read-back for evidence. A status failure here must not mask the real classifier. */
    private String readBack(TargetReplica replica) {
        try {
            return adapter.status(replica).observedHash();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code BE-D22}'s exhaustion journal: assess (hashless, read-only), then — only if a restore
     * target exists — a {@code ROLLBACK} intent carrying that target's hash, opened <b>before</b>
     * the destructive call, then one {@code VERIFY} per replica.
     */
    private ApplyOutcome exhausted(TenantId t, ApplyId id, PlacementRef placement,
            List<TargetReplica> replicas, long generation, String intendedHash, ErrorClass why) {
        // Plan 004's ensureIntent scope-lock watch item, RESOLVED by measurement rather than by
        // preference (EnsureIntentRaceIT). ensureIntent takes no apply_scope row lock, so a
        // concurrent OutcomeService.land -- a supersede, an operator stop -- can commit a terminal
        // state between its read and its insert; the measurement shows the intent then lands on a
        // terminal request, because the FK does not fence on state. The lock is NOT added: it would
        // widen plan 004's admission lock to cover every step of every apply and serialize
        // unrelated instances behind one scope row. The engine re-reads instead, and it re-reads
        // HERE because this is the longest stretch of writes the loop can enter after a failure and
        // the only one that is destructive. A request that is no longer ours is reported as it
        // stands; rolling it back would fight whoever took the scope.
        RequestState observed = outcomes.stateOf(t, id);
        if (observed.isTerminal()) {
            return new ApplyOutcome.Landed(id, observed, why);
        }
        StepIntentRow assessIntent = intents.ensureIntent(t, id, placement.placement(), Step.ROLLBACK_ASSESS,
                ReplicaRef.PLACEMENT,
                idempotencyKey(id, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT), null);
        AttemptRow assessAttempt = attempts.open(t, id, assessIntent, null, props.stepBudget());

        RollbackAssessment assessment;
        try {
            assessment = adapter.assessRollback(placement, generation, intendedHash);
        } catch (RuntimeAdapterException e) {
            attempts.close(t, assessAttempt.attemptId(), AttemptOutcome.FAILED, null,
                    e.errorClass(), null);
            return land(t, id, RequestState.APPLY_INDETERMINATE, e.errorClass());
        }
        attempts.close(t, assessAttempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null, null);

        if (assessment instanceof RollbackAssessment.NothingToRestore) {
            return land(t, id, RequestState.FAILED_NO_EFFECT, why);
        }
        if (assessment instanceof RollbackAssessment.Unknowable unknowable) {
            // Includes rollback.target.absent and rollback.state.unknown: in both the engine either
            // cannot journal what it would do, or must not overwrite a value it did not write.
            // OR-102: and rollback.preimage.altered, which also says WHICH declared paths differ.
            reportPreImageChange(t, id, assessAttempt.attemptId(), unknowable.change());
            return land(t, id, RequestState.APPLY_INDETERMINATE, unknowable.why());
        }
        String targetHash = ((RollbackAssessment.Restorable) assessment).targetHash();

        // The ROLLBACK intent carries the target hash and is COMMITTED before restore() is called.
        // That single ordering is what makes a crash in the window decidable rather than a guess.
        StepIntentRow rollbackIntent = intents.ensureIntent(t, id, placement.placement(), Step.ROLLBACK,
                ReplicaRef.PLACEMENT, idempotencyKey(id, Step.ROLLBACK, ReplicaRef.PLACEMENT),
                targetHash);
        AttemptRow rollbackAttempt = attempts.open(t, id, rollbackIntent, null, props.stepBudget());
        ErrorClass restoreFailure = null;
        try {
            RestoreResult result = adapter.restore(placement, generation, targetHash);
            attempts.close(t, rollbackAttempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null,
                    result.runtimeRef());
        } catch (RuntimeAdapterException e) {
            restoreFailure = e.errorClass();
            attempts.close(t, rollbackAttempt.attemptId(), AttemptOutcome.FAILED, null,
                    restoreFailure, null);
            reportPreImageChange(t, id, rollbackAttempt.attemptId(), e.preImageChange().orElse(null));
        }

        // VERIFY runs either way, because the per-replica evidence is worth recording even when the
        // restore was refused. But a refused restore is NOT allowed to be masked by a verify that
        // happens to find journal-known values: BE-D22 correction B says a pre-image that vanished
        // or moved reaches APPLY_INDETERMINATE. The engine intended to correct the state and was
        // refused, which is an operator's call, not CONFIG_DIVERGED's "we halted and here is why".
        return verifyRestore(t, id, replicas, targetHash, intendedHash, why, restoreFailure);
    }

    /**
     * One {@code VERIFY} step per replica against the restore target. This is where the per-replica
     * restored evidence becomes <b>durable</b>: {@code VERIFY} is replica-scoped and hash-bearing,
     * so each read-back lands in its own {@code apply_attempt.observed_hash}.
     */
    private ApplyOutcome verifyRestore(TenantId t, ApplyId id, List<TargetReplica> replicas,
            String targetHash, String intendedHash, ErrorClass why, ErrorClass restoreFailure) {
        boolean allAtTarget = true;
        boolean anyUnknown = false;

        for (TargetReplica replica : replicas) {
            StepIntentRow intent = intents.ensureIntent(t, id, replica.placement().placement(),
                    Step.VERIFY, replica.ref(),
                    idempotencyKey(id, Step.VERIFY, replica.ref()), targetHash);
            AttemptRow attempt = attempts.open(t, id, intent, null, props.stepBudget());
            String observed;
            try {
                observed = adapter.status(replica).observedHash();
            } catch (RuntimeAdapterException e) {
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, e.errorClass(),
                        null);
                anyUnknown = true;
                continue;
            }
            attempts.close(t, attempt.attemptId(),
                    observed == null ? AttemptOutcome.FAILED : AttemptOutcome.SUCCEEDED, observed,
                    observed == null ? new ErrorClass("verify.no.readback") : null, null);
            // The same two journal-known values the assessment used. A third one after a restore
            // means somebody wrote while we were working: report, never overwrite (BE-D22, B/D12).
            anyUnknown |= observed == null
                    || !(targetHash.equals(observed) || intendedHash.equals(observed));
            allAtTarget &= targetHash.equals(observed);
        }

        if (anyUnknown) {
            return land(t, id, RequestState.APPLY_INDETERMINATE,
                    new ErrorClass("rollback.state.unknown"));
        }
        if (restoreFailure != null) {
            // The evidence above is now durable; the verdict is still "SM could not put this right".
            return land(t, id, RequestState.APPLY_INDETERMINATE, restoreFailure);
        }
        // Every replica holds a value this apply wrote, but not all hold the target: the restore
        // landed only partially, so the config is genuinely not uniform. That IS CONFIG_DIVERGED --
        // unlike the PRE-restore mixture, which is the ordinary case a rollback exists for.
        return allAtTarget
                ? land(t, id, RequestState.FAILED_ROLLED_BACK, why)
                : land(t, id, RequestState.CONFIG_DIVERGED, why);
    }

    private StepOutcome<Plan> closeFailed(TenantId t, AttemptRow attempt, ErrorClass why) {
        attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, why, null);
        return StepOutcome.failed(why);
    }

    /** The journal identity of one runtime action (plan 015 T3.1): the intent's attempt-free key is the identity, the attempt is correlation. */
    private static RuntimeOperationContext context(StepIntentRow intent, AttemptRow attempt) {
        return new RuntimeOperationContext(intent.applyId(), intent.intentId(), intent.idempotencyKey(),
                attempt.attemptId(), intent.step());
    }

    private ApplyOutcome land(TenantId t, ApplyId id, RequestState state, ErrorClass why) {
        outcomes.land(t, id, state);
        return new ApplyOutcome.Landed(id, state, why);
    }

    // ============================================================================================
    // Verify-then-retry — plan 010 T6.8. Supersedes resume's "APPLY_INDETERMINATE is skipped on purpose".
    // ============================================================================================

    /**
     * One FRESH observation of the host for an {@code APPLY_INDETERMINATE} request. Reads only: no lock, no write, no
     * lifecycle verb, and every fact is taken from the host now - never from the journal alone.
     *
     * <p>What it reads, per replica of the request's {@code MATERIALIZE} intents: the replica's status (its file as a keyed
     * hash, its start baseline, its declared-probe health), and, once for the placement, the adapter's
     * <b>retained pre-image</b> through {@link RuntimeAdapter#assessRollback} - a read-only operation that mutates nothing,
     * and the one that knows what the file held before this generation's write. {@code PreBaseline} is not that: it is the
     * container's start record, and says nothing about file content.
     *
     * <p>A replica is judged "restarted by this request" exactly as {@link HealthGate} judges it: its current start baseline
     * is present and differs from the {@code pre_baseline} of this request's latest {@code LIFECYCLE} attempt (host-clock
     * values only; no DP timestamp is compared). A replica that would need that judgement and has no readable baseline - not
     * running, or mid-restart (M18) - makes the whole observation {@link HostObservation.Undecided}: it is retried, and a
     * replica nobody could read is never restarted.
     */
    public HostObservation observeHost(TenantId t, ApplyRequestRow row) {
        if (row.operation() != Operation.APPLY || row.scope().placement().isInstanceWide() || row.generation() == null
                || row.strategy() == null) {
            return new HostObservation.NotReconcilable(new ErrorClass("reconcile.unsupported.scope"));
        }
        List<StepIntentRow> journal = intents.intentsOf(t, row.applyId());
        List<StepIntentRow> materialize = journal.stream().filter(i -> i.step() == Step.MATERIALIZE).toList();
        String intended = materialize.stream().map(StepIntentRow::configHash).filter(Objects::nonNull).findFirst()
                .orElse(null);
        if (intended == null) {
            return new HostObservation.NotReconcilable(new ErrorClass("reconcile.no.intent"));
        }
        long generation = row.generation();
        PlacementRef placement = new PlacementRef(row.scope().tenant(), row.scope().instance(), row.scope().placement());

        java.util.Map<ReplicaRef, RuntimeStatus> statuses = new java.util.LinkedHashMap<>();
        for (StepIntentRow intent : materialize) {
            TargetReplica replica = new TargetReplica(placement, intent.replica());
            try {
                RuntimeStatus status = adapter.status(replica);
                if (status.liveness() == Liveness.ABSENT) {
                    return new HostObservation.Undecided(new ErrorClass("reconcile.replica.absent"));
                }
                statuses.put(replica.ref(), status);
            } catch (RuntimeAdapterException e) {
                return new HostObservation.Undecided(e.errorClass());
            } catch (RuntimeException e) {
                // Only a classifier is kept: an adapter's own message can carry a path or a host (I6).
                return new HostObservation.Undecided(new ErrorClass("reconcile.host.unreadable"));
            }
        }

        RollbackAssessment assessment;
        try {
            assessment = adapter.assessRollback(placement, generation, intended);
        } catch (RuntimeAdapterException e) {
            return new HostObservation.Undecided(e.errorClass());
        } catch (RuntimeException e) {
            return new HostObservation.Undecided(new ErrorClass("reconcile.host.unreadable"));
        }
        // What the file held before this generation's write, as the adapter retained it. Q-R1 (a): "the absence was
        // recorded" is a prior. Anything the adapter cannot place (no pre-image was ever retained, or it is retained but
        // the file is some third value) leaves no prior to recognise, so only the intended file is recognisable.
        if (assessment instanceof RollbackAssessment.Unknowable u) {
            // OR-102: a retained copy that changed since it was retained is not a prior to recognise. It is reported,
            // and a file that matches only that copy reads FOREIGN below, so it is never overwritten.
            reportPreImageChange(t, row.applyId(), null, u.change());   // no attempt: a reconcile inspection is not a journal step
        }
        String priorHash = assessment instanceof RollbackAssessment.Restorable r ? r.targetHash() : null;
        boolean priorAbsent = assessment instanceof RollbackAssessment.Unknowable u
                && "rollback.target.absent".equals(u.why().token());

        boolean restarts = row.strategy() != Strategy.MANUAL;
        List<HostObservation.ReplicaFacts> facts = new ArrayList<>();
        for (StepIntentRow intent : materialize) {
            TargetReplica replica = new TargetReplica(placement, intent.replica());
            RuntimeStatus status = statuses.get(replica.ref());
            String current = status.observedHash();
            HostObservation.Content content;
            if (current != null && current.equals(intended)) {
                content = HostObservation.Content.INTENDED;
            } else if (current != null && current.equals(priorHash)) {
                content = HostObservation.Content.PRIOR;
            } else if (current == null && priorAbsent) {
                content = HostObservation.Content.PRIOR;
            } else {
                content = HostObservation.Content.FOREIGN;
            }
            boolean restarted = false;
            if (content == HostObservation.Content.INTENDED && restarts) {
                Optional<PreBaseline> now = status.startBaseline();
                if (now.isEmpty()) {
                    return new HostObservation.Undecided(new ErrorClass("reconcile.restart.undecided"));
                }
                restarted = restartedSince(t, journal, replica, now.get());
            }
            facts.add(new HostObservation.ReplicaFacts(replica, content, current, restarted, status.health(),
                    status.runtimeRef()));
        }
        return new HostObservation.Observed(generation, row.strategy(), intended, facts);
    }

    /**
     * OR-102's report: that the retained pre-apply copy of the config changed since it was retained, and which declared
     * non-secret paths differ. {@code I6}: {@link PreImageChange} holds paths and a flag, never a value, and a
     * secret-flagged path is never in it. A {@code null} change (nothing to report, or no trustworthy record to compare
     * with) logs nothing: no claim is made about a copy nobody can check.
     */
    private void reportPreImageChange(TenantId t, ApplyId id, UUID attemptId, PreImageChange change) {
        if (change == null) {
            return;
        }
        LOG.warn("apply {} of tenant {}: {}", id, t, change.describe());
        if (attemptId == null) {
            return;
        }
        // OR-108 (1): and keep it with the attempt that found it, so the console can name the fields. Best effort and
        // after the attempt is closed: a failure to keep the evidence must never turn a refusal into a crash, and the
        // log line above already carries it. Only the exception's class is logged (I6).
        try {
            attempts.recordPreImageChange(t, attemptId,
                    ChangedContent.of(change.declaredPathsChanged(), change.otherContentChanged()));
        } catch (RuntimeException e) {
            LOG.warn("apply {} of tenant {}: the changed pre-image paths were not kept with attempt {} ({})", id, t,
                    attemptId, e.getClass().getSimpleName());
        }
    }

    /**
     * {@link HealthGate}'s test, applied to the journal: the replica's current baseline differs from the one this request's
     * latest {@code LIFECYCLE} attempt recorded before it acted. No such attempt (or an intent with no attempt) means the
     * verb provably was not issued by this request. A {@code null} recorded baseline means the replica was not running
     * before the verb, so being up now is the restart.
     */
    private boolean restartedSince(TenantId t, List<StepIntentRow> journal, TargetReplica replica, PreBaseline now) {
        Optional<AttemptRow> attempt = journal.stream()
                .filter(i -> i.step() == Step.LIFECYCLE && i.replica().equals(replica.ref())).findFirst()
                .flatMap(i -> attempts.latest(t, i.intentId()));
        if (attempt.isEmpty()) {
            return false;
        }
        PreBaseline before = attempt.get().preBaseline();
        return before == null || !now.startedAt().equals(before.startedAt());
    }

    /**
     * Records that a gated confirmation observed the declared probe {@code HEALTHY} on a replica this request restarted
     * (Q-R2 (a)): one {@code LIFECYCLE} attempt per such replica, opened and closed {@code SUCCEEDED} with the read-back
     * hash. {@code observeApply} derives {@code gateProved} from a succeeded {@code LIFECYCLE} step - which, under
     * {@code rolling-health-gated}, <i>is</i> a passed gate - so this is how the proof becomes visible. The attempt issues no
     * verb: it is the journal's record of an observation, and says so by carrying no {@code pre_baseline}.
     *
     * <p>Best effort. A replica whose latest {@code LIFECYCLE} attempt already succeeded needs none, and one whose intent
     * has spent its attempt cap, or has an attempt open, is skipped: the confirmation stands on the evidence it carries
     * (the restarted flag and the read-back hash), and the proof is simply not claimed in the step list.
     */
    public void recordGateProof(TenantId t, ApplyId id, List<HostObservation.ReplicaFacts> proved) {
        for (HostObservation.ReplicaFacts facts : proved) {
            StepIntentRow intent = intents.ensureIntent(t, id, facts.replica().placement().placement(),
                    Step.LIFECYCLE, facts.replica().ref(),
                    idempotencyKey(id, Step.LIFECYCLE, facts.replica().ref()), null);
            Optional<AttemptRow> latest = attempts.latest(t, intent.intentId());
            if (latest.isPresent() && latest.get().outcome() == AttemptOutcome.SUCCEEDED) {
                continue;
            }
            try {
                AttemptRow attempt = attempts.open(t, id, intent, null, props.stepBudget());
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, facts.currentHash(), null,
                        facts.runtimeRef());
            } catch (AttemptService.RetryCapExceeded | AttemptService.ConcurrentAttempt e) {
                // not claimed - see above
            }
        }
    }

    /**
     * Runs a re-drive on a request the reconciler has walked to {@code RUNNING}
     * ({@link OutcomeService#beginRedrive}); it ends terminal, or back on {@code APPLY_INDETERMINATE}.
     *
     * <p>A re-drive is the ORIGINAL request's steps again, as new attempts on the same intents - one fleet member, one
     * journal line, one apply-outcome per stamp - in the same order the forward path runs them: the conditional
     * re-writes for every replica that needs one (stage everywhere), then, unless the strategy is {@code manual}, the
     * restarts the plan names through the very loop the forward path uses ({@link #restartAndGate}), each gated and
     * verified as the strategy requires. Under {@code manual} the normal landing is {@code STAGED}.
     *
     * <p><b>It never enters {@code BE-D22}'s rollback.</b> A failed re-write is a re-drive that was cut off again
     * ({@code APPLY_INDETERMINATE}, inspected again, bounded by {@code max-redrives}); a file that moved since the
     * observation is content SM did not write ({@code reconcile.foreign.content}, a give-up); and the per-intent attempt cap
     * is a give-up ({@code reconcile.redrive.cap}). Restoring over a replica a human or another process touched is exactly
     * what this task exists not to do.
     */
    public RedriveResult redrive(TenantId t, ApplyId id, RedrivePlan plan) {
        ApplyRequestRow row = outcomes.requestOf(t, id);
        if (row.state() != RequestState.RUNNING) {
            throw new IllegalStateException("redrive expects a request the reconciler already walked to RUNNING; "
                    + id.value() + " is " + row.state());
        }
        try {
            // The request was APPLY_INDETERMINATE, so no worker was executing it: an attempt the dead run left open is
            // abandoned, and attempt_open_fence would otherwise refuse the new one.
            for (StepIntentRow intent : intents.intentsOf(t, id)) {
                settle(t, openAttemptOf(t, intent), AttemptOutcome.TIMED_OUT, null,
                        new ErrorClass("reconcile.attempt.abandoned"));
            }

            // What was running BEFORE this re-drive touches anything: the same fact pre-flight captures, and what lets the
            // restart loop tell a replica taken down underneath it (obligation (r)) from one that was already down.
            Set<ReplicaRef> running = new LinkedHashSet<>();
            for (RedrivePlan.Step step : plan.steps()) {
                if (!step.restart()) {
                    continue;
                }
                try {
                    if (adapter.status(step.replica()).liveness() == Liveness.RUNNING) {
                        running.add(step.replica().ref());
                    }
                } catch (RuntimeAdapterException e) {
                    return redriveLanded(t, id, e.errorClass());
                }
            }

            RenderedConfig config = null;
            if (plan.anyRewrite()) {
                PlacementRef placement = new PlacementRef(row.scope().tenant(), row.scope().instance(),
                        row.scope().placement());
                try {
                    config = configs.render(placement, plan.generation());
                } catch (RuntimeException e) {
                    return redriveLanded(t, id, new ErrorClass("render.failed"));   // I6: the message is never read
                }
                if (!plan.intendedHash().equals(config.hash())) {
                    // What the generation renders to now is not what the intents bound: writing it would be writing bytes
                    // the journal never promised.
                    return redriveGaveUp(t, id, new ErrorClass("reconcile.render.drift"));
                }
            }
            for (RedrivePlan.Step step : plan.steps()) {
                if (!step.rewrite()) {
                    continue;
                }
                ErrorClass failure = rematerialize(t, id, step, plan.generation(), config);
                if (failure != null) {
                    return "reconcile.target.moved".equals(failure.token())
                            ? redriveGaveUp(t, id, new ErrorClass("reconcile.foreign.content"))
                            : redriveLanded(t, id, failure);
                }
            }
            if (plan.strategy() == Strategy.MANUAL) {
                return redriveResult(land(t, id, RequestState.STAGED, null));
            }
            List<TargetReplica> restart = plan.steps().stream().filter(RedrivePlan.Step::restart)
                    .map(RedrivePlan.Step::replica).toList();
            return redriveResult(restartAndGate(t, id, plan.strategy() == Strategy.ROLLING_HEALTH_GATED,
                    plan.generation(), restart, running, plan.intendedHash()));
        } catch (AttemptService.RetryCapExceeded e) {
            return redriveGaveUp(t, id, new ErrorClass("reconcile.redrive.cap"));
        } catch (AttemptService.ConcurrentAttempt e) {
            return redriveLanded(t, id, new ErrorClass("reconcile.attempt.unavailable"));
        }
    }

    /** One replica's conditional re-write, journaled as another attempt of its original {@code MATERIALIZE} intent. */
    private ErrorClass rematerialize(TenantId t, ApplyId id, RedrivePlan.Step step, long generation,
            RenderedConfig config) {
        TargetReplica replica = step.replica();
        StepIntentRow intent = intents.ensureIntent(t, id, replica.placement().placement(),
                Step.MATERIALIZE, replica.ref(),
                idempotencyKey(id, Step.MATERIALIZE, replica.ref()), config.hash());
        AttemptRow attempt = attempts.open(t, id, intent, null, props.stepBudget());
        try {
            MaterializeResult result = adapter.rematerializeIfCurrent(replica, generation, config,
                    step.expectedCurrentHash(), context(intent, attempt));
            // A return value is not evidence: only a read-back closes the attempt SUCCEEDED (rule 2), as in materialize().
            RuntimeStatus status = adapter.status(replica);
            String observed = status.observedHash();
            if (!intent.configHash().equals(result.writtenHash()) || !intent.configHash().equals(observed)) {
                ErrorClass mismatch = new ErrorClass(observed == null ? "materialize.readback.unavailable"
                        : "materialize.hash.mismatch");
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, observed, mismatch, result.runtimeRef());
                return mismatch;
            }
            // The container start the host reported at this read-back is kept with the write, exactly as materialize() keeps it
            // (OR-112 (1)): dp-remote records status.baseline(); the agent path records the ack's start of the intent's newest
            // attempt that has one (AGW-D28 item 2, OR-139), so a re-drive retrying after a read-back miss still carries it
            // (plan 015 W4 note (d); BE-D34, clarified).
            attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, observed, null, result.runtimeRef(),
                    WriteBaselinePolicy.of(adapter, replica.placement(), intent.intentId(), status));
            rollout(t, id, replica.ref(), RolloutStep.STAGED);
            return null;
        } catch (RuntimeAdapterException e) {
            attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica), e.errorClass(), null);
            return e.errorClass();
        }
    }

    private RedriveResult redriveLanded(TenantId t, ApplyId id, ErrorClass why) {
        return redriveResult(land(t, id, RequestState.APPLY_INDETERMINATE, why));
    }

    private RedriveResult redriveGaveUp(TenantId t, ApplyId id, ErrorClass why) {
        outcomes.recordReconcileGiveUp(t, id, why);
        return new RedriveResult(RequestState.APPLY_INDETERMINATE, why, true);
    }

    private static RedriveResult redriveResult(ApplyOutcome outcome) {
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) outcome;
        return new RedriveResult(landed.state(), landed.errorClass(), false);
    }

    // ============================================================================================
    // Resume — design §2.2 rule 3, and the reason every write in this class precedes its action.
    // ============================================================================================

    /**
     * Picks up every apply of one tenant that a crash left in flight.
     *
     * <p><b>Nothing calls this on startup.</b> There is no application class in this build — no
     * {@code main}, no scheduler, and {@code G2} forbids writing one — so this is a public method
     * with tests and no production caller. Stated as a limit rather than dressed up as a feature
     * ({@code L19}): the resume path is implemented and proven; nothing invokes it yet.
     *
     * <p>{@code APPLY_INDETERMINATE} requests are still not resumed HERE - but no longer because nobody looks at them.
     * Plan 010 T6.8 (owner ruling verify-then-retry, {@code OR-59}) supersedes plan 005's "recovery never touches them"
     * (flagged, not ratified). Resuming one from the journal would still be the engine confirming its own uncertainty, so
     * they are handed to {@code IndeterminateReconciler} (startup enqueues, the worker's poll runs them), which decides
     * from a FRESH observation of the host and never from the journal alone. See {@link #observeHost} and
     * {@link #redrive}.
     */
    public List<ApplyOutcome> resume(TenantId t) {
        return resume(t, null);
    }

    /**
     * The same sweep, narrowed to one scope ({@code BE-D26}). An operator recovering one instance
     * should not have to sweep a tenant.
     *
     * <p>{@code ScopeKey.overlaps} gives instance-wide its usual meaning, so a scope naming the
     * nil sentinel resumes every request of that instance, and one naming a placement resumes that
     * placement's own requests plus any instance-wide request covering it.
     */
    public List<ApplyOutcome> resume(ScopeKey scope) {
        return resume(scope.tenant(), scope);
    }

    /**
     * Task 4b's startup sweep: {@code RUNNING} rows only, for one tenant.
     *
     * <p>A crashed worker leaves exactly one kind of row behind — one whose claim committed and
     * whose execution never reached a landing. That is {@code RUNNING}, and only {@code RUNNING}:
     * a row still {@code REQUESTED} needs no sweep at all, because it is indistinguishable from one
     * simply not yet claimed, and the ordinary poll (Task 4a) picks it up on its own. So this method
     * filters to {@code RUNNING} explicitly rather than reusing {@link #resume(TenantId)} — which
     * would also re-enter {@code REQUESTED} rows via {@code run()} directly, bypassing the claim
     * entirely and racing a worker that polls the same row a moment later.
     *
     * <p><b>Never calls {@code outcomes.start}</b> — the dead worker's claim already performed that
     * transition before it crashed; this sweep only ever hands a row to {@link #resumeOne}, exactly
     * as {@link #resume(TenantId)} does. No second state machine: same decision table, same
     * {@code resumeOne}/{@code strand}, narrowed to one state.
     */
    public List<ApplyOutcome> resumeRunning(TenantId t) {
        List<ApplyOutcome> resumed = new ArrayList<>();
        for (ApplyRequestRow row : resumableRunning(t)) {
            resumed.add(resumeRunningOne(t, row));
        }
        return resumed;
    }

    /**
     * The rows {@link #resumeRunning(TenantId)} would hand to {@code resumeOne}: this tenant's {@code RUNNING} requests, less a
     * restart (the reconciler's, not a crash's) and an instance-wide apply (refused before admission, {@code BE-D45}). Exposed so
     * the startup sweep can decide per request WHEN to resume it (OR-146 (2): an agent-target request waits, bounded, for its
     * agent's session) without a second filter that could drift from this one.
     */
    public List<ApplyRequestRow> resumableRunning(TenantId t) {
        List<ApplyRequestRow> rows = new ArrayList<>();
        for (ApplyRequestRow row : outcomes.nonTerminal(t)) {
            if (row.state() == RequestState.RUNNING
                    && row.operation() != Operation.RESTART
                    && !(row.operation() == Operation.APPLY && row.scope().placement().isInstanceWide())) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** One row of {@link #resumableRunning}: resumed, or stranded {@code APPLY_INDETERMINATE} when it cannot be. Never throws. */
    public ApplyOutcome resumeRunningOne(TenantId t, ApplyRequestRow row) {
        try {
            return resumeOne(t, row);
        } catch (RuntimeException e) {
            // Isolated per request, exactly as resume() is: one poisoned row must not abort the
            // sweep for the rest of the tenant's in-flight work.
            return strand(t, row);
        }
    }

    private List<ApplyOutcome> resume(TenantId t, ScopeKey only) {
        List<ApplyOutcome> resumed = new ArrayList<>();
        for (ApplyRequestRow row : outcomes.nonTerminal(t)) {
            if (row.state() == RequestState.APPLY_INDETERMINATE
                    || row.operation() == Operation.RESTART
                    || (only != null && !only.overlaps(row.scope()))
                    || (row.operation() == Operation.APPLY
                            && row.scope().placement().isInstanceWide())) {
                continue;
            }
            try {
                resumed.add(resumeOne(t, row));
            } catch (RuntimeException e) {
                // A sweep that stops at the first request it cannot recover recovers nothing: the
                // same request is re-encountered next time, so one poisoned row would block every
                // other in-flight apply of this tenant forever. The classifier is FIXED here and
                // the exception's own text is never read (I6) -- a repository failure's message
                // can carry a URL, and an adapter's can carry a path.
                resumed.add(strand(t, row));
            }
        }
        return resumed;
    }

    /**
     * A request resume could not make progress on. {@code APPLY_INDETERMINATE} is the honest state
     * and the one with an operator exit ({@code resolveIndeterminate}); leaving it
     * {@code RUNNING} would block its scope with nothing an operator could do about it. Since plan 010 T6.8 it is also
     * the reconciler's input: a stranded request is observed against the host and confirmed, re-driven or given up on,
     * bounded and alerting (the supersession noted on {@link #resume(TenantId)}).
     */
    private ApplyOutcome strand(TenantId t, ApplyRequestRow row) {
        ErrorClass why = new ErrorClass("resume.failed");
        // state_fits_operation gives a stop no APPLY_INDETERMINATE: its four states are REQUESTED,
        // RUNNING, STOPPED and STOP_FAILED. Landing the wrong one here would throw inside the very
        // method that exists to stop an exception escaping, and leave the emergency lever RUNNING.
        RequestState stranded = row.operation() == Operation.STOP
                ? RequestState.STOP_FAILED
                : RequestState.APPLY_INDETERMINATE;
        try {
            return land(t, row.applyId(), stranded, why);
        } catch (RuntimeException unlandable) {
            // Even the landing failed -- report what the row still says rather than throwing out
            // of the sweep, which is the failure this method exists to prevent.
            return new ApplyOutcome.Landed(row.applyId(), row.state(), why);
        }
    }

    /**
     * One request.
     *
     * <p><b>Routed by PHASE, not by the frontier's step.</b> Once any of {@code BE-D22}'s three
     * steps has been journalled, the apply has given up on its generation: re-entering the step
     * loop would re-materialize that generation — possibly over a restore that had already
     * completed — and would assess a second time, which after a partial restore reads the restored
     * file as the current state and concludes {@code NothingToRestore}. An earlier version of this
     * method routed on the frontier alone and did exactly that for a {@code VERIFY} frontier.
     *
     * <p>Within a phase the <b>frontier</b> — the newest intent — is the only step whose fate is in
     * doubt, because rule 0 commits each intent before its action and the loop is sequential: every
     * earlier intent has a closed attempt, and every later step has no intent at all.
     */
    private ApplyOutcome resumeOne(TenantId t, ApplyRequestRow row) {
        ApplyId id = row.applyId();
        ScopeKey scope = row.scope();
        List<StepIntentRow> intents = this.intents.intentsOf(t, id);

        // A stop is wholly idempotent -- every step is STOP, ensureIntent returns the rows that
        // exist, and an adapter reports a replica already stopped as success without acting
        // (§2.2 rule 2). Resume therefore re-runs it from the top, exactly as it re-runs a
        // PREFLIGHT, once the attempt a crash left open is settled. The decision table's two STOP
        // rows describe the same answer and are what ResumePlannerTest walks.
        if (row.operation() == Operation.STOP) {
            if (!intents.isEmpty()) {
                settle(t, openAttemptOf(t, intents.get(intents.size() - 1)),
                        AttemptOutcome.TIMED_OUT, null,
                        new ErrorClass("resume.attempt.abandoned"));
            }
            return runStop(t, id, scope);
        }

        if (intents.isEmpty()) {
            ApplyCommand command = new ApplyCommand(scope, row.operation(), row.generation(),
                    row.strategy(), row.outageConfirmedBy(), row.requestedBy());
            return run(t, id, command);      // it died before the first intent: nothing was issued
        }
        // Safe only past the stop branch: an instance-wide stop's scope has no PlacementRef, and
        // PlacementRef refuses the sentinel by construction.
        PlacementRef placement =
                new PlacementRef(scope.tenant(), scope.instance(), scope.placement());

        // The replica set comes from the JOURNAL, not from a fresh prepare(): the MATERIALIZE
        // intents name every replica this apply addressed, and they survived the crash. Asking the
        // runtime again would let a replica that appeared or vanished while the DP was down change
        // what resume thinks this apply was doing.
        List<TargetReplica> replicas = intents.stream()
                .filter(i -> i.step() == Step.MATERIALIZE)
                .map(i -> new TargetReplica(placement, i.replica())).toList();
        String intendedHash = intents.stream().filter(i -> i.step() == Step.MATERIALIZE)
                .map(StepIntentRow::configHash).filter(Objects::nonNull).findFirst().orElse(null);

        // By ROLLBACK_ASSESS / ROLLBACK only. Every VERIFY of BE-D22's tail follows its ROLLBACK_ASSESS (the assessment is
        // journalled first), so those two still send a crash inside that VERIFY sweep to the tail; but a VERIFY alone is
        // also the FORWARD path's post-restart read-back (T6.9), and routing that to the rollback tail would assess a
        // restore over a replica the apply had finished.
        if (intents.stream().anyMatch(i -> i.step() == Step.ROLLBACK_ASSESS
                || i.step() == Step.ROLLBACK)) {
            return resumeExhaustion(t, row, placement, intents, replicas, intendedHash);
        }

        StepIntentRow frontier = intents.get(intents.size() - 1);
        Optional<AttemptRow> open = openAttemptOf(t, frontier);
        List<RuntimeStatus> observed = observe(frontier, placement, replicas);
        StepDecision decision = planner.decide(frontier, open, observed, intendedHash);

        if (decision == StepDecision.INDETERMINATE) {
            settle(t, open, AttemptOutcome.TIMED_OUT, firstHash(observed),
                    new ErrorClass("resume.state.unknown"));
            return land(t, id, RequestState.APPLY_INDETERMINATE,
                    new ErrorClass("resume.state.unknown"));
        }
        settle(t, open,
                decision == StepDecision.CONTINUE ? AttemptOutcome.SUCCEEDED
                        : AttemptOutcome.TIMED_OUT,
                firstHash(observed),
                decision == StepDecision.CONTINUE ? null
                        : new ErrorClass("resume.attempt.abandoned"));
        return run(t, id, new ApplyCommand(scope, row.operation(), row.generation(),
                row.strategy(), row.outageConfirmedBy(), row.requestedBy()));
    }

    /**
     * {@code BE-D22}'s tail, resumed — whichever of its three steps the crash interrupted, and
     * whether or not it left an attempt open.
     *
     * <p>Two shapes. With no {@code ROLLBACK} intent yet, the crash landed on or before the
     * assessment: that step is read-only, no destructive call has been journalled, and re-running
     * the whole tail is free and deterministic. With a {@code ROLLBACK} intent, its own
     * {@code config_hash} is the restore target — written <b>before</b> the destructive call — so
     * the tail finishes by comparing bytes against it, and never assesses again.
     */
    private ApplyOutcome resumeExhaustion(TenantId t, ApplyRequestRow row, PlacementRef placement,
            List<StepIntentRow> intents, List<TargetReplica> replicas, String intendedHash) {
        ApplyId id = row.applyId();
        ErrorClass why = originalFailure(t, id);
        StepIntentRow frontier = intents.get(intents.size() - 1);
        Optional<AttemptRow> open = openAttemptOf(t, frontier);

        Optional<StepIntentRow> rollbackIntent =
                intents.stream().filter(i -> i.step() == Step.ROLLBACK).findFirst();
        if (rollbackIntent.isEmpty()) {
            settle(t, open, AttemptOutcome.TIMED_OUT, null,
                    new ErrorClass("resume.attempt.abandoned"));
            return exhausted(t, id, placement, replicas, row.generation(), intendedHash, why);
        }

        StepIntentRow rollback = rollbackIntent.get();
        String targetHash = rollback.configHash();
        List<RuntimeStatus> observed = observeAll(placement, replicas);
        StepDecision decision = planner.decideRollback(rollback, observed, intendedHash);

        boolean restored = decision == StepDecision.CONTINUE && frontier.step() == Step.ROLLBACK;
        settle(t, open, restored ? AttemptOutcome.SUCCEEDED : AttemptOutcome.TIMED_OUT, null,
                restored ? null : new ErrorClass("resume.attempt.abandoned"));

        if (decision == StepDecision.INDETERMINATE) {
            return land(t, id, RequestState.APPLY_INDETERMINATE,
                    new ErrorClass("rollback.state.unknown"));
        }

        ErrorClass restoreFailure = null;
        if (decision == StepDecision.REISSUE) {
            AttemptRow attempt = attempts.open(t, id, rollback, null, props.stepBudget());
            try {
                RestoreResult result = adapter.restore(placement, row.generation(), targetHash);
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, null, null,
                        result.runtimeRef());
            } catch (RuntimeAdapterException e) {
                restoreFailure = e.errorClass();
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, null, restoreFailure,
                        null);
                reportPreImageChange(t, id, attempt.attemptId(), e.preImageChange().orElse(null));
            }
        }
        return verifyRestore(t, id, replicas, targetHash, intendedHash, why, restoreFailure);
    }

    /** The attempt a crash left open on this intent, if there is one. */
    private Optional<AttemptRow> openAttemptOf(TenantId t, StepIntentRow intent) {
        return attempts.latest(t, intent.intentId()).filter(a -> a.outcome() == null);
    }

    /**
     * Closes the attempt a crash left open, so the step that follows may open a fresh one —
     * {@code attempt_open_fence} admits exactly one open attempt per intent. {@code TIMED_OUT}
     * rather than {@code FAILED} wherever the work is redone: the attempt did not fail, it never
     * reported, which is what a step deadline is for.
     */
    private void settle(TenantId t, Optional<AttemptRow> open, AttemptOutcome outcome,
            String observedHash, ErrorClass why) {
        open.ifPresent(a -> attempts.close(t, a.attemptId(), outcome, observedHash, why, null));
    }

    /**
     * What the runtime says about the replicas this step addresses. Read-only, and never allowed to
     * throw: an unreadable replica is an observation with no hash, which is what the decision table
     * calls unreadable.
     */
    private List<RuntimeStatus> observe(StepIntentRow frontier, PlacementRef placement,
            List<TargetReplica> replicas) {
        return frontier.step().placementScoped()
                ? observeAll(placement, replicas)
                : observeAll(placement, List.of(new TargetReplica(placement, frontier.replica())));
    }

    private List<RuntimeStatus> observeAll(PlacementRef placement, List<TargetReplica> replicas) {
        List<RuntimeStatus> observed = new ArrayList<>();
        for (TargetReplica replica : replicas) {
            try {
                observed.add(adapter.status(replica));
            } catch (RuntimeException e) {
                return List.of();   // could not observe: the table's INDETERMINATE, not a guess
            }
        }
        return observed;
    }

    /**
     * Why the apply was rolling back, read out of the journal rather than carried in memory — the
     * crash is precisely the thing that destroyed the in-memory copy.
     */
    private ErrorClass originalFailure(TenantId t, ApplyId id) {
        return intents.intentsOf(t, id).stream()
                .filter(i -> i.step() == Step.MATERIALIZE)
                .map(i -> attempts.latest(t, i.intentId()).map(AttemptRow::errorClass).orElse(null))
                .filter(Objects::nonNull).findFirst()
                .orElse(new ErrorClass("resume.cause.unrecorded"));
    }

    /**
     * Deterministic, so resume recomputes the same key rather than minting a second one for a step
     * that already has an intent. The apply id is a UUID, which is what makes the column's GLOBAL
     * {@code UNIQUE} satisfiable without a registry.
     *
     * <p>The key of a <b>placement-scoped</b> request: {@code applyId:step:replica}. Its {@code apply_id} already fixes
     * the placement, so the format is unchanged since before {@code V021} ({@code BE-D44}) — which is exactly what lets
     * a request admitted before that migration resume onto its own intent rows, because
     * {@link StepIntentService}'s read-compare requires key equality. Every step but an instance-wide stop's uses this.
     *
     * <p><b>Why the non-stop sites use this overload and not the scope-aware one ({@code BE-D45}).</b> Every step but
     * STOP belongs to an apply or its rollback; an instance-wide apply is refused before admission (and restart is unsupported), so
     * their {@code apply_id} always fixes the placement and this key is the right one. Only STOP can be instance-wide:
     * it alone calls the scope-aware overload below. A new step that can run under an instance-wide request must use it.
     */
    public static String idempotencyKey(ApplyId id, Step step, ReplicaRef replica) {
        return id.value() + ":" + step + ":" + replica.value();
    }

    /**
     * The key of the intent for {@code step}/{@code replica} in {@code placement}, under a request of {@code scope}
     * ({@code BE-D44}): {@code applyId:step:replica} for a placement-scoped request (unchanged, see the three-argument
     * overload), and {@code applyId:placementId:step:replica} for an instance-wide one, whose one {@code apply_id}
     * addresses several placements that may name their replicas alike. The two formats cannot collide on the column's
     * global {@code UNIQUE}: one {@code apply_id} is either instance-wide or it is not, and a UUID cannot be both.
     */
    public static String idempotencyKey(ApplyId id, ScopeKey scope, PlacementId placement, Step step,
            ReplicaRef replica) {
        return scope.placement().isInstanceWide()
                ? id.value() + ":" + placement.value() + ":" + step + ":" + replica.value()
                : idempotencyKey(id, step, replica);
    }
}
