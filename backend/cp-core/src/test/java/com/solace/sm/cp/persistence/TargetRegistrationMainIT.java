package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.target.TargetFixtures;
import com.solace.sm.cp.target.TargetFixtures.Tree;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link TargetRegistrationMain} against a real {@code sm_control} schema, connecting <b>as {@code sm_cp_runtime}</b>
 * exactly as an operator would (plan 010 T2.7): a real login through {@code LocalLoginService}, the real
 * {@code target.create} check, an audited write, and a revoked session afterwards.
 */
class TargetRegistrationMainIT {

    private static final String CREDENTIAL = "an-operator-credential-0001";

    private final NamedParameterJdbcTemplate migrator = TargetFixtures.migrator();

    private Tree tree;
    private String url;
    private String password;

    @BeforeEach
    void setUp() {
        tree = TargetFixtures.seedTree();
        url = SmControlPostgres.runtimeUrlAtVersion(TargetFixtures.CACHE_KEY);
        password = SmControlPostgres.runtimePasswordAtVersion(TargetFixtures.CACHE_KEY);
    }

    private String adminAt(com.solace.sm.cp.api.authz.ScopePath scope) {
        String username = "admin-" + UUID.randomUUID();
        UUID id = TargetFixtures.localPrincipal(tree.organization(), username, CREDENTIAL, false);
        TargetFixtures.bind(id, "Administrator", scope);
        return username;
    }

    private Map<String, String> env(String name) {
        Map<String, String> env = new HashMap<>();
        env.put(TargetRegistrationMain.URL_VARIABLE, url);
        env.put(TargetRegistrationMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(TargetRegistrationMain.PASSWORD_VARIABLE, password);
        env.put(TargetRegistrationMain.NAME_VARIABLE, name);
        env.put(TargetRegistrationMain.ORGANIZATION_VARIABLE, tree.organization().toString());
        return env;
    }

    private static TargetRegistrationMain.Credentials terminal(String username, String credential) {
        return new TargetRegistrationMain.Credentials() {
            @Override
            public String readUsername() {
                return username;
            }

            @Override
            public char[] readCredential() {
                return credential.toCharArray();
            }
        };
    }

    private record Result(int exit, String out, String err) {
    }

    private static Result run(Map<String, String> env, TargetRegistrationMain.Credentials terminal) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = TargetRegistrationMain.exitCode(env, terminal, new PrintStream(out), new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private int liveSessions(String username) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control.session s JOIN sm_control.principal p"
                        + " ON p.id = s.principal_id WHERE p.username = :u AND s.revoked_at IS NULL",
                Map.of("u", username), Integer.class);
    }

    @Test
    void theCommandRegistersAnAuditedTargetPrintsOnlyItsIdAndLeavesNoLiveSession() {
        String admin = adminAt(tree.organizationScope());
        Map<String, String> env = env("cmd-vm");
        env.put(TargetRegistrationMain.OWNER_UNIT_VARIABLE, tree.unit().toString());
        env.put(TargetRegistrationMain.OWNER_ENVIRONMENT_VARIABLE, tree.environment().toString());

        Result r = run(env, terminal(admin, CREDENTIAL));

        assertEquals(0, r.exit, r.err);
        UUID printed = UUID.fromString(r.out.strip());
        assertEquals("", r.err, "nothing but the id on stdout and nothing on stderr");
        Map<String, Object> row = migrator.queryForMap("SELECT organization_id, name, kind, transport, created_by"
                + " FROM sm_control.target WHERE target_id = :id", Map.of("id", printed));
        assertEquals(tree.organization(), row.get("organization_id"));
        assertEquals("cmd-vm", row.get("name"));
        assertEquals("docker", row.get("kind"));
        assertEquals("ssh-docker", row.get("transport"));
        List<Map<String, Object>> audits = migrator.queryForList("SELECT audit_operation, client_address,"
                + " actor_principal_id FROM sm_control.change_audit WHERE table_name = 'target'"
                + " AND row_key::text LIKE :k", Map.of("k", "%" + printed + "%"));
        assertEquals(1, audits.size());
        assertEquals("TARGET_REGISTERED", audits.get(0).get("audit_operation"));
        assertEquals("127.0.0.1", audits.get(0).get("client_address"));
        assertNotNull(audits.get(0).get("actor_principal_id"));
        assertEquals(0, liveSessions(admin), "the session the command opened is revoked before it exits");
        assertFalse((r.out + r.err).contains(CREDENTIAL));
    }

