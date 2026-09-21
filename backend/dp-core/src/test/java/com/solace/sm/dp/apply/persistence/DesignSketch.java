package com.solace.sm.dp.apply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates and extracts the §3.1 DDL block out of {@code docs/design/backend/eda-walking-skeleton.md}.
 *
 * <p>Resolution mirrors {@code ContractArtifacts}: {@code -Dsm.repo.root} wins, otherwise an ancestor
 * walk from the working directory to the first directory that holds the design file. Surefire's
 * working directory is the module, so the walk is what makes this work from both {@code dp-core/} and
 * the reactor root.
 *
 * <p>The block is found <b>structurally</b> — the fenced region whose first line is the Phase-0
 * banner, to the closing fence — never by line number. Line numbers in a living design document go
 * stale the first time a paragraph is edited above them, and a mirror test that silently extracts the
 * wrong lines is worse than no mirror test.
 */
final class DesignSketch {

    static final String DESIGN_PATH = "docs/design/backend/eda-walking-skeleton.md";

    /** The first line of the block — the Phase-0 banner's opening comment. */
    private static final String BLOCK_START = "-- PHASE 0 — install bootstrap";

    private static final String FENCE = "```";

    private DesignSketch() {
    }

    static Path repoRoot() {
        String override = System.getProperty("sm.repo.root");
        if (override != null) {
            if (override.isBlank()) {
                throw new IllegalStateException("sm.repo.root is set but blank");
            }
            Path candidate = Path.of(override);
            if (!Files.isRegularFile(candidate.resolve(DESIGN_PATH))) {
                throw new IllegalStateException("sm.repo.root=" + override + " has no " + DESIGN_PATH);
            }
            return candidate;
        }
        Path from = Path.of("").toAbsolutePath();
        for (Path p = from; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve(DESIGN_PATH))) {
                return p;
            }
        }
        throw new IllegalStateException(DESIGN_PATH + " not found walking up from " + from);
    }

    /** The whole §3.1 SQL block, Phase 0 and Phase 1 together, exactly as the design carries it. */
    static String sqlBlock() {
        String doc = read(repoRoot().resolve(DESIGN_PATH));
        String[] lines = doc.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(BLOCK_START)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("no line starting with '" + BLOCK_START + "' in " + DESIGN_PATH
                    + " — the design moved and this test must be re-pointed, not deleted");
        }
        // Walk back to the opening fence — the banner sits under a `-- ===` rule, so it is the
        // block's second line, not its first. Anchoring on the fence keeps that a detail.
        int open = -1;
        for (int i = start; i >= 0; i--) {
            if (lines[i].startsWith(FENCE)) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            throw new IllegalStateException("the Phase-0 banner is not inside a fenced block");
        }
        int end = -1;
        for (int i = start; i < lines.length; i++) {
            if (lines[i].startsWith(FENCE)) {
                end = i;
                break;
            }
        }
        start = open + 1;
        if (end < 0) {
            throw new IllegalStateException("unterminated fenced block after the Phase-0 banner");
        }
        return String.join("\n", java.util.Arrays.asList(lines).subList(start, end));
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
