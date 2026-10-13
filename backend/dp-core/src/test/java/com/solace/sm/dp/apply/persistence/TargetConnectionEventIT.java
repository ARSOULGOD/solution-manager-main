package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 013 T1.3 - {@code sm_data} V022 (D-REG sections 9 and 10.2, {@code BE-D56}): the stored key's public fingerprint and
 * the append-only {@code target_connection_event}. As {@code sm_dp_runtime}; every refusal is asserted by SQLSTATE and by
 * the constraint's own name, so a refusal that fires for another reason is not a proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetConnectionEventIT {

    private static final String FP_A = "SHA256:" + "A".repeat(43);
    private static final String FP_B = "SHA256:" + "B".repeat(43);

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    /** A legal connection row (root /srv/sm, prefix sm-) with a host key no other test has used. */
    private static UUID target(UUID tenant) {
        UUID id = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', 'vm-1.corp.example', 22, 'ec2-user',"
                        + " :fp, :ref, '/srv/sm', 'sm-', :by, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", id)
                        .addValue("fp", "SHA256:" + id.toString().replace("-", "").substring(0, 22) + "A".repeat(21))
                        .addValue("ref", UUID.randomUUID()).addValue("by", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        return id;
    }

    private static void event(UUID tenant, UUID target, String kind, String address, String fieldsLiteral,
            String fingerprint) {
        runtime.update("INSERT INTO sm_data.target_connection_event (tenant_id, target_id, event_id, kind, actor,"
                        + " correlation_id, client_address, changed_fields, public_key_fingerprint, recorded_at)"
                        + " VALUES (:t, :x, :e, :kind, :actor, :corr, :addr, CAST(:fields AS text[]), :fp, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target)
                        .addValue("e", UUID.randomUUID()).addValue("kind", kind).addValue("actor", UUID.randomUUID())
                        .addValue("corr", UUID.randomUUID()).addValue("addr", address)
                        .addValue("fields", fieldsLiteral).addValue("fp", fingerprint)
                        .addValue("now", OffsetDateTime.now()));
    }

    // ---- the column -----------------------------------------------------------------------------------------------

    @Test
    void aRowIsBornWithNoPublicFingerprintAndTheRuntimeMayWriteItAfterwards() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        MapSqlParameterSource key = new MapSqlParameterSource("t", tenant).addValue("x", target);

        assertNull(runtime.queryForObject("SELECT credential_public_fingerprint FROM sm_data.target_connection"
                + " WHERE tenant_id = :t AND target_id = :x", key, String.class), "a legacy row: unknown, not empty");

        assertEquals(1, runtime.update("UPDATE sm_data.target_connection SET credential_public_fingerprint = :fp"
                + " WHERE tenant_id = :t AND target_id = :x", key.addValue("fp", FP_A)),
                "the back-fill and the rotation write exactly this column");
        assertEquals(FP_A, runtime.queryForObject("SELECT credential_public_fingerprint FROM sm_data.target_connection"
                + " WHERE tenant_id = :t AND target_id = :x", key, String.class));
    }

    @Test
    void theFingerprintHasTheSshKeygenGrammarAndNothingElse() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        for (String bad : List.of("", "nope", "SHA256:short", "MD5:" + "A".repeat(43), "SHA256:" + "A".repeat(43) + "!",
                "sha256:" + "A".repeat(43), "SHA256:" + "A".repeat(87))) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_credential_fp", () -> runtime.update(
                    "UPDATE sm_data.target_connection SET credential_public_fingerprint = :fp"
                            + " WHERE tenant_id = :t AND target_id = :x",
                    new MapSqlParameterSource("t", tenant).addValue("x", target).addValue("fp", bad)), "[" + bad + "]");
        }
    }

    @Test
    void theNewColumnsAreTheOnlyAdditionsToTheRuntimesUpdateSurfaceOnTheConnectionRow() {
        assertEquals(List.of("credential_public_fingerprint", "credential_ref", "host", "host_key_algorithm",
                        "host_key_fingerprint", "port", "retired_at", "ssh_user"),
                superuser.queryForList("SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'target_connection'"
                        + " AND grantee = 'sm_dp_runtime' AND privilege_type = 'UPDATE' ORDER BY column_name",
                        Map.of(), String.class),
                "the envelope (root and prefix), the transport and the creator stay out of every UPDATE grant");
    }

    // ---- the event table: shape -----------------------------------------------------------------------------------

    @Test
    void everyKindOfTheClosedVocabularyIsWritableAndNothingElseIs() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        event(tenant, target, "registered", "10.0.0.7", "{}", FP_A);
        event(tenant, target, "edited", "10.0.0.7", "{host,port}", null);
        event(tenant, target, "repinned", "::1", "{hostKeyFingerprint}", null);
        event(tenant, target, "credential_rotated", "2001:db8::1", "{}", FP_B);
        event(tenant, target, "retired", "operator-cli", "{}", null);
        assertEquals(5, runtime.queryForObject("SELECT count(*)::int FROM sm_data.target_connection_event"
                + " WHERE tenant_id = :t AND target_id = :x", new MapSqlParameterSource("t", tenant).addValue("x", target),
                Integer.class));

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_kind",
                () -> event(tenant, target, "deleted", "10.0.0.7", "{}", null));
    }

    @Test
    void theClientAddressIsOneShortTokenAndNeverFreeText() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        for (String bad : List.of("", "two words", "a".repeat(65), "10.0.0.7; DROP", "x\ny")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_client_address",
                    () -> event(tenant, target, "retired", bad, "{}", null), "[" + bad + "]");
        }
    }

    @Test
    void changedFieldsAreNamesNeverValues() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        for (String bad : List.of("{\"vm-1.corp.example\"}", "{\"host=vm-1\"}", "{\"a b\"}", "{\"1host\"}",
                "{host,NULL}", "{\"host; DROP\"}", "{" + "a,".repeat(16) + "a}", "{\"" + "x".repeat(65) + "\"}")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_fields",
                    () -> event(tenant, target, "edited", "10.0.0.7", bad, null), "[" + bad + "]");
        }
        event(tenant, target, "edited", "10.0.0.7", "{host,port,sshUser,hostKeyFingerprint}", null);
    }

    @Test
    void anEditOrARePinNamesAtLeastOneFieldAndNoOtherKindNeedsOne() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        for (String kind : List.of("edited", "repinned")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_names_fields",
                    () -> event(tenant, target, kind, "10.0.0.7", "{}", null), kind);
        }
        event(tenant, target, "retired", "10.0.0.7", "{}", null);
    }

    @Test
    void thePublicFingerprintRidesTheTwoEventsThatSetAKeyAndNoOther() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        for (String kind : List.of("registered", "credential_rotated")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_fp_iff_key_set",
                    () -> event(tenant, target, kind, "10.0.0.7", "{}", null), kind + " without one");
        }
        for (String kind : List.of("edited", "repinned", "retired")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_fp_iff_key_set",
                    () -> event(tenant, target, kind, "10.0.0.7", "{host}", FP_A), kind + " carrying one");
        }
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_event_fp",
                () -> event(tenant, target, "registered", "10.0.0.7", "{}", "not-a-fingerprint"));
    }

    @Test
    void anEventNamesAConnectionTheDataPlaneHasAndKeysByTenantFirst() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID other = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "target_connection_event_has_target",
                () -> event(tenant, UUID.randomUUID(), "retired", "10.0.0.7", "{}", null), "an unknown target");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "target_connection_event_has_target",
                () -> event(other, target, "retired", "10.0.0.7", "{}", null),
                "another tenant's event on this tenant's target (I5): the key leads with tenant_id");
        assertEquals(List.of("tenant_id", "target_id", "event_id"), superuser.queryForList(
                "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid"
                        + " AND a.attnum = ANY (i.indkey) WHERE i.indrelid = 'sm_data.target_connection_event'::regclass"
                        + " AND i.indisprimary ORDER BY array_position(i.indkey::int2[], a.attnum)",
                Map.of(), String.class), "tenant first (I5)");
    }

    // ---- the event table: append-only -----------------------------------------------------------------------------

    @Test
    void theRuntimeRoleCanSelectAndInsertAnEventAndCannotUpdateOrDeleteOne() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant);
        event(tenant, target, "edited", "10.0.0.7", "{host}", null);
        MapSqlParameterSource key = new MapSqlParameterSource("t", tenant).addValue("x", target);

        for (String statement : List.of(
                "UPDATE sm_data.target_connection_event SET kind = 'retired' WHERE tenant_id = :t AND target_id = :x",
                "UPDATE sm_data.target_connection_event SET changed_fields = '{}' WHERE tenant_id = :t",
                "UPDATE sm_data.target_connection_event SET actor = gen_random_uuid() WHERE tenant_id = :t",
                "DELETE FROM sm_data.target_connection_event WHERE tenant_id = :t AND target_id = :x")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(statement, key),
                    statement);
        }
        assertEquals(1, runtime.queryForObject("SELECT count(*)::int FROM sm_data.target_connection_event"
                + " WHERE tenant_id = :t AND target_id = :x", key, Integer.class), "the row is untouched");
        assertEquals(List.of("INSERT", "SELECT"), superuser.queryForList(
                "SELECT DISTINCT privilege_type FROM information_schema.table_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'target_connection_event'"
                        + " AND grantee = 'sm_dp_runtime' ORDER BY privilege_type", Map.of(), String.class),
                "SELECT and INSERT at table level, and no UPDATE, DELETE, TRUNCATE or REFERENCES grant at all");
        assertEquals(List.of(), superuser.queryForList("SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'target_connection_event'"
                        + " AND grantee = 'sm_dp_runtime' AND privilege_type = 'UPDATE'", Map.of(), String.class),
                "no column of the event table is runtime-updatable");
    }
}
