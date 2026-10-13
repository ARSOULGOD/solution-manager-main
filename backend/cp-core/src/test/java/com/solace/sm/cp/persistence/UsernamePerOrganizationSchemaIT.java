package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code V019} (plan 013 T3.2, {@code OR-125 (4)}): a username is unique <b>per organization</b>, compared without regard
 * to case, and no longer unique install-wide.
 *
 * <p>Each test names the fact it proves; the refusals carry the SQLSTATE and the index name, so a different guard firing
 * is not a pass. Fixture rows are written as the migrator (the {@code OR-25} audit context is for the runtime role).
 */
class UsernamePerOrganizationSchemaIT {

    private static final String CACHE_KEY = "username-per-organization-it";
    private static final String VERSION = "019";
    private static final String INDEX = "principal_org_username_ci_uq";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(CACHE_KEY, VERSION);

    @Test
    void theSameUsernameMayBelongToTwoOrganizations() {
        UUID first = organization();
        UUID second = organization();

        insert(first, "operator");
        insert(second, "operator");

        assertThat(usernamesOf(first)).containsExactly("operator");
        assertThat(usernamesOf(second)).containsExactly("operator");
    }

    @Test
    void theSameUsernameTwiceInOneOrganizationIsRefusedByTheNamedIndex() {
        UUID organization = organization();
        insert(organization, "operator");

        Refusal refusal = refusalOf(() -> insert(organization, "operator"));

        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("23505");
        assertThat(refusal.message).contains(INDEX);
    }

    @Test
    void aUsernameThatDiffersOnlyByCaseIsTheSameUsernameWithinOneOrganization() {
        UUID organization = organization();
        insert(organization, "Operator");

        Refusal refusal = refusalOf(() -> insert(organization, "operator"));

        assertThat(refusal.sqlState).as(refusal.message).isEqualTo("23505");
        assertThat(refusal.message).contains(INDEX);
    }

    @Test
    void aUsernameThatDiffersOnlyByCaseIsAnotherOrganizationsOwnName() {
        UUID first = organization();
        UUID second = organization();
        insert(first, "Operator");

        insert(second, "operator");

        assertThat(usernamesOf(second)).containsExactly("operator");
    }

    @Test
    void theInstallWideUniqueConstraintIsGone() {
        assertThat(migrator.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM pg_catalog.pg_constraint WHERE conname = 'principal_username_unique'",
                Integer.class)).isZero();
    }

    @Test
    void theIndexIsOnTheOrganizationAndTheLowercasedUsername() {
        String definition = migrator.getJdbcTemplate().queryForObject(
                "SELECT indexdef FROM pg_catalog.pg_indexes WHERE schemaname = 'sm_control'"
                        + " AND tablename = 'principal' AND indexname = ?", String.class, INDEX);

        assertThat(definition).contains("UNIQUE").contains("organization_id").contains("lower(username)");
    }

    @Test
    void theBootstrapAdministratorIsStillThereUnderItsOwnOrganization() {
        assertThat(migrator.getJdbcTemplate().queryForList(
                "SELECT organization_id::text FROM sm_control.principal WHERE username = 'bootstrap-admin'",
                String.class)).containsExactly("00000000-0000-4000-8000-000000000011");
    }

    // ---------------------------------------------------------------------------------------------------- fixtures

    private UUID organization() {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                id, "Org-" + id);
        return id;
    }

    private void insert(UUID organization, String username) {
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", UUID.randomUUID(), organization, username);
    }

    private List<String> usernamesOf(UUID organization) {
        return migrator.getJdbcTemplate().queryForList(
                "SELECT username FROM sm_control.principal WHERE organization_id = ? ORDER BY username",
                String.class, organization);
    }

    private record Refusal(String sqlState, String message) {
    }

    private static Refusal refusalOf(Runnable work) {
        try {
            work.run();
        } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql) {
                    return new Refusal(sql.getSQLState(), String.valueOf(sql.getMessage()));
                }
            }
            throw e;
        }
        throw new AssertionError("expected the write to be refused, but it succeeded");
    }
}
