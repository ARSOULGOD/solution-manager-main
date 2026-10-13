package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The operator {@link EntitlementMain} against the schema <b>head</b> ({@link SmControlPostgres#LATEST_VERSION}),
 * connecting as {@code sm_cp_runtime}. {@code EntitlementMainIT} pins V013, so it cannot see V018's narrowing of the
 * no-actor {@code ENTITLEMENT_SET} exemption to client address {@code operator-cli}; if the command's constant and the
 * migration's literal ever drift apart, this is the test that fails (W2 review W2D-3).
 */
class EntitlementMainAtHeadIT {

    private static final String KEY = "entitlement-main-at-head-it";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);

    @Test
    void theCommandStillWritesItsNoActorAuditedRowAfterTheExemptionIsNarrowedToOperatorCli() {
        UUID organization = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        String username = "entitlement-" + principal;
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)",
                organization, "Org-" + organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username,"
                + " credential_hash) VALUES (?, ?, 'local', ?, 'argon2id-placeholder-hash')", principal, organization,
                username);
        Map<String, String> env = new LinkedHashMap<>();
        env.put(EntitlementMain.URL_VARIABLE, SmControlPostgres.runtimeUrlAtVersion(KEY));
        env.put(EntitlementMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(EntitlementMain.PASSWORD_VARIABLE, SmControlPostgres.runtimePasswordAtVersion(KEY));
        env.put(EntitlementMain.ORGANIZATION_VARIABLE, organization.toString());
        env.put(EntitlementMain.SOLUTION_VARIABLE, "cache");
        env.put(EntitlementMain.MAX_INSTANCES_VARIABLE, "4");
        env.put(EntitlementMain.SET_BY_VARIABLE, username);
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = EntitlementMain.exitCode(env, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(0);
        assertThat(migrator.queryForList("SELECT client_address FROM sm_control.change_audit WHERE table_name ="
                + " 'entitlement' AND row_key ->> 'organization_id' = :o", Map.of("o", organization.toString()),
                String.class)).containsExactly("operator-cli");
    }
}
