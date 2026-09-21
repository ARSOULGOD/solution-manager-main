package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyCommand;
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

    public AdmissionService(TransactionTemplate tx, ApplyScopeRepository scopes,
            ApplyRequestRepository requests, Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.scopes = scopes;
        this.requests = requests;
        this.clock = clock;
    }

    /**
     * Every real placement of this instance SM has ever been asked to act on — {@code BE-D26}'s
     * enumerator for an instance-wide stop. A read, taking no lock.
     */
    public java.util.List<com.solace.sm.dp.apply.model.PlacementId> placementsOf(ScopeKey scope) {
        return tx.execute(status -> requests.placementsOf(scope.tenant(), scope.instance()));
    }

    public AdmissionOutcome admit(ApplyCommand cmd) {
        ScopeKey scope = cmd.scope();
        TenantId t = scope.tenant();
        InstanceId i = scope.instance();
        PlacementId p = scope.placement();

        return tx.execute(status -> {
            Instant now = clock.instant();
            scopes.ensureScopeRow(t, i);
            long instanceWatermark = scopes.lockScope(t, i);

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

            if (cmd.operation() == Operation.APPLY) {
                long fence = p.isInstanceWide()
                        ? Math.max(instanceWatermark, scopes.maxPlacementWatermark(t, i))
                        : Math.max(instanceWatermark, scopes.placementWatermark(t, i, p));
                if (cmd.generation() <= fence) {
                    return refused(RefusalReason.GENERATION_NOT_ABOVE_WATERMARK,
                            "generation " + cmd.generation() + " is not above the fence " + fence);
                }
                Optional<ApplyId> elder = requests.stagedRequest(t, i, p);
                if (elder.isPresent()) {
                    requests.transition(t, elder.get(), RequestState.STAGED,
                            RequestState.SUPERSEDED, now);
                }
            }

            ApplyId applyId = ApplyId.next();
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
            return new AdmissionOutcome.Admitted(applyId);
        });
    }

    private static AdmissionOutcome refused(RefusalReason reason, String detail) {
        return new AdmissionOutcome.Refused(reason, detail);
    }
}
