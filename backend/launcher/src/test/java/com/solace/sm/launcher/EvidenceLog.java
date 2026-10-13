package com.solace.sm.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What a walkthrough observed, in the order it observed it (plan 010 T6.6): counts, topics, ids, hashes, every skip with
 * its reason. A run writes it under {@code target/walkthrough-evidence/} and prints each line as it is recorded, and the
 * operator carries it into {@code docs/plans/backend/evidence/010-first-e2e-walkthrough.md}.
 *
 * <p><b>It cannot carry a secret.</b> {@link #forbid} registers every secret the run was handed (the Cache broker's
 * password, the local broker's per-run credentials); a line containing one is refused WITHOUT echoing it. Hashes, ids
 * and topics are evidence; envelopes are recorded as ids only, never as bodies that could carry a value.
 */
final class EvidenceLog {

    private static final int MIN_FORBIDDEN_LENGTH = 6;

    private final String title;
    private final Map<String, List<String>> sections = new LinkedHashMap<>();
    private final List<String> skips = new ArrayList<>();
    private final List<String> forbidden = new CopyOnWriteArrayList<>();

    EvidenceLog(String title) {
        this.title = title;
    }

    /** A secret that must never appear in any recorded line. Blank or very short values are ignored. */
    void forbid(String secret) {
        if (secret != null && secret.length() >= MIN_FORBIDDEN_LENGTH) {
            forbidden.add(secret);
        }
    }

    synchronized void record(String section, String line) {
        for (String secret : forbidden) {
            if (line.contains(secret)) {
                throw new IllegalStateException("an evidence line for section '" + section
                        + "' carried a registered secret and was refused (the secret is not repeated here)");
            }
        }
        sections.computeIfAbsent(section, s -> new ArrayList<>()).add(line);
        System.out.println("[walkthrough-evidence] " + section + ": " + line);
    }

    synchronized void skip(String what, String reason) {
        String line = what + " - " + reason;
        for (String secret : forbidden) {
            if (line.contains(secret)) {
                throw new IllegalStateException("a skip line carried a registered secret and was refused");
            }
        }
        skips.add(line);
        System.out.println("[walkthrough-evidence] SKIPPED: " + line);
    }

    synchronized String render() {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
        for (Map.Entry<String, List<String>> section : sections.entrySet()) {
            out.append("## ").append(section.getKey()).append("\n\n");
            for (String line : section.getValue()) {
                out.append("- ").append(line).append('\n');
            }
            out.append('\n');
        }
        if (!skips.isEmpty()) {
            out.append("## Skips, each with its reason\n\n");
            for (String skip : skips) {
                out.append("- SKIPPED: ").append(skip).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Writes {@code <prefix>-<UTC timestamp>.md} under {@code directory} and returns its path. */
    Path writeTo(Path directory, String prefix) {
        try {
            Files.createDirectories(directory);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
            Path file = directory.resolve(prefix + "-" + stamp + ".md");
            Files.writeString(file, render(), StandardCharsets.UTF_8);
            System.out.println("[walkthrough-evidence] written to " + file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
