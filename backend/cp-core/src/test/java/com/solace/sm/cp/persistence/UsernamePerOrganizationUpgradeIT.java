package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code V019} as an UPGRADE (W5D-2): a database built at V018 that already holds rows, migrated to V019. V018's
 * {@code principal_username_unique} (V002) was case-SENSITIVE, so {@code Alice} and {@code alice} in one organization
 * were legal there; the case-insensitive index V019 builds cannot take them and the migration must fail loudly, naming
 * the index, rather than silently pick one. Each test owns its container (rows seeded at V018 would otherwise leak).
 */
class UsernamePerOrganizationUpgradeIT {

    @Test
    void twoSpellingsOfOneUsernameInOneOrganizationFailTheUpgradeNamingTheIndex() {
        String key = "username-upgrade-collision-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(key, "018");
        UUID organization = organization(owner);
        insert(owner, organization, "Alice");
        insert(owner, organization, "alice");

        assertThatThrownBy(() -> SmControlPostgres.migrateTo(key, "019")).satisfies(failure -> {
            String sqlState = null;
            StringBuilder messages = new StringBuilder();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                messages.append(t.getMessage()).append('\n');
                if (t instanceof SQLException sql && sql.getSQLState() != null) {
                    sqlState = sql.getSQLState();
                }
            }
            assertThat(sqlState).as(messages.toString()).isEqualTo("23505");
            assertThat(messages.toString()).contains("principal_org_username_ci_uq");
        });
    }

    @Test
    void caseVariantsAcrossTwoOrganizationsAndTheBootstrapAdministratorUpgradeCleanly() {
        String key = "username-upgrade-clean-it";
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(key, "018");
        UUID first = organization(owner);
        UUID second = organization(owner);
        insert(owner, first, "Alice");
        insert(owner, second, "alice");

        SmControlPostgres.migrateTo(key, "019");

        assertThat(owner.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM sm_control.principal WHERE organization_id IN (?, ?)", Integer.class,
                first, second)).isEqualTo(2);
        assertThat(owner.getJdbcTemplate().queryForList(
                "SELECT organization_id::text FROM sm_control.principal WHERE username = 'bootstrap-admin'",
                String.class)).containsExactly("00000000-0000-4000-8000-000000000011");
        assertThat(owner.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_indexes WHERE schemaname = 'sm_control'"
                        + " AND indexname = 'principal_org_username_ci_uq'", Integer.class)).isEqualTo(1);
    }

    private static UUID organization(NamedParameterJdbcTemplate owner) {
        UUID id = UUID.randomUUID();
        owner.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                id, "Org-" + id);
        return id;
    }

    private static void insert(NamedParameterJdbcTemplate owner, UUID organization, String username) {
        owner.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", UUID.randomUUID(), organization, username);
    }
}
