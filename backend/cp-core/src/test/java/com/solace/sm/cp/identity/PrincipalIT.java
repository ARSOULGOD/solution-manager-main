package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PrincipalIT {

    private NamedParameterJdbcTemplate jdbc;
    private PrincipalRepository principals;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("002");
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.principal");
        principals = new JdbcPrincipalRepository(jdbc);
    }

    @Test
    void sourceIsConstrainedToLocalOrIdp() {
        assertRefused(() -> insert(organization(), "saml", "sam@example.test", null), "principal_source");
    }

    @Test
    void anIdpPrincipalMayHaveNoCredentialHash() {
        UUID organizationId = organization();
        UUID id = principals.createIdp(organizationId, "idp@example.test");

        PrincipalRepository.Principal principal = principals.find(id).orElseThrow();
        assertThat(principal.source()).isEqualTo(PrincipalRepository.Source.IDP);
        assertThat(principal.credentialHash()).isNull();
        assertThat(principal.organizationId()).isEqualTo(organizationId);
    }

    @Test
    void aLocalPrincipalWithoutACredentialHashIsRefused() {
        assertRefused(() -> insert(organization(), "local", "local@example.test", null),
                "principal_local_needs_hash");
    }

    @Test
    void aPrincipalCannotBeStoredWithoutAnOrganization() {
        assertRefused(() -> insert(null, "idp", "idp@example.test", null), "23502", "organization_id");
    }

    @Test
    void aPrincipalCannotReferenceAnotherOrganizationsMissingRow() {
        assertRefused(() -> insert(UUID.randomUUID(), "idp", "idp@example.test", null),
                "23503", "principal_organization_fk");
    }

    @Test
    void aUsernameIsGloballyUniqueBecauseLoginCannotSelectAnOrganization() {
        principals.createIdp(organization(), "shared@example.test");

        assertThatThrownBy(() -> principals.createIdp(organization(), "shared@example.test"))
                .satisfies(error -> assertSqlState(error, "23505", "principal_username_unique"));
    }

    @Test
    void mutationWithoutTheSourceConstraintMakesTheInvalidSourceWriteAccepted() {
        assertAcceptedWithDroppedConstraint("principal_source",
                () -> insert(organization(), "saml", "sam@example.test", null));
    }

    @Test
    void mutationWithoutTheLocalCredentialConstraintMakesTheMissingHashWriteAccepted() {
        assertAcceptedWithDroppedConstraint("principal_local_needs_hash",
                () -> insert(organization(), "local", "local@example.test", null));
    }

    @Test
    void mutationWithoutTheOrganizationForeignKeyMakesTheMissingOrganizationWriteAccepted() {
        assertAcceptedWithDroppedConstraint("principal_organization_fk", "23503",
                () -> insert(UUID.randomUUID(), "idp", "idp@example.test", null));
    }

    @Test
    void deactivationSetsStatusAndTimestampAndKeepsTheRow() {
        UUID id = principals.createLocal(organization(), "local@example.test", "{argon2id}stored", false);

        principals.deactivate(id);

        PrincipalRepository.Principal principal = principals.find(id).orElseThrow();
        assertThat(principal.status()).isEqualTo(PrincipalRepository.Status.DEACTIVATED);
        assertThat(principal.deactivatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.principal WHERE id = :id",
                Map.of("id", id), Integer.class)).isEqualTo(1);
    }

    private void insert(UUID organizationId, String source, String username, String credentialHash) {
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash) "
                        + "VALUES (:id, :organizationId, :source, :username, :credentialHash)",
                new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                        .addValue("organizationId", organizationId).addValue("source", source)
                        .addValue("username", username).addValue("credentialHash", credentialHash));
    }

    private UUID organization() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", id));
        return id;
    }

    private static void assertRefused(ThrowingSql action, String sqlState, String detail) {
        assertThatThrownBy(action::run).satisfies(error -> {
            assertSqlState(error, sqlState, detail);
        });
    }

    private static void assertRefused(ThrowingSql action, String constraint) {
        assertRefused(action, "23514", constraint);
    }

    private static void assertSqlState(Throwable error, String sqlState, String detail) {
        SQLException sql = sqlException(error);
        assertThat(sql.getSQLState()).isEqualTo(sqlState);
        assertThat(sql.getMessage()).contains(detail);
    }

    private static SQLException sqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                return sql;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException", error);
    }

    private void assertAcceptedWithDroppedConstraint(String constraint, ThrowingSql action) {
        assertAcceptedWithDroppedConstraint(constraint, "23514", action);
    }

    private void assertAcceptedWithDroppedConstraint(String constraint, String sqlState, ThrowingSql action) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        transaction.executeWithoutResult(status -> {
            jdbc.getJdbcTemplate().execute("ALTER TABLE sm_control.principal DROP CONSTRAINT " + constraint);
            assertThatThrownBy(() -> assertRefused(action, sqlState, constraint))
                    .as("the corresponding refusal test must fail after dropping " + constraint)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
            status.setRollbackOnly();
        });
    }

    @FunctionalInterface
    private interface ThrowingSql {
        void run();
    }
}
