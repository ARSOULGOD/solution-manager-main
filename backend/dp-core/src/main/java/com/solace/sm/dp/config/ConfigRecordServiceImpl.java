package com.solace.sm.dp.config;

import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.api.logs.LoggerLevel;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHashUnverifiableException;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The config of record (plan 010 T5.2; OR-33, OR-32, OR-34).
 *
 * <p><b>One stage is one transaction of its own</b> (Global Constraints, lock order): it takes the
 * {@code instance_state} row lock and nothing else, so a copy-forward that is followed by a submit (rollback,
 * restore, accept) commits the stage first and only then lets admission take {@code apply_scope} and
 * {@code instance_state} in that order. Hence {@code PROPAGATION_REQUIRES_NEW}: a stage that joined a caller's
 * transaction could invert the lock order.
 *
 * <p>Inside it, in this order: lock the instance row; refuse a stale base ({@code 409}); compare the content's
 * keyed digest with the latest generation's and, when equal, write nothing (guard 1); otherwise insert generation
 * {@code last + 1}, advance the head, and prune this instance's generations beyond the retention bound.
 *
 * <p><b>Layout is not config (OR-96).</b> The designer's canvas layout is presentation state. A command that carries
 * one has it written to the per-instance presentation record in the same transaction, after the stale-base check and
 * before guard 1, so a layout-only save (which guard 1 answers "unchanged") is persisted, mints no generation, draws no
 * stamp and moves no hash. A command that carries none (a copy-forward) leaves the record alone. The generation row
 * never holds layout.
 *
 * <p><b>The active metric list is born with its generation (plan 011 M-Q15).</b> Every generation this class mints gets
 * one list, written in the same transaction as the generation and never edited. A stage carries the gateway's list (the
 * control plane's evaluator ran over the draft); a copy-forward (rollback, restore, accept's copy of nothing, a
 * configured log level) carries none, because its draft is the source's, and so copies the source generation's list. This
 * class evaluates no predicate.
 *
 * <p>Nothing here ever holds a resolved secret: the content is the draft plus the server-rendered set, where a
 * secret is a reference (I6). The digest is taken over that content, so it is keyed (OR-32) without depending on a
 * resolved value.
 */
public final class ConfigRecordServiceImpl implements ConfigRecordService {

    private static final int MAX_HISTORY = 500;

    private final TransactionTemplate stage;
    private final ConfigGenerationRows rows;
    private final ConfigHasher hasher;
    private final Retention retention;
    private final Clock clock;
    private final RollbackTargetRows written;
    private final MetricDeclarationRows activeMetrics;

    /**
     * Plan 010's arity: no metrics repository, so a stage that carries active metric names is refused and one that
     * carries none writes no list. ⛔ No {@code src/main} class may call it ({@code MetricsDeclarationConstructorTest}):
     * the composition root passes the real repository.
     */
    public ConfigRecordServiceImpl(TransactionTemplate transactions, ConfigGenerationRows rows, ConfigHasher hasher,
            Retention retention, Clock clock, RollbackTargetRows written) {
        this(transactions, rows, hasher, retention, clock, written, MetricDeclarationRows.DISCARDING);
    }

    public ConfigRecordServiceImpl(TransactionTemplate transactions, ConfigGenerationRows rows, ConfigHasher hasher,
            Retention retention, Clock clock, RollbackTargetRows written, MetricDeclarationRows activeMetrics) {
        Objects.requireNonNull(transactions, "transactions");
        this.stage = new TransactionTemplate(transactions.getTransactionManager());
        this.stage.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rows = Objects.requireNonNull(rows, "rows");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.written = Objects.requireNonNull(written, "written");
        this.activeMetrics = Objects.requireNonNull(activeMetrics, "activeMetrics");
    }

