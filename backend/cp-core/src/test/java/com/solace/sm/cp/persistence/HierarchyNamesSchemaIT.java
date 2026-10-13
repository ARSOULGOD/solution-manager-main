package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.config.ControlPlaneConfiguration;
import com.solace.sm.cp.events.StubMeshPublisherConfiguration;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** {@code V015} (OR-120): the display {@code name} on organization, organization_unit and environment. */
class HierarchyNamesSchemaIT {

    private static final String KEY = "hierarchy-names-schema-it";

    private final NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);

    @Test
    void existingRowsAreBackFilledWithTheirIdAsText() {
        String key = "hierarchy-names-backfill-it";
        NamedParameterJdbcTemplate atV014 = SmControlPostgres.jdbcAtVersion(key, "014");
        UUID org = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        atV014.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id) VALUES (?)", org);
        atV014.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id) VALUES (?, ?)", org, unit);
        atV014.getJdbcTemplate().update("INSERT INTO sm_control.environment "
                + "(organization_id, organization_unit_id, environment_id) VALUES (?, ?, ?)", org, unit, env);

        applyV015AsOwner(atV014);

        assertThat(atV014.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.organization WHERE organization_id = ?", String.class, org))
                .isEqualTo(org.toString());
        assertThat(atV014.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.organization_unit WHERE organization_unit_id = ?", String.class, unit))
                .isEqualTo(unit.toString());
        assertThat(atV014.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.environment WHERE environment_id = ?", String.class, env))
                .isEqualTo(env.toString());
        // V007's install organization keeps its id and, with no seed renaming it, its id-text name.
        assertThat(atV014.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.organization WHERE organization_id = '00000000-0000-4000-8000-000000000011'",
                String.class)).isEqualTo("00000000-0000-4000-8000-000000000011");
    }

    @Test
    void anOmittedNameIsRefusedOnEveryTableNotDefaulted() {
        UUID org = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        // No default and no trigger: only rows that existed before V015 carry their id text. A future inserter that
        // forgets the name gets a constraint error, not a UUID label.
        assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization (organization_id) VALUES (?)", org))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("not-null");
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)", org, "ok-" + org);
        assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id) VALUES (?, ?)", org, unit))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("not-null");
        owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'ok')",
                org, unit);
        assertThatThrownBy(() -> owner.getJdbcTemplate().update("INSERT INTO sm_control.environment "
                + "(organization_id, organization_unit_id, environment_id) VALUES (?, ?, ?)", org, unit, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("not-null");
        assertThat(owner.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname LIKE '%default_name'", Integer.class)).isZero();
    }

    @Test
    void aSuppliedNameIsKept() {
        UUID org = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                org, "ACME-" + org);
        assertThat(owner.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.organization WHERE organization_id = ?", String.class, org))
                .isEqualTo("ACME-" + org);
    }

    @Test
    void theNameShapeCheckRefusesEmptyBlankOverlongAndControlCharacterNamesOnEveryTable() {
        UUID org = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)", org, "ok-" + org);
        owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'ok')",
                org, unit);
        String[] bad = {"", "   ", "x".repeat(101), "a\nb", "tab\there", "del\u007f", "c1\u0085x"};
        for (String name : bad) {
            assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                    "INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)", UUID.randomUUID(), name))
                    .as("organization name %s", name.length() > 20 ? "(101 chars)" : name.replace("\n", "\\n"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("organization_name_shape");
            assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                    "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) "
                            + "VALUES (?, ?, ?)", org, UUID.randomUUID(), name))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("organization_unit_name_shape");
            assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                    "INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name) "
                            + "VALUES (?, ?, ?, ?)", org, unit, UUID.randomUUID(), name))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("environment_name_shape");
        }
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                UUID.randomUUID(), UUID.randomUUID() + "x".repeat(64));
    }

    @Test
    void siblingNamesAreUniqueIgnoringCaseAndOrganizationNamesAreUniqueAcrossTheInstall() {
        UUID org = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID otherUnit = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                org, "Uniq Org " + org);
        owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'Sub')",
                org, unit);
        owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'Other')",
                org, otherUnit);
        owner.getJdbcTemplate().update("INSERT INTO sm_control.environment "
                + "(organization_id, organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'Dev')",
                org, unit, UUID.randomUUID());

        // Same name under a different parent is accepted; the clash is among siblings only.
        owner.getJdbcTemplate().update("INSERT INTO sm_control.environment "
                + "(organization_id, organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'Dev')",
                org, otherUnit, UUID.randomUUID());
        UUID org2 = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                org2, "Other Org " + org2);
        owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'Sub')",
                org2, UUID.randomUUID());

        assertThatThrownBy(() -> owner.getJdbcTemplate().update("INSERT INTO sm_control.environment "
                + "(organization_id, organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'dev')",
                org, unit, UUID.randomUUID()))
                .satisfies(e -> assertUniqueViolation(e, "environment_name_ci_uq"));
        assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (?, ?, 'SUB')",
                org, UUID.randomUUID()))
                .satisfies(e -> assertUniqueViolation(e, "organization_unit_name_ci_uq"));
        assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                UUID.randomUUID(), ("uniq ORG " + org).toUpperCase(java.util.Locale.ROOT)))
                .satisfies(e -> assertUniqueViolation(e, "organization_name_ci_uq"));
    }

    private static void assertUniqueViolation(Throwable error, String index) {
        assertThat(error).isInstanceOf(DuplicateKeyException.class);
        Throwable cause = error;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("an SQLException in the cause chain").isNotNull();
        assertThat(((SQLException) cause).getSQLState()).isEqualTo("23505");
        assertThat(cause.getMessage()).contains(index);
    }

    @Test
    void aNameCannotBeNulledAfterwards() {
        UUID org = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)", org, "ok-" + org);
        assertThatThrownBy(() -> owner.getJdbcTemplate().update(
                "UPDATE sm_control.organization SET name = NULL WHERE organization_id = ?", org))
                .hasStackTraceContaining("not-null");
    }

    @Test
    void theRuntimeRoleReadsNamesAndWritesThemOnlyThroughAnAuditedRename() {
        // V015 granted the runtime role no write on a name; V018 (plan 013 T2.1) grants UPDATE (name) and nothing else,
        // and the write is audited or it is refused: a rename with no audit context is OR-25's, not a silent write.
        UUID org = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)", org, "ACME-" + org);
        assertThat(runtime.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.organization WHERE organization_id = ?", String.class, org))
                .isEqualTo("ACME-" + org);
        assertThatThrownBy(() -> runtime.getJdbcTemplate().update(
                "UPDATE sm_control.organization SET name = 'X' WHERE organization_id = ?", org))
                .hasStackTraceContaining("no known audit_operation");
        assertThatThrownBy(() -> runtime.getJdbcTemplate().update(
                "UPDATE sm_control.organization SET organization_id = organization_id WHERE organization_id = ?", org))
                .hasStackTraceContaining("permission denied");
        assertThat(owner.getJdbcTemplate().queryForObject(
                "SELECT name FROM sm_control.organization WHERE organization_id = ?", String.class, org))
                .as("neither refused update landed").isEqualTo("ACME-" + org);
    }

    @Test
    void readinessRefusesAV014SchemaThatLacksTheNameColumns() {
        String key = "hierarchy-names-readiness-it";
        SmControlPostgres.jdbcAtVersion(key, "014");
        new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + SmControlPostgres.runtimeUrlAtVersion(key),
                        "sm.control.runtime.username=" + SmControlPostgres.RUNTIME_USERNAME,
                        "sm.control.runtime.password=" + SmControlPostgres.runtimePasswordAtVersion(key))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    StringBuilder chain = new StringBuilder();
                    for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                        chain.append(t.getMessage()).append(" | ");
                    }
                    assertThat(chain.toString()).contains("V015").contains("SmControlMigrationMain");
                });
    }

    private static void applyV015AsOwner(NamedParameterJdbcTemplate migrator) {
        try (var in = HierarchyNamesSchemaIT.class.getClassLoader()
                .getResourceAsStream("db/migration/sm_control/V015__hierarchy_display_names.sql")) {
            String sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            migrator.getJdbcTemplate().execute((java.sql.Connection c) -> {
                try (var st = c.createStatement()) {
                    st.execute("SET ROLE sm_cp_owner");
                    st.execute(sql);
                    st.execute("RESET ROLE");
                }
                return null;
            });
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
