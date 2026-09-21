package com.solace.sm.dp.apply.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable config writes and their rollback pre-images. Runtime-neutral: the Docker adapter writes
 * through a bind mount and a future systemd adapter writes to a real host directory, and the file
 * semantics are identical.
 *
 * <p><b>temp → fsync → rename</b>, per {@code BE-D12} §2.1's binding rules, so a crash mid-write
 * leaves either the old file or the new one and never a half-written one.
 *
 * <p><b>Pre-images, not generation copies.</b> Rollback must restore "the prior persisted config"
 * ({@code B/D12}), and on a first apply that prior config is one SM never wrote — or nothing at all.
 * So each write snapshots what it is about to replace, keyed by the generation replacing it;
 * restoring generation {@code G} puts back exactly what was there before {@code G} landed, including
 * <b>absence</b>. Keeping N generations of SM's own output instead would have no answer for the first
 * apply, which is the case rollback is most likely to be asked about.
 *
 * <p>POSIX hosts only in v1: the directory fsync opens the directory for read, which Windows refuses.
 * {@code dp-remote}'s v1 runtimes (Docker on Linux/macOS, systemd) are all POSIX.
 */
public final class ConfigFileStore {

    private static final String PRE_IMAGE_INFIX = ".sm-pre-";
    private static final String ABSENT_MARKER = ".absent";

    /**
     * The expected-hash value that means "the prior state was the absence of a file".
     *
     * <p>Deliberately not hash-shaped: a real hash matches
     * {@code ^[a-z0-9]+:[0-9a-f]{16,128}$}, and this does not, so it can never collide with one.
     * The v1 engine never passes it — {@code BE-D22} routes an absent target to
     * {@code APPLY_INDETERMINATE}, because {@code hash_on_config_steps} gives a {@code ROLLBACK}
     * intent nowhere to record it. The store supports the case so a later persistence plan that
     * journals absence can use it without changing this class.
     */
    public static final String ABSENT_TARGET = "absent:no-prior-file";

    private final int retainedGenerations;

    /**
     * Every path this store put bytes at, in order, through {@link #durablyPlace}.
     *
     * <p>A test-only observation on production state, and it earns its place: "one shared durable
     * helper, and every writer uses it" is a claim about which CODE PATH ran, and no assertion over
     * the resulting files can distinguish an atomic temp-and-rename from a plain
     * {@code Files.copy} — both leave the same directory contents and no residue. This list is what
     * makes reverting a writer to a direct copy turn a test red instead of passing silently.
     */
    private final List<String> placements = new ArrayList<>();

