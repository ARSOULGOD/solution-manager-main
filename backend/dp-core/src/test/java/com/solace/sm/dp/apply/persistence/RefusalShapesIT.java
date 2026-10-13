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
    /** A placement-scoped request since V021 (BE-D44): an intent's placement must be its request's own. */
    private static final UUID PLACEMENT = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        tenant = SmDataPostgres.freshTenant();
        instance = UUID.randomUUID();
        ApplyRows.insertScope(runtime, tenant, instance);
        applyId = ApplyRows.insertRequest(runtime, tenant, instance, PLACEMENT,
                "REQUESTED");
        intentId = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id,"
                        + " placement_id, replica_ref, step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, :p, 'replica-1', 'MATERIALIZE', :k, 'sha256:aaa', :now)",
                new MapSqlParameterSource().addValue("id", intentId).addValue("t", tenant).addValue("p", PLACEMENT)
                        .addValue("a", applyId).addValue("k", "idem-" + intentId)
                        .addValue("now", OffsetDateTime.now()));
    }

    @Test
    void aPartialUniqueIndexRefusalNamesTheIndexJustLikeATableConstraintWould() {
        PSQLException pg = Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "apply_inflight_fence",
                () -> ApplyRows.insertRequest(runtime, tenant, instance, PLACEMENT,
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
                        "apply_step_intent_command_key",              // V028 (plan 015 T1.1): the referenced key of agent_command's composite FK
                        "apply_step_intent_idempotency_key_key",
                        "apply_step_intent_intent_id_step_key",
                        "apply_step_intent_pkey",
                        "apply_step_intent_placement_identity_key",   // V021 (BE-D44): was ..._tenant_id_apply_id_step_replica_ref_key
                        "apply_step_intent_tenant_id_apply_id_fkey",
                        "apply_step_intent_tenant_id_apply_id_intent_id_key"),
                generatedNames("apply_step_intent"));

        // request_has_scope is the one FK §3.1 names explicitly (it is added by a later ALTER
        // TABLE), so it appears here beside the two generated names rather than instead of them. V024 (BE-D48) adds the
        // second explicitly named foreign key, apply_request_placement_registered, the same way.
        assertEquals(List.of("apply_request_pkey", "apply_request_placement_registered",
                        "apply_request_tenant_id_apply_id_key", "request_has_scope"),
                generatedNames("apply_request"));

        assertEquals(List.of("placement_watermark_pkey",
                        "placement_watermark_tenant_id_instance_id_fkey"),
                generatedNames("placement_watermark"));
        // scope_has_instance is plan 010 T2.2's (V008): an explicitly NAMED foreign key, so it too appears beside the
        // generated primary key rather than instead of it.
        assertEquals(List.of("apply_scope_pkey", "scope_has_instance"), generatedNames("apply_scope"));
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
        // 24 = 23 + BE-D16's staged_requires_manual, the one CHECK this repair-wave task added;
        // 46 = 24 + V007's 22 (instance_state 3, target_connection 7, placement 12) — plan 010 T2.1, after
        // OR-82 removed the acquisition_host grammar and host-iff-direct CHECKs.
        // 59 = 46 + V010's 13 (config_generation 11, instance_state's last_generation_nonneg and
        // reached_rung_known) — plan 010 T5.2.
        // 61 = 59 + V013's 2 (designer_layout_is_object, designer_layout_positions) — OR-96.
        // 65 = 61 + V011's 4 (reconciliation_inspections_nonnegative, reconciliation_redrives_nonnegative,
        // reconciliation_last_class_grammar, and apply_request's reconcile_class_grammar) — plan 010 T6.8.
        // 67 = 65 + V012's 2 (target_host_key_fingerprint, placement_health_state) — plan 010 T7.1/T7.2.
        // 86 = 67 + V014's 19 (fleet_action 7: fleet_strategy_known, fleet_batch_size_positive, fleet_on_failure_known,
        // fleet_state_known, fleet_current_batch_nonneg, fleet_terminal_has_timestamp, fleet_outage_confirmed;
        // fleet_member 12: ordinal_nonneg, batch_nonneg, generation_positive, disposition_known, refusal_code_known,
        // admission_refusal_grammar, settled_known, refusal_iff_refused, batch_unless_refused, apply_id_unless_refused,
        // admission_refusal_iff_not_admitted, settled_fits_disposition) — plan 010 T11.2.
        // 88 = 86 + V015's 2 (apply_attempt: pre_image_change_shape, pre_image_change_only_for_rollback_steps) — OR-108 (1).
        // 97 = 88 + V016's 9 (package_digest_grammar; metric_name_grammar, metric_type_known, metric_unit_length,
        // metric_source_kind_known, metric_interval_range; metrics_source_port_range, metrics_host_port_range;
        // active_metrics_generation_nonneg) - plan 011 P11-2.2.
        // 102 = 97 + V017's 5 (broker_connection: scheme, host, port, vpn, username) - plan 011 P11-5.1. V017's
        // secret_purpose re-add lives in sm_secret, so it does not move this sm_data count.
        // 103 = 102 + V018's 1 (placement_start_observation: start_observation_process_ref) - OR-112 (1). V019 is an index, no CHECK.
        // 105 = 103 + V020's 2 (apply_attempt: write_baseline_shape, write_baseline_only_for_materialize) - OR-112 (1), second round.
        // 112 = 105 + V022's 7 (target_connection: credential_fp; target_connection_event 6: kind, client_address, fields,
        // fp, fp_iff_key_set, names_fields) - plan 013 T1.3. V021 added no CHECK; V023 re-creates instance_lifecycle (net 0).
        // 117 = 112 + V023's 5 (instance_retirement: disposition, health_shape, outcome, closed_together,
        // placement_outcome) - plan 012 T3.
        // 132 = 117 + V025's 15 (apply_schedule: generation_positive, rollback_generation_positive, names_one_target,
        // strategy, outage_confirmed, window_ordered, state, target_generation_positive, admitted_generation_positive,
        // target_is_a_rollbacks, reason, state_shape, missed_reason, admitted_inside_window, missed_after_window) - plan 012 T7.
        // 134 = 132 + V025's 2 (admitted_rollback_records_target, admitted_apply_is_its_generation) - W3 review W3B-6.
        // 135 = 134 + V026's 1 (placement: retired_by_needs_retirement) - OR-125 (1).
        // 136 = 135 + V027's 1 (target_connection: host_key_algorithm) - OR-125 (2).
        // 183 = 136 + V028's 47: target_connection 1 (transport_shape; the transport CHECK is dropped and re-added, net 0),
        // target_connection_event 2 (actor_kind, agent_iff_enrolled; the kind CHECK re-added, net 0), then the new tables:
        // agent_identity 6, agent_session 3, agent_lease 8, agent_command 10, agent_report_watermark 1,
        // agent_telemetry_watermark 2, agent_discovery 8, agent_event 6 (= 44); instance_state's acquisition-mode CHECK
        // is re-added, net 0. V029 adds a function, no CHECK. Plan 015 T1.1.
        // 184 = 183 + 1 (agent_lease: verb_ceilings, an object of at most 4096 bytes) - T1.1 fix round, finding 6.
        // 185 = 184 + V030's 1 (agent_session: features within the closed vocabulary) - plan 015 T2.1b.
        // 195 = 185 + plans 013/014's Connections (OR-147 reconciliation; plan 015's V028..V031 above are now V043..V046):
        // V038's 7 (connection_runtime: type, host, port, user, fp, config_root, container_prefix), V039's 2
        // (instance_deployment_mode; placement's target-xor-connection), V040 net 0 (four dropped and re-added), V041 none
        // (it drops a foreign key), V042's 1 (instance_role_non_blank). V037's CHECK is in sm_secret, not counted here.
        assertEquals(195, SmDataPostgres.superuserJdbc().queryForObject(
                        "SELECT count(*)::int FROM pg_constraint con"
                                + " JOIN pg_class c ON c.oid = con.conrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'sm_data' AND con.contype = 'c'",
                        Map.of(), Integer.class),
                "every CHECK in §3.1 is explicitly named; the count changing means one was added, "
                        + "removed, or lost its name");
    }
}
