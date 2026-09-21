package com.solace.sm.dp.apply.runtime;

import java.util.List;

/**
 * The {@code dp-remote} apply protocol — {@code BE-D12} §2.1's runtime-neutral operations, as amended
 * by {@code BE-D21} (parameter types), {@code BE-D22} (the sixth operation split in two) and
 * {@code BE-D25} (a generation-free replica observation).
 *
 * <p>The core apply engine speaks only these eight; everything runtime-specific lives behind this
 * seam. Lifecycle and observation are <b>replica-scoped</b>, because {@code SM-D15}'s strategies are
 * per-replica by construction and an instance-scoped verb cannot say "restart replica 2, wait, then
 * replica 3".
 *
 * <p><b>There is deliberately no {@code healthGate} operation.</b> A gate is a policy over
 * observations — the core's — expressed through repeated {@link #status} calls under a bounded
 * deadline, not a runtime action.
 *
 * <p>Every implementation must pass {@code AbstractRuntimeAdapterContractTest}. An implementation
 * without a subclass of that kit is not certified, and {@code PlaneBoundaryTest}'s adapter inventory
 * is where that promise is recorded.
 */
public interface RuntimeAdapter {

    /**
     * The replicas of one placement, as the runtime currently has them. <b>Read-only</b>, and
     * <b>generation-free</b> ({@code BE-D25}).
     *
     * <p>{@link #prepare} is the other enumerator and cannot serve here: it is generation-bearing
     * by design, and {@code apply_has_generation} makes a {@code stop}'s generation NULL, so
     * without this operation a stop cannot learn which replicas to address. {@code prepare} is
     * therefore "{@code replicas} plus the per-apply validation", and this operation makes no
     * claim about whether the replicas are fit to apply to — every pre-flight refusal stays there.
     *
     * <p>Every implementation must return the same replica set its own {@link #prepare} returns
     * for a <b>ready</b> placement; the contract kit pins that case directly. The two deliberately
     * differ elsewhere, and the difference is the point: a placement with no replicas is an
     * <b>empty list</b> here and a {@link PreflightResult.Refused} there, because an apply cannot
     * write to nothing while an observation has nothing to report — which is also the answer
     * {@code ResumePlanner} gives an {@code ABSENT} replica, and the three must agree.
     */
    List<TargetReplica> replicas(PlacementRef placement);

    /**
     * Per-placement pre-flight, run <b>before every apply</b>, never once at enrollment
     * ({@code SM-D21}'s round-2 correction: launch command, mount and competing-source set can all
     * change between applies). Resolves the declared config location, verifies reachability,
     * permissions and writer ownership, detects competing sources, and captures the replica set the
     * apply will address. <b>Never mutates the target.</b>
     */
    PreflightResult prepare(PlacementRef placement, long generation);

    /**
     * Persists generation {@code G}'s rendered bytes durably at the declared location for
     * <b>one replica</b>, and returns the hash of what it wrote. <b>Zero lifecycle effect</b> —
     * writing configuration never restarts anything ({@code SM-D15}/{@code AG-D3}).
     */
    MaterializeResult materializeConfig(TargetReplica replica, long generation, RenderedConfig config);

    /**
     * Makes one replica reflect the materialized generation by executing the declared lifecycle verb
     * ({@code SM-D19}). Invoked <b>only</b> under an operator-selected restart strategy: under the
     * default {@code manual} the core never calls this. <b>Never pulls, replaces or re-tags an
     * artifact</b> — deployment is the customer's CI/CD ({@code SM-D2}, {@code I1}).
     */
    LifecycleResult startOrUpdate(TargetReplica replica, long generation);

    /** The declared stop verb on one replica, on explicit operator command only. */
    LifecycleResult stop(TargetReplica replica);

    /**
     * Observes one replica: liveness, health signal, the persisted-config hash read back at the
     * declared path, and the start baseline {@code restart-observed} detection compares against.
     * Read-only and safe to call at any time.
     */
    RuntimeStatus status(TargetReplica replica);

    /**
     * The bounded-retry exhaustion path's <b>read-only</b> half ({@code BE-D22}), placement-scoped.
     * <b>Mutates nothing</b> — the obligation {@code BE-D13} §3.1 assigns to "adapter contract tests,
     * per adapter", and the reason the engine may journal it as the hashless {@code ROLLBACK_ASSESS}
     * step and re-run it freely after a crash.
     *
     * <p>Classifies each replica against exactly two <b>journal-known</b> values: the rollback target
     * read back from the retained pre-image, and {@code intendedHash} — the {@code config_hash} the
     * {@code MATERIALIZE} intent already bound, handed in by the engine because it is a row and not
     * an inference. A mixture of the two is the ordinary partial-write case and is
     * {@link RollbackAssessment.Restorable}; anything else is
     * {@link RollbackAssessment.Unknowable}.
     */
    RollbackAssessment assessRollback(PlacementRef placement, long generation, String intendedHash);

    /**
     * The exhaustion path's <b>destructive</b> half ({@code BE-D22}): restore the prior persisted
     * config on every replica of the placement, to the hash
     * {@link RollbackAssessment.Restorable} named. Idempotent under the same key — writing identical
     * bytes twice is the same write.
     *
     * <p><b>Re-checks its own target before writing.</b> If the retained pre-image has gone or no
     * longer hashes to {@code targetHash}, the world moved between assess and restore: the adapter
     * writes <b>nothing</b> and throws {@link RuntimeAdapterException} carrying
     * {@code rollback.preimage.vanished} or {@code rollback.preimage.altered}.
     *
     * <p>The engine opens a {@code ROLLBACK} intent carrying {@code targetHash} as its
     * {@code config_hash} <b>before</b> calling this, which is what makes resume decidable: a crash
     * in the window is resolved by reading each replica's hash back against the intent's own, never
     * by guessing whether the restore occurred.
     */
    RestoreResult restore(PlacementRef placement, long generation, String targetHash);
}
