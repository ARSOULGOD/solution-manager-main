package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * OR-102: the keyed fingerprint recorded when a pre-apply copy is retained, and the check made before it is used.
 * Keyed hasher throughout: the point of the key is that nobody without it can forge a matching record.
 */
class PreImageFingerprintTest {

    private static final ConfigHasher HASHER = TestHashers.keyed();
    private static final TenantId TENANT = TestHashers.TENANT;
    private static final String FILE = "application.yml";

    private static final Set<String> DECLARED =
            Set.of("cache-engine.ttl", "cache-engine.max-size", "cache-engine.solace.host");

    private static final String ORIGINAL = """
            cache-engine:
              ttl: 5m
              max-size: 100
              solace:
                host: broker.example
                password: hunter2
            other: x
            """;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] recorded(String preImage, Set<String> paths) {
        return PreImageFingerprint.record(HASHER, TENANT, FILE, 8, bytes(preImage), paths);
    }

    private static PreImageFingerprint.Verdict check(byte[] sidecar, String current) {
        return PreImageFingerprint.check(HASHER, TENANT, FILE, 8, sidecar, bytes(current));
    }

    private static PreImageChange changed(PreImageFingerprint.Verdict verdict) {
        return assertInstanceOf(PreImageFingerprint.Verdict.Changed.class, verdict).change();
    }

    @Test
    void anUnchangedCopyIsIntact() {
        assertInstanceOf(PreImageFingerprint.Verdict.Intact.class, check(recorded(ORIGINAL, DECLARED), ORIGINAL));
    }

    @Test
    void aChangedDeclaredNonSecretPathIsNamedAndNothingElseIsClaimed() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED), ORIGINAL.replace("ttl: 5m", "ttl: 9m")));

        assertEquals(List.of("cache-engine.ttl"), change.declaredPathsChanged());
        assertFalse(change.otherContentChanged());
    }

    @Test
    void severalChangedPathsAreListedSorted() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED),
                ORIGINAL.replace("ttl: 5m", "ttl: 9m").replace("broker.example", "evil.example")));

        assertEquals(List.of("cache-engine.solace.host", "cache-engine.ttl"), change.declaredPathsChanged());
        assertFalse(change.otherContentChanged());
    }

    @Test
    void aDeclaredPathRemovedOutrightIsAChangedPath() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED), ORIGINAL.replace("  max-size: 100\n", "")));

        assertEquals(List.of("cache-engine.max-size"), change.declaredPathsChanged());
        assertFalse(change.otherContentChanged());
    }

    /** A secret-flagged path is never declared, so its change can only ever read "other content changed". */
    @Test
    void aChangedSecretFlaggedPathIsReportedAsOtherContentAndNeverByName() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED), ORIGINAL.replace("hunter2", "hunter3")));

        assertEquals(List.of(), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged());
        assertFalse(change.describe().contains("password"), change.describe());
        assertFalse(change.describe().contains("hunter"), change.describe());
    }

    /**
     * OR-113 (5): the remainder digest includes secret-flagged values. With a declared path changed as well, the
     * "other content changed" flag cannot come from the "nothing else explains it" fallback, so only the keyed
     * remainder digest can raise it.
     */
    @Test
    void aSecretEditAlongsideADeclaredPathChangeIsStillOtherContentChanged() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED),
                ORIGINAL.replace("ttl: 5m", "ttl: 9m").replace("hunter2", "hunter3")));

        assertEquals(List.of("cache-engine.ttl"), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged(), "the secret edit sits in the remainder the keyed digest covers");
        assertFalse(change.describe().contains("hunter"), change.describe());
    }

    @Test
    void aDeclaredAndAnUndeclaredChangeAreBothReported() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED),
                ORIGINAL.replace("ttl: 5m", "ttl: 9m").replace("other: x", "other: y")));

        assertEquals(List.of("cache-engine.ttl"), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged());
    }

    /** Whitespace and comments change the bytes while changing no declared value: the whole-file hash still catches it. */
    @Test
    void aChangeThatTouchesNoDeclaredPathIsOtherContentChanged() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED), ORIGINAL + "# edited by hand\n"));

        assertEquals(List.of(), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged());
    }

    @Test
    void aCopyThatNoLongerParsesIsOtherContentChangedWithNoPaths() {
        PreImageChange change = changed(check(recorded(ORIGINAL, DECLARED), "cache-engine: [unclosed\n  - : :\n"));

        assertEquals(List.of(), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged());
    }

    @Test
    void aCopyThatNeverParsedStillGetsAWholeFileFingerprint() {
        byte[] sidecar = recorded("not: [valid\n", DECLARED);

        assertInstanceOf(PreImageFingerprint.Verdict.Intact.class, check(sidecar, "not: [valid\n"));
        PreImageChange change = changed(check(sidecar, "not: [valid2\n"));
        assertEquals(List.of(), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged());
    }

    @Test
    void withNoDeclaredPathsAnyChangeIsOtherContentChanged() {
        PreImageChange change = changed(check(recorded(ORIGINAL, Set.of()), ORIGINAL.replace("ttl: 5m", "ttl: 9m")));

        assertEquals(List.of(), change.declaredPathsChanged());
        assertTrue(change.otherContentChanged());
    }

    // ---- what is recorded --------------------------------------------------------------------------

    /** I6: names declared paths and keyed digests, and nothing else. No value, no secret-flagged path. */
    @Test
    void theRecordNamesOnlyTheDeclaredPathsAndCarriesNoValue() {
        String record = new String(recorded(ORIGINAL, DECLARED), StandardCharsets.UTF_8);

        for (String path : DECLARED) {
            assertTrue(record.contains(path), path);
        }
        for (String leaked : List.of("hunter2", "password", "solace.password", "5m", "broker.example", "other")) {
            assertFalse(record.contains(leaked), "the record must not carry: " + leaked);
        }
    }

    @Test
    void thePerPathDigestsAreKeyedSoAnotherTenantsKeyCannotForgeThem() {
        byte[] sidecar = recorded(ORIGINAL, DECLARED);
        TenantId other = new TenantId(UUID.randomUUID());

        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class,
                PreImageFingerprint.check(HASHER, other, FILE, 8, sidecar, bytes(ORIGINAL)),
                "a record sealed under one tenant's key does not verify under another's");
    }

    // ---- a record that cannot be trusted ------------------------------------------------------------

    @Test
    void noRecordAtAllIsUnrecordedNotIntact() {
        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class, check(null, ORIGINAL));
    }

    @Test
    void aGarbledRecordIsUnrecorded() {
        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class, check(bytes("{not json"), ORIGINAL));
        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class, check(new byte[0], ORIGINAL));
    }

    /** Whoever can write the copy can write the record beside it; without the key they cannot make the two agree. */
    @Test
    void aRecordEditedToMatchTamperedContentIsUnrecorded() {
        String tampered = ORIGINAL.replace("ttl: 5m", "ttl: 9m");
        byte[] forgedWhole = recorded(tampered, DECLARED);
        String genuine = new String(recorded(ORIGINAL, DECLARED), StandardCharsets.UTF_8);
        String forged = new String(forgedWhole, StandardCharsets.UTF_8);
        // Splice the forger's whole-file fingerprint into the genuine record, keeping the genuine seal.
        String genuineWhole = genuine.replaceAll("(?s).*\"whole\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String forgedWholeHash = forged.replaceAll("(?s).*\"whole\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        byte[] spliced = genuine.replace(genuineWhole, forgedWholeHash).getBytes(StandardCharsets.UTF_8);

        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class, check(spliced, tampered));
    }

    @Test
    void aRecordFromAnotherGenerationOrFileDoesNotVerify() {
        byte[] sidecar = recorded(ORIGINAL, DECLARED);

        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class,
                PreImageFingerprint.check(HASHER, TENANT, FILE, 9, sidecar, bytes(ORIGINAL)));
        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class,
                PreImageFingerprint.check(HASHER, TENANT, "other.yml", 8, sidecar, bytes(ORIGINAL)));
    }
}
