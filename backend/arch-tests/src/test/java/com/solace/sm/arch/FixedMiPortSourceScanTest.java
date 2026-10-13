package com.solace.sm.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-9.1, {@code noProductionSourceNamesAFixedMiPort}: no production source file names the Cache Engine's
 * fixed management port, {@code :8081}. Where a monitored port comes from is the placement's declared source, never
 * a literal on a production path.
 *
 * <p><b>Why a source scan and not an ArchUnit rule (MF22, measured).</b> ArchUnit 1.5.0's domain model reaches
 * dependencies, accesses (field, method and constructor), annotations and annotation enum constants
 * ({@code JavaEnumConstant}); its jar has no type for a string literal or a constant-pool entry, and its user guide
 * (read at 1.5.1) lists the same accesses and says nothing about literals. A string constant is therefore not visible
 * to a rule, so the guard reads source.
 */
class FixedMiPortSourceScanTest {

    static final String FIXED_PORT = ":8081";

    /** One match: the file, the 1-based line, and the line's text. */
    record Hit(Path file, int line, String text) {
        @Override
        public String toString() {
            return file + ":" + line + ": " + text.strip();
        }
    }

    /** The repository root: the nearest ancestor holding {@code backend/pom.xml} (override: -Dsm.repo.root). */
    static Path repoRoot() {
        String override = System.getProperty("sm.repo.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        Path from = Path.of("").toAbsolutePath();
        for (Path p = from; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("backend/pom.xml"))) {
                return p;
            }
        }
        throw new IllegalStateException("backend/pom.xml not found walking up from " + from);
    }

    /** Every {@code backend/<module>/src/main/java/**}{@code /*.java} file under {@code backendDir}. */
    static List<Path> productionSources(Path backendDir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(backendDir, Files::isDirectory)) {
            for (Path module : modules) {
                Path main = module.resolve("src/main/java");
                if (Files.isDirectory(main)) {
                    try (Stream<Path> walk = Files.walk(main)) {
                        walk.filter(f -> f.toString().endsWith(".java")).sorted().forEach(out::add);
                    }
                }
            }
        }
        return out;
    }

    /** The scanner: the same code runs over the real tree and over the planted fixture. */
    static List<Hit> scan(List<Path> files) {
        List<Hit> hits = new ArrayList<>();
        for (Path file : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(FIXED_PORT)) {
                    hits.add(new Hit(file, i + 1, lines.get(i)));
                }
            }
        }
        return hits;
    }

    @Test
    void noProductionSourceNamesAFixedMiPort() throws IOException {
        List<Path> sources = productionSources(repoRoot().resolve("backend"));
        assertFalse(sources.isEmpty(), "no production source found: the scan would prove nothing");
        assertTrue(sources.stream().anyMatch(p -> p.endsWith("SshjDockerTransport.java")),
                "the scan must reach dp-core's production tree");
        List<Hit> hits = scan(sources);
        assertTrue(hits.isEmpty(), "a production source names " + FIXED_PORT + " (the MI port comes from the"
                + " placement's declared source, never a literal):\n  "
                + String.join("\n  ", hits.stream().map(Hit::toString).toList()));
    }

    @Test
    void theScannerReportsThePlantedFixtureByFileAndLine() {
        Path fixture = Path.of("src/test/resources/archfixture/monitoring/FixedPort.java.txt").toAbsolutePath();
        assertTrue(Files.isRegularFile(fixture), "planted fixture missing: " + fixture);
        List<Hit> hits = scan(List.of(fixture));
        assertEquals(1, hits.size(), "the fixture holds exactly one literal: " + hits);
        assertEquals(fixture, hits.get(0).file());
        assertEquals(4, hits.get(0).line(), "the report names the line");
    }
}
