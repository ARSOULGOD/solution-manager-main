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

/** {@link OrganizationRenameMain}'s environment contract (OR-131): every refusal precedes any connection and echoes no secret. */
class OrganizationRenameMainTest {

    private static final String PASSWORD = "s3cr3t-rename-pw";

    private static Map<String, String> complete() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(OrganizationRenameMain.URL_VARIABLE, "jdbc:postgresql://127.0.0.1:1/never_connected");
        env.put(OrganizationRenameMain.USERNAME_VARIABLE, "sm_cp_runtime");
        env.put(OrganizationRenameMain.PASSWORD_VARIABLE, PASSWORD);
        env.put(OrganizationRenameMain.ORGANIZATION_VARIABLE, UUID.randomUUID().toString());
        env.put(OrganizationRenameMain.NAME_VARIABLE, "Acme Renamed");
        return env;
    }

    @Test
    void everyRequiredVariableMissingExitsTwoNamingItWithoutEchoingThePassword() {
        for (String variable : new String[] {OrganizationRenameMain.URL_VARIABLE, OrganizationRenameMain.USERNAME_VARIABLE,
                OrganizationRenameMain.PASSWORD_VARIABLE, OrganizationRenameMain.ORGANIZATION_VARIABLE,
                OrganizationRenameMain.NAME_VARIABLE}) {
            Map<String, String> env = complete();
            env.remove(variable);
            assertRefused(variable, env);
        }
    }

    @Test
    void aMalformedUrlIsRefusedBeforeAnyConnectionAndNeverEchoed() {
        String sentinel = "SNTL-rename-77d1";
        Map<String, String> env = complete();
        env.put(OrganizationRenameMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm?password=" + sentinel);
        Run run = run(env);
        assertEquals(OrganizationRenameMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertFalse(run.stderr().contains(sentinel), run.stderr());
    }

    @Test
    void anOrganizationThatIsNotAUuidIsRefusedBeforeAnyConnection() {
        Map<String, String> env = complete();
        env.put(OrganizationRenameMain.ORGANIZATION_VARIABLE, "not-a-uuid");
        assertRefused(OrganizationRenameMain.ORGANIZATION_VARIABLE, env);
    }

    @Test
    void aNameBreakingTheApisRulesIsRefusedWithFixedTextAndNoValue() {
        // shape is checked before the connection is used for anything it could fail on: the connection here is dead, so a
        // shape refusal (exit 2) is distinguishable from a connection failure (exit 1)
        for (String bad : new String[] {"x".repeat(101), "bad\u0007name"}) {
            Map<String, String> env = complete();
            env.put(OrganizationRenameMain.NAME_VARIABLE, bad);
            Run run = run(env);
            assertEquals(OrganizationRenameMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
            assertTrue(run.stderr().contains(OrganizationRenameMain.NAME_VARIABLE), run.stderr());
            assertFalse(run.stderr().contains(PASSWORD), run.stderr());
        }
    }

    @Test
    void theRenameCommandNeverStartsASpringContext() throws IOException {
        String constantPool;
        try (InputStream bytes = OrganizationRenameMain.class.getResourceAsStream("OrganizationRenameMain.class")) {
            constantPool = new String(bytes.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        for (String forbidden : new String[] {"SpringApplication", "ApplicationContext", "AnnotationConfig",
                "SpringBootApplication"}) {
            assertFalse(constantPool.contains(forbidden), "must not reference " + forbidden);
        }
    }

    private record Run(int code, ByteArrayOutputStream err) {
        String stderr() {
            return err.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = OrganizationRenameMain.exitCode(env, new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, err);
    }

    private static void assertRefused(String variable, Map<String, String> env) {
        Run run = run(env);
        assertEquals(OrganizationRenameMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(variable), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }
}
