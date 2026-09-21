package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Obligations <b>(k)</b>, the grant half of <b>(o)</b>, and the identity half of <b>(t)</b> of
 * design §3.3: what the {@code sm_dp_runtime} credential may and may not write.
 *
 * <p>Each refusal is paired with a positive control. A grant that refuses everything is
 * indistinguishable from a broken table, and §3.3 says so explicitly: obligation (k) demands the
 * same statement <i>succeed</i> as the owner, and (t) demands the upsert, the {@code FOR UPDATE} and
 * the legal raise all still work. Without those halves this file would prove only that the tests
 * connect as somebody weak.
 */
@Testcontainers(disabledWithoutDocker = true)
class PrivilegeTopologyIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    // --- (k) intent immutability IS the grant ---------------------------------------------------

    @Test
    void theRuntimeRoleCannotUpdateAnIntentWhileTheOwnerRunningTheSameStatementCan() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance,
                ApplyRows.INSTANCE_WIDE, "REQUESTED");
        UUID intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id,"
                        + " replica_ref, step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, 'replica-1', 'MATERIALIZE', :k, 'sha256:aaa', now())",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant)
                        .addValue("a", applyId).addValue("k", "idem-" + intentId));

        String update = "UPDATE sm_data.apply_step_intent SET config_hash = 'sha256:zzz'"
                + " WHERE intent_id = :id";
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update(update, new MapSqlParameterSource("id", intentId)));

        // The positive control: identical SQL, migrator connection, SET ROLE sm_dp_owner first, one
        // row that certainly matches — so a 1 here means the refusal above was the grant and not a
        // broken table (obligation (k)). Both statements share one connection: SET ROLE is session
        // state. Rolled back inside asOwner, so the intent stays immutable in fact as well as in law.
        assertEquals(1, asOwner("UPDATE sm_data.apply_step_intent SET config_hash = 'sha256:zzz'"
                        + " WHERE intent_id = ?", intentId),
                "the refusal must be the grant, not a broken table (obligation (k))");
    }

    @Test
    void theRuntimeRoleCannotDeleteAnIntentEither() {
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.getJdbcTemplate().execute("DELETE FROM sm_data.apply_step_intent"));
    }

    // --- (o) the column-scoped grant on apply_request -------------------------------------------

    @Test
    void theRuntimeRoleCannotRetenantOrReattributeARequestButMayWalkItsState() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance,
                ApplyRows.INSTANCE_WIDE, "REQUESTED");

        // The I5 write surface the column grant closes: re-tenanting an audit row.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("UPDATE sm_data.apply_request SET tenant_id = :new WHERE apply_id = :a",
                        new MapSqlParameterSource().addValue("new", UUID.randomUUID())
                                .addValue("a", applyId)));

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("UPDATE sm_data.apply_request SET requested_by = 'someone-else'"
                                + " WHERE apply_id = :a",
                        new MapSqlParameterSource().addValue("a", applyId)));

        assertEquals(1, runtime.update(
                        "UPDATE sm_data.apply_request SET state = 'RUNNING' WHERE apply_id = :a",
                        new MapSqlParameterSource().addValue("a", applyId)),
                "state IS granted, and REQUESTED -> RUNNING is not a terminal regression");
    }

    // --- (t) identity half: the column grant on both watermark tables ---------------------------

    @Test
    void theRuntimeRoleCannotMoveAWatermarkRowsIdentityOnEitherTable() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 5);

        // Re-keying a row moves a fence to another scope WITHOUT lowering any watermark, so the
        // value trigger cannot see it. These five refusals are the other half of that door.
        for (String column : List.of("tenant_id", "instance_id")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                    () -> runtime.update("UPDATE sm_data.apply_scope SET " + column + " = :new"
                                    + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance).addValue("new", UUID.randomUUID())));
        }
        for (String column : List.of("tenant_id", "instance_id", "placement_id")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                    () -> runtime.update("UPDATE sm_data.placement_watermark SET " + column + " = :new"
                                    + " WHERE tenant_id = :t AND instance_id = :i",
                            ApplyRows.scope(tenant, instance).addValue("new", UUID.randomUUID())));
        }

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("DELETE FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i", ApplyRows.scope(tenant, instance)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("DELETE FROM sm_data.placement_watermark"
                        + " WHERE tenant_id = :t AND instance_id = :i", ApplyRows.scope(tenant, instance)));
    }

    @Test
    void theNarrowGrantStillAdmitsEveryStepOfTheAdmissionTransaction() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();

        // 1. the single-arbiter upsert
        assertEquals(1, runtime.update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                ApplyRows.scope(tenant, instance)));
        assertEquals(0, runtime.update(
                "INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                ApplyRows.scope(tenant, instance)),
                "the second upsert is the concurrent admitter's — it must be a no-op, not an error");

        // 2. SELECT … FOR UPDATE — which needs UPDATE on at least one column, and has exactly one
        assertEquals(Integer.valueOf(0), runtime.queryForObject(
                "SELECT instance_watermark FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                ApplyRows.scope(tenant, instance), Integer.class));

        // 3. the raise
        assertEquals(1, runtime.update(
                "UPDATE sm_data.apply_scope SET instance_watermark = 7"
                        + " WHERE tenant_id = :t AND instance_id = :i", ApplyRows.scope(tenant, instance)));
        ApplyRows.insertPlacementWatermark(runtime, tenant, instance, placement, 3);
        assertEquals(1, runtime.update(
                "UPDATE sm_data.placement_watermark SET generation = 9"
                        + " WHERE tenant_id = :t AND instance_id = :i AND placement_id = :p",
                ApplyRows.scope(tenant, instance).addValue("p", placement)));
    }

    // --- (I6) resolution_note: the one free-text column, and the runtime cannot write it ---------

    /**
     * The database half of "v1 writes no free text". The Java half ({@code Resolution} has no note
     * component, the repository's SET list omits the column) guards the engine's own paths; this
     * guards every OTHER path — a future or mistaken persistence call issuing a perfectly legal
     * resolution UPDATE that also carries a note. The refusal is {@code 42501}, the GRANT's, and it
     * comes before any CHECK or trigger could speak. The owner control proves the column is writable
     * by someone, so the refusal is the grant and not a broken column.
     */
    @Test
    void theRuntimeRoleCannotWriteResolutionNoteOnALegalResolutionUpdateButTheOwnerCan() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        UUID applyId = ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE,
                "APPLY_INDETERMINATE");

        String legalResolutionWithNote = "UPDATE sm_data.apply_request"
                + " SET state = 'INDETERMINATE_RESOLVED', terminal_at = now(), resolved_by = 'op:res',"
                + "     resolution_outcome = 'applied',"
                + "     resolution_observed = CAST(:ev AS jsonb), resolution_note = :note"
                + " WHERE tenant_id = :t AND apply_id = :a";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ev", "{\"stagedGeneration\": 1, \"replicas\": [\"r0\"]}")
                .addValue("note", "pasted: token=SNTL-note-9d2e")
                .addValue("t", tenant).addValue("a", applyId);
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update(legalResolutionWithNote, params));
        // Even naming the column with NULL: it is the privilege that is absent, not a value rule.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.update("UPDATE sm_data.apply_request SET resolution_note = NULL"
                        + " WHERE tenant_id = :t AND apply_id = :a", params));
        assertEquals("APPLY_INDETERMINATE", runtime.queryForObject(
                        "SELECT state FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                        params, String.class),
                "the row is untouched by the refusals");

        // The positive control, rolled back inside asOwner.
        assertEquals(1, asOwner("UPDATE sm_data.apply_request SET resolution_note = NULL"
                        + " WHERE tenant_id = ? AND apply_id = ?", tenant, applyId),
                "the owner holds the column privilege, so the refusal above is the grant");
    }

    /** The INSERT door: a row cannot be BORN with a note either. */
    @Test
    void theRuntimeRoleCannotInsertARowNamingResolutionNoteButTheOwnerCan() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        String bornWithNote = "INSERT INTO sm_data.apply_request"
                + " (apply_id, tenant_id, instance_id, placement_id, operation, generation, strategy,"
                + "  requested_by, requested_at, state, resolution_note)"
                + " VALUES (?, ?, ?, ?, 'apply', 1, 'manual', 'op:born', now(), 'REQUESTED', NULL)";
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null,
                () -> runtime.getJdbcTemplate().update(bornWithNote, UUID.randomUUID(), tenant,
                        instance, ApplyRows.INSTANCE_WIDE));
        assertEquals(1, asOwner(bornWithNote, UUID.randomUUID(), tenant, instance,
                        ApplyRows.INSTANCE_WIDE),
                "the owner may name the column; the runtime's refusal is the INSERT column grant");
        // …and the runtime's own INSERT, which never names the column, is unaffected.
        ApplyRows.insertRequest(runtime, tenant, instance, ApplyRows.INSTANCE_WIDE, "REQUESTED");
    }

    // --- the catalog view of the same facts, so a grant cannot widen unnoticed ------------------

    @Test
    void theCatalogAgreesWithTheDesignAboutEveryColumnScopedUpdateGrant() {
        assertEquals(List.of("attempt_summary", "resolution_observed",
                        "resolution_outcome", "resolved_by", "state", "terminal_at"),
                updatableColumns("apply_request"),
                "the six columns §3.1's GRANT lists — identity is not runtime-writable, and neither "
                        + "is resolution_note (I6: v1 writes no free text)");
        assertEquals(List.of("apply_id", "attempt_summary", "generation", "instance_id", "operation",
                        "outage_confirmed_by", "placement_id", "requested_at", "requested_by",
                        "resolution_observed", "resolution_outcome", "resolved_by", "state",
                        "strategy", "tenant_id", "terminal_at"),
                columnsWithPrivilege("apply_request", "INSERT"),
                "INSERT is column-scoped too — every column but resolution_note — or a row could be "
                        + "BORN with a note");
        assertEquals(List.of("SELECT"), superuser.getJdbcTemplate().queryForList(
                        "SELECT privilege_type FROM information_schema.column_privileges"
                                + " WHERE table_schema = 'sm_data' AND table_name = 'apply_request'"
                                + "   AND grantee = 'sm_dp_runtime' AND column_name = 'resolution_note'"
                                + " ORDER BY 1", String.class),
                "the runtime holds SELECT and nothing else on resolution_note (table-level SELECT is "
                        + "expanded per column by information_schema)");
        assertEquals(List.of("instance_watermark"), updatableColumns("apply_scope"));
        assertEquals(List.of("generation"), updatableColumns("placement_watermark"));
        assertEquals(List.of(), updatableColumns("apply_step_intent"),
                "IMMUTABLE: apply_step_intent must appear in no UPDATE grant at all");

        assertEquals(14, updatableColumns("apply_attempt").size(),
                "apply_attempt is granted table-wide UPDATE — all 14 columns");

        assertEquals(List.of("apply_attempt"),
                superuser.queryForList(
                        "SELECT table_name FROM information_schema.table_privileges"
                                + " WHERE table_schema = 'sm_data' AND grantee = 'sm_dp_runtime'"
                                + "   AND privilege_type = 'DELETE' ORDER BY table_name",
                        Map.of(), String.class),
                "only the prunable diagnostics table may be deleted from (§3.3 retention split)");
    }

    private static List<String> updatableColumns(String table) {
        return columnsWithPrivilege(table, "UPDATE");
    }

    private static List<String> columnsWithPrivilege(String table, String privilege) {
        return superuser.queryForList(
                "SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = :tbl"
                        + "   AND grantee = 'sm_dp_runtime' AND privilege_type = :priv"
                        + " ORDER BY column_name",
                new MapSqlParameterSource("tbl", table).addValue("priv", privilege), String.class);
    }

    /**
     * Runs one statement on a migrator connection that has first done {@code SET ROLE sm_dp_owner}.
     * Rolled back, always: the positive controls exist to prove the statement is permitted, not to
     * leave owner-made changes behind for the next test. Parameters are bound, never interpolated.
     */
    private static int asOwner(String sql, Object... params) {
        try (Connection c = SmDataPostgres.migratorDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(SmDataMigrator.INIT_SQL);
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                int updated = ps.executeUpdate();
                c.rollback();
                return updated;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("owner control failed: " + sql, e);
        }
    }

    @Test
    void theOwnerControlReallyRunsAsTheOwnerAndNotAsTheMigratorLogin() {
        // Cheap, and it closes the hole that would make every asOwner() control vacuous: if SET ROLE
        // silently did nothing, the controls would still pass on the migrator's own membership.
        try (Connection c = SmDataPostgres.migratorDataSource().getConnection();
                Statement s = c.createStatement()) {
            s.execute(SmDataMigrator.INIT_SQL);
            var rs = s.executeQuery("SELECT current_user");
            assertTrue(rs.next());
            assertEquals("sm_dp_owner", rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
