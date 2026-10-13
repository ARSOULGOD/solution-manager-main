package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import com.solace.sm.dp.apply.persistence.Constraints;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Design §3.3's admission control, as ONE transaction.
 *
 * <p>The order below is the design's, and every line of it is load-bearing:
 *
 * <ol>
 *   <li><b>{@code ensureScopeRow} then {@code lockScope}</b> — the serialization point. Everything
 *       after this runs with the scope's {@code apply_scope} row held {@code FOR UPDATE}, so a
 *       concurrent admission for the same instance blocks here (measured in
 *       {@code AdmissionSerializationIT}) rather than racing the checks below.
 *   <li><b>Declared and active</b> (plan 010 T2.2) — read before step 1 writes anything, and again under the
 *       lock. Only {@code lifecycle = 'active'} is admissible; the {@code scope_has_instance} foreign key and
 *       {@code apply_request}'s stamp trigger refuse the same thing independently.
 *   <li><b>The blocking check, cross-scope</b> — {@link ScopeKey#overlaps} expresses what the partial
 *       unique indexes cannot: instance-wide excludes every placement and vice versa. {@code stop} is
 *       EXEMPT, deliberately (§3.3): architecture.md §16.1 blocks further <i>applies</i> behind an
 *       unresolved {@code APPLY_INDETERMINATE}, and fencing the operator's emergency lever would be a
 *       wider reading than the constraint asks for.
 *   <li><b>Monotonicity</b> (§2.2 rule 1) — the fence is the GREATEST of the maintained watermarks
 *       for this scope SHAPE, so an instance-wide {@code APPLIED} at 10 fences a later placement 9
 *       and vice versa.
 *   <li><b>Supersede the elder {@code STAGED} row BEFORE inserting</b> — obligation (p). The
 *       {@code staged_fence} turns forgetting this order into a 23505 instead of silent
 *       accumulation, which is why the order is code and the fence is the check on the code.
 *   <li><b>Insert</b>, and classify any fence 23505 as {@link RefusalReason#FENCE_RACE_LOST}.
 * </ol>
 *
 * <p><b>On the rollback of a lost race:</b> a 23505 aborts the PostgreSQL transaction, so the
 * callback marks the status rollback-only and returns the refusal as a value.
 * {@code TransactionTemplate} then rolls back and returns that value normally — no
 * {@code UnexpectedRollbackException}, because the rollback was locally requested rather than
 * globally imposed. The supersession mark of step 4 rolls back with it, which is correct: this
 * request never existed.
 */
public final class AdmissionService {

    private final TransactionTemplate tx;
    private final ApplyScopeRepository scopes;
    private final ApplyRequestRepository requests;
    private final Clock clock;
    private final ApplyEventEmitter events;

    /** An engine that publishes no notices: the journal-only suites. Production uses the emitter form. */
    public AdmissionService(TransactionTemplate tx, ApplyScopeRepository scopes,
            ApplyRequestRepository requests, Clock clock) {
        this(tx, scopes, requests, clock, ApplyEventEmitter.silent());
    }

    public AdmissionService(TransactionTemplate tx, ApplyScopeRepository scopes,
            ApplyRequestRepository requests, Clock clock, ApplyEventEmitter events) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.scopes = scopes;
        this.requests = requests;
        this.clock = clock;
        this.events = java.util.Objects.requireNonNull(events, "events");
    }

    public AdmissionOutcome admit(ApplyCommand cmd) {
        return admit(cmd, GenerationBinding.UNCHECKED);
    }

    /**
     * As {@link #admit(ApplyCommand)}, and with {@link GenerationBinding#MUST_BE_STAGED} an apply is also refused
     * ({@link RefusalReason#GENERATION_NOT_STAGED}) unless its generation exists in the config of record, checked
     * under the {@code instance_state} row lock (lock order {@code apply_scope → instance_state}).
     */
    public AdmissionOutcome admit(ApplyCommand cmd, GenerationBinding binding) {
        return admit(cmd, binding, null);
    }

    /**
     * As {@link #admit(ApplyCommand, GenerationBinding)} for a caller that PRE-MINTED the request's id (plan 010 T11.2,
     * {@code fleet-apply.md} section 4.2): the request is inserted under {@code reserved}, and a request that already
     * exists under it is answered {@link AdmissionOutcome.AlreadyAdmitted} - looked up under the scope lock and BEFORE the
     * blocking check, which would otherwise refuse a re-admission of a request that is genuinely running (as
     * {@code SCOPE_BUSY}) or landed (as the fence). That makes admission idempotent per id: a fleet that crashed between
     * this commit and recording it admits the member again and is told so. A {@code null} {@code reserved} is a single
     * submit, whose path is unchanged.
     */
    public AdmissionOutcome admit(ApplyCommand cmd, GenerationBinding binding, ApplyId reserved) {
        ScopeKey scope = cmd.scope();
        TenantId t = scope.tenant();
        InstanceId i = scope.instance();
        PlacementId p = scope.placement();

        return tx.execute(status -> {
            Instant now = clock.instant();
            // Plan 010 T2.2: only a declared, active instance is admissible. Read FIRST so an undeclared one is
            // refused before any row is written (the scope_has_instance foreign key would otherwise refuse the
            // scope row with a 23503 instead of this readable value) …
            if (!requests.instanceIsActive(t, i)) {
                return notDeclared();
            }
            scopes.ensureScopeRow(t, i);
            long instanceWatermark = scopes.lockScope(t, i);
            // … and again UNDER the lock: an instance abandoned while this admission queued behind another writer
            // of the scope must still be refused. The unlocked read above is a courtesy; this one is the check.
            if (!requests.instanceIsActive(t, i)) {
                return notDeclared();
            }

            // Plan 010 T11.2: a request that already exists under the id the caller pre-minted is answered BEFORE the
            // blocking check, because that check would refuse it for the wrong reason - SCOPE_BUSY while it is still
            // REQUESTED, RUNNING or APPLY_INDETERMINATE, GENERATION_NOT_ABOVE_WATERMARK once it landed APPLIED or STAGED
            // (its own landing raised the fence) - and a fleet would record a member whose apply is genuinely running as
            // refused. Under the scope lock, so it serializes with the admission that created it; the scope must be this
            // one (an id minted for one member names one scope), else the insert's primary key refuses it as the invariant
            // violation it would be.
            if (reserved != null && requests.find(t, reserved).filter(row -> row.scope().equals(scope)).isPresent()) {
                return new AdmissionOutcome.AlreadyAdmitted(reserved);
            }

            if (cmd.operation() == Operation.STOP) {
                if (requests.stopInFlight(t, i, p)) {
                    return refused(RefusalReason.STOP_IN_FLIGHT,
                            "a stop is already in flight on this scope");
                }
            } else {
                Optional<ApplyRequestRow> blocker = requests.blockingRequests(t, i).stream()
                        .filter(r -> r.scope().overlaps(scope))
                        .findFirst();
                if (blocker.isPresent()) {
                    ApplyRequestRow b = blocker.get();
                    return refused(RefusalReason.SCOPE_BUSY,
                            b.operation().sql() + " " + b.applyId().value() + " is " + b.state()
                                    + " on placement " + b.scope().placement().value());
                }
            }

            if (cmd.operation() == Operation.APPLY && binding == GenerationBinding.MUST_BE_STAGED
                    && !requests.generationExistsLockingInstance(t, i, cmd.generation())) {
                return refused(RefusalReason.GENERATION_NOT_STAGED,
                        "the generation is not in the config of record for this instance");
            }

            Optional<ApplyId> elder = Optional.empty();
            if (cmd.operation() == Operation.APPLY) {
                long fence = p.isInstanceWide()
                        ? Math.max(instanceWatermark, scopes.maxPlacementWatermark(t, i))
                        : Math.max(instanceWatermark, scopes.placementWatermark(t, i, p));
                if (cmd.generation() <= fence) {
                    return refused(RefusalReason.GENERATION_NOT_ABOVE_WATERMARK,
                            "generation " + cmd.generation() + " is not above the fence " + fence);
                }
                elder = requests.stagedRequest(t, i, p);
                if (elder.isPresent()) {
                    requests.transition(t, elder.get(), RequestState.STAGED,
                            RequestState.SUPERSEDED, now);
                }
            }

            ApplyId applyId = reserved != null ? reserved : ApplyId.next();
            try {
                requests.insert(new ApplyRequestRow(applyId, scope, cmd.operation(),
                        cmd.generation(), cmd.strategy(), cmd.outageConfirmedBy(),
                        cmd.requestedBy(), now, RequestState.REQUESTED, null));
            } catch (PgConstraintViolation e) {
                if (e.namesAnyOf(Constraints.APPLY_INFLIGHT_FENCE, Constraints.STOP_INFLIGHT_FENCE,
                        Constraints.STAGED_FENCE)) {
                    status.setRollbackOnly();
                    return refused(RefusalReason.FENCE_RACE_LOST, e.constraint());
                }
                throw e;
            }
            // The elder's SUPERSEDED is a terminal apply landing like any other, and a consumer holding it STAGED must be
            // told. Announced only here, once the insert has succeeded: every refusal above - including a lost fence
            // race, which rolls this transaction back - returns earlier, so a request that never existed announces
            // nothing about the one it did not supersede.
            if (elder.isPresent()) {
                ApplyRequestRow superseded = requests.find(t, elder.get()).orElseThrow();
                ApplyEventEmitter.bestEffort("apply-outcome", () -> events.onApplyOutcome(superseded));
            }
            return new AdmissionOutcome.Admitted(applyId);
        });
    }

    private static AdmissionOutcome notDeclared() {
        return refused(RefusalReason.INSTANCE_NOT_DECLARED,
                "the instance is not declared and active in this data plane");
    }

    private static AdmissionOutcome refused(RefusalReason reason, String detail) {
        return new AdmissionOutcome.Refused(reason, detail);
    }
}
