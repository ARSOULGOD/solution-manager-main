package com.solace.sm.cp.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate 16 (plan 011 P11-5.1; I6): a {@code solace_event} metric's {@code instanceKey.configField} names a field of the
 * same component that is not a secret. The configured value is what a broker event is matched against, and a secret is
 * never read for that.
 *
 * <p>A unit test and not a file in {@code contracts/validation/negative/}, exactly as {@link CrossComponentMetricGateTest}
 * is for gate 15: the frontend's {@code validate.test.ts} iterates every file in that directory and its
 * {@code validate.ts} has no gate 16 (this plan may not edit {@code frontend/}; OQ-24), so the three shared negatives are
 * HELD ({@code generate.py HELD_CASES}). The Python gate is the oracle here, as it is in {@link NegativeFixtureParityTest}.
 */
class InstanceKeyGateTest {

    private static final PackageValidator VALIDATOR = ValidationFixtures.validator();

    @TempDir
    Path tmp;

    /** The Cache fixture with its first metric turned into a valid count-of-events source keyed by {@code field}. */
    private static ObjectNode withEventMetric(String configField) {
        ObjectNode pkg = (ObjectNode) ValidationFixtures.read(
                ValidationFixtures.validation().resolve("fixtures/cache-2.4.package.json"));
        ObjectNode metric = (ObjectNode) pkg.get("components").get(0).get("metrics").get(0);
        metric.put("metricType", "counter");
        ObjectNode source = metric.putObject("source");
        source.put("kind", "solace_event");
        source.put("topicPattern", "a/b/*/heartbeat");
        source.putObject("instanceKey").put("configField", configField).put("pointer", "/header/adapterID");
        source.put("extract", "count");
        source.put("onUnavailable", "degrade");
        return pkg;
    }

    private static JsonNode field(ObjectNode pkg, String key) {
        for (JsonNode f : pkg.get("components").get(0).get("fields")) {
            if (key.equals(f.get("key").asText())) {
                return f;
            }
        }
        throw new IllegalStateException("the Cache fixture has no field " + key);
    }

    private String python(JsonNode pkg) throws IOException {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        Path file = tmp.resolve("planted-" + System.nanoTime() + ".json");
        Files.writeString(file, ValidationFixtures.MAPPER.writeValueAsString(pkg));
        return PythonGate.expectFailFirstToken(file);
    }

    @Test
    void aPlainStringFieldIsALegalInstanceKey() {
        assertThat(VALIDATOR.validate(withEventMetric("namespace"), false).ok()).isTrue();
    }

    @Test
    void aFieldOfTypeSecretIsGate16InBothValidators() throws IOException {
        ObjectNode pkg = withEventMetric("namespace");
        ((ObjectNode) field(pkg, "namespace")).put("type", "secret");

        ValidationResult java = VALIDATOR.validate(pkg, false);

        assertThat(java.ok()).isFalse();
        assertThat(java.firstGate()).as(java.failures().toString()).isEqualTo("16");
        assertThat(python(pkg)).isEqualTo("gate16");
    }

    @Test
    void aFieldFlaggedSecretIsGate16InBothValidators() throws IOException {
        ObjectNode pkg = withEventMetric("namespace");
        ((ObjectNode) field(pkg, "namespace")).put("secret", true);

        ValidationResult java = VALIDATOR.validate(pkg, false);

        assertThat(java.firstGate()).as(java.failures().toString()).isEqualTo("16");
        assertThat(python(pkg)).isEqualTo("gate16");
    }

    @Test
    void aKeyNamingNoFieldOfTheComponentIsGate16InBothValidators() throws IOException {
        ObjectNode pkg = withEventMetric("no.such.field");

        ValidationResult java = VALIDATOR.validate(pkg, false);

        assertThat(java.firstGate()).as(java.failures().toString()).isEqualTo("16");
        assertThat(python(pkg)).isEqualTo("gate16");
    }

    @Test
    void anHttpJsonMetricNeedsNoInstanceKeyAndTheMessageNamesStructureOnly() {
        // the unplanted Cache fixture carries no solace_event metric, and passes
        assertThat(VALIDATOR.validate(ValidationFixtures.read(
                ValidationFixtures.validation().resolve("fixtures/cache-2.4.package.json")), false).ok()).isTrue();

        ObjectNode pkg = withEventMetric("namespace");
        ((ObjectNode) field(pkg, "namespace")).put("secret", true).put("default", "hunter2-SECRET-VALUE");
        String message = VALIDATOR.validate(pkg, false).failures().toString();
        assertThat(message).contains("cache-engine").contains("cache.hit_rate").contains("namespace");
        assertThat(message).as("a gate message names structure, never a value (I6)").doesNotContain("hunter2");
    }
}
