package com.solace.sm.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects, per scenario, what was expected, what was observed and the commands that produced it, and writes one
 * markdown file under {@code target/<dir>/} that is copied into the plan's evidence folder by hand.
 */
final class MatrixEvidence {
    record Entry(String id, String title, String expected, List<String> observations, String verdict) { }

    private final String title;
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    private String id;
    private String scenarioTitle;
    private String expected;
    private List<String> observations;

    MatrixEvidence(String title, Path file) {
        this.title = title;
        this.file = file;
    }

    void begin(String id, String scenarioTitle, String expected) {
        this.id = id;
        this.scenarioTitle = scenarioTitle;
        this.expected = expected;
        this.observations = new ArrayList<>();
    }

    void observe(String line) {
        observations.add(line);
        System.out.println("[matrix " + id + "] " + line);
    }

    void pass(String summary) {
        end("PASS — " + summary);
    }

    void fail(String summary) {
        end("FAIL — " + summary);
    }

    private void end(String verdict) {
        if (id == null) {
            return;
        }
        entries.add(new Entry(id, scenarioTitle, expected, observations, verdict));
        flush();
        id = null;
    }

    /** A scenario that ended by an exception still lands in the file. */
    void abort(Throwable t) {
        end("FAIL — " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private void flush() {
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\nGenerated ").append(Instant.now())
                .append(" by the integration test; do not edit by hand.\n\n| Scenario | Verdict |\n|---|---|\n");
        for (Entry e : entries) {
            out.append("| ").append(e.id()).append(" ").append(e.title()).append(" | ")
                    .append(e.verdict().replace("\n", " ").replace("|", "/")).append(" |\n");
        }
        for (Entry e : entries) {
            out.append("\n## ").append(e.id()).append(" — ").append(e.title()).append("\n\n**Expected:** ")
                    .append(e.expected()).append("\n\n**Observed:**\n");
            e.observations().forEach(o -> out.append("- ").append(o.replace("\n", " ")).append("\n"));
            out.append("\n**Verdict:** ").append(e.verdict()).append("\n");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, out.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
