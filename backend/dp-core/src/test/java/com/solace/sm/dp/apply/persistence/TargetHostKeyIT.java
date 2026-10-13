package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T7.1 - {@code sm_data} V012 section 1 (OR-89's residual): every host key a target's host offers is unique
 * per organization among live targets, whichever of them a registration pinned. As {@code sm_dp_runtime}, the credential
 * the application holds; every refusal is asserted by SQLSTATE and by the index's name.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetHostKeyIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private static String fp() {
        return "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static UUID target(UUID tenant, String pinned) {
        UUID target = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', 'vm.corp.example', 22, 'sm', :fp,"
                        + " :cred, '/srv/sm', 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target).addValue("fp", pinned)
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        return target;
    }

    private static void addKey(UUID tenant, UUID target, String fingerprint) {
        runtime.update("INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint)"
                        + " VALUES (:t, :x, :fp)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target).addValue("fp", fingerprint));
    }

    private static List<String> keys(UUID tenant, UUID target) {
        return runtime.queryForList("SELECT fingerprint FROM sm_data.target_host_key"
                        + " WHERE tenant_id = :t AND target_id = :x ORDER BY fingerprint",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target), String.class);
    }

    @Test
    void theDatabaseRecordsTheTargetsPinnedKeyWhoeverInsertsIt() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pinned = fp();
        UUID target = target(tenant, pinned);

        assertEquals(List.of(pinned), keys(tenant, target), "a trigger, not only the registration command");
    }

    @Test
    void aSecondLiveTargetHoldingAnyOfAHostsKeysIsRefusedWhicheverItPins() {
        UUID tenant = SmDataPostgres.freshTenant();
        String ed = fp();
        String rsa = fp();
        UUID first = target(tenant, ed);
        addKey(tenant, first, rsa);

        // a later registration pins the host's OTHER key type: V007's pinned-key index cannot see it, this one can
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "target_host_key_once", () -> target(tenant, rsa));
        // and a key added to an existing second target
        UUID second = target(tenant, fp());
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "target_host_key_once", () -> addKey(tenant, second, rsa));
        assertEquals(0, runtime.queryForObject("SELECT count(*)::int FROM sm_data.target_connection"
                        + " WHERE tenant_id = :t AND host_key_fingerprint = :fp",
                new MapSqlParameterSource().addValue("t", tenant).addValue("fp", rsa), Integer.class),
                "the refused target row was rolled back with its key");
    }

    @Test
    void anotherOrganizationMayHoldTheSameKeys() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID other = SmDataPostgres.freshTenant();
        String ed = fp();
        String rsa = fp();
        UUID first = target(tenant, ed);
        addKey(tenant, first, rsa);

        UUID elsewhere = target(other, rsa);
        addKey(other, elsewhere, ed);

        assertEquals(2, keys(other, elsewhere).size());
    }

    @Test
    void retiringATargetFreesEveryOneOfItsKeys() {
        UUID tenant = SmDataPostgres.freshTenant();
        String ed = fp();
        String rsa = fp();
        UUID first = target(tenant, ed);
        addKey(tenant, first, rsa);

        runtime.update("UPDATE sm_data.target_connection SET retired_at = :now"
                        + " WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("t", tenant)
                        .addValue("x", first));

        UUID replacement = target(tenant, rsa);
        addKey(tenant, replacement, ed);
        assertEquals(2, keys(tenant, replacement).size());
        assertEquals(2, keys(tenant, first).size(), "the retired target's rows are kept, not deleted");
    }

    @Test
    void rePinningATargetRecordsTheNewKeyToo() {
        UUID tenant = SmDataPostgres.freshTenant();
        String before = fp();
        String after = fp();
        UUID target = target(tenant, before);

        runtime.update("UPDATE sm_data.target_connection SET host_key_fingerprint = :fp"
                        + " WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource().addValue("fp", after).addValue("t", tenant).addValue("x", target));

        assertTrue(keys(tenant, target).containsAll(List.of(before, after)));
        // the OLD pin is still a key of this host: another target may not take it
        UUID second = target(tenant, fp());
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "target_host_key_once", () -> addKey(tenant, second, before));
    }

    @Test
    void theRuntimeRoleCannotDeleteOrRewriteAKey() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = target(tenant, fp());
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.target_host_key WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.target_host_key SET fingerprint = :fp WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("fp", fp()).addValue("t", tenant)));
        assertEquals(1, keys(tenant, target).size());
    }
}
