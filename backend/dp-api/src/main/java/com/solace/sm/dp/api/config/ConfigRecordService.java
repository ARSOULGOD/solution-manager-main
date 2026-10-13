package com.solace.sm.dp.api.config;

import com.solace.sm.dp.api.logs.LoggerLevel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The config of record (OR-33): immutable generations, bounded. Every method is tenant-scoped by
 * {@code organizationId}, which is {@code Session.organizationId()}, never a request field (I5).
 */
public interface ConfigRecordService {

    /**
     * OR-33 guard 1: content equal to the latest generation writes nothing and returns it with
     * {@code unchanged = true}. Otherwise writes generation {@code last + 1} under the instance row lock, advances
     * the instance's {@code stateVersion}, and prunes that instance's generations beyond the retention bound.
     *
     * @throws StaleStateVersionException {@code baseStateVersion} is not the instance's (409)
     * @throws java.util.NoSuchElementException the instance is not declared in this organization (404)
     */
    StageResult stage(UUID organizationId, UUID instanceId, StageCommand command);

    /**
     * Operator rollback, restore and accept all begin here (OR-42, OR-45): a NEW generation, {@code last + 1}, that
     * takes {@code fromGeneration}'s content (component selection, draft values, rendered set, package version),
     * with {@code origin} and {@code derived_from_generation = fromGeneration}. The only-goes-up fence is untouched:
     * the copy is a higher number, never the old one re-admitted.
     *
     * <p><b>A transaction of its own</b> ({@code PROPAGATION_REQUIRES_NEW}, as {@link #stage}): it holds
     * {@code instance_state} and nothing else, so the submit that follows takes {@code apply_scope} then
     * {@code instance_state} in the recorded order. A crash between the two leaves a generation with no apply; the
     * operator's retry copies the same content, which OR-33 guard 1 returns as {@code unchanged = true} (the latest
     * generation), so a retry never mints a second one. There is no base {@code stateVersion}: the source is named
     * by number, not by an operator's view of the instance. The layout record is left alone.
     *
     * @param origin must not be {@link GenerationOrigin#EDIT}: an edit has a body, a copy does not
     * @throws java.util.NoSuchElementException the instance is not declared in this organization, or it has no such
     *                                          generation (404)
     * @throws IllegalArgumentException         {@code origin} is {@code EDIT}
     */
    StageResult copyForward(UUID organizationId, UUID instanceId, long fromGeneration, GenerationOrigin origin,
            UUID actor);

    /**
     * As {@link #copyForward}, for the instance's CURRENT latest generation, resolved under the same instance row lock
     * that mints the copy, so a stage that lands between a caller's read and this call cannot make the copy a stale one
     * (restore-runtime's "desired", OR-45).
     *
     * <p><b>It writes a new generation even when the content equals the latest's.</b> OR-33 guard 1 answers an operator
     * EDIT that changes nothing; a restore's whole point is that the host differs while the record does not, and the
     * only way to re-apply is a number above the placement's watermark. The one idempotence it keeps is the crash
     * window: when the latest generation is itself a copy of this {@code origin} that no apply request names yet, it is
     * returned as {@code unchanged = true} for the retry to apply, rather than copied again.
     *
     * @param origin must not be {@link GenerationOrigin#EDIT}
     * @return empty when the instance has no generation yet
     * @throws java.util.NoSuchElementException the instance is not declared in this organization (404)
     */
    Optional<StageResult> copyLatestForward(UUID organizationId, UUID instanceId, GenerationOrigin origin, UUID actor);

    /**
     * The log level as configuration (OR-103, P2): a NEW generation (origin {@code edit}) with the latest generation's
     * content and one SM-owned overlay entry, {@code logging.level.<loggerPrefix>}, set to {@code level}. The entry is
     * built by {@code OverlayKeys}, never from a package field. Resolved under the instance row lock, in a transaction
     * of its own like {@link #copyForward}, so the apply that follows takes its locks in the recorded order. Content equal
     * to the latest's (the level is already configured) writes nothing and returns it with {@code unchanged = true}
     * (OR-33 guard 1). The layout record is left alone.
     *
     * <p>W2-01: refused ({@code UNAPPLIED_DRAFT_PENDING}) when the latest generation, setting the level entries aside,
     * differs from the generation last written to {@code placementId}: the copy would otherwise carry an unapproved
     * designer edit to the host under {@code logs.setLevel} alone. Checked under the instance lock; nothing is minted.
     *
     * @throws IllegalArgumentException {@code loggerPrefix} is outside the logger-name grammar (or is {@code ROOT})
     * @throws java.util.NoSuchElementException the instance is not declared in this organization (404)
     * @throws com.solace.sm.dp.api.apply.ApplyRefusedException {@code UNAPPLIED_DRAFT_PENDING}
     * @return empty when the instance has no generation yet
     */
    Optional<StageResult> copyLatestWithLogLevel(UUID organizationId, UUID instanceId, UUID placementId,
            String loggerPrefix, LoggerLevel level, UUID actor);

    /**
     * The levels the instance's LATEST generation configures, by logger name (OR-103, T9.4): empty before the first
     * stage and for a generation that configures none. A stored value that is not one of the contract's levels is left
     * out. Read-only, tenant-scoped.
     */
    java.util.Map<String, LoggerLevel> configuredLogLevels(UUID organizationId, UUID instanceId);

    /** The latest generation's draft, or empty before the first stage. What {@code getDesigner} re-projects. */
    Optional<ConfigDraft> latestDraft(UUID organizationId, UUID instanceId);

    /** Newest first, at most {@code limit} (1 to 500). Redacted: no values, and the keyed digest. */
    List<GenerationView> history(UUID organizationId, UUID instanceId, int limit);

    Optional<GenerationView> generation(UUID organizationId, UUID instanceId, long generation);
}
