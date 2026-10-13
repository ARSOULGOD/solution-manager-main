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
 * {@link OrganizationRenameMain} (OR-131) against the schema head, connecting as {@code sm_cp_runtime}: it renames an
 * organization other than any session's, refuses a case-only clash, and writes exactly one audit row with no actor
 * (the EntitlementMain pattern, OR-131 Q1).
 */
class OrganizationRenameMainIT {

    private static final String KEY = "organization-rename-main-it";

    private final NamedParameterJdbcTemplate migrator =
            SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);

    private record Seed(UUID organization, String name) {
    }

    private Seed seed() {
        UUID organization = UUID.randomUUID();
        String name = "Org-" + organization;
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                organization, name);
        return new Seed(organization, name);
    }

    private Map<String, String> env(Seed seed, String newName) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(OrganizationRenameMain.URL_VARIABLE, SmControlPostgres.runtimeUrlAtVersion(KEY));
        env.put(OrganizationRenameMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(OrganizationRenameMain.PASSWORD_VARIABLE, SmControlPostgres.runtimePasswordAtVersion(KEY));
        env.put(OrganizationRenameMain.ORGANIZATION_VARIABLE, seed.organization.toString());
        env.put(OrganizationRenameMain.NAME_VARIABLE, newName);
        return env;
    }

    private record Run(int code, String out, String err) {
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = OrganizationRenameMain.exitCode(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private String nameOf(Seed seed) {
        return migrator.queryForObject("SELECT name FROM sm_control.organization WHERE organization_id = :o",
                Map.of("o", seed.organization), String.class);
    }

    private List<Map<String, Object>> auditRows(Seed seed) {
        return migrator.queryForList("SELECT audit_operation, actor_principal_id, client_address, db_role"
                + " FROM sm_control.change_audit WHERE table_name = 'organization'"
                + " AND row_key ->> 'organization_id' = :o AND operation = 'UPDATE'",
                Map.of("o", seed.organization.toString()));
    }

    @Test
    void itRenamesAnotherOrganizationAndWritesExactlyOneOperatorCliAuditRow() {
        Seed seed = seed();
        String fresh = "Renamed " + UUID.randomUUID();

        Run run = run(env(seed, "  " + fresh + "  "));

        assertThat(run.code).as(run.err).isEqualTo(0);
        assertThat(run.out).contains(fresh).doesNotContain(SmControlPostgres.RUNTIME_USERNAME + ":");
        assertThat(nameOf(seed)).isEqualTo(fresh);
        List<Map<String, Object>> audit = auditRows(seed);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("audit_operation")).isEqualTo("HIERARCHY_NODE_RENAMED");
        assertThat(audit.get(0).get("client_address")).isEqualTo("operator-cli");
        assertThat(audit.get(0).get("actor_principal_id")).as("EntitlementMain pattern: no actor").isNull();
        assertThat(audit.get(0).get("db_role")).isEqualTo("sm_cp_runtime");
    }

    @Test
    void aCaseOnlyClashWithAnotherOrganizationIsRefusedAndWritesNothing() {
        Seed seed = seed();
        Seed other = seed();

        Run run = run(env(seed, other.name.toUpperCase()));

        assertThat(run.code).as(run.err).isEqualTo(OrganizationRenameMain.EXIT_NAME_TAKEN);
        assertThat(run.err).doesNotContain(other.name).doesNotContain(SmControlPostgres.runtimePasswordAtVersion(KEY));
        assertThat(nameOf(seed)).isEqualTo(seed.name);
        assertThat(auditRows(seed)).isEmpty();
    }

    @Test
    void anAbsentOrganizationExitsThreeAndWritesNothing() {
        Seed seed = seed();
        Map<String, String> absent = env(seed, "X " + UUID.randomUUID());
        absent.put(OrganizationRenameMain.ORGANIZATION_VARIABLE, UUID.randomUUID().toString());
        assertThat(run(absent).code).isEqualTo(OrganizationRenameMain.EXIT_UNKNOWN_ORGANIZATION);
        assertThat(nameOf(seed)).isEqualTo(seed.name);
        assertThat(auditRows(seed)).isEmpty();
    }

    @Test
    void aWrongPasswordFailsWithExitOneAndNeverEchoesIt() {
        Seed seed = seed();
        Map<String, String> env = env(seed, "Y " + UUID.randomUUID());
        env.put(OrganizationRenameMain.PASSWORD_VARIABLE, "wrong-" + UUID.randomUUID());
        Run run = run(env);
        assertThat(run.code).isEqualTo(OrganizationRenameMain.EXIT_FAILED);
        assertThat(run.err).doesNotContain(env.get(OrganizationRenameMain.PASSWORD_VARIABLE));
        assertThat(nameOf(seed)).isEqualTo(seed.name);
    }
}
