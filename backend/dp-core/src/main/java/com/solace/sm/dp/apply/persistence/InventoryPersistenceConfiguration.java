package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TargetConnectionRows;
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
 * The three inventory persistence ports over the runtime credential's template (plan 010 T2.5), beside
 * {@link SecretPersistenceConfiguration}. Declares its OWN readiness check for its OWN tables, so no task edits
 * {@link ApplyPersistenceConfiguration}'s {@code EXPECTED_TABLES}. Imported by {@code DataPlaneConfiguration} (T2.6).
 */
@Configuration(proxyBeanMethods = false)
public class InventoryPersistenceConfiguration {

    /** The tables V007 creates, and the whole of what the runtime role may look for. A literal: nothing caller-supplied. */
    static final String READINESS_QUERY = "SELECT count(*) FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name IN ('instance_state','placement','target_connection')";

    static final int EXPECTED_TABLES = 3;

    /**
     * What {@code sm_data} V022 adds and plan 013 T1.4 is the first reader of: the stored key's public fingerprint
     * ({@code target_connection.credential_public_fingerprint}) and the {@code target_connection_event} table. A literal
     * again. Their absence means a V021 schema, on which the registration's INSERT would fail at the first row, so the
     * new binary must not go Ready on it. Both are visible to the runtime role, which holds SELECT on them.
     */
    static final String V022_QUERY = "SELECT (SELECT count(*) FROM information_schema.columns"
            + " WHERE table_schema = 'sm_data' AND table_name = 'target_connection'"
            + " AND column_name = 'credential_public_fingerprint')"
            + " + (SELECT count(*) FROM information_schema.tables"
            + " WHERE table_schema = 'sm_data' AND table_name = 'target_connection_event')";

    static final int EXPECTED_V022_OBJECTS = 2;

    /**
     * What {@code sm_data} V026 adds (OR-125 (1)): {@code placement.created_by} and {@code placement.retired_by}. Every
     * placement read selects {@code created_by}, so a V025 schema would fail at the first read; the build must not go Ready
     * on it.
     */
    static final String V026_QUERY = "SELECT count(*) FROM information_schema.columns"
            + " WHERE table_schema = 'sm_data' AND table_name = 'placement'"
            + " AND column_name IN ('created_by', 'retired_by')";

    static final int EXPECTED_V026_OBJECTS = 2;

    /**
     * What {@code sm_data} V027 adds (OR-125 (2)): {@code target_connection.host_key_algorithm}. Every connection read selects
     * it, so a V026 schema would fail at the first read; the build must not go Ready on it.
     */
    static final String V027_QUERY = "SELECT count(*) FROM information_schema.columns"
            + " WHERE table_schema = 'sm_data' AND table_name = 'target_connection'"
            + " AND column_name = 'host_key_algorithm'";

    static final int EXPECTED_V027_OBJECTS = 1;

    @Bean
    InitializingBean inventorySchemaReadiness(DataSource smDataDataSource) {
        return () -> {
            int found;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(READINESS_QUERY)) {
                rs.next();
                found = rs.getInt(1);
            }
            if (found != EXPECTED_TABLES) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects (found "
                        + found + " of " + EXPECTED_TABLES + " inventory tables): run the migration step "
                        + "— SmDataMigrationMain with the sm_dp_migrator credential — before starting the application");
            }
            int v022;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(V022_QUERY)) {
                rs.next();
                v022 = rs.getInt(1);
            }
            if (v022 != EXPECTED_V022_OBJECTS) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects "
                        + "(target_connection.credential_public_fingerprint or target_connection_event is absent, "
                        + "V022 has not run): run the migration step — SmDataMigrationMain with the sm_dp_migrator "
                        + "credential — before starting the application");
            }
            int v026;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(V026_QUERY)) {
                rs.next();
                v026 = rs.getInt(1);
            }
            if (v026 != EXPECTED_V026_OBJECTS) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects "
                        + "(placement.created_by or placement.retired_by is absent, V026 has not run): run the migration "
                        + "step — SmDataMigrationMain with the sm_dp_migrator credential — before starting the application");
            }
            int v027;
            try (Connection c = smDataDataSource.getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(V027_QUERY)) {
                rs.next();
                v027 = rs.getInt(1);
            }
            if (v027 != EXPECTED_V027_OBJECTS) {
                throw new IllegalStateException("sm_data is not migrated to the shape this build expects "
                        + "(target_connection.host_key_algorithm is absent, V027 has not run): run the migration "
                        + "step — SmDataMigrationMain with the sm_dp_migrator credential — before starting the application");
            }
        };
    }

    @Bean
    @DependsOn("inventorySchemaReadiness")
    InstanceRows instanceRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcInstanceStateRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("inventorySchemaReadiness")
    PlacementRows placementRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcPlacementRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("inventorySchemaReadiness")
    TargetConnectionRows targetConnectionRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcTargetConnectionRepository(smDataJdbc);
    }
}
