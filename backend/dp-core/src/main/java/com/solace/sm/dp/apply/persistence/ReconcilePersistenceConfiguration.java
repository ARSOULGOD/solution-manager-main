package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The persistence port of the indeterminate reconciler (plan 010 T6.8): {@code apply_reconciliation} and
 * {@code apply_request.reconcile_class}, over the runtime credential's template, with its OWN readiness check for its OWN
 * objects (plan 010 "Single-owner files": readiness is per configuration, so no task edits another area's expected-table
 * list). Imported through {@code ReconcileConfiguration}, which {@code DataPlaneConfiguration} imports.
 */
@Configuration(proxyBeanMethods = false)
public class ReconcilePersistenceConfiguration {

    /** What V011 adds: one table and one column. A literal; nothing in it is caller-supplied. */
    static final String READINESS_QUERY =
            "SELECT (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sm_data'"
                    + " AND table_name = 'apply_reconciliation')"
                    + " + (SELECT count(*) FROM information_schema.columns WHERE table_schema = 'sm_data'"
                    + " AND table_name = 'apply_request' AND column_name = 'reconcile_class')";

    static final int EXPECTED = 2;

    @Bean
    InitializingBean reconciliationReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED) {
                throw new IllegalStateException("sm_data is not migrated to the reconciliation shape this build expects "
                        + "(found " + found + " of " + EXPECTED + " objects): run the migration step "
                        + "(SmDataMigrationMain) before starting the application");
            }
        };
    }

    /** Both the engine's narrow {@code ReconciliationLedger} port and the reconciler's own scheduling reads and writes. */
    @Bean
    @DependsOn("reconciliationReadiness")
    JdbcReconciliationRepository reconciliationRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcReconciliationRepository(smDataJdbc);
    }
}
