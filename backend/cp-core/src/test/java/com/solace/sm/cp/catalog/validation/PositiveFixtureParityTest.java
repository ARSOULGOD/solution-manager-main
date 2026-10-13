package com.solace.sm.cp.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every positive fixture passes every gate. {@code run-checks.sh} step 1 runs three files, two of them with
 * {@code --dev-mode}; {@code engine-edge} is the one {@code run-checks.sh} never ran before plan 010 added it
 * (MINOR 4), so this is the first gate run over it from Java as well.
 */
class PositiveFixtureParityTest {

    private static final PackageValidator VALIDATOR = ValidationFixtures.validator();

    static Stream<Path> positives() {
        return ValidationFixtures.positives().stream();
    }

    @Test
    void thereAreExactlyThreePositiveFixturesAndTwoAreDevMode() {
        assertThat(ValidationFixtures.positives()).extracting(p -> p.getFileName().toString())
                .containsExactly("cache-2.4.package.json", "engine-edge.package.json", "filemesh-ftp-solace.package.json");
        // The dev-mode list is a measured fact about the fixtures, not a convention: derive it and compare.
        List<String> devModeByProvenance = ValidationFixtures.positives().stream()
                .filter(p -> "dev-mode".equals(ValidationFixtures.read(p).path("provenance").path("author").asText()))
                .map(p -> p.getFileName().toString()).toList();
        assertThat(devModeByProvenance).containsExactlyElementsOf(ValidationFixtures.DEV_MODE_FIXTURES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("positives")
    void everyPositiveFixturePassesEveryGate(Path pkg) {
        boolean devMode = ValidationFixtures.DEV_MODE_FIXTURES.contains(pkg.getFileName().toString());
        ValidationResult result = VALIDATOR.validate(ValidationFixtures.read(pkg), devMode);
        assertThat(result.failures()).as(pkg.getFileName().toString()).isEmpty();
    }

    /** The production policy: gate 2 runs with dev mode OFF, exactly the Python's default. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("devModePositives")
    void aDevModePackageIsRefusedByGate2WithDevModeOff(Path pkg) {
        JsonNode node = ValidationFixtures.read(pkg);
        ValidationResult result = VALIDATOR.validate(node, false);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.firstGate()).isEqualTo("2");
        // ...and the same document passes once dev mode is on, so gate 2 was the only refusal.
        assertThat(VALIDATOR.validate(node, true).ok()).isTrue();
    }

    static Stream<Path> devModePositives() {
        return ValidationFixtures.positives().stream()
                .filter(p -> ValidationFixtures.DEV_MODE_FIXTURES.contains(p.getFileName().toString()));
    }

    @Test
    void theSignedFixturePassesWithDevModeOff() {
        Path cache = ValidationFixtures.positives().stream()
                .filter(p -> p.getFileName().toString().equals("cache-2.4.package.json")).findFirst().orElseThrow();
        assertThat(VALIDATOR.validate(ValidationFixtures.read(cache), false).ok()).isTrue();
    }

    @Test
    void malformedJsonIsAGate1RefusalThatDoesNotEchoTheDocument() {
        ValidationResult result = VALIDATOR.validateJson("{\"secret\": \"hunter2-SECRET\" oops", false);
        assertThat(result.firstGate()).isEqualTo("1");
        assertThat(result.failures().toString()).doesNotContain("hunter2");
    }
}
