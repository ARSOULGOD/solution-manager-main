package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link EntitlementMain}'s environment contract, in the style of {@link SmControlLockoutRecoveryMainTest}: every
 * refusal is decided BEFORE any connection is attempted, names the variable, and never echoes the password.
 */
class EntitlementMainTest {

    private static final String PASSWORD = "s3cr3t-entitlement-pw";

    private static Map<String, String> complete() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(EntitlementMain.URL_VARIABLE, "jdbc:postgresql://127.0.0.1:1/never_connected");
        env.put(EntitlementMain.USERNAME_VARIABLE, "sm_cp_runtime");
        env.put(EntitlementMain.PASSWORD_VARIABLE, PASSWORD);
        env.put(EntitlementMain.ORGANIZATION_VARIABLE, UUID.randomUUID().toString());
        env.put(EntitlementMain.SOLUTION_VARIABLE, "cache");
        env.put(EntitlementMain.MAX_INSTANCES_VARIABLE, "5");
        env.put(EntitlementMain.SET_BY_VARIABLE, "bootstrap-admin");
        return env;
    }

    @Test
    void everyRequiredVariableMissingExitsTwoNamingItWithoutEchoingThePassword() {
        for (String variable : new String[] {EntitlementMain.URL_VARIABLE, EntitlementMain.USERNAME_VARIABLE,
                EntitlementMain.PASSWORD_VARIABLE, EntitlementMain.ORGANIZATION_VARIABLE,
                EntitlementMain.SOLUTION_VARIABLE, EntitlementMain.MAX_INSTANCES_VARIABLE,
                EntitlementMain.SET_BY_VARIABLE}) {
            Map<String, String> env = complete();
            env.remove(variable);
            assertRefused(variable, env);
        }
    }

    @Test
    void aBlankPasswordCountsAsMissing() {
        Map<String, String> env = complete();
        env.put(EntitlementMain.PASSWORD_VARIABLE, "  ");
        assertRefused(EntitlementMain.PASSWORD_VARIABLE, env);
    }

    @Test
    void aMalformedUrlIsRefusedBeforeAnyConnectionAndNeverEchoed() {
        String sentinel = "SNTL-entitlement-91c2";
        Map<String, String> env = complete();
        env.put(EntitlementMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm?password=" + sentinel);

        Run run = run(env);

        assertEquals(EntitlementMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(EntitlementMain.URL_VARIABLE), run.stderr());
        assertFalse(run.stderr().contains(sentinel), run.stderr());
    }

    @Test
    void anOrganizationThatIsNotAUuidIsRefusedBeforeAnyConnection() {
        Map<String, String> env = complete();
        env.put(EntitlementMain.ORGANIZATION_VARIABLE, "not-a-uuid");
        assertRefused(EntitlementMain.ORGANIZATION_VARIABLE, env);
    }

    @Test
    void aSolutionThatCannotNameACatalogEntryIsRefusedBeforeAnyConnection() {
        for (String bad : new String[] {"Cache", "9cache", "has space", "a".repeat(65), "x'; DROP TABLE t;--"}) {
            Map<String, String> env = complete();
            env.put(EntitlementMain.SOLUTION_VARIABLE, bad);
            assertRefused(EntitlementMain.SOLUTION_VARIABLE, env);
        }
    }

    @Test
    void aLimitThatIsNotAPositiveIntegerIsRefusedBeforeAnyConnection() {
        for (String bad : new String[] {"0", "-1", "five", "1.5", "99999999999999999999"}) {
            Map<String, String> env = complete();
            env.put(EntitlementMain.MAX_INSTANCES_VARIABLE, bad);
            assertRefused(EntitlementMain.MAX_INSTANCES_VARIABLE, env);
        }
    }

    /**
     * M3: a setup command that boots a Spring context could start a second ApplyWorker against the live database
     * (plan 010 Global Constraints). The class's own constant pool is the evidence: it names no context type.
     */
    @Test
    void theEntitlementCommandNeverStartsASpringContext() throws IOException {
        String constantPool;
        try (InputStream bytes = EntitlementMain.class.getResourceAsStream("EntitlementMain.class")) {
            constantPool = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        for (String forbidden : new String[] {"SpringApplication", "ApplicationContext", "AnnotationConfig",
                "SpringBootApplication"}) {
            assertFalse(constantPool.contains(forbidden), "EntitlementMain must not reference " + forbidden);
        }
    }

    private record Run(int code, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        String stderr() {
            return err.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EntitlementMain.exitCode(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out, err);
    }

    private static void assertRefused(String variable, Map<String, String> env) {
        Run run = run(env);
        assertEquals(EntitlementMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(variable), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }
}
