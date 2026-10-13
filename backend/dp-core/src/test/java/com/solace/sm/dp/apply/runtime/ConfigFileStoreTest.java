package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Uuid7;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileStoreTest {

    private final ConfigFileStore store = new ConfigFileStore(3, TestHashers.SHA256);

    private static RenderedConfig rendered(String yaml) {
        return TestHashers.rendered(yaml.getBytes(StandardCharsets.UTF_8));
    }

    /** BE-D39: the Docker adapter's bounded read; the bound is the SSH adapter's, so both refuse the same file. */
    @Test
    void aBoundedReadReturnsTheBytesAbsenceAsEmptyAndRefusesAnOverBoundFileWithoutNamingThePath(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("application.yml");
        assertTrue(store.readBounded(file, 16).isEmpty(), "absent");
        assertTrue(store.readBounded(dir, 16).isEmpty(), "a directory is not a regular file");
        Files.writeString(file, "a: 1\n");
        assertEquals("a: 1\n", new String(store.readBounded(file, 16).orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(5, store.readBounded(file, 5).orElseThrow().length, "exactly at the bound is fine");
        java.io.UncheckedIOException tooBig = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.UncheckedIOException.class, () -> store.readBounded(file, 4));
        assertFalse(String.valueOf(tooBig.getMessage()).contains(dir.toString()), "I6: the path is not repeated");
    }

    @Test
    void aWriteLandsAtomicallyAndReportsTheHashOfWhatItWrote(@TempDir Path dir) throws Exception {
        RenderedConfig c = rendered("a: 1\n");
        String written = store.write(TestHashers.TENANT, dir, "application.yml", c, 7);

        assertEquals(c.hash(), written, "the store must report the hash of the bytes it was given");
        assertEquals("a: 1\n", Files.readString(dir.resolve("application.yml")));
        assertEquals(written, store.readBackHash(TestHashers.TENANT, dir.resolve("application.yml")).orElseThrow(),
                "read-back must agree with the write");
        try (var s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "no temp file may survive a successful write");
        }
    }

    /**
     * The hash this store produces is handed straight to AttemptRow.observedHash, which validates
     * against ValueGrammar.HASH. Asserting the grammar with a second copy of the regex here would
     * prove only that two regexes agree; handing the value to the type that owns the rule proves the
     * thing that matters.
     */
    @Test
    void theHashIsAcceptedByTheModelTypesThatPersistIt(@TempDir Path dir) {
        String h = store.write(TestHashers.TENANT, dir, "application.yml", rendered("a: 1\n"), 7);
        AttemptRow row = new AttemptRow(Uuid7.next(), Uuid7.next(), Step.MATERIALIZE, (short) 1,
                null, Instant.EPOCH, null, Instant.EPOCH.plusSeconds(60), null, h, null, null);
        assertEquals(h, row.observedHash());
    }

    /** Plan 010 T6.8: the reconciler's re-write lands the bytes atomically and retains NO pre-image. */
    @Test
    void aRewriteLandsTheBytesAndRetainsNoPreImage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "prior: true\n");
        RenderedConfig c = rendered("intended: true\n");

        String written = store.rewrite(TestHashers.TENANT, dir, "application.yml", c);

        assertEquals(c.hash(), written);
        assertEquals("intended: true\n", Files.readString(dir.resolve("application.yml")));
        assertEquals(ConfigFileStore.PreImage.MISSING, store.preImageKind(dir, "application.yml", 9),
                "capturing here could retain content SM did not write and offer it as a rollback target");
        try (var s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains(".sm-pre-")),
                    "no pre-image file of any generation appeared");
        }
    }

    @Test
    void readBackOfAnAbsentFileIsEmptyRatherThanAnException(@TempDir Path dir) {
        assertTrue(store.readBackHash(TestHashers.TENANT, dir.resolve("nothing.yml")).isEmpty());
    }

    @Test
    void restoringReturnsTheFileToWhatItHeldBeforeThatGenerationWasWritten(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        String originalHash = store.readBackHash(TestHashers.TENANT, dir.resolve("application.yml")).orElseThrow();

        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);
        assertEquals("generation: 8\n", Files.readString(dir.resolve("application.yml")));

        assertEquals(ConfigFileStore.Restoration.RESTORED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 8, originalHash));
        assertEquals("original: true\n", Files.readString(dir.resolve("application.yml")),
                "rollback restores the PRIOR persisted config, which SM did not write");
    }

    @Test
    void restoringAGenerationWrittenOverNothingRemovesTheFile(@TempDir Path dir) throws Exception {
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 9\n"), 9);

        assertEquals(ConfigFileStore.Restoration.RESTORED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 9, ConfigFileStore.ABSENT_TARGET));
        assertFalse(Files.exists(dir.resolve("application.yml")),
                "the prior state was ABSENT, and restoring to absent is a restoration, not a no-op");
    }

    @Test
    void restoringAGenerationThisStoreNeverWroteReportsNothingToRestore(@TempDir Path dir) {
        assertEquals(ConfigFileStore.Restoration.NOTHING_TO_RESTORE,
                store.restore(TestHashers.TENANT, dir, "application.yml", 11, "sha256:" + "0".repeat(64)),
                "an untouched target is FAILED_NO_EFFECT's evidence, never a silent success");
    }

    @Test
    void onlyTheConfiguredNumberOfPreImagesIsRetained(@TempDir Path dir) throws Exception {
        for (long g = 1; g <= 5; g++) {
            store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: " + g + "\n"), g);
        }
        List<String> preImages;
        try (var s = Files.list(dir)) {
            preImages = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains(".sm-pre-"))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(".application.yml.sm-pre-3", ".application.yml.sm-pre-4",
                ".application.yml.sm-pre-5"), preImages,
                "B/D12 retains the last N generations; the oldest go first");
    }

    /**
     * BE-D22's read half: the engine must learn the restore target's hash BEFORE it journals a
     * ROLLBACK intent, and learning it must not restore anything.
     */
    @Test
    void thePreImageCanBeReadWithoutRestoringIt(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        String originalHash = store.readBackHash(TestHashers.TENANT, dir.resolve("application.yml")).orElseThrow();
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);

        assertEquals(ConfigFileStore.PreImage.PRESENT, store.preImageKind(dir, "application.yml", 8));
        assertEquals(originalHash, store.preImageHash(TestHashers.TENANT, dir, "application.yml", 8).orElseThrow());
        assertEquals("generation: 8\n", Files.readString(dir.resolve("application.yml")),
                "reading the pre-image must not put it back — that is the whole point of the split");
    }

    /** The case hash_on_config_steps cannot express: a restore target with no hash. */
    @Test
    void aPreImageOfAbsenceIsReportedAsAbsentAndHasNoHash(@TempDir Path dir) {
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 9\n"), 9);

        assertEquals(ConfigFileStore.PreImage.ABSENT, store.preImageKind(dir, "application.yml", 9));
        assertTrue(store.preImageHash(TestHashers.TENANT, dir, "application.yml", 9).isEmpty());
        assertEquals(ConfigFileStore.PreImage.MISSING,
                store.preImageKind(dir, "application.yml", 99));
    }

    /**
     * BE-D22 correction B. The pre-image is re-hashed against the target the assessment recorded,
     * BEFORE anything is written -- so a pre-image that changed in between is refused and the live
     * file is left exactly as it was.
     */
    @Test
    void aPreImageAlteredSinceTheAssessmentIsRefusedAndNothingIsWritten(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        String assessedHash = store.readBackHash(TestHashers.TENANT, dir.resolve("application.yml")).orElseThrow();
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);

        // Somebody edits the retained pre-image between assess and restore.
        Files.writeString(dir.resolve(".application.yml.sm-pre-8"), "tampered: true\n");

        assertEquals(ConfigFileStore.Restoration.TARGET_MOVED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 8, assessedHash));
        assertEquals("generation: 8\n", Files.readString(dir.resolve("application.yml")),
                "the live file must be untouched: a restore that cannot prove its target writes "
                        + "nothing at all");
    }

    /** The absence marker gets the same guard: only the absence sentinel may delete the file. */
    @Test
    void restoringToAbsenceWithAHashTargetIsRefused(@TempDir Path dir) throws Exception {
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 9\n"), 9);

        assertEquals(ConfigFileStore.Restoration.TARGET_MOVED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 9, "sha256:" + "0".repeat(64)));
        assertTrue(Files.exists(dir.resolve("application.yml")));
    }

    // ---- durability: one shared helper, and every writer uses it -------------------------------

    /**
     * The claim is about which CODE PATH ran, so the assertion has to be too: no comparison of
     * directory contents can tell an atomic temp-and-rename from a plain {@code Files.copy}.
     * {@link ConfigFileStore#placements()} records what went through the one durable helper, in
     * order, and reverting any writer to a direct copy drops its entry.
     */
    @Test
    void aWriteOverAnExistingFilePlacesThePreImageThenTheConfig(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.SHA256);

        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);

        assertEquals(List.of(".application.yml.sm-fp-8", ".application.yml.sm-pre-8", "application.yml"),
                store.placements(),
                "the fingerprint record, the pre-image capture and the config write all go through the durable "
                        + "helper, and the capture (record first, then the pre-image) goes BEFORE the config write");
    }

    /**
     * The absence marker must be on disk before the config write that follows it. A marker lost to
     * a crash that kept the config write would make a later rollback restore a file that should
     * never have existed.
     */
    @Test
    void theAbsenceMarkerIsPlacedDurablyBeforeTheConfigWriteThatFollowsIt(@TempDir Path dir) {
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.SHA256);

        store.write(TestHashers.TENANT, dir, "application.yml", rendered("first: true\n"), 7);

        assertEquals(List.of(".application.yml.sm-pre-7.absent", "application.yml"),
                store.placements(),
                "the marker is placed through the durable helper, and before the config write");
    }

    @Test
    void restoringAPresentPreImageGoesThroughTheDurableHelper(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.SHA256);
        String originalHash = store.readBackHash(TestHashers.TENANT, dir.resolve("application.yml")).orElseThrow();
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);

        store.restore(TestHashers.TENANT, dir, "application.yml", 8, originalHash);

        assertEquals(List.of(".application.yml.sm-fp-8", ".application.yml.sm-pre-8", "application.yml",
                "application.yml"), store.placements(),
                "the restore is the last placement: it replaces the live file durably, not with a "
                        + "direct copy");
    }

    /** A refused restore writes nothing at all, so it places nothing. */
    @Test
    void arefusedRestorePlacesNothing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.SHA256);
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);
        int before = store.placements().size();

        assertEquals(ConfigFileStore.Restoration.TARGET_MOVED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 8, "sha256:" + "0".repeat(64)));
        assertEquals(before, store.placements().size());
    }

    @Test
    void noTempFileSurvivesAnyOperation(@TempDir Path dir) throws Exception {
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.SHA256);
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 7\n"), 7);
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);
        store.restore(TestHashers.TENANT, dir, "application.yml", 8,
                store.preImageHash(TestHashers.TENANT, dir, "application.yml", 8).orElseThrow());

        try (var s = Files.list(dir)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "the helper removes its temp file on every path, including the failing one");
        }
    }

    /**
     * An idempotent re-materialize of the SAME generation must not re-capture the pre-image.
     * §2.2 rule 2 makes re-execution under the same key a success, and the contract kit calls
     * materializeConfig twice; re-capturing would overwrite the rollback target with this
     * generation's own bytes and silently turn a later rollback into a no-op.
     */
    @Test
    void reMaterializingTheSameGenerationDoesNotDestroyTheRollbackTarget(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("application.yml"), "original: true\n");
        String originalHash = store.readBackHash(TestHashers.TENANT, dir.resolve("application.yml")).orElseThrow();

        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: 8\n"), 8);

        assertEquals(originalHash, store.preImageHash(TestHashers.TENANT, dir, "application.yml", 8).orElseThrow(),
                "the pre-image still names what was there before generation 8 first landed");
    }

    // ---- OR-102: the retained pre-image carries a recorded keyed fingerprint ---------------------------

    private static final String PRIOR = """
            cache-engine:
              ttl: 5m
              solace:
                password: hunter2
            """;

    private static RenderedConfig renderedWithPaths(ConfigHasher hasher, String yaml, String... paths) {
        return RenderedConfig.of(TestHashers.TENANT, yaml.getBytes(StandardCharsets.UTF_8), hasher,
                java.util.Set.of(paths));
    }

    private static List<String> names(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void retainingAPreImageRecordsAKeyedFingerprintOfItBesideIt(@TempDir Path dir) throws Exception {
        ConfigHasher keyed = TestHashers.keyed();
        ConfigFileStore store = new ConfigFileStore(3, keyed);
        Files.writeString(dir.resolve("application.yml"), PRIOR);

        store.write(TestHashers.TENANT, dir, "application.yml",
                renderedWithPaths(keyed, "cache-engine:\n  ttl: 9m\n", "cache-engine.ttl"), 8);

        assertTrue(names(dir).contains(".application.yml.sm-fp-8"), names(dir).toString());
        String record = Files.readString(dir.resolve(".application.yml.sm-fp-8"));
        assertTrue(record.contains("hmacsha256v1:"), "keyed under OR-32's scheme: " + record);
        assertTrue(record.contains("cache-engine.ttl"), "the declared non-secret path is named");
        assertFalse(record.contains("hunter2") || record.contains("password") || record.contains("5m"),
                "a record carries no value and no secret-flagged path (I6)");
        assertInstanceOf(PreImageFingerprint.Verdict.Intact.class,
                store.checkPreImage(TestHashers.TENANT, dir, "application.yml", 8));
    }

    /**
     * The residual the S6+S7 review accepted: a copy altered BEFORE the assessment read it. The assessment then records
     * the altered copy's own hash, restore finds it matches, and the altered bytes went live. The fingerprint was taken
     * at retention, so it disagrees.
     */
    @Test
    void aPreImageAlteredBeforeTheAssessmentIsRefusedNotRestored(@TempDir Path dir) throws Exception {
        ConfigHasher keyed = TestHashers.keyed();
        ConfigFileStore store = new ConfigFileStore(3, keyed);
        Files.writeString(dir.resolve("application.yml"), PRIOR);
        store.write(TestHashers.TENANT, dir, "application.yml",
                renderedWithPaths(keyed, "cache-engine:\n  ttl: 9m\n", "cache-engine.ttl"), 8);
        Files.writeString(dir.resolve(".application.yml.sm-pre-8"), PRIOR.replace("5m", "1d"));
        String assessedAfterTheAlteration =
                store.preImageHash(TestHashers.TENANT, dir, "application.yml", 8).orElseThrow();

        assertEquals(ConfigFileStore.Restoration.TARGET_MOVED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 8, assessedAfterTheAlteration),
                "the hash the assessment read matches the altered copy, so only the retention-time record can refuse it");
        assertEquals("cache-engine:\n  ttl: 9m\n", Files.readString(dir.resolve("application.yml")),
                "nothing was written");
        PreImageChange change = assertInstanceOf(PreImageFingerprint.Verdict.Changed.class,
                store.checkPreImage(TestHashers.TENANT, dir, "application.yml", 8)).change();
        assertEquals(List.of("cache-engine.ttl"), change.declaredPathsChanged());
        assertFalse(change.otherContentChanged());
    }

    @Test
    void aPreImageWithItsRecordRemovedIsRefusedNotTrusted(@TempDir Path dir) throws Exception {
        ConfigHasher keyed = TestHashers.keyed();
        ConfigFileStore store = new ConfigFileStore(3, keyed);
        Files.writeString(dir.resolve("application.yml"), PRIOR);
        store.write(TestHashers.TENANT, dir, "application.yml", renderedWithPaths(keyed, "ttl: 9m\n"), 8);
        String hash = store.preImageHash(TestHashers.TENANT, dir, "application.yml", 8).orElseThrow();
        Files.delete(dir.resolve(".application.yml.sm-fp-8"));

        assertInstanceOf(PreImageFingerprint.Verdict.Unrecorded.class,
                store.checkPreImage(TestHashers.TENANT, dir, "application.yml", 8));
        assertEquals(ConfigFileStore.Restoration.TARGET_MOVED,
                store.restore(TestHashers.TENANT, dir, "application.yml", 8, hash));
        assertEquals("ttl: 9m\n", Files.readString(dir.resolve("application.yml")));
    }

    @Test
    void anUnchangedPreImageStillRestores(@TempDir Path dir) throws Exception {
        ConfigHasher keyed = TestHashers.keyed();
        ConfigFileStore store = new ConfigFileStore(3, keyed);
        Files.writeString(dir.resolve("application.yml"), PRIOR);
        store.write(TestHashers.TENANT, dir, "application.yml",
                renderedWithPaths(keyed, "ttl: 9m\n", "cache-engine.ttl"), 8);

        assertEquals(ConfigFileStore.Restoration.RESTORED, store.restore(TestHashers.TENANT, dir, "application.yml", 8,
                store.preImageHash(TestHashers.TENANT, dir, "application.yml", 8).orElseThrow()));
        assertEquals(PRIOR, Files.readString(dir.resolve("application.yml")));
    }

    @Test
    void aPreImageOfAbsenceAndARewriteRecordNoFingerprint(@TempDir Path dir) throws Exception {
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.keyed());
        store.write(TestHashers.TENANT, dir, "application.yml", rendered("ttl: 9m\n"), 7);   // nothing was there
        store.rewrite(TestHashers.TENANT, dir, "application.yml", rendered("ttl: 1m\n"));   // the reconciler's write

        assertEquals(List.of(".application.yml.sm-pre-7.absent", "application.yml"), names(dir),
                "no content to fingerprint for an absence, and a rewrite retains nothing");
    }

    @Test
    void retentionPrunesAFingerprintWithItsPreImage(@TempDir Path dir) throws Exception {
        ConfigFileStore store = new ConfigFileStore(3, TestHashers.keyed());
        for (long g = 1; g <= 5; g++) {
            store.write(TestHashers.TENANT, dir, "application.yml", rendered("generation: " + g + "\n"), g);
        }
        assertEquals(List.of(".application.yml.sm-fp-3", ".application.yml.sm-fp-4", ".application.yml.sm-fp-5",
                ".application.yml.sm-pre-3", ".application.yml.sm-pre-4", ".application.yml.sm-pre-5",
                "application.yml"), names(dir));
    }

    /** The same generation landing again must not re-fingerprint what is by then this generation's own bytes. */
    @Test
    void anIdempotentRematerializeKeepsTheOriginalRecord(@TempDir Path dir) throws Exception {
        ConfigHasher keyed = TestHashers.keyed();
        ConfigFileStore store = new ConfigFileStore(3, keyed);
        Files.writeString(dir.resolve("application.yml"), PRIOR);
        store.write(TestHashers.TENANT, dir, "application.yml", renderedWithPaths(keyed, "ttl: 9m\n"), 8);
        byte[] before = Files.readAllBytes(dir.resolve(".application.yml.sm-fp-8"));

        store.write(TestHashers.TENANT, dir, "application.yml", renderedWithPaths(keyed, "ttl: 9m\n"), 8);

        assertEquals(new String(before), Files.readString(dir.resolve(".application.yml.sm-fp-8")));
        assertInstanceOf(PreImageFingerprint.Verdict.Intact.class,
                store.checkPreImage(TestHashers.TENANT, dir, "application.yml", 8));
    }
}
