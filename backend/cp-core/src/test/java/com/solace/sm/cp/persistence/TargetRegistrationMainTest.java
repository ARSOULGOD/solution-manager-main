package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetRegistrationMain}'s input handling (plan 010 T2.7): every refusal below happens before a connection is
 * attempted, and nothing it was given is ever echoed.
 */
class TargetRegistrationMainTest {

    private static final String DB_PASSWORD = "SENTINEL-db-password";
    private static final String CONSOLE_USER = "SENTINEL-console-user";
    private static final String CONSOLE_CREDENTIAL = "SENTINEL-console-credential";

    private static Map<String, String> good() {
        Map<String, String> env = new HashMap<>();
        env.put(TargetRegistrationMain.URL_VARIABLE, "jdbc:postgresql://localhost:5432/sm");
        env.put(TargetRegistrationMain.USERNAME_VARIABLE, "sm_cp_runtime");
        env.put(TargetRegistrationMain.PASSWORD_VARIABLE, DB_PASSWORD);
        env.put(TargetRegistrationMain.NAME_VARIABLE, "vm-1");
        env.put(TargetRegistrationMain.ORGANIZATION_VARIABLE, "ACME");
        return env;
    }

    private record Result(int exit, String out, String err) {
    }

    /** A terminal that counts how often it was asked, so a refusal before the prompt is observable. */
    private static final class Terminal implements TargetRegistrationMain.Credentials {
        final AtomicInteger asked = new AtomicInteger();
        final boolean present;

        Terminal(boolean present) {
            this.present = present;
        }

        @Override
        public String readUsername() {
            asked.incrementAndGet();
            return present ? CONSOLE_USER : null;
        }

        @Override
        public char[] readCredential() {
            asked.incrementAndGet();
            return present ? CONSOLE_CREDENTIAL.toCharArray() : null;
        }
    }

    private static Result run(Map<String, String> env, Terminal terminal) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = TargetRegistrationMain.exitCode(env, terminal, new PrintStream(out), new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertNoLeak(Result r) {
        String all = r.out + r.err;
        assertFalse(all.contains(DB_PASSWORD), all);
        assertFalse(all.contains(CONSOLE_USER) || all.contains(CONSOLE_CREDENTIAL), all);
    }

    @Test
    void aMissingVariableIsNamedAndNothingIsPromptedOrAttempted() {
        for (String name : new String[] {TargetRegistrationMain.URL_VARIABLE, TargetRegistrationMain.USERNAME_VARIABLE,
                TargetRegistrationMain.PASSWORD_VARIABLE, TargetRegistrationMain.NAME_VARIABLE,
                TargetRegistrationMain.ORGANIZATION_VARIABLE}) {
            Map<String, String> env = good();
            env.remove(name);
            Terminal terminal = new Terminal(true);
            Result r = run(env, terminal);
            assertEquals(TargetRegistrationMain.EXIT_MISSING_VARIABLE, r.exit, name);
            assertTrue(r.err.contains(name), r.err);
            assertEquals(0, terminal.asked.get(), "the console is not prompted when the configuration is wrong");
            assertEquals("", r.out);
            assertNoLeak(r);
        }
    }

    @Test
    void aUrlThatCarriesACredentialIsRefusedWithoutBeingEchoed() {
        Map<String, String> env = good();
        env.put(TargetRegistrationMain.URL_VARIABLE, "jdbc:postgresql://localhost/sm?password=" + DB_PASSWORD);
        Result r = run(env, new Terminal(true));
        assertEquals(TargetRegistrationMain.EXIT_MISSING_VARIABLE, r.exit);
        assertNoLeak(r);
    }

    @Test
    void anEnvironmentOwnerNeedsItsParentUnitAndEveryIdMustBeAUuid() {
        Map<String, String> env = good();
        env.put(TargetRegistrationMain.OWNER_ENVIRONMENT_VARIABLE, UUID.randomUUID().toString());
        Result orphan = run(env, new Terminal(true));
        assertEquals(TargetRegistrationMain.EXIT_MISSING_VARIABLE, orphan.exit);
        assertTrue(orphan.err.contains(TargetRegistrationMain.OWNER_UNIT_VARIABLE), orphan.err);

        for (String name : new String[] {TargetRegistrationMain.OWNER_UNIT_VARIABLE,
                TargetRegistrationMain.OWNER_ENVIRONMENT_VARIABLE, TargetRegistrationMain.TARGET_ID_VARIABLE}) {
            Map<String, String> bad = good();
            bad.put(TargetRegistrationMain.OWNER_UNIT_VARIABLE, UUID.randomUUID().toString());
            bad.put(name, "not-a-uuid");
            Result r = run(bad, new Terminal(true));
            assertEquals(TargetRegistrationMain.EXIT_MISSING_VARIABLE, r.exit, name);
            assertTrue(r.err.contains(name), r.err);
            assertFalse(r.err.contains("not-a-uuid"), "the bad value is not echoed: " + r.err);
        }
    }

    @Test
    void withoutATerminalTheCommandRefusesAndDoesNotFallBackToAVariable() {
        Map<String, String> env = good();
        // a determined operator might try to supply them anyway: they are simply never read
        env.put("SM_TARGET_USERNAME", CONSOLE_USER);
        env.put("SM_TARGET_CREDENTIAL", CONSOLE_CREDENTIAL);
        env.put("SM_CONTROL_USERNAME", CONSOLE_USER);
        env.put("SM_CONTROL_CREDENTIAL", CONSOLE_CREDENTIAL);

        Result r = run(env, new Terminal(false));

        assertEquals(TargetRegistrationMain.EXIT_NO_CONSOLE, r.exit);
        assertTrue(r.err.contains("console"), r.err);
        assertEquals("", r.out);
        assertNoLeak(r);
    }

    @Test
    void theCommandStartsNoSpringContext() throws IOException {
        // M3: a context would start a second worker pool. Direct references are what a main can make; the bytecode
        // of the command and its nested types must name none of the context or boot types.
        for (String className : List.of("TargetRegistrationMain", "TargetRegistrationMain$Spec",
                "TargetRegistrationMain$Credentials", "TargetRegistrationMain$CredentialMustRotateException")) {
            String bytes = bytecodeOf(className);
            for (String forbidden : new String[] {"org/springframework/context/", "org/springframework/boot/",
                    "SpringApplication", "ApplicationContext"}) {
                assertFalse(bytes.contains(forbidden), className + " references " + forbidden);
            }
        }
    }

    private static String bytecodeOf(String simpleName) throws IOException {
        try (InputStream in = TargetRegistrationMain.class.getResourceAsStream(simpleName + ".class")) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
