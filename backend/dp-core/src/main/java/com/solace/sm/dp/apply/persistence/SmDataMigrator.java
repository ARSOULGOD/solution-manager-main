package com.solace.sm.dp.apply.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Phase 1 of the {@code sm_data} bootstrap (eda-walking-skeleton §3.1), as Flyway configuration and
 * nothing else.
 *
 * <p><b>The two-phase split is not cosmetic.</b> Phase 0 — roles, {@code CREATE SCHEMA sm_data
 * AUTHORIZATION sm_dp_owner}, the schema grants and the {@code REVOKE CREATE ON SCHEMA public FROM
 * PUBLIC} — is install-runbook material run ONCE by the cluster administrator (on the PG 14 floor,
 * the bootstrap superuser: only the grantor can revoke, and for anyone else that last statement is a
 * silent {@code WARNING} no-op). It ships as {@code db/bootstrap/sm_data_phase0.sql} for the runbook
 * and the tests to execute, and Flyway must never see it. Phase 1 is everything below, run as
 * {@code sm_dp_migrator}.
 *
 * <p><b>{@link #INIT_SQL} is the load-bearing line.</b> {@code sm_dp_migrator} is already a member
 * of {@code sm_dp_owner}, so membership alone would let it {@code ALTER} owner-owned objects — what
 * {@code SET ROLE} buys is <b>ownership at CREATE time</b>, so every table, index, function and
 * trigger this migrator creates is born owned by {@code sm_dp_owner} rather than by the migrator
 * login. Flyway applies {@code initSql} to each connection it opens, which is why it is expressed
 * here and not as the first line of each migration file (§3.1's header says exactly that).
 *
 * <p><b>{@code createSchemas(false)} is a gate, not an optimisation.</b> {@code sm_data} is created
 * by Phase 0 with an explicit {@code AUTHORIZATION}; letting Flyway create it would produce a
 * migrator-owned schema and quietly diverge from the topology obligation (s) asserts. With the flag
 * off, a database that skipped Phase 0 fails loudly on the first migrate instead of coming up wrong.
 */
public final class SmDataMigrator {

    /** Flyway's per-connection init statement — see the class javadoc for why it is here. */
    public static final String INIT_SQL = "SET ROLE sm_dp_owner";

    /** The data plane's only schema ({@code I7}: no cross-schema joins with {@code sm_control}). */
    public static final String SCHEMA = "sm_data";

    /** Classpath location of the Phase-1 migrations, split out of §3.1 at statement boundaries. */
    public static final String LOCATION = "classpath:db/migration/sm_data";

    /** Classpath location of the Phase-0 script — for the runbook and the bootstrap IT, not Flyway. */
    public static final String PHASE0_RESOURCE = "db/bootstrap/sm_data_phase0.sql";

    private SmDataMigrator() {
    }

    /**
     * Applies Phase 1 with the migrator credential.
     *
     * @param migratorDataSource a {@code sm_dp_migrator} data source — never the runtime one, which
     *                           is not an {@code sm_dp_owner} member and cannot run {@link #INIT_SQL}
     * @return Flyway's own report: which versions were applied, in order
     */
    public static MigrateResult migrate(DataSource migratorDataSource) {
        return Flyway.configure()
                .dataSource(migratorDataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .initSql(INIT_SQL)
                .locations(LOCATION)
                .createSchemas(false)
                .load()
                .migrate();
    }
}