    public ConfigFileStore(int retainedGenerations) {
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException(
                    "at least one pre-image must be retained, or rollback has nothing to restore");
        }
        this.retainedGenerations = retainedGenerations;
    }

    /** What {@link #restore} did. */
    public enum Restoration {
        /** The pre-image matched the expected hash and is back in place. */
        RESTORED,
        /** No pre-image for that generation: nothing was ever written, so nothing was undone. */
        NOTHING_TO_RESTORE,
        /**
         * A pre-image exists but no longer hashes to the expected target — the world moved between
         * assess and restore. <b>Nothing was written.</b>
         */
        TARGET_MOVED
    }

    /**
     * What {@link #restore} WOULD put back, without putting it back — the read half {@code BE-D22}
     * needs so the engine can journal a {@code ROLLBACK} intent's {@code config_hash} BEFORE the
     * destructive call.
     */
    public enum PreImage {
        /** A file was there before this generation; {@link #preImageHash} names its hash. */
        PRESENT,
        /** Nothing was there before this generation. The case {@code hash_on_config_steps} cannot express. */
        ABSENT,
        /** This store never wrote this generation at all. */
        MISSING
    }

    public String hashOf(byte[] bytes) {
        return RenderedConfig.sha256(bytes);
    }

    /** The hash of what is at {@code file} now, or empty when nothing is. */
    public Optional<String> readBackHash(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(hashOf(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw io(e);
        }
    }

    /** Whether generation {@code G}'s pre-image is a file, a recorded absence, or not there at all. */
    public PreImage preImageKind(Path dir, String fileName, long generation) {
        Path preImage = preImage(dir, fileName, generation);
        if (Files.isRegularFile(preImage)) {
            return PreImage.PRESENT;
        }
        return Files.isRegularFile(Path.of(preImage + ABSENT_MARKER))
                ? PreImage.ABSENT
                : PreImage.MISSING;
    }

    /** The hash a restore of generation {@code G} would land, or empty unless the kind is PRESENT. */
    public Optional<String> preImageHash(Path dir, String fileName, long generation) {
        return preImageKind(dir, fileName, generation) == PreImage.PRESENT
                ? readBackHash(preImage(dir, fileName, generation))
                : Optional.empty();
    }

    /**
     * Snapshots what is there, then lands {@code config} atomically. Returns the hash of the bytes
     * written — the same value {@link RenderedConfig#hash()} already holds, returned so the caller
     * reports what the FILE now has rather than what it intended.
     */
    public String write(Path dir, String fileName, RenderedConfig config, long generation) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            capturePreImage(dir, fileName, target, generation);

            durablyPlace(dir, target, config.bytes());
            prunePreImages(dir, fileName);
            return readBackHash(target).orElseThrow(() -> new IllegalStateException(
                    "the file is not readable immediately after an atomic rename"));
        } catch (IOException e) {
            throw io(e);
        }
    }

    /**
     * Puts back whatever was there before {@code generation} landed — including nothing — but
     * <b>only if the pre-image still hashes to {@code expectedHash}</b>, the value the assessment
     * recorded on the {@code ROLLBACK} intent.
     *
     * <p>{@code BE-D22} correction B: without this check the hash on the intent would be a claim
     * about a file nobody re-read, and a restore could overwrite live configuration with bytes that
     * are no longer the recorded target. A pre-image that vanished or changed between assess and
     * restore means the world moved, so this writes <b>nothing</b> and says which.
     */
    public Restoration restore(Path dir, String fileName, long generation, String expectedHash) {
        try {
            Path preImage = preImage(dir, fileName, generation);
            Path target = dir.resolve(fileName);
            if (Files.isRegularFile(preImage)) {
                if (!readBackHash(preImage).map(h -> h.equals(expectedHash)).orElse(false)) {
                    return Restoration.TARGET_MOVED;          // nothing written
                }
                durablyPlace(dir, target, Files.readAllBytes(preImage));
                return Restoration.RESTORED;
            }
            if (Files.isRegularFile(Path.of(preImage + ABSENT_MARKER))) {
                if (!ABSENT_TARGET.equals(expectedHash)) {
                    return Restoration.TARGET_MOVED;          // nothing written
                }
                Files.deleteIfExists(target);
                fsyncDirectory(dir);
                return Restoration.RESTORED;
            }
            return Restoration.NOTHING_TO_RESTORE;
        } catch (IOException e) {
            throw io(e);
        }
    }

    /**
     * <b>The one durable replace.</b> Everything in this store that puts bytes at a path goes
     * through it: the config write, the pre-image capture, the absence marker and the restore.
     *
     * <p>temp in the SAME directory → fsync the temp file → {@code ATOMIC_MOVE} with
     * {@code REPLACE_EXISTING} → fsync the directory → remove any temp residue. A crash at any
     * point leaves either the old bytes or the new ones, never a half-written file and never a
     * pre-image that exists but is not on disk yet.
     *
     * <p>The content is passed as bytes rather than copied stream-wise on purpose: a copy that is
     * not routed through a temp-and-rename is not atomic, and unifying on one byte-taking helper is
     * what makes "every writer is durable" checkable rather than asserted. Config files are a single
     * property file by construction ({@code SM-D3}), so holding one in memory is not a concern.
     */
    private void durablyPlace(Path dir, Path target, byte[] content) throws IOException {
        Path tmp = dir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        fsyncDirectory(dir);
        placements.add(target.getFileName().toString());
    }

    /** Every path {@link #durablyPlace} put bytes at, in order. See the field's javadoc. */
    public List<String> placements() {
        return List.copyOf(placements);
    }

    /**
     * Snapshots what is at {@code target} before this generation replaces it.
     *
     * <p>Both branches are durable, and the ordering matters: this runs BEFORE the config write, so
     * by the time the new bytes land the record of what they replaced — a pre-image file, or the
     * marker saying there was nothing — is already fsynced. A marker written without an fsync could
     * be lost by a crash that kept the config write, and rollback would then restore a file that
     * should not exist.
     */
    private void capturePreImage(Path dir, String fileName, Path target, long generation)
            throws IOException {
        Path preImage = preImage(dir, fileName, generation);
        if (Files.isRegularFile(preImage) || Files.isRegularFile(Path.of(preImage + ABSENT_MARKER))) {
            // Idempotent re-materialize of the SAME generation: the pre-image already records what
            // was there BEFORE this generation first landed. Re-capturing would overwrite it with
            // this generation's own bytes and silently make rollback a no-op.
            return;
        }
        if (Files.isRegularFile(target)) {
            durablyPlace(dir, preImage, Files.readAllBytes(target));
        } else {
            durablyPlace(dir, Path.of(preImage + ABSENT_MARKER), new byte[0]);
        }
    }

    private Path preImage(Path dir, String fileName, long generation) {
        return dir.resolve("." + fileName + PRE_IMAGE_INFIX + generation);
    }

    private void prunePreImages(Path dir, String fileName) throws IOException {
        String prefix = "." + fileName + PRE_IMAGE_INFIX;
        List<Path> all;
        try (var stream = Files.list(dir)) {
            all = stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .sorted(Comparator.comparingLong(ConfigFileStore::generationOf))
                    .toList();
        }
        // Prune by DISTINCT generation rather than by file count. MEASURED (plan 005 Task 1): with
        // today's capturePreImage a generation has either a pre-image or an .absent marker and never
        // both, so the two are numerically identical and a mutation to `all.size()` turns NOTHING
        // red. Recorded as an untestable branch rather than dressed up as a guard (L7): it is kept
        // because it states the intent -- retain N GENERATIONS -- and stays correct if a later
        // change ever does write both forms, not because a test proves it necessary today.
        List<Long> generations =
                all.stream().map(ConfigFileStore::generationOf).distinct().sorted().toList();
        if (generations.size() <= retainedGenerations) {
            return;
        }
        List<Long> doomed = generations.subList(0, generations.size() - retainedGenerations);
        for (Path p : all) {
            if (doomed.contains(generationOf(p))) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static long generationOf(Path p) {
        String name = p.getFileName().toString();
        String tail = name.substring(name.lastIndexOf(PRE_IMAGE_INFIX) + PRE_IMAGE_INFIX.length());
        if (tail.endsWith(ABSENT_MARKER)) {
            tail = tail.substring(0, tail.length() - ABSENT_MARKER.length());
        }
        return Long.parseLong(tail);
    }

    private static void fsyncDirectory(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    /**
     * A filesystem failure is a corruption report, not a caller-recoverable one, and it carries no
     * path: a bind-mount source is exactly the kind of value {@code RuntimeRef.MOUNT_PATH} is
     * sentinel-tested for.
     */
    private static UncheckedIOException io(IOException e) {
        return new UncheckedIOException(
                "a config-store operation failed; the path is deliberately not repeated here (I6)",
                new IOException(e.getClass().getSimpleName()));
    }
}
