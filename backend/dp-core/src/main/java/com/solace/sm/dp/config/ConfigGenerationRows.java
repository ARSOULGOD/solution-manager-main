package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * The persistence port of the config of record. The service in this package imports no JDBC type
 * ({@code jdbcIsConfinedToPersistencePackages}); {@code JdbcConfigGenerationRepository} implements it. Every
 * method is tenant-scoped ({@code I5}) and binds its parameters.
 */
public interface ConfigGenerationRows {

    /** Takes the instance row lock the whole stage is serialised by; empty when the instance is not declared. */
    Optional<InstanceHead> lockInstance(TenantId tenant, InstanceId instance);

    /** The generation {@code instance_state.last_generation} names, in one statement (one snapshot). */
    Optional<GenerationRecord> latest(TenantId tenant, InstanceId instance);

    /**
     * OR-96: the instance's presentation layout as JSON text, or empty when none was saved. Read from OUTSIDE
     * {@code config_generation}; it is not a column of any generation.
     */
    Optional<String> layout(TenantId tenant, InstanceId instance);

    /**
     * OR-96: replaces the instance's layout when it differs from the stored one (JSON equality, so key order and
     * {@code 2} versus {@code 2.0} are not differences) and writes nothing when it does not. Creates no record for an
     * empty layout where none exists. Called under the instance row lock; draws no {@code stateVersion} stamp.
     */
    void saveLayout(TenantId tenant, InstanceId instance, String layoutJson, java.util.UUID actor,
            java.time.Instant at);

    /** @return the {@code state_version} the database stamped on the new row */
    long insert(GenerationRecord row);

    /** Sets {@code last_generation}; @return the instance's new {@code state_version} (the update stamps it) */
    long advanceHead(TenantId tenant, InstanceId instance, long lastGeneration);

    /**
     * Deletes, for ONE instance, every generation outside the newest {@code keep} EXCEPT the instance's latest and
     * any generation an apply request names. The database trigger refuses those two even if this method were wrong.
     *
     * @return rows deleted
     */
    int pruneBeyondNewest(TenantId tenant, InstanceId instance, int keep);

    /**
     * The generation's {@code rendered_entries} as canonical JSON text, and nothing else of the row (plan 010 T6.2:
     * the worker's renderer reads this and the secret store, no more). The text holds references for secrets and never
     * a resolved value ({@code I6}); empty when the tenant has no such generation of that instance.
     */
    Optional<String> renderedEntries(TenantId tenant, InstanceId instance, long generation);

    /**
     * Whether any {@code apply_request} of this instance names {@code generation}: the journal's account that an apply
     * was ever admitted for it. A read; the caller holds the instance row lock.
     */
    boolean namedByAnApplyRequest(TenantId tenant, InstanceId instance, long generation);

    /**
     * The generation in full (a copy-forward's source, OR-42/OR-45); empty when the tenant has no such generation of
     * that instance. The three JSON texts hold references for secrets and never a resolved value ({@code I6}).
     */
    Optional<GenerationRecord> find(TenantId tenant, InstanceId instance, long generation);

    Optional<GenerationView> view(TenantId tenant, InstanceId instance, long generation);

    /** Newest first, no values. */
    List<GenerationView> history(TenantId tenant, InstanceId instance, int limit);
}
