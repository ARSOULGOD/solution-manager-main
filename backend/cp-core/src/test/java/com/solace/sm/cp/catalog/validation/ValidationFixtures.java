package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates {@code contracts/} (read-only from this module) and names the fixture sets the parity tests share.
 * Also carries the one rule the Python states in prose: which gate a negative's file name claims.
 */
final class ValidationFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** The two positive fixtures whose provenance is {@code dev-mode}; {@code run-checks.sh} passes --dev-mode for them. */
    static final List<String> DEV_MODE_FIXTURES = List.of("engine-edge.package.json", "filemesh-ftp-solace.package.json");

    /** gates 6, 7, 8 and 11 live in the schema, so the Python reports them as gate1 (its only schema label). */
    private static final List<Integer> SCHEMA_ONLY = List.of(6, 7, 8, 11);
    private static final List<String> LEGACY_SCHEMA_FIXTURES = List.of(
            "connection-without-type.json", "valueFrom-secret-mismatch.json", "valueFrom-unknown-ref.json");
    private static final Pattern GATE_NAME = Pattern.compile("^gate(\\d{2})([a-z]?)-");

    private ValidationFixtures() {
    }

    static Path contracts() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("contracts/validation/negative"))) {
                return dir.resolve("contracts");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate contracts/validation by walking up from " + Path.of("").toAbsolutePath());
    }

    static Path validation() {
        return contracts().resolve("validation");
    }

    static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PackageValidator validator() {
        return new PackageValidator(read(contracts().resolve("solution-package.schema.json")));
    }

    static List<Path> positives() {
        return list(validation().resolve("fixtures"), ".package.json");
    }

    static List<Path> negatives() {
        return list(validation().resolve("negative"), ".json");
    }

    static List<Path> overlaps() {
        return list(validation().resolve("fixtures/overlap"), ".json");
    }

    private static List<Path> list(Path dir, String suffix) {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code gate05b-when-value-not-an-option.json} -> {@code "5b"}; {@code gate07-...} -> {@code "1"}. */
    static String expectedGate(String fileName) {
        if (LEGACY_SCHEMA_FIXTURES.contains(fileName)) {
            return "1";
        }
        Matcher m = GATE_NAME.matcher(fileName);
        if (!m.find()) {
            throw new IllegalArgumentException("negative fixture does not follow gateNN[x]-: " + fileName);
        }
        int number = Integer.parseInt(m.group(1));
        if (SCHEMA_ONLY.contains(number)) {
            return "1";
        }
        return number + m.group(2);
    }
}