    @Test
    void runningTheCommandAgainIsIdempotentAndPrintsTheSameId() {
        String admin = adminAt(tree.organizationScope());
        Map<String, String> env = env("idem-cmd-vm");

        Result first = run(env, terminal(admin, CREDENTIAL));
        Result second = run(env, terminal(admin, CREDENTIAL));

        assertEquals(0, first.exit, first.err);
        assertEquals(0, second.exit, second.err);
        assertEquals(first.out.strip(), second.out.strip());
        assertEquals(1, TargetFixtures.targetRows(tree.organization()));
        env.put(TargetRegistrationMain.OWNER_UNIT_VARIABLE, tree.unit().toString());
        Result differing = run(env, terminal(admin, CREDENTIAL));
        assertEquals(TargetRegistrationMain.EXIT_FAILED, differing.exit, "same name, different owner: refused");
        assertEquals("", differing.out);
    }

    @Test
    void aPrincipalWithoutTargetCreateIsRefusedAndNothingIsWritten() {
        String username = "viewer-" + UUID.randomUUID();
        UUID viewer = TargetFixtures.localPrincipal(tree.organization(), username, CREDENTIAL, false);
        TargetFixtures.bind(viewer, "Viewer", tree.organizationScope());

        Result r = run(env("refused-vm"), terminal(username, CREDENTIAL));

        assertEquals(TargetRegistrationMain.EXIT_REFUSED, r.exit, r.err);
        assertEquals("", r.out);
        assertEquals(0, TargetFixtures.targetRows(tree.organization()));
        assertThrows(AccessDeniedException.class, () -> TargetRegistrationMain.run(url,
                SmControlPostgres.RUNTIME_USERNAME, password, tree.organization().toString(), username,
                CREDENTIAL.toCharArray(),
                new TargetRegistrationMain.Spec(null, "refused-vm", "docker", "ssh-docker", null, null)));
    }

    @Test
    void aWrongCredentialIsRefusedByTheSameLoginTheHttpPathUses() {
        String admin = adminAt(tree.organizationScope());

        Result r = run(env("login-refused-vm"), terminal(admin, "not-the-credential"));

        assertEquals(TargetRegistrationMain.EXIT_REFUSED, r.exit);
        assertEquals("", r.out);
        assertEquals(0, TargetFixtures.targetRows(tree.organization()));
        assertThrows(LoginRefusedException.class, () -> TargetRegistrationMain.run(url,
                SmControlPostgres.RUNTIME_USERNAME, password, tree.organization().toString(), admin,
                "nope".toCharArray(),
                new TargetRegistrationMain.Spec(null, "x", "docker", "ssh-docker", null, null)));
        assertTrue(migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit"
                + " WHERE audit_operation = 'LOGIN_REFUSED'", Map.of(), Integer.class) >= 1,
                "the refusal is audited like any login");
    }

    @Test
    void aPrincipalThatMustRotateItsCredentialIsRefusedUntilItHas() {
        String username = "rotating-" + UUID.randomUUID();
        UUID id = TargetFixtures.localPrincipal(tree.organization(), username, CREDENTIAL, true);
        TargetFixtures.bind(id, "Administrator", tree.organizationScope());

        Result r = run(env("rotate-vm"), terminal(username, CREDENTIAL));

        assertEquals(TargetRegistrationMain.EXIT_REFUSED, r.exit, r.err);
        assertTrue(r.err.contains("rotate"), r.err);
        assertEquals(0, TargetFixtures.targetRows(tree.organization()));
        assertEquals(0, liveSessions(username));
    }

    @Test
    void anAdministratorBoundAtAnEnvironmentCannotOwnATargetAtTheOrganization() {
        String admin = adminAt(tree.environmentScope());

        Result atOrganization = run(env("up-vm"), terminal(admin, CREDENTIAL));
        Map<String, String> env = env("own-env-vm");
        env.put(TargetRegistrationMain.OWNER_UNIT_VARIABLE, tree.unit().toString());
        env.put(TargetRegistrationMain.OWNER_ENVIRONMENT_VARIABLE, tree.environment().toString());
        Result atEnvironment = run(env, terminal(admin, CREDENTIAL));

        assertEquals(TargetRegistrationMain.EXIT_REFUSED, atOrganization.exit, atOrganization.err);
        assertEquals(0, atEnvironment.exit, atEnvironment.err);
        TargetView listed = new com.solace.sm.cp.persistence.JdbcTargetRepository(TargetFixtures.runtime())
                .listLive(tree.organization()).get(0);
        assertEquals(tree.environmentScope(), listed.ownershipScope());
    }
}
