package com.solace.sm.events.contracttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.events.EventJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * THE test-scope resolver for contract artifacts (BE-D11) — the only way Java tests reach
 * contracts/. Resolution order: -Dsm.contracts.root (the repo-split seam, and the lever the
 * lock test's planted-divergence demonstrations use), then ancestor walk from the working
 * directory to the first directory containing contracts/<sub>.
 */
public final class ContractArtifacts {

    private ContractArtifacts() {
    }

    /** Locates {@code contracts/<sub>}, e.g. {@code dir("events/fixtures")}. */
    public static Path dir(String sub) {
        String override = System.getProperty("sm.contracts.root");
        if (override != null) {
            // Set-and-blank is a misconfiguration, never a request for the ancestor walk. Falling
            // back would resolve the REAL contracts tree, so a lock test's planted-divergence demo
            // could "pass" against unmutated files and a broken lock would look proven.
            if (override.isBlank()) {
                throw new IllegalStateException("sm.contracts.root is set but blank"
                        + " — unset it or point it at a contracts root");
            }
            Path candidate = Path.of(override).resolve(sub);
            if (!Files.isDirectory(candidate)) {
                throw new IllegalStateException(
                        "sm.contracts.root=" + override + " has no " + sub + ": " + candidate);
            }
            return candidate;
        }
        Path from = Path.of("").toAbsolutePath();
        for (Path p = from; p != null; p = p.getParent()) {
            Path candidate = p.resolve("contracts").resolve(sub);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("contracts/" + sub + " not found walking up from " + from);
    }

    /**
     * The .json files in {@code dir}, sorted, minus {@code excludeNames}. Throws when the result
     * is empty — a golden suite that finds nothing must fail, never pass vacuously (L7).
     */
    public static List<Path> jsonFiles(Path dir, String... excludeNames) {
        Set<String> excluded = Set.copyOf(Arrays.asList(excludeNames));
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !excluded.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
            if (files.isEmpty()) {
                throw new IllegalStateException("no JSON files found under " + dir
                        + " (excluding " + excluded + ")");
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses one artifact with the contract mapper configuration. */
    public static JsonNode json(Path file) {
        try {
            return EventJson.mapper().readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
