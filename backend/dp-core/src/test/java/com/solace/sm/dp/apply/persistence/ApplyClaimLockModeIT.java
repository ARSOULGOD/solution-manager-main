package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * W3 review W3I-6 (and its nits W3F-2, W3F-3): the apply claim's row lock must not block a foreign key's
 * {@code FOR KEY SHARE}. Deterministic, two connections: connection 1 holds the WHOLE claim open in a transaction, the exact
 * SELECT ({@link ApplyClaimRepository#CLAIM_SELECT}) AND the exact UPDATE that follows it
 * ({@link ApplyRequestRepository#transition}, REQUESTED to RUNNING, with the stamp trigger it fires), because the update is
 * the second half of the claim and takes a row lock of its own (W3F-2); connection 2, with a short {@code lock_timeout},
 * does what the schedule firer's update does to the request, an insert whose foreign key shares the {@code apply_request}
 * row ({@code apply_reconciliation}), and it must go through. Under {@code FOR UPDATE} it waits and times out (SQLSTATE
 * 55P03), which is the first half of the deadlock the schedule firer lost. A second claimer must still be refused the row
 * ({@code SKIP LOCKED} returns nothing), so exclusivity between claimers is kept. Every statement binds its parameters
 * (W3F-3): the SQL under test is the repository's own text, executed unmodified.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyClaimLockModeIT {

    @Test
    void theClaimsRowLockDoesNotBlockAForeignKeyShareButStillExcludesAnotherClaimer() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, instance);
        UUID applyId = ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, instance, UUID.randomUUID(), "REQUESTED");
        MapSqlParameterSource claimParameters = new MapSqlParameterSource("t", tenant)
                .addValue("instanceWide", ApplyRows.INSTANCE_WIDE);

        try (Connection claimer = SmDataPostgres.superuser().getConnection();
             Connection other = SmDataPostgres.superuser().getConnection()) {
            claimer.setAutoCommit(false);
            other.setAutoCommit(false);
            NamedParameterJdbcTemplate claimerJdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(claimer, true));
            NamedParameterJdbcTemplate otherJdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(other, true));
            assertEquals(List.of(applyId), claimerJdbc.queryForList(ApplyClaimRepository.CLAIM_SELECT, claimParameters,
                    UUID.class), "connection 1 holds the claim's row");
            // W3F-2: the claim's other half. Held in the same transaction, so both locks are live while connection 2 acts.
            assertEquals(1, new ApplyRequestRepository(claimerJdbc).transition(new TenantId(tenant), new ApplyId(applyId),
                    RequestState.REQUESTED, RequestState.RUNNING, null), "and the claim's UPDATE, REQUESTED to RUNNING");

            try {
                otherJdbc.getJdbcTemplate().execute("SET lock_timeout = '1500ms'");
                // a second claimer skips the held row: exclusivity between claimers is kept
                assertEquals(List.of(), otherJdbc.queryForList(ApplyClaimRepository.CLAIM_SELECT, claimParameters,
                        UUID.class), "SKIP LOCKED must not hand the held row to a second claimer");
                // the foreign key's FOR KEY SHARE on the held row must not wait
                otherJdbc.update("INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at,"
                        + " first_indeterminate_at) VALUES (:t, :a, now(), now())",
                        new MapSqlParameterSource("t", tenant).addValue("a", applyId));
            } catch (DataAccessException e) {
                String state = e.getCause() instanceof SQLException sql ? sql.getSQLState() : "?";
                throw new AssertionError("the foreign key's FOR KEY SHARE waited on the claim's lock (SQLSTATE " + state
                        + "): the claim must lock FOR NO KEY UPDATE", e);
            } finally {
                other.rollback();
                claimer.rollback();
            }
        }
    }

    @Test
    void anExplicitUpdateLockStillConflictsWithTheClaim() throws SQLException {
        // the control: the assertion above is not vacuous, a lock that DOES conflict is observed to wait
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, instance);
        UUID applyId = ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, instance, UUID.randomUUID(), "REQUESTED");
        try (Connection one = SmDataPostgres.superuser().getConnection();
             Connection two = SmDataPostgres.superuser().getConnection()) {
            one.setAutoCommit(false);
            two.setAutoCommit(false);
            String row = "SELECT 1 FROM sm_data.apply_request WHERE tenant_id = '" + tenant + "' AND apply_id = '"
                    + applyId + "' FOR UPDATE";
            try (Statement a = one.createStatement(); Statement b = two.createStatement()) {
                a.execute(row);
                b.execute("SET lock_timeout = '500ms'");
                SQLException e = assertThrows(SQLException.class, () -> b.execute(
                        "INSERT INTO sm_data.apply_reconciliation (tenant_id, apply_id, next_attempt_at,"
                                + " first_indeterminate_at) VALUES ('" + tenant + "', '" + applyId + "', now(), now())"));
                assertEquals("55P03", e.getSQLState(), "FOR UPDATE does block the foreign key: the control");
            } finally {
                two.rollback();
                one.rollback();
            }
        }
    }
}
