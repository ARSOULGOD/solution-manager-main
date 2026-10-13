package com.solace.sm.dp.api.apply;

import java.util.List;
import java.util.UUID;

/**
 * The data plane's apply surface (plan 010 T6.3): submit, stop and observe. Every call is scoped to
 * {@code organizationId} ({@code I5}) - the caller passes the session's, never a request field.
 *
 * <p><b>Submission is admission only.</b> {@link #submitApply} and {@link #stopInstance} commit a {@code REQUESTED}
 * journal row and return; no adapter is called on the request thread. The durable worker claims the row and the engine
 * executes it. That is what makes the {@code 202} a promise, and the journal the only truth {@link #observeApply}
 * reads.
 */
public interface ApplyService {

    /**
     * Admits an apply of a staged generation to one live placement of the instance.
     *
     * @throws ApplyRefusedException           the instance or placement is not found ({@code NOT_FOUND}), the
     *                                         generation was never staged, or admission refused the scope
     * @throws NotImplementedStrategyException {@code restart-all}, {@code rolling-no-validation} or {@code scheduled}
     * @throws IllegalArgumentException        {@code strategy} is not one of the five engine tokens
     */
    ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command);

    /**
     * As {@link #submitApply(UUID, UUID, SubmitApplyCommand)} for a caller that PRE-MINTED the request's id (OR-125 (5)): the
     * request is inserted under {@code reservedApplyId}, and one that already exists under it is answered with its own view
     * and nothing is written, so a retry after a crash between the engine's commit and the caller's own record is told so
     * instead of being refused by the very fences its first admission raised. The id belongs to ONE scope: a request under
     * it for any other is the invariant violation the primary key refuses.
     */
    default ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command,
            UUID reservedApplyId) {
        throw new UnsupportedOperationException("this service cannot admit under a reserved id");
    }

    /** As {@link #rollbackApply(UUID, UUID, RollbackCommand)} for a caller that pre-minted the request's id; see {@link #submitApply(UUID, UUID, SubmitApplyCommand, UUID)}. */
    default ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command,
            UUID reservedApplyId) {
        throw new UnsupportedOperationException("this service cannot admit under a reserved id");
    }

    /**
     * Operator rollback as copy-forward (OR-42, flagged: names {@code B/D12}): resolves the target among the
     * generations actually written to {@link RollbackCommand#placementId()}, copies its content forward as a NEW,
     * higher generation (origin {@code rollback}), then admits a normal apply of that generation under
     * {@link RollbackCommand#strategy()}. The two are separate transactions (the lock order), so a crash between
     * leaves a rollback generation with no apply and the operator's retry reuses it.
     *
     * @throws ApplyRefusedException           {@code NOT_FOUND} (instance, placement, or the named apply is not this
     *                                         organization's), {@code ROLLBACK_TARGET_NOT_ELIGIBLE}, or the refusals
     *                                         of {@link #submitApply}
     * @throws NotImplementedStrategyException {@code restart-all}, {@code rolling-no-validation} or {@code scheduled}
     */
    ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId, RollbackCommand command);

    /**
     * Restore-runtime (OR-45): copies the instance's CURRENT desired generation (its newest, applied or not) forward as
     * a NEW generation with origin {@code restore-runtime}, then admits a normal apply of that copy to one placement
     * under {@link RestoreRuntimeCommand#strategy()}. The copy is a new number because the placement's watermark sits
     * at or above the desired generation's own, so re-submitting it would be refused; the content is the desired
     * content, never the latest APPLIED generation's. The two are separate transactions (the lock order).
     *
     * @throws ApplyRefusedException           {@code NOT_FOUND} (instance or placement), {@code GENERATION_NOT_STAGED}
     *                                         (the instance has no generation to restore), or the refusals of
     *                                         {@link #submitApply}
     * @throws NotImplementedStrategyException {@code restart-all}, {@code rolling-no-validation} or {@code scheduled}
     */
    ApplyAcceptedView restoreRuntime(UUID organizationId, UUID instanceId, RestoreRuntimeCommand command);

    /**
     * The log level as configuration (OR-103, P2): mints a NEW generation with the latest content and
     * {@code logging.level.<loggerPrefix>} set ({@link com.solace.sm.dp.api.config.ConfigRecordService#copyLatestWithLogLevel}), then admits a normal
     * apply of it to one placement under {@code manual} (the file is written; nothing restarts, so the level takes
     * effect at the next restart). The scope is checked BEFORE anything is minted (S811-2), and the two are separate
     * transactions (the lock order).
     *
     * <p>W2-12: when admission is refused AFTER the copy committed (a {@code SCOPE_BUSY} race that lands between the
     * pre-check and the admission), the minted level generation stays the instance's latest and is applied by its next
     * apply; re-submitting the same level answers "unchanged" and admits it. W2-01: refused
     * ({@code UNAPPLIED_DRAFT_PENDING}) while a designer edit is staged and not applied, because the copy would
     * carry it to the host.
     *
     * @throws ApplyRefusedException {@code NOT_FOUND} (instance or placement), {@code GENERATION_NOT_STAGED} (the
     *                               instance has no generation to carry the level), {@code UNAPPLIED_DRAFT_PENDING},
     *                               or the refusals of {@link #submitApply} (including {@code SCOPE_BUSY})
     */
    ApplyAcceptedView setLogLevel(UUID organizationId, UUID instanceId, SetLogLevelCommand command);

    /**
     * Admits a stop of one live placement, or of the whole instance when {@link StopCommand#placementId()} is
     * {@code null}. Idempotent: stopping what is already stopped is another admitted request that lands {@code STOPPED}.
     *
     * @throws ApplyRefusedException the instance or placement is not found, or a stop is already in flight
     */
    ApplyAcceptedView stopInstance(UUID organizationId, UUID instanceId, StopCommand command);

    /**
     * The journal's account of the instance's requests, ascending by {@code stateVersion}. Never calls an adapter, so
     * it answers when the host is unreachable.
     *
     * @throws ApplyRefusedException {@code NOT_FOUND} when {@link ObserveQuery#applyId()} names no request of this
     *                               instance
     */
    List<ApplyObservationView> observeApply(UUID organizationId, UUID instanceId, ObserveQuery query);
}
