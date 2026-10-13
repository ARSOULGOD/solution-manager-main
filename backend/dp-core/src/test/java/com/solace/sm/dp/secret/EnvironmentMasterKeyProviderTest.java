package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D-KEY 4.1/4.2 as ruled by OR-85 (env var or file, identical validation). */
class EnvironmentMasterKeyProviderTest {

    @TempDir
    Path dir;

    private static MasterKeyRing load(Map<String, String> env) {
        return new EnvironmentMasterKeyProvider(env::get).load();
    }

    private static Map<String, String> good() {
        Map<String, String> env = new HashMap<>();
        env.put("SM_DP_MASTER_KEY", Keys.base64(7));
        env.put("SM_DP_MASTER_KEY_ID", "2026-q4");
        return env;
    }

    private static IllegalStateException refused(Map<String, String> env) {
        return assertThrows(IllegalStateException.class, () -> load(env));
    }

    @Test
    void aWellFormedKeyAndIdLoad() {
        MasterKeyRing ring = load(good());
        assertEquals(new KeyId("2026-q4"), ring.current().id());
        assertTrue(ring.previous().isEmpty());
    }

    @Test
    void aMissingMalformedOrShortMasterKeyRefusesStartupWithoutEchoingIt() {
        String sentinel = "SENTINEL+not+base64!!";
        String shortKey = Base64.getEncoder().encodeToString(new byte[20]); // 28 chars
        String longKey = Base64.getEncoder().encodeToString(new byte[33]);
        String urlSafe = Keys.base64(0xfb).replace('+', '-'); // 0xfb fill encodes with '+'/'/'

        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("absent", null);
        cases.put("empty", "");
        cases.put("not base64", sentinel);
        cases.put("short", shortKey);
        cases.put("long", longKey);
        cases.put("trailing newline", Keys.base64(7) + "\n");
        cases.put("url-safe alphabet", urlSafe);
        cases.put("surrounding space", " " + Keys.base64(7));

        for (var c : cases.entrySet()) {
            Map<String, String> env = good();
            if (c.getValue() == null) {
                env.remove("SM_DP_MASTER_KEY");
            } else {
                env.put("SM_DP_MASTER_KEY", c.getValue());
            }
            IllegalStateException e = refused(env);
            String m = e.getMessage();
            assertTrue(m.contains("SM_DP_MASTER_KEY"), c.getKey() + ": names the variable. Was: " + m);
            assertNoEcho(m, c.getKey(), sentinel, shortKey, longKey, urlSafe, Keys.base64(7));
            assertFalse(m.contains("28"), c.getKey() + ": must not print the length it found. Was: " + m);
            assertEquals(null, e.getCause(), c.getKey() + ": no chained cause");
        }
    }

    private static void assertNoEcho(String message, String label, String... values) {
        for (String v : values) {
            if (!v.isEmpty()) {
                assertFalse(message.contains(v.strip()), label + ": the message echoed a value: " + message);
            }
        }
    }

    @Test
    void theKeyIdIsRequiredAndMustMatchItsGrammarAndNeverEchoesAMalformedOne() {
        Map<String, String> env = good();
        env.remove("SM_DP_MASTER_KEY_ID");
        assertTrue(refused(env).getMessage().contains("SM_DP_MASTER_KEY_ID"));

        env.put("SM_DP_MASTER_KEY_ID", "has space and $ign");
        IllegalStateException e = refused(env);
        assertTrue(e.getMessage().contains("SM_DP_MASTER_KEY_ID"));
        assertFalse(e.getMessage().contains("has space"), e.getMessage());

        env.put("SM_DP_MASTER_KEY_ID", "x".repeat(65));
        assertTrue(refused(env).getMessage().contains("SM_DP_MASTER_KEY_ID"));
    }

    @Test
    void aKeyFileIsReadWithTheSameValidationAsTheVariable() throws IOException {
        Path file = dir.resolve("master.key");
        Files.writeString(file, Keys.base64(5), StandardCharsets.US_ASCII);
        Map<String, String> env = good();
        env.remove("SM_DP_MASTER_KEY");
        env.put("SM_DP_MASTER_KEY_FILE", file.toString());

        assertEquals(new KeyId("2026-q4"), load(env).current().id());

        // identical strictness: a trailing newline (what `echo` writes) is refused, not trimmed
        Files.writeString(file, Keys.base64(5) + "\n", StandardCharsets.US_ASCII);
        IllegalStateException nl = refused(env);
        assertTrue(nl.getMessage().contains("SM_DP_MASTER_KEY_FILE"), nl.getMessage());

        Files.writeString(file, "SENTINEL-not-a-key", StandardCharsets.US_ASCII);
        IllegalStateException bad = refused(env);
        assertFalse(bad.getMessage().contains("SENTINEL"), bad.getMessage());
    }

