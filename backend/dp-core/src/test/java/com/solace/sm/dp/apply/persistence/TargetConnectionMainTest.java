package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * {@link TargetConnectionMain}'s input handling (plan 010 T2.7): every refusal below happens before a connection is
 * attempted, and neither the key, the key file's path, the database password nor the master key is ever echoed.
 */
class TargetConnectionMainTest {

    private static final String DB_PASSWORD = "SENTINEL-db-password";
    private static final String KEY_TEXT = "-----BEGIN OPENSSH PRIVATE KEY-----SENTINEL-key-body";
    private static final String KEY_PATH = "/secret/SENTINEL-path/id_ed25519";
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private static String masterKey() {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) 7);
        return Base64.getEncoder().encodeToString(b);
    }

    private static Map<String, String> good() {
        Map<String, String> env = new HashMap<>();
        env.put("SM_DATA_RUNTIME_URL", "jdbc:postgresql://localhost:1/sm");
        env.put("SM_DATA_RUNTIME_USERNAME", "sm_dp_runtime");
        env.put("SM_DATA_RUNTIME_PASSWORD", DB_PASSWORD);
        env.put("SM_DP_MASTER_KEY", masterKey());
        env.put("SM_DP_MASTER_KEY_ID", "unit");
        env.put(TargetConnectionMain.TENANT_VARIABLE, UUID.randomUUID().toString());
        env.put(TargetConnectionMain.TARGET_ID_VARIABLE, UUID.randomUUID().toString());
        env.put(TargetConnectionMain.HOST_VARIABLE, "vm1.example.test");
        env.put(TargetConnectionMain.SSH_USER_VARIABLE, "sm");
        env.put(TargetConnectionMain.FINGERPRINT_VARIABLE, FINGERPRINT);
        env.put(TargetConnectionMain.CONFIG_ROOT_VARIABLE, "/srv/sm");
        env.put(TargetConnectionMain.CONTAINER_PREFIX_VARIABLE, "sm-");
        env.put(TargetConnectionMain.KEY_FILE_VARIABLE, KEY_PATH);
        return env;
    }

    private record Result(int exit, String out, String err) {
    }

    private static final Function<String, char[]> KEY = path -> KEY_TEXT.toCharArray();

    private static Result run(Map<String, String> env, Function<String, char[]> reader) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        // These cases are about the command's own validation, not about the network: the host "presents" exactly the
        // key it is pinned with. The key-enumeration cases are in TargetConnectionMainIT.
        com.solace.sm.dp.apply.runtime.ssh.HostKeyScanner scanner = (host, port) -> java.util.Set
                .of(env.getOrDefault(TargetConnectionMain.FINGERPRINT_VARIABLE, FINGERPRINT));
        int exit = TargetConnectionMain.exitCode(env, reader, scanner, new PrintStream(out), new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertNoLeak(Result r) {
        String all = r.out + r.err;
        assertFalse(all.contains(DB_PASSWORD), all);
        assertFalse(all.contains("SENTINEL-key-body"), all);
        assertFalse(all.contains("SENTINEL-path"), all);
        assertFalse(all.contains(masterKey()), all);
    }

    @Test
    void aMissingVariableIsNamedAndNothingIsAttempted() {
        for (String name : new String[] {"SM_DATA_RUNTIME_URL", "SM_DATA_RUNTIME_USERNAME", "SM_DATA_RUNTIME_PASSWORD",
                TargetConnectionMain.TENANT_VARIABLE, TargetConnectionMain.TARGET_ID_VARIABLE,
                TargetConnectionMain.HOST_VARIABLE, TargetConnectionMain.SSH_USER_VARIABLE,
                TargetConnectionMain.FINGERPRINT_VARIABLE, TargetConnectionMain.CONFIG_ROOT_VARIABLE,
                TargetConnectionMain.CONTAINER_PREFIX_VARIABLE, TargetConnectionMain.KEY_FILE_VARIABLE}) {
            Map<String, String> env = good();
            env.remove(name);
            Result r = run(env, path -> {
                throw new AssertionError("the key file is not read when the configuration is wrong");
            });
            assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit, name);
            assertTrue(r.err.contains(name), r.err);
            assertNoLeak(r);
        }
    }

    @Test
    void aUrlThatCarriesACredentialIsRefusedWithoutBeingEchoed() {
        Map<String, String> env = good();
        env.put("SM_DATA_RUNTIME_URL", "jdbc:postgresql://localhost/sm?password=" + DB_PASSWORD);
        Result r = run(env, KEY);
        assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit);
        assertNoLeak(r);
    }

    @Test
    void anEnvelopeOfSlashOrAnEmptyOrEscapingRootIsRefusedBeforeAnythingIsRead() {
        // B2: a root of "/" is no envelope at all.
        for (String root : new String[] {"/", "", "srv/sm", "/srv/../etc", "/srv//sm", "/srv/sm/", "/ srv"}) {
            Map<String, String> env = good();
            env.put(TargetConnectionMain.CONFIG_ROOT_VARIABLE, root);
            Result r = run(env, path -> {
                throw new AssertionError("refused before the key file is read: " + root);
            });
            assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit, "root '" + root + "'");
            assertTrue(r.err.contains(TargetConnectionMain.CONFIG_ROOT_VARIABLE), r.err);
        }
        for (String prefix : new String[] {"", "  ", "-x", "a/b", "a b", "a".repeat(65)}) {
            Map<String, String> env = good();
            env.put(TargetConnectionMain.CONTAINER_PREFIX_VARIABLE, prefix);
            Result r = run(env, path -> {
                throw new AssertionError("refused before the key file is read");
            });
            assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit, "prefix '" + prefix + "'");
        }
    }

    @Test
    void theOtherFieldsKeepV007sGrammar() {
        Map<String, String> badHost = good();
        badHost.put(TargetConnectionMain.HOST_VARIABLE, "vm1; rm -rf /");
        Map<String, String> badUser = good();
        badUser.put(TargetConnectionMain.SSH_USER_VARIABLE, "root user");
        Map<String, String> badFingerprint = good();
        badFingerprint.put(TargetConnectionMain.FINGERPRINT_VARIABLE, "MD5:aa:bb");
        Map<String, String> badPort = good();
        badPort.put(TargetConnectionMain.PORT_VARIABLE, "70000");
        Map<String, String> badTenant = good();
        badTenant.put(TargetConnectionMain.TENANT_VARIABLE, "not-a-uuid");
        for (Map<String, String> env : List.of(badHost, badUser, badFingerprint, badPort, badTenant)) {
            Result r = run(env, KEY);
            assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit, r.err);
            assertNoLeak(r);
        }
    }

    @Test
    void anUnreadableKeyFileIsRefusedWithoutNamingThePath() {
        Result r = run(good(), path -> null);
        assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit);
        assertTrue(r.err.contains(TargetConnectionMain.KEY_FILE_VARIABLE), r.err);
        assertNoLeak(r);
    }

    @Test
    void aMissingMasterKeyIsRefusedBeforeTheKeyFileIsRead() {
        Map<String, String> env = good();
        env.remove("SM_DP_MASTER_KEY");
        Result r = run(env, path -> {
            throw new AssertionError("refused before the key file is read");
        });
        assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit);
        assertTrue(r.err.contains("SM_DP_MASTER_KEY"), r.err);
        assertNoLeak(r);
    }

    @Test
    void theKeyBufferIsZeroisedAndNothingIsEchoedEvenWhenTheRegistrationFails() {
        // localhost:1 refuses the connection at once, so this takes the failure path after the key was read.
        char[] buffer = KEY_TEXT.toCharArray();

        Result r = run(good(), path -> buffer);

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit, r.err);
        for (char c : buffer) {
            assertEquals('\0', c, "the key buffer is zeroised in a finally");
        }
        assertNoLeak(r);
        assertEquals("", r.out);
    }

    @Test
    void readKeyFileZeroisesItsIntermediateBytesAndRefusesAnEmptyOrUnreadableFile() throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("target-key", ".pem");
        try {
            java.nio.file.Files.writeString(file, KEY_TEXT);
            assertEquals(KEY_TEXT, new String(TargetConnectionMain.readKeyFile(file.toString())));
            java.nio.file.Files.writeString(file, "");
            assertEquals(null, TargetConnectionMain.readKeyFile(file.toString()));
            assertEquals(null, TargetConnectionMain.readKeyFile(file.toString() + ".absent"));
            java.nio.file.Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28});
            assertEquals(null, TargetConnectionMain.readKeyFile(file.toString()), "invalid UTF-8 is not a key");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    void theCommandStartsNoSpringContext() throws IOException {
        // M3: a context would start a second ApplyWorker and recovery sweep against the live database.
        try (InputStream in = TargetConnectionMain.class.getResourceAsStream("TargetConnectionMain.class")) {
            String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String forbidden : new String[] {"org/springframework/context/", "org/springframework/boot/",
                    "SpringApplication", "ApplicationContext"}) {
                assertFalse(bytes.contains(forbidden), "TargetConnectionMain references " + forbidden);
            }
        }
    }
}
