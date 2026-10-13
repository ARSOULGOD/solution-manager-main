package com.solace.sm.cp.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate 15 (P11-1.1, plan 011 M-Q5): a metric name is unique across ALL of a package's components, because the key a
 * metric is stored under has no component in it. Gate 9 already covers a repeat WITHIN one component.
 *
 * <p>This is a unit test, not a file in {@code contracts/validation/negative/}, on purpose: the frontend's
 * {@code validate.test.ts} iterates every file in that directory and its {@code validate.ts} has no gate 15 (and this
 * plan may not edit {@code frontend/}), so the shared negative is held back (OQ-24; see {@code generate.py}
 * {@code HELD_CASES}). The Python gate is the oracle here exactly as it is in {@link NegativeFixtureParityTest}.
 */
class CrossComponentMetricGateTest {

    private static final PackageValidator VALIDATOR = ValidationFixtures.validator();

    @TempDir
    Path tmp;

    private static ObjectNode base() {
        return (ObjectNode) ValidationFixtures.read(ValidationFixtures.validation().resolve("fixtures/cache-2.4.package.json"));
    }

    /** A second component with no fields and no predicate, so no other gate can be the one that fires. */
    private static ObjectNode sidecar(String type, String... metricNames) {
        ObjectNode c = ValidationFixtures.MAPPER.createObjectNode();
        c.put("type", type);
        c.put("cardinality", "zero-or-more");
        c.put("role", "none");
        c.put("configPath", "sidecar");
        ArrayNode metrics = c.putArray("metrics");
        for (String name : metricNames) {
            ObjectNode m = metrics.addObject();
            m.put("name", name);
            m.put("metricType", "gauge");
            m.put("unit", "ratio");
            ObjectNode source = m.putObject("source");
            source.put("kind", "http_json");
            source.put("path", "/x");
            source.put("onUnavailable", "degrade");
        }
        return c;
    }

    private static ObjectNode withSidecar(ObjectNode sidecar) {
        ObjectNode pkg = base();
        ((ArrayNode) pkg.get("components")).add(sidecar);
        return pkg;
    }

    private String python(JsonNode pkg) throws IOException {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        Path file = tmp.resolve("planted-" + System.nanoTime() + ".json");
        Files.writeString(file, ValidationFixtures.MAPPER.writeValueAsString(pkg));
        return PythonGate.expectFailFirstToken(file);
    }

    @Test
    void aMetricNameDeclaredByTwoComponentsIsGate15InBothValidators() throws IOException {
        ObjectNode pkg = withSidecar(sidecar("cache-sidecar", "cache.hit_rate"));

        ValidationResult java = VALIDATOR.validate(pkg, false);

        assertThat(java.ok()).isFalse();
        assertThat(java.firstGate()).as(java.failures().toString()).isEqualTo("15");
        assertThat(python(pkg)).isEqualTo("gate15");
    }

    @Test
    void theSameTypeDeclaredTwiceStillCollides() throws IOException {
        // keyed by component POSITION, not type: two components that share a type and a metric name collide too
        ObjectNode pkg = withSidecar(sidecar("cache-engine", "cache.hit_rate"));

        assertThat(VALIDATOR.validate(pkg, false).firstGate()).isEqualTo("15");
        assertThat(python(pkg)).isEqualTo("gate15");
    }

    @Test
    void aRepeatWithinOneComponentIsGate9NotGate15() throws IOException {
        ObjectNode pkg = withSidecar(sidecar("cache-sidecar", "sidecar.up", "sidecar.up"));

        ValidationResult java = VALIDATOR.validate(pkg, false);

        assertThat(java.firstGate()).isEqualTo("9");
        assertThat(java.failures()).noneMatch(f -> f.gate().equals("15"));
        assertThat(python(pkg)).isEqualTo("gate9");
    }

    @Test
    void distinctNamesAcrossComponentsPassAndTheMessageNamesStructureOnly() {
        assertThat(VALIDATOR.validate(withSidecar(sidecar("cache-sidecar", "sidecar.up")), false).ok()).isTrue();

        ValidationResult repeated = VALIDATOR.validate(withSidecar(sidecar("cache-sidecar", "cache.hit_rate")), false);
        assertThat(repeated.failures().toString()).contains("cache-sidecar").contains("cache.hit_rate").contains("cache-engine");
    }
}
