package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The runtime-role readiness probe's three shapes, pure (no database): each of the tables, V021's column and V024's
 * validated constraint is a separate count, answered here by the query that asks for it. Docker-free, so it runs
 * offline; the same probe against a real V023 and V024 schema is {@code ApplyPlacementGuardUpgradeIT}'s.
 */
class ApplyPersistenceReadinessTest {

    private static DataSource answering(int tables, int placementColumn, int placementGuard) throws SQLException {
        Statement statement = mock(Statement.class);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            int value;
            if (sql.equals(ApplyPersistenceConfiguration.READINESS_QUERY)) {
                value = tables;
            } else if (sql.equals(ApplyPersistenceConfiguration.PLACEMENT_COLUMN_QUERY)) {
                value = placementColumn;
            } else if (sql.equals(ApplyPersistenceConfiguration.PLACEMENT_GUARD_QUERY)) {
                value = placementGuard;
            } else {
                throw new AssertionError("an unexpected readiness query: " + sql);
            }
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getInt(1)).thenReturn(value);
            return rs;
        });
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private static void check(DataSource ds) throws Exception {
        new ApplyPersistenceConfiguration().smDataSchemaReadiness(ds).afterPropertiesSet();
    }

    @Test
    void aFullyMigratedSchemaIsReady() throws Exception {
        assertDoesNotThrow(() -> check(answering(ApplyPersistenceConfiguration.EXPECTED_TABLES, 1, 1)));
    }

    /** W3 review W3A-3: V023 has every table and the V021 column, and lacks only the V024 guard. */
    @Test
    void aV023SchemaWithoutTheValidatedPlacementGuardIsRefusedNamingTheRunbookSection() throws Exception {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> check(answering(ApplyPersistenceConfiguration.EXPECTED_TABLES, 1, 0)));
        assertTrue(refused.getMessage().contains("apply_request_placement_registered")
                && refused.getMessage().contains("V024")
                && refused.getMessage().contains("Upgrade across the apply-request placement guard"),
                refused.getMessage());
    }

    @Test
    void theGuardProbeAsksForAValidatedConstraintNotJustAPresentOne() {
        assertTrue(ApplyPersistenceConfiguration.PLACEMENT_GUARD_QUERY.contains("convalidated"),
                "an unvalidated guard is not the guard");
        assertTrue(ApplyPersistenceConfiguration.PLACEMENT_GUARD_QUERY.contains("apply_request_placement_registered"));
    }

    @Test
    void aV020SchemaStillFailsOnTheV021ColumnFirst() throws Exception {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> check(answering(ApplyPersistenceConfiguration.EXPECTED_TABLES, 0, 0)));
        assertTrue(refused.getMessage().contains("placement_id is absent"), refused.getMessage());
    }

    @Test
    void anUnmigratedSchemaStillFailsOnTheTableCount() throws Exception {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> check(answering(0, 0, 0)));
        assertTrue(refused.getMessage().contains("found 0 of"), refused.getMessage());
    }
}
