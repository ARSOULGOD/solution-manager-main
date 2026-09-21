package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a refusal LOOKS LIKE over JDBC, per violation class — the input the whole
 * assert-by-name discipline of §3.3 depends on.
 *
 * <p>The evidence report {@code evidence/ddl-execution-pg14.md} recorded these as psql
 * {@code CONSTRAINT NAME:} lines. Over JDBC the same facts arrive through
 * {@code PSQLException.getServerErrorMessage()}, and they are NOT uniform: a unique index and a
 * named CHECK give a constraint name, a NOT NULL violation gives a <b>column</b> and no constraint,
 * and a privilege refusal gives neither. Repository code and every future refusal test are written
 * against that shape, so it is pinned here rather than rediscovered.
 *
 * <p>It also pins the PostgreSQL-<b>generated</b> constraint names the design's inline
 * {@code UNIQUE (…)} clauses produce. Those names are not in the design text — they are derived
 * from table and column names — so any column rename silently renames a constraint that tests and
 * error handling refer to. Listing them makes that a visible change.
 */
@Testcontainers(disabledWithoutDocker = true)
class RefusalShapesIT {

    private static NamedParameterJdbcTemplate runtime;
    private static UUID tenant;
    private static UUID instance;
    private static UUID applyId;
    private static UUID intentId;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        applyId = ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE,
                "REQUESTED");
        intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id,"
                        + " replica_ref, step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, 'replica-1', 'MATERIALIZE', :k, 'sha256:aaa', :now)",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant)
                        .addValue("a", applyId).addValue("k", "idem-" + intentId)
                        .addValue("now", OffsetDateTime.now()));
    }

    @Test
    void aPartialUniqueIndexRefusalNamesTheIndexJustLikeATableConstraintWould() {
        PSQLException pg = Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "apply_inflight_fence",
                () -> ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE,
                        "REQUESTED"));
        assertEquals("apply_request", pg.getServerErrorMessage().getTable());
        assertNull(pg.getServerErrorMessage().getColumn(),
                "a unique violation names the index, never a column");
    }

    @Test
    void aNamedCheckRefusalNamesTheCheck() {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "apply_has_generation",
                () -> runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id,"
                                + " instance_id, placement_id, operation, generation, strategy,"
                                + " requested_by, requested_at, state)"
                                + " VALUES (:a, :t, :i, :p, 'apply', NULL, 'manual', 'op', :now, 'REQUESTED')",
                        new MapSqlParameterSource().addValue("a", UUID.randomUUID())
                                .addValue("t", tenant).addValue("i", instance)
                                .addValue("p", UUID.randomUUID()).addValue("now", OffsetDateTime.now())));
    }

    @Test
    void aNotNullRefusalCarriesTheColumnAndNoConstraintAtAll() {
        PSQLException pg = Refusals.assertRefused(Refusals.NOT_NULL_VIOLATION, null,
                () -> runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id,"
                                + " instance_id, placement_id, operation, generation, strategy,"
                                + " requested_by, requested_at, state)"
                                + " VALUES (:a, :t, :i, NULL, 'apply', 1, 'manual', 'op', :now, 'REQUESTED')",
                        new MapSqlParameterSource().addValue("a", UUID.randomUUID())
                                .addValue("t", tenant).addValue("i", instance)
                                .addValue("now", OffsetDateTime.now())));
        assertNull(pg.getServerErrorMessage().getConstraint(),
                "23502 has no constraint name — code that reads getConstraint() to classify a "
                        + "refusal must handle null, and this is the case that produces it");
        assertEquals("placement_id", pg.getServerErrorMessage().getColumn(),
                "the column is where a NOT NULL violation identifies itself");
        assertEquals("apply_request", pg.getServerErrorMessage().getTable());
    }

    @Test
    void aPrivilegeRefusalCarriesNeitherConstraintNorColumnNorTable() {
        PSQLException pg = Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.getJdbcTemplate()
                        .execute("UPDATE sm_data.apply_step_intent SET config_hash = 'x'"));
        assertNull(pg.getServerErrorMessage().getConstraint());
        assertNull(pg.getServerErrorMessage().getColumn());
        assertNull(pg.getServerErrorMessage().getTable(),
                "42501 identifies its object only in the free-text message ('permission denied for "
                        + "table apply_step_intent') — there is no structured field to match on");
    }

    @Test
    void aTriggerRefusalCarriesOnlyItsOwnMessage() {
        PSQLException pg = Refusals.assertRaised("instance watermark may not decrease (4 -> 1)",
                () -> {
                    runtime.update("UPDATE sm_data.apply_scope SET instance_watermark = 4"
                            + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance));
                    runtime.update("UPDATE sm_data.apply_scope SET instance_watermark = 1"
                            + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance));
                });
        assertNull(pg.getServerErrorMessage().getConstraint(),
                "P0001 is a RAISE, not a constraint — the wording IS the identity, which is exactly "
                        + "why §3.1 puts the table name inside each message");
    }

    @Test
    void theGeneratedNamesOfTheDesignsUnnamedUniquesAndForeignKeysArePinned() {
        assertEquals(List.of(
                        "apply_attempt_intent_id_attempt_no_key",
                        "apply_attempt_intent_id_step_fkey",
                        "apply_attempt_pkey",
                        "apply_attempt_tenant_id_apply_id_fkey",
                        "apply_attempt_tenant_id_apply_id_intent_id_fkey"),
                generatedNames("apply_attempt"));

        assertEquals(List.of(
                        "apply_step_intent_idempotency_key_key",
                        "apply_step_intent_intent_id_step_key",
                        "apply_step_intent_pkey",
                        "apply_step_intent_tenant_id_apply_id_fkey",
                        "apply_step_intent_tenant_id_apply_id_intent_id_key",
                        "apply_step_intent_tenant_id_apply_id_step_replica_ref_key"),
                generatedNames("apply_step_intent"));

        // request_has_scope is the one FK §3.1 names explicitly (it is added by a later ALTER
        // TABLE), so it appears here beside the two generated names rather than instead of them.
        assertEquals(List.of("apply_request_pkey", "apply_request_tenant_id_apply_id_key",
                        "request_has_scope"),
                generatedNames("apply_request"));

        assertEquals(List.of("placement_watermark_pkey",
                        "placement_watermark_tenant_id_instance_id_fkey"),
                generatedNames("placement_watermark"));
        assertEquals(List.of("apply_scope_pkey"), generatedNames("apply_scope"));
    }

    /** Constraint names PostgreSQL derived, i.e. everything but the CHECKs the design names. */
    private static List<String> generatedNames(String table) {
        return SmDataPostgres.superuserJdbc().queryForList(
                "SELECT con.conname FROM pg_constraint con"
                        + " JOIN pg_class c ON c.oid = con.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'sm_data' AND c.relname = :tbl AND con.contype <> 'c'"
                        + " ORDER BY con.conname",
                new MapSqlParameterSource("tbl", table), String.class);
    }

    @Test
    void theDesignsOwnNamedConstraintsAreAllPresent() {
        // 24 = 23 + BE-D16's staged_requires_manual, the one CHECK this repair-wave task added.
        assertEquals(24, SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT count(*)::int FROM pg_constraint con"
                                + " JOIN pg_class c ON c.oid = con.conrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'sm_data' AND con.contype = 'c'",
                        Map.of(), Integer.class),
                "every CHECK in §3.1 is explicitly named; the count changing means one was added, "
                        + "removed, or lost its name");
    }
}
