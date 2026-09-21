package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
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
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

/**
 * The apply step loop — {@code BE-D12} §2.2's safety properties, driven over plan 004's persistence
 * and one {@link RuntimeAdapter}.
 *
 * <p><b>Write before act</b> (rule 0): the intent row and the attempt row for the try about to run
 * are committed before the adapter is invoked, so an intent with no attempt row provably issued
 * nothing. Every service this class calls runs {@code REQUIRES_NEW} already, so "committed" is a
 * fact about the call returning rather than a hope about an enclosing transaction.
 *
 * <p><b>Single placement</b> ({@code BE-D23}): an instance-wide request is refused before admission,
 * because {@code apply_step_intent}'s identity cannot hold two placements' placement-scoped steps
 * under one {@code apply_id}.
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

    private final AdmissionService admission;
    private final StepIntentService intents;
    private final AttemptService attempts;
    private final OutcomeService outcomes;
    private final RuntimeAdapter adapter;
    private final RenderedConfigSource configs;
    private final HealthGate gate;
    private final ResumePlanner planner;
    private final ApplyEngineProperties props;

    /**
     * The strategies this build executes. {@code SCHEDULED} is absent for the whole of plan 005:
     * it needs a scheduler, and there is no application class to host one.
     */
    private static final Set<Strategy> SUPPORTED = EnumSet.of(Strategy.MANUAL,
            Strategy.ROLLING_HEALTH_GATED, Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL);

    public ApplyExecutor(AdmissionService admission, StepIntentService intents,
            AttemptService attempts, OutcomeService outcomes, RuntimeAdapter adapter,
            RenderedConfigSource configs, HealthGate gate, ResumePlanner planner,
            ApplyEngineProperties props) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.configs = Objects.requireNonNull(configs, "configs");
        this.gate = Objects.requireNonNull(gate, "health gate");
        this.planner = Objects.requireNonNull(planner, "resume planner");
        this.props = Objects.requireNonNull(props, "props");
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

    public ApplyOutcome execute(ApplyCommand command) {
        // A restart REQUEST is a different slice: no generation to bind, so no MATERIALIZE step and
        // a lifecycle journal with nothing to compare a hash against. Plan 005 does not open it.
        if (command.operation() == Operation.RESTART) {
            return new ApplyOutcome.OperationUnsupported(command.operation(),
                    "this build's executor drives apply and stop requests");
        }
        if (command.operation() == Operation.APPLY) {
            // SM-D15: "SM offers only strategies the solution's declared capabilities support."
            // Refused BEFORE admission, so no row is written for a request that was never going
            // to run.
            if (!SUPPORTED.contains(command.strategy())) {
                return new ApplyOutcome.StrategyUnsupported(command.strategy(),
                        "this build executes " + SUPPORTED + ", and scheduled applies need a "
                                + "scheduler no deployable exists to host");
            }
            // BE-D23: apply_step_intent's identity is placement-blind -- UNIQUE (tenant_id,
            // apply_id, step, replica_ref) with placement_scoped_steps pinning replica_ref to
            // '@placement' admits at most ONE PREFLIGHT intent per apply_id. A fan-out would
            // journal placement B's pre-flight as a retry of placement A's, and would have
            // nowhere to record B's rollback.
            //
            // BE-D26: this limit is NOT the same as "instance-wide is impossible". It is a
            // property of the placement-scoped steps, and a stop journals none of them -- so the
            // refusal below is deliberately not applied to a stop.
            if (command.scope().placement().isInstanceWide()) {
                return new ApplyOutcome.ScopeUnsupported(command.scope(),
                        "the v1 journal cannot hold per-placement steps for one instance-wide "
                                + "apply");
            }
        }

        AdmissionOutcome admitted = admission.admit(command);
        if (admitted instanceof AdmissionOutcome.Refused r) {
            return new ApplyOutcome.NotAdmitted(r.reason(), r.detail());
        }
        ApplyId id = ((AdmissionOutcome.Admitted) admitted).applyId();
        TenantId t = command.scope().tenant();
        outcomes.start(t, id);
        return command.operation() == Operation.STOP
                ? runStop(t, id, command.scope())
                : run(t, id, command);
    }

    /**
     * {@code BE-D26}'s stop: scope-granular, one {@code STOP} intent per replica.
     *
     * <p><b>Instance-wide is admissible here and not for an apply</b>, and the asymmetry is
     * structural rather than a concession: {@code placement_scoped_steps} pins {@code replica_ref}
     * to {@code '@placement'} for {@code PREFLIGHT}, {@code ROLLBACK} and {@code ROLLBACK_ASSESS},
     * which is what makes an instance-wide apply unjournalable — and a stop writes none of those
     * steps. {@code UNIQUE (tenant_id, apply_id, step, replica_ref)} then admits one {@code STOP}
     * per replica across any number of placements under one {@code apply_id}.
     *
     * <p>No pre-flight, no generation, no rendering. The replica set comes from
     * {@link RuntimeAdapter#replicas} ({@code BE-D25}), which exists because {@code prepare} is
     * generation-bearing and a stop has no generation.
     */
    private ApplyOutcome runStop(TenantId t, ApplyId id, ScopeKey scope) {
        List<PlacementId> placements = scope.placement().isInstanceWide()
                ? admission.placementsOf(scope)
                : List.of(scope.placement());
        if (placements.isEmpty()) {
            // An instance SM has never been asked to act on: nothing was addressed, so nothing is
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

        // ENUMERATE EVERYTHING FIRST, and act only if the whole set is journalable.
        //
        // BE-D23 has a second clause that BE-D26's first draft did not carry over, and an
        // independent review caught the omission: replica-scoped steps are keyed by `replica_ref`
        // ALONE within the apply, and `replica_ref` is adapter vocabulary (BE-D19) -- "two
        // placements whose adapter produces the same label collapse onto one intent". STOP is a
        // replica-scoped step, so an instance-wide stop over two placements whose adapters both
        // say `cache-engine` (the SSH adapter's replica ref is the configured container name, not
        // a daemon-unique id) would journal the second placement's stop as a RETRY of the first's
        // -- same key, both hashes null, so not even an IntentMismatch to notice it by.
        //
        // Encoding the placement into `replica_ref` is exactly what BE-D23 forbids. So the engine
        // refuses instead, before writing anything or stopping anything.
        ErrorClass failure = null;
        List<TargetReplica> targets = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
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
            for (TargetReplica replica : found) {
                if (!labels.add(replica.ref().value())) {
                    return land(t, id, RequestState.STOP_FAILED,
                            new ErrorClass("stop.replica.label.collision"));
                }
                targets.add(replica);
            }
        }

        for (TargetReplica replica : targets) {
            ErrorClass stopped = stopOne(t, id, replica);
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
    private ErrorClass stopOne(TenantId t, ApplyId id, TargetReplica replica) {
        StepIntentRow intent = intents.ensureIntent(t, id, Step.STOP, replica.ref(),
                idempotencyKey(id, Step.STOP, replica.ref()), null);
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
            LifecycleResult result = adapter.stop(replica);
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
            return land(t, id, RequestState.FAILED_NO_EFFECT, preflight.errorClass());
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
        long generation = command.generation();
        boolean gated = command.strategy() == Strategy.ROLLING_HEALTH_GATED;

        for (TargetReplica replica : plan.replicas()) {
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
            boolean wasRunning = plan.runningAtPreflight().contains(replica.ref());
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
                StepIntentRow halted = intents.ensureIntent(t, id, Step.LIFECYCLE, replica.ref(),
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

            StepIntentRow intent = intents.ensureIntent(t, id, Step.LIFECYCLE, replica.ref(),
                    idempotencyKey(id, Step.LIFECYCLE, replica.ref()), null);
            AttemptRow attempt = attempts.open(t, id, intent, before, props.stepBudget());

            LifecycleResult issued;
            try {
                issued = adapter.startOrUpdate(replica, generation);
            } catch (RuntimeAdapterException e) {
                attempts.close(t, attempt.attemptId(), AttemptOutcome.FAILED, readBack(replica),
                        e.errorClass(), null);
                return land(t, id, RequestState.CONFIG_DIVERGED, e.errorClass());
            }

            if (!gated) {
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, readBack(replica),
                        null, issued.runtimeRef());
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
        }
        return land(t, id, RequestState.APPLIED, null);
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
        StepIntentRow intent = intents.ensureIntent(t, id, Step.PREFLIGHT, ReplicaRef.PLACEMENT,
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
            result = adapter.prepare(placement, generation);
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
        StepIntentRow intent = intents.ensureIntent(t, id, Step.MATERIALIZE, replica.ref(),
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
                MaterializeResult result = adapter.materializeConfig(replica, generation, config);
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
                attempts.close(t, attempt.attemptId(), AttemptOutcome.SUCCEEDED, observed, null,
                        result.runtimeRef());
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
        StepIntentRow assessIntent = intents.ensureIntent(t, id, Step.ROLLBACK_ASSESS,
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
            return land(t, id, RequestState.APPLY_INDETERMINATE, unknowable.why());
        }
        String targetHash = ((RollbackAssessment.Restorable) assessment).targetHash();

        // The ROLLBACK intent carries the target hash and is COMMITTED before restore() is called.
        // That single ordering is what makes a crash in the window decidable rather than a guess.
        StepIntentRow rollbackIntent = intents.ensureIntent(t, id, Step.ROLLBACK,
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
            StepIntentRow intent = intents.ensureIntent(t, id, Step.VERIFY, replica.ref(),
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

    private ApplyOutcome land(TenantId t, ApplyId id, RequestState state, ErrorClass why) {
        outcomes.land(t, id, state);
        return new ApplyOutcome.Landed(id, state, why);
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
     * <p>{@code APPLY_INDETERMINATE} requests are skipped on purpose. They block the scope until an
     * operator resolves them (§3.4), and resuming one would be the engine confirming its own
     * uncertainty.
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
     * and the only one with an operator exit ({@code resolveIndeterminate}); leaving it
     * {@code RUNNING} would block its scope with nothing an operator could do about it.
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

        if (intents.stream().anyMatch(i -> i.step() == Step.ROLLBACK_ASSESS
                || i.step() == Step.ROLLBACK || i.step() == Step.VERIFY)) {
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
     */
    public static String idempotencyKey(ApplyId id, Step step, ReplicaRef replica) {
        return id.value() + ":" + step + ":" + replica.value();
    }
}
