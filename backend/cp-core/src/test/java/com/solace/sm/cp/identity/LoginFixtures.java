package com.solace.sm.cp.identity;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Test fixtures for the tests that sign a user in: a login names an organization, so each needs one. */
public final class LoginFixtures {

    private LoginFixtures() {
    }

    /** A fresh organization with a unique name (V015 requires one), written as the migrator. */
    public static UUID organization(NamedParameterJdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                id, "login-fixture-" + id);
        return id;
    }

    /**
     * The USERNAME throttle subject a login of {@code username} is counted under (W5D-3): the principal's id when that
     * principal exists (found the way login finds it, by {@code lower()}), else the resolved-organization fallback.
     */
    public static String usernameSubject(NamedParameterJdbcTemplate jdbc, UUID organization, String username) {
        java.util.List<UUID> ids = jdbc.getJdbcTemplate().query(
                "SELECT id FROM sm_control.principal WHERE organization_id = ? AND lower(username) = lower(?)"
                        + " AND source = 'local'",
                (rs, row) -> rs.getObject("id", UUID.class), organization, username);
        return ids.isEmpty() ? LocalLoginService.loginSubjectHash(organization, username)
                : LocalLoginService.principalSubjectHash(ids.get(0));
    }
}