    @Override
    public StageResult stage(UUID organizationId, UUID instanceId, StageCommand command) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        Objects.requireNonNull(command, "command");
        // Validation first, so a malformed body never takes the instance lock.
        CanonicalContent content = CanonicalContent.of(command);
        return stage.execute(status -> stageLocked(tenant, instance, command, content));
    }

    private StageResult stageLocked(TenantId tenant, InstanceId instance, StageCommand command,
            CanonicalContent content) {
        InstanceHead head = rows.lockInstance(tenant, instance)
                .orElseThrow(() -> new NoSuchElementException("instance is not declared"));
        if (command.baseStateVersion() != head.stateVersion()) {
            throw new StaleStateVersionException(head.stateVersion());
        }

        if (content.layout() != null) {
            rows.saveLayout(tenant, instance, content.layout(), command.actor(), clock.instant());
        }

        return writeUnlessUnchanged(tenant, instance, head, command, CanonicalContent.carryingLevelsOf(command, content, rows.latest(tenant, instance)));
    }

    /**
     * The tail every generation-minting path shares, run under the instance row lock: OR-33 guard 1, then insert
     * {@code last + 1}, advance the head, prune.
     */
    private StageResult writeUnlessUnchanged(TenantId tenant, InstanceId instance, InstanceHead head,
            StageCommand command, CanonicalContent content) {
        byte[] digestInput = content.digestInput();
        Optional<GenerationRecord> latest = rows.latest(tenant, instance);
        if (latest.isPresent() && sameContent(tenant, latest.get().contentDigest(), digestInput)) {
            // OR-33 guard 1: nothing observable changes, so nothing is written and no stamp is drawn.
            return new StageResult(viewOf(latest.get()), true, head.stateVersion());
        }
        return writeNext(tenant, instance, head, command, content);
    }

    /** Inserts generation {@code last + 1}, advances the head and prunes: what every minting path ends in. */
    private StageResult writeNext(TenantId tenant, InstanceId instance, InstanceHead head, StageCommand command,
            CanonicalContent content) {
        long next = head.lastGeneration() + 1;
        GenerationRecord row = new GenerationRecord(tenant, instance, next, command.packageSolution(),
                command.packageVersion(), content.componentSelection(), content.draftValues(),
                content.renderedEntries(), hasher.hash(tenant, content.digestInput()), command.origin(),
                command.derivedFromGeneration(), command.actor(), clock.instant(), 0L);
        long generationStamp = rows.insert(row);
        activeMetrics.insertActiveMetrics(tenant.value(), instance.value(), next, command.activeMetricNames(),
                clock.instant());
        long instanceStamp = rows.advanceHead(tenant, instance, next);
        rows.pruneBeyondNewest(tenant, instance, retention.retainedGenerations());
        return new StageResult(viewOf(row, generationStamp), false, instanceStamp);
    }

    /**
     * Whether the stored keyed digest describes this content, under whichever key version it names. A digest whose
     * key can no longer be loaded cannot be checked; that is treated as "changed", which writes one extra
     * generation and loses nothing, where treating it as "unchanged" could drop an edit.
     */
    private boolean sameContent(TenantId tenant, String storedDigest, byte[] digestInput) {
        try {
            return hasher.verifies(tenant, storedDigest, digestInput);
        } catch (ConfigHashUnverifiableException e) {
            return false;
        }
    }

    @Override
    public StageResult copyForward(UUID organizationId, UUID instanceId, long fromGeneration, GenerationOrigin origin,
            UUID actor) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(actor, "actor");
        if (origin == GenerationOrigin.EDIT) {
            throw new IllegalArgumentException("a copy-forward is a rollback, a restore or an accept, never an edit");
        }
        if (fromGeneration < 1) {
            throw new IllegalArgumentException("a generation is a positive number");
        }
        return stage.execute(status -> copyLocked(tenant, instance, fromGeneration, origin, actor));
    }

    private StageResult copyLocked(TenantId tenant, InstanceId instance, long fromGeneration, GenerationOrigin origin,
            UUID actor) {
        InstanceHead head = rows.lockInstance(tenant, instance)
                .orElseThrow(() -> new NoSuchElementException("instance is not declared"));
        GenerationRecord source = rows.find(tenant, instance, fromGeneration)
                .orElseThrow(() -> new NoSuchElementException("generation does not exist"));
        // The source is held under the same lock, so it cannot be pruned between this read and the insert.
        StageCommand command = copyOf(tenant, instance, head, source, origin, actor);
        return writeUnlessUnchanged(tenant, instance, head, command, CanonicalContent.of(command));
    }

    /**
     * The command that mints a copy of {@code source}. No layout: a copy-forward leaves the presentation record alone
     * (OR-96). The base is the head just read - there is no operator view to be stale against; the source is named by
     * number (or is the latest, resolved under the lock). The draft is the source's, so its active metric list is the
     * source's too (M-Q15): read here, under the same lock, and never re-evaluated.
     */
    private StageCommand copyOf(TenantId tenant, InstanceId instance, InstanceHead head, GenerationRecord source,
            GenerationOrigin origin, UUID actor) {
        return new StageCommand(head.stateVersion(), source.packageSolution(), source.packageVersion(),
                source.componentSelectionJson(), source.draftValuesJson(),
                CanonicalContent.renderedFrom(source.renderedEntriesJson()), origin, source.generation(), actor, null,
                null, activeMetricsOf(tenant, instance, source));
    }

    /**
     * The list the source generation was born with. A generation minted before this list existed has none recorded, and
     * its copy records an empty one: nothing is collectable until a stage evaluates the draft, which is the honest reading
     * of "nothing was recorded".
     */
    private List<String> activeMetricsOf(TenantId tenant, InstanceId instance, GenerationRecord source) {
        return activeMetrics.activeMetricsFor(tenant.value(), instance.value(), source.generation()).orElse(List.of());
    }

    @Override
    public Optional<StageResult> copyLatestForward(UUID organizationId, UUID instanceId, GenerationOrigin origin,
            UUID actor) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(actor, "actor");
        if (origin == GenerationOrigin.EDIT) {
            throw new IllegalArgumentException("a copy-forward is a rollback, a restore or an accept, never an edit");
        }
        return stage.execute(status -> copyLatestLocked(tenant, instance, origin, actor));
    }

    private Optional<StageResult> copyLatestLocked(TenantId tenant, InstanceId instance, GenerationOrigin origin,
            UUID actor) {
        InstanceHead head = rows.lockInstance(tenant, instance)
                .orElseThrow(() -> new NoSuchElementException("instance is not declared"));
        Optional<GenerationRecord> latest = rows.latest(tenant, instance);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        GenerationRecord source = latest.get();
        if (source.origin() == origin && !rows.namedByAnApplyRequest(tenant, instance, source.generation())) {
            // The crash window: an earlier call of this kind committed its copy and never reached admission. Adopt it,
            // so the retry applies the copy that is already there instead of minting a second.
            return Optional.of(new StageResult(viewOf(source), true, head.stateVersion()));
        }
        // No OR-33 guard 1 here: the content equals the latest's by construction, and that is exactly the case a
        // restore exists for (the record is right, the host is not). A new number is the only way to re-apply it.
        StageCommand command = copyOf(tenant, instance, head, source, origin, actor);
        return Optional.of(writeNext(tenant, instance, head, command, CanonicalContent.of(command)));
    }

    @Override
    public Optional<StageResult> copyLatestWithLogLevel(UUID organizationId, UUID instanceId, UUID placementId,
            String loggerPrefix, LoggerLevel level, UUID actor) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        PlacementId placement = new PlacementId(Objects.requireNonNull(placementId, "placement"));
        Objects.requireNonNull(actor, "actor");
        // The one function that turns a logger name into a property key; refuses before the instance lock is taken.
        OverlayKeys.Entry entry = OverlayKeys.forLogLevel(loggerPrefix, level);
        return stage.execute(status -> copyWithLevelLocked(tenant, instance, placement, entry, actor));
    }

    private Optional<StageResult> copyWithLevelLocked(TenantId tenant, InstanceId instance, PlacementId placement,
            OverlayKeys.Entry entry, UUID actor) {
        InstanceHead head = rows.lockInstance(tenant, instance)
                .orElseThrow(() -> new NoSuchElementException("instance is not declared"));
        Optional<GenerationRecord> latest = rows.latest(tenant, instance);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        GenerationRecord source = latest.get();
        refuseAnUnappliedDraft(tenant, instance, placement, source);
        List<RenderedEntryValue> rendered = new ArrayList<>();
        for (RenderedEntryValue e : CanonicalContent.renderedFrom(source.renderedEntriesJson())) {
            boolean thisLevel = OverlayKeys.INSTANCE_KEY.equals(e.instanceKey()) && entry.path().equals(e.renderedPath());
            if (!thisLevel) {
                rendered.add(e);
            }
        }
        rendered.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, entry.path(), entry.valueJson()));
        // An edit: the operator changed one configuration value. No derived-from: that names a copied source (OR-42).
        StageCommand command = new StageCommand(head.stateVersion(), source.packageSolution(), source.packageVersion(),
                source.componentSelectionJson(), source.draftValuesJson(), rendered, GenerationOrigin.EDIT, null, actor,
                null, null, activeMetricsOf(tenant, instance, source));
        return Optional.of(writeUnlessUnchanged(tenant, instance, head, command, CanonicalContent.of(command)));
    }

    /**
     * W2-01, under the instance lock: a level change copies the LATEST generation, so when that generation carries a
     * designer edit nobody applied, the copy would write it to the host under {@code logs.setLevel} alone. Compared
     * with the generation last written to this placement, level entries set aside (a level-only generation, including
     * one whose admission was refused, is not a draft). A placement nothing was ever written to, or a written
     * generation retention has pruned, cannot be compared and is refused: the conservative answer.
     */
    private void refuseAnUnappliedDraft(TenantId tenant, InstanceId instance, PlacementId placement,
            GenerationRecord latest) {
        OptionalLong lastWritten = written.highestEligibleBelow(tenant, instance, placement, Long.MAX_VALUE);
        Optional<GenerationRecord> onHost = lastWritten.isPresent()
                ? rows.find(tenant, instance, lastWritten.getAsLong()) : Optional.empty();
        if (onHost.isEmpty() || !withoutLevels(latest.renderedEntriesJson())
                .equals(withoutLevels(onHost.get().renderedEntriesJson()))) {
            throw new ApplyRefusedException(ApplyRefusedException.Reason.UNAPPLIED_DRAFT_PENDING);
        }
    }

    /** The rendered set as an unordered set, without the SM-owned level entries. */
    private static Set<RenderedEntryValue> withoutLevels(String renderedEntriesJson) {
        Set<RenderedEntryValue> kept = new HashSet<>();
        for (RenderedEntryValue e : CanonicalContent.renderedFrom(renderedEntriesJson)) {
            boolean level = OverlayKeys.INSTANCE_KEY.equals(e.instanceKey()) && OverlayKeys.isLevelPath(e.renderedPath());
            if (!level) {
                kept.add(e);
            }
        }
        return kept;
    }

    @Override
    public java.util.Map<String, LoggerLevel> configuredLogLevels(UUID organizationId, UUID instanceId) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        java.util.Map<String, LoggerLevel> levels = new java.util.TreeMap<>();
        rows.latest(tenant, instance).ifPresent(latest -> {
            for (OverlayKeys.Entry entry : CanonicalContent.overlayLevelsOf(latest.renderedEntriesJson())) {
                OverlayKeys.levelOf(entry.valueJson()).ifPresent(level -> levels.put(OverlayKeys.loggerOf(entry.path()), level));
            }
        });
        return java.util.Collections.unmodifiableMap(levels);
    }

    @Override
    public Optional<ConfigDraft> latestDraft(UUID organizationId, UUID instanceId) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        return rows.latest(tenant, instance)
                .map(r -> new ConfigDraft(r.generation(), r.packageSolution(), r.packageVersion(),
                        r.componentSelectionJson(), r.draftValuesJson(), r.contentDigest(),
                        rows.layout(tenant, instance).orElse("{}")));
    }

    @Override
    public List<GenerationView> history(UUID organizationId, UUID instanceId, int limit) {
        if (limit < 1 || limit > MAX_HISTORY) {
            throw new IllegalArgumentException("history limit must be between 1 and " + MAX_HISTORY);
        }
        return rows.history(new TenantId(Objects.requireNonNull(organizationId, "organization")),
                new InstanceId(Objects.requireNonNull(instanceId, "instance")), limit);
    }

    @Override
    public Optional<GenerationView> generation(UUID organizationId, UUID instanceId, long generation) {
        return rows.view(new TenantId(Objects.requireNonNull(organizationId, "organization")),
                new InstanceId(Objects.requireNonNull(instanceId, "instance")), generation);
    }

    private static GenerationView viewOf(GenerationRecord r) {
        return viewOf(r, r.stateVersion());
    }

    private static GenerationView viewOf(GenerationRecord r, long stateVersion) {
        return new GenerationView(r.generation(), r.packageSolution(), r.packageVersion(), r.contentDigest(),
                r.origin(), r.derivedFromGeneration(), r.createdBy(), r.createdAt(), stateVersion);
    }
}
