package com.solace.sm.cp.persistence;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Phase 1 of the {@code sm_control} bootstrap, as Flyway configuration and nothing else — mirroring
 * dp-core's {@code SmDataMigrator}.
 *
 * <p><b>The two-phase split is not cosmetic.</b> Phase 0 — roles, {@code CREATE SCHEMA sm_control
 * AUTHORIZATION sm_cp_owner}, the schema grants — is install-runbook material, run ONCE by the
 * cluster administrator. It ships as {@code db/bootstrap/sm_control_phase0.sql} and Flyway must never
 * see it. Phase 1 is everything below, run as {@code sm_cp_migrator}.
 *
 * <p><b>{@link #INIT_SQL} is the load-bearing line.</b> {@code sm_cp_migrator} is already a member of
 * {@code sm_cp_owner}, so membership alone would let it {@code ALTER} owner-owned objects — what
 * {@code SET ROLE} buys is ownership AT CREATE TIME, so every table, index and constraint this
 * migrator creates is born owned by {@code sm_cp_owner} rather than by the migrator login.
 *
 * <p>{@code bootstrap_credential_hash} is Flyway's placeholder for V007's Argon2id hash — see
 * {@link SmControlMigrationMain}, which validates it before it ever reaches Flyway.
 */
public final class SmControlMigrator {

    /** Flyway's per-connection init statement — see the class javadoc for why it is here. */
    public static final String INIT_SQL = "SET ROLE sm_cp_owner";

    /** The control plane's only schema ({@code I7}: no cross-schema joins with {@code sm_data}). */
    public static final String SCHEMA = "sm_control";

    /** Classpath location of the Phase-1 migrations. */
    public static final String LOCATION = "classpath:db/migration/sm_control";

    /** Classpath location of the Phase-0 script — for the runbook and tests, not Flyway. */
    public static final String PHASE0_RESOURCE = "db/bootstrap/sm_control_phase0.sql";

    private SmControlMigrator() {
    }

    /**
     * Applies Phase 1 with the migrator credential and the installer-supplied bootstrap credential
     * hash.
     *
     * @param migratorDataSource     a {@code sm_cp_migrator} data source — never the runtime one,
     *                               which is not an {@code sm_cp_owner} member and cannot run
     *                               {@link #INIT_SQL}
     * @param bootstrapCredentialHash the Argon2id hash V007 inserts for the bootstrap administrator —
     *                               never the plaintext credential
     * @return Flyway's own report: which versions were applied, in order
     */
    public static MigrateResult migrate(DataSource migratorDataSource, String bootstrapCredentialHash) {
        return Flyway.configure()
                .dataSource(migratorDataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .initSql(INIT_SQL)
                .locations(LOCATION)
                .createSchemas(false)
                .placeholders(Map.of("bootstrap_credential_hash", bootstrapCredentialHash))
                .load()
                .migrate();
    }
}
