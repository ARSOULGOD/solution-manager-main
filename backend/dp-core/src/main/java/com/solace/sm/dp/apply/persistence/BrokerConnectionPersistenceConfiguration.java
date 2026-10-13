package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The persistence beans of the broker push path (plan 011 P11-5.1; OR-107), with their OWN readiness check for their OWN
 * objects, so no task edits another area's expected-table list. Imported by {@code DataPlaneConfiguration}.
 *
 * <p>The check asks for the two tables <b>and for the widened {@code secret_purpose} CHECK</b>: a schema with the tables and
 * without {@code BROKER_CREDENTIAL} would let the application boot and then refuse, at the operator's first registration, the
 * one thing this path needs. The message names the migration by its description, which does not move when the integrator
 * renumbers it. Every statement is schema-qualified: the {@code sm-data} pool pins {@code setSchema("sm_data")}.
 */
@Configuration(proxyBeanMethods = false)
public class BrokerConnectionPersistenceConfiguration {

    static final String MIGRATION = "broker_connection / broker_ingest_counter";

    /** One row per object found, as {@code table schema.x} or {@code purpose BROKER_CREDENTIAL}: the check compares NAMES. */
    static final String READINESS_QUERY = "SELECT 'table ' || table_schema || '.' || table_name"
            + " FROM information_schema.tables WHERE (table_schema = 'sm_data' AND table_name = 'broker_connection')"
            + " OR (table_schema = 'sm_metrics' AND table_name = 'broker_ingest_counter')"
            + " UNION ALL SELECT 'purpose BROKER_CREDENTIAL' FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
            + " JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'sm_secret' AND t.relname = 'secret'"
            + " AND c.conname = 'secret_purpose' AND pg_get_constraintdef(c.oid) LIKE '%BROKER_CREDENTIAL%'";

    static final List<String> EXPECTED = List.of("table sm_data.broker_connection",
            "table sm_metrics.broker_ingest_counter", "purpose BROKER_CREDENTIAL");

    /** What the schema lacks; empty when this build's shape is all there. */
    static List<String> missing(DataSource smDataDataSource) {
        List<String> found = new ArrayList<>();
        try (Connection c = smDataDataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(READINESS_QUERY)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the broker-connection schema could not be checked (SQLSTATE "
                    + e.getSQLState() + ")");
        }
        List<String> missing = new ArrayList<>(EXPECTED);
        missing.removeAll(found);
        return missing;
    }

    static void verify(DataSource smDataDataSource) {
        List<String> missing = missing(smDataDataSource);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("the database is not migrated to the shape this build expects (the "
                    + MIGRATION + " migrations are missing: " + String.join(", ", missing) + "): run the migration step "
                    + "- SmDataMigrationMain with the sm_dp_migrator credential - before starting the application");
        }
    }

    @Bean
    InitializingBean brokerConnectionSchemaReadiness(DataSource smDataDataSource) {
        return () -> verify(smDataDataSource);
    }

    @Bean
    @DependsOn("brokerConnectionSchemaReadiness")
    JdbcBrokerConnectionRepository brokerConnections(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcBrokerConnectionRepository(smDataJdbc);
    }

    @Bean
    @DependsOn("brokerConnectionSchemaReadiness")
    JdbcBrokerCounterRepository brokerCounters(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcBrokerCounterRepository(smDataJdbc);
    }
}