    @Test
    void aKeyFileThatIsMissingOrOversizedIsRefusedNamingTheVariable() throws IOException {
        Map<String, String> env = good();
        env.remove("SM_DP_MASTER_KEY");
        env.put("SM_DP_MASTER_KEY_FILE", dir.resolve("absent.key").toString());
        assertTrue(refused(env).getMessage().contains("SM_DP_MASTER_KEY_FILE"));

        Path big = dir.resolve("big.key");
        Files.write(big, new byte[4096]);
        env.put("SM_DP_MASTER_KEY_FILE", big.toString());
        assertTrue(refused(env).getMessage().contains("SM_DP_MASTER_KEY_FILE"));
    }

    @Test
    void bothFormsSetOrNeitherSetIsRefused() throws IOException {
        Path file = dir.resolve("master.key");
        Files.writeString(file, Keys.base64(5), StandardCharsets.US_ASCII);
        Map<String, String> both = good();
        both.put("SM_DP_MASTER_KEY_FILE", file.toString());
        String m = refused(both).getMessage();
        assertTrue(m.contains("SM_DP_MASTER_KEY") && m.contains("SM_DP_MASTER_KEY_FILE"), m);

        Map<String, String> neither = good();
        neither.remove("SM_DP_MASTER_KEY");
        assertTrue(refused(neither).getMessage().contains("SM_DP_MASTER_KEY"));
    }

    @Test
    void anEmptyVariableCountsAsUnsetSoAContainerRuntimeThatDeclaresBothStillWorks() throws IOException {
        Path file = dir.resolve("master.key");
        Files.writeString(file, Keys.base64(5), StandardCharsets.US_ASCII);
        Map<String, String> env = good();
        env.put("SM_DP_MASTER_KEY", "");
        env.put("SM_DP_MASTER_KEY_FILE", file.toString());
        assertEquals(new KeyId("2026-q4"), load(env).current().id());
    }

    @Test
    void aPreviousKeyNeedsItsIdAndAnIdNeedsItsKeyAndTheTwoIdsDiffer() {
        Map<String, String> env = good();
        env.put("SM_DP_MASTER_KEY_PREVIOUS", Keys.base64(3));
        assertTrue(refused(env).getMessage().contains("SM_DP_MASTER_KEY_PREVIOUS_ID"));

        env.put("SM_DP_MASTER_KEY_PREVIOUS_ID", "2026-q4");
        assertTrue(refused(env).getMessage().contains("SM_DP_MASTER_KEY_PREVIOUS_ID"),
                "the same id for two keys is refused");

        env.put("SM_DP_MASTER_KEY_PREVIOUS_ID", "2026-q3");
        MasterKeyRing ring = load(env);
        assertEquals(new KeyId("2026-q3"), ring.previous().orElseThrow().id());
        assertEquals(ring.current(), ring.find(new KeyId("2026-q4")).orElseThrow());
        assertEquals(ring.previous().orElseThrow(), ring.find(new KeyId("2026-q3")).orElseThrow());
        assertTrue(ring.find(new KeyId("other")).isEmpty());

        Map<String, String> orphanId = good();
        orphanId.put("SM_DP_MASTER_KEY_PREVIOUS_ID", "2026-q3");
        assertTrue(refused(orphanId).getMessage().contains("SM_DP_MASTER_KEY_PREVIOUS"));
    }

    @Test
    void aMalformedPreviousKeyNamesThePreviousVariableAndEchoesNothing() {
        Map<String, String> env = good();
        env.put("SM_DP_MASTER_KEY_PREVIOUS", "SENTINEL-prev");
        env.put("SM_DP_MASTER_KEY_PREVIOUS_ID", "2026-q3");
        String m = refused(env).getMessage();
        assertTrue(m.contains("SM_DP_MASTER_KEY_PREVIOUS"), m);
        assertFalse(m.contains("SENTINEL"), m);
    }

    @Test
    void aLoadedRingNeverPrintsItsKeys() {
        MasterKeyRing ring = load(good());
        assertFalse(ring.toString().contains(Keys.base64(7)), ring.toString());
        assertFalse(ring.current().toString().contains(Keys.base64(7)));
        assertTrue(ring.toString().contains("2026-q4"));
    }
}
