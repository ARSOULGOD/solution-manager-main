package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link EntitlementMain} against a migrated database, connecting as {@code sm_cp_runtime}: the row it writes, the
 * audit row the trigger writes for it (operation {@code ENTITLEMENT_SET}, actor {@code NULL}, client address
 * {@code operator-cli}), and every refusal that must leave the entitlement table untouched.
 */
class EntitlementMainIT {

    private static final String KEY = "entitlement-main-it";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "013");

    @Test
    void theCommandWritesAnAuditedRowWithNoActorOnEntitlementOnly() {
        Seed seed = seed();

        Run run = run(env(seed, "cache", "4", seed.username));

        assertThat(run.code).as(run.err).isEqualTo(0);
        assertThat(run.out).contains("cache").contains("4").doesNotContain(SmControlPostgres.RUNTIME_USERNAME + ":");
        Map<String, Object> row = migrator.queryForMap("SELECT max_instances, set_by FROM sm_control.entitlement"
                + " WHERE organization_id = :o AND solution = 'cache'", Map.of("o", seed.organization));
        assertThat(row.get("max_instances")).isEqualTo(4);
        assertThat(row.get("set_by")).isEqualTo(seed.principal);
        List<Map<String, Object>> audit = migrator.queryForList(
                "SELECT table_name, operation, audit_operation, actor_principal_id, client_address, db_role"
                        + " FROM sm_control.change_audit WHERE table_name = 'entitlement'"
                        + " AND row_key ->> 'organization_id' = :o", Map.of("o", seed.organization.toString()));
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("operation")).isEqualTo("INSERT");
        assertThat(audit.get(0).get("audit_operation")).isEqualTo("ENTITLEMENT_SET");
        assertThat(audit.get(0).get("actor_principal_id")).isNull();
        assertThat(audit.get(0).get("client_address")).isEqualTo("operator-cli");
        assertThat(audit.get(0).get("db_role")).isEqualTo("sm_cp_runtime");
    }

    @Test
    void runningItAgainChangesTheLimitAndIsAuditedAsAnUpdate() {
        Seed seed = seed();
        assertThat(run(env(seed, "cache", "4", seed.username)).code).isEqualTo(0);

        Run second = run(env(seed, "cache", "9", seed.username));

        assertThat(second.code).as(second.err).isEqualTo(0);
        assertThat(migrator.queryForObject("SELECT max_instances FROM sm_control.entitlement"
                + " WHERE organization_id = :o AND solution = 'cache'", Map.of("o", seed.organization),
                Integer.class)).isEqualTo(9);
        assertThat(migrator.queryForList("SELECT operation FROM sm_control.change_audit WHERE table_name ="
                + " 'entitlement' AND row_key ->> 'organization_id' = :o ORDER BY change_audit_id",
                Map.of("o", seed.organization.toString()), String.class)).containsExactly("INSERT", "UPDATE");
    }

    @Test
    void aSetByUsernameThatDoesNotExistExitsThreeAndWritesNothing() {
        Seed seed = seed();

        Run run = run(env(seed, "cache", "4", "nobody-" + UUID.randomUUID()));

        assertThat(run.code).as(run.err).isEqualTo(EntitlementMain.EXIT_UNKNOWN_PRINCIPAL);
        assertNothingWritten(seed);
    }

    @Test
    void aSetByPrincipalOfAnotherOrganizationExitsThreeAndWritesNothing() {
        Seed seed = seed();
        Seed other = seed();

        Run run = run(env(seed, "cache", "4", other.username));

        assertThat(run.code).as(run.err).isEqualTo(EntitlementMain.EXIT_UNKNOWN_PRINCIPAL);
        assertNothingWritten(seed);
    }

    @Test
    void anOrganizationThatDoesNotExistExitsThreeAndWritesNothing() {
        Seed seed = seed();
        Map<String, String> env = env(seed, "cache", "4", seed.username);
        env.put(EntitlementMain.ORGANIZATION_VARIABLE, UUID.randomUUID().toString());

        Run run = run(env);

        assertThat(run.code).as(run.err).isEqualTo(EntitlementMain.EXIT_UNKNOWN_PRINCIPAL);
        assertNothingWritten(seed);
    }

    @Test
    void aWrongPasswordFailsWithExitOneAndNeverEchoesIt() {
        Seed seed = seed();
        Map<String, String> env = env(seed, "cache", "4", seed.username);
        env.put(EntitlementMain.PASSWORD_VARIABLE, "wrong-" + UUID.randomUUID());

        Run run = run(env);

        assertThat(run.code).isEqualTo(EntitlementMain.EXIT_FAILED);
        assertThat(run.err).doesNotContain(env.get(EntitlementMain.PASSWORD_VARIABLE));
        assertNothingWritten(seed);
    }

    // ---------------------------------------------------------------------------------------- helpers

    private record Seed(UUID organization, UUID principal, String username) {
    }

    private Seed seed() {
        UUID organization = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        String username = "entitlement-" + principal;
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id) VALUES (?)",
                organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username,"
                + " credential_hash) VALUES (?, ?, 'local', ?, 'argon2id-placeholder-hash')", principal, organization,
                username);
        return new Seed(organization, principal, username);
    }

    private Map<String, String> env(Seed seed, String solution, String max, String setBy) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(EntitlementMain.URL_VARIABLE, SmControlPostgres.runtimeUrlAtVersion(KEY));
        env.put(EntitlementMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(EntitlementMain.PASSWORD_VARIABLE, SmControlPostgres.runtimePasswordAtVersion(KEY));
        env.put(EntitlementMain.ORGANIZATION_VARIABLE, seed.organization.toString());
        env.put(EntitlementMain.SOLUTION_VARIABLE, solution);
        env.put(EntitlementMain.MAX_INSTANCES_VARIABLE, max);
        env.put(EntitlementMain.SET_BY_VARIABLE, setBy);
        return env;
    }

    private void assertNothingWritten(Seed seed) {
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.entitlement WHERE organization_id = :o",
                Map.of("o", seed.organization), Long.class)).isZero();
    }

    private record Run(int code, String out, String err) {
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EntitlementMain.exitCode(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
