package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigSecretService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.SecretValueRejectedException;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretStore;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One config-field secret stored and the generation that references it written, in ONE transaction (plan 010 T10.1;
 * OR-30, OR-45, OR-60).
 *
 * <p><b>One transaction, and its own.</b> {@link ConfigRecordServiceImpl#stage} is a transaction of its own
 * ({@code PROPAGATION_REQUIRES_NEW}), so it cannot be called from inside another transaction without committing
 * separately and breaking the pairing. This class therefore takes the same lock and writes the same rows itself, through
 * the same {@link ConfigGenerationRows} port and {@link CanonicalContent}, so a generation it writes is
 * indistinguishable from a staged one: next number, keyed digest, advanced head, pruned tail. The secret store's JDBC
 * template runs over the same data source as this transaction, so {@link SecretStore#put} joins it.
 *
 * <p>Inside it, in this order: lock the instance row; refuse a stale base ({@code 409}) before anything is stored; store
 * the secret; ask the caller for the command that names it; check the command is the edit this call promised; write the
 * generation. Anything thrown after the secret insert, including by the caller's builder, rolls the secret back with it,
 * so no stored secret is ever unreferenced and no generation ever names a secret that is not there.
 *
 * <p><b>It is the second place a generation is minted</b> ({@link ConfigRecordServiceImpl#writeNext} is the first), so it
 * writes the generation's active metric list too (plan 011 M-Q15): the gateway re-projects this edit, so the command
 * carries the list, and a generation is never born without one.
 *
 * <p>Layout is left alone: the command carries none ({@code layoutJson == null}), as for any copy-forward (OR-96).
 *
 * <p><b>I6.</b> The value goes to {@link SecretStore#put} and nowhere else; it is not copied, held, logged or named in
 * an exception, and its zeroisation is the caller's (the store's contract). The generation holds the reference only.
 */
public final class ConfigSecretServiceImpl implements ConfigSecretService {

    private final TransactionTemplate transaction;
    private final ConfigGenerationRows rows;
    private final SecretStore secrets;
    private final ConfigHasher hasher;
    private final Retention retention;
    private final Clock clock;
    private final MetricDeclarationRows activeMetrics;

    /**
     * Plan 010's arity: no metrics repository, so a command that carries active metric names is refused and one that
     * carries none writes no list. ⛔ No {@code src/main} class may call it ({@code MetricsDeclarationConstructorTest}).
     */
    public ConfigSecretServiceImpl(TransactionTemplate transactions, ConfigGenerationRows rows, SecretStore secrets,
            ConfigHasher hasher, Retention retention, Clock clock) {
        this(transactions, rows, secrets, hasher, retention, clock, MetricDeclarationRows.DISCARDING);
    }

    public ConfigSecretServiceImpl(TransactionTemplate transactions, ConfigGenerationRows rows, SecretStore secrets,
            ConfigHasher hasher, Retention retention, Clock clock, MetricDeclarationRows activeMetrics) {
        Objects.requireNonNull(transactions, "transactions");
        this.transaction = new TransactionTemplate(transactions.getTransactionManager());
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.rows = Objects.requireNonNull(rows, "rows");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeMetrics = Objects.requireNonNull(activeMetrics, "activeMetrics");
    }

    @Override
    public StageResult storeAndStage(UUID organizationId, UUID instanceId, long baseStateVersion, char[] value,
            UUID actor, StageBuilder stage) {
        TenantId tenant = new TenantId(Objects.requireNonNull(organizationId, "organization"));
        InstanceId instance = new InstanceId(Objects.requireNonNull(instanceId, "instance"));
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(stage, "stage");
        return transaction.execute(status -> storeAndStageLocked(tenant, instance, baseStateVersion, value, actor, stage));
    }

    private StageResult storeAndStageLocked(TenantId tenant, InstanceId instance, long baseStateVersion, char[] value,
            UUID actor, StageBuilder stage) {
        InstanceHead head = rows.lockInstance(tenant, instance)
                .orElseThrow(() -> new NoSuchElementException("instance is not declared"));
        if (baseStateVersion != head.stateVersion()) {
            // Before the value is touched: the loser of a race stores nothing.
            throw new StaleStateVersionException(head.stateVersion());
        }

        UUID reference;
        try {
            reference = secrets.put(tenant.value(), SecretPurpose.CONFIG_FIELD, value, actor);
        } catch (IllegalArgumentException refused) {
            // The store's message is not repeated and no cause is kept: the value is a secret (I6).
            throw new SecretValueRejectedException();
        }

        StageCommand command = Objects.requireNonNull(stage.build(reference), "stage command");
        if (command.baseStateVersion() != baseStateVersion) {
            throw new IllegalStateException("the staged command is not built from this call's base");
        }
        if (command.origin() != GenerationOrigin.EDIT || command.derivedFromGeneration() != null) {
            throw new IllegalStateException("a secret is staged as an edit, not as a copy of another generation");
        }
        // Fable APPLY-01: the instance is locked, so the latest generation read here is the one this one succeeds.
        CanonicalContent content = CanonicalContent.carryingLevelsOf(command, CanonicalContent.of(command),
                rows.latest(tenant, instance));

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
        return new StageResult(new GenerationView(row.generation(), row.packageSolution(), row.packageVersion(),
                row.contentDigest(), row.origin(), row.derivedFromGeneration(), row.createdBy(), row.createdAt(),
                generationStamp), false, instanceStamp);
    }
}
