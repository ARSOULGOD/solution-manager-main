package com.solace.sm.cp.catalog.validation;

import static com.solace.sm.cp.catalog.validation.NegativeFixtureParityTest.field;
import static com.solace.sm.cp.catalog.validation.NegativeFixtureParityTest.mutate;
import static com.solace.sm.cp.catalog.validation.NegativeFixtureParityTest.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Beyond the plan's fixture list: the shipped negatives exercise only some branches of the semantic gates
 * (gate 5b's int and bool branches, gate 5's contradiction branch, gate 4's action-input branch, three of gate
 * 14's four messages and gate 2's empty-signature branch have no negative). Each mutant below takes
 * {@code cache-2.4.package.json}, breaks it one way, and requires the Java port to report the Python's own
 * ORDERED gate sequence — the Python is the oracle, run per file, never a hand-written table. Gate-1 mutants
 * compare only that the first label is gate1 (the two libraries report different NUMBERS of schema violations).
 */
class MutantParityTest {

    private static final PackageValidator VALIDATOR = ValidationFixtures.validator();

    private static ObjectNode component(ObjectNode p) {
        return (ObjectNode) p.get("components").get(0);
    }

    private static ArrayNode ints(int... v) {
        ArrayNode a = ValidationFixtures.MAPPER.createArrayNode();
        for (int i : v) {
            a.add(i);
        }
        return a;
    }

    private static Map<String, Consumer<ObjectNode>> mutants() {
        Map<String, Consumer<ObjectNode>> m = new LinkedHashMap<>();
        m.put("gate5b-int-branch-string-value", p -> field(p, "solace.host").set("when", obj("max-size", "ten")));
        m.put("gate5b-int-branch-bool-value-is-an-int-in-python", p -> field(p, "solace.host").set("when", obj("max-size", true)));
        m.put("gate5b-int-branch-float-value", p -> {
            ObjectNode w = ValidationFixtures.MAPPER.createObjectNode();
            w.put("max-size", 10.5);
            field(p, "solace.host").set("when", w);
        });
        m.put("gate5b-bool-branch", p -> field(p, "solace.host").set("when", obj("solace.enabled", "yes")));
        m.put("gate5b-bool-branch-int-value", p -> field(p, "solace.host").set("when", obj("solace.enabled", 1)));
        m.put("gate5b-options-branch", p -> field(p, "solace.host").set("when", obj("provider", "nope")));
        m.put("gate5b-options-branch-case-differs", p -> field(p, "solace.host").set("when", obj("provider", "CAFFEINE")));
        m.put("gate5-contradicts-ancestor", p -> {
            ObjectNode w = obj("solace.management.enabled", true);
            w.put("solace.enabled", false);
            field(p, "solace.management.management-queue").set("when", w);
        });
        m.put("gate5-one-equals-true-in-python", p -> {
            ObjectNode w = obj("solace.management.enabled", true);
            w.put("solace.enabled", 1);
            field(p, "solace.management.management-queue").set("when", w);
        });
        m.put("gate5b-numeric-options-float-equals-int", p -> {
            ArrayNode opts = ints(1, 2);
            field(p, "mode").set("options", opts);
            ObjectNode w = ValidationFixtures.MAPPER.createObjectNode();
            w.put("mode", 1.0);
            field(p, "solace.host").set("when", w);
        });
        m.put("gate5b-numeric-options-true-equals-one-in-python", p -> {
            field(p, "mode").set("options", ints(1, 2));
            field(p, "solace.host").set("when", obj("mode", true));
        });
        m.put("gate5-omits-one-of-two-ancestors", p -> field(p, "solace.management.management-queue").set("when", obj("solace.management.enabled", true)));
        // Fable review (code-GW-2): both patterns end with (?![\s\S]) so the Python gate refuses the trailing newline the Java gate does
        m.put("gate1-loggers-path-trailing-newline", p -> ((ObjectNode) component(p).get("logs").get("loggers")).put("path", "/actuator/loggers\n"));
        m.put("gate1-loggers-levelproperty-trailing-newline", p -> ((ObjectNode) component(p).get("logs").get("loggers")).put("levelProperty", "logging.level\n"));
        m.put("gate4-action-input-not-a-field", p -> ((ArrayNode) component(p).get("actions").get(0).get("inputs")).add("no-such-field"));
        m.put("gate4-metric-when-unknown-field", p -> ((ObjectNode) component(p).get("metrics").get(0)).set("when", obj("no-such-field", "x")));
        m.put("gate4-action-when-unknown-field", p -> ((ObjectNode) component(p).get("actions").get(0)).set("when", obj("no-such-field", "x")));
        m.put("gate9-duplicate-action-key", p -> ((ArrayNode) component(p).get("actions")).add(component(p).get("actions").get(0).deepCopy()));
        m.put("gate10-yaml-subtree-collision", p -> {
            ObjectNode y = ValidationFixtures.MAPPER.createObjectNode();
            y.put("key", "extra-yaml");
            y.put("type", "yaml");
            y.put("yamlSubtree", "solace.management");
            ((ArrayNode) component(p).get("fields")).add(y);
        });
        m.put("gate10-yaml-subtree-no-collision", p -> {
            ObjectNode y = ValidationFixtures.MAPPER.createObjectNode();
            y.put("key", "extra-yaml");
            y.put("type", "yaml");
            y.put("yamlSubtree", "elsewhere");
            ((ArrayNode) component(p).get("fields")).add(y);
        });
        m.put("gate12-readonly-discriminator-no-default", p -> {
            ObjectNode f = field(p, "solace.enabled");
            f.remove("default");
            f.set("ui", obj("readOnly", true));
        });
        m.put("gate12-null-default-counts-as-none", p -> {
            ObjectNode f = field(p, "solace.enabled");
            f.putNull("default");
            f.set("ui", obj("readOnly", true));
        });
        m.put("gate13-secret-false-on-secret-type", p -> {
            ObjectNode f = field(p, "solace.password");
            f.put("type", "secret");
            f.put("secret", false);
        });
        m.put("gate14-component-version-undeclared", p -> component(p).set("version", ints(3)));
        m.put("gate14-field-version-undeclared", p -> field(p, "mode").set("version", ints(3)));
        m.put("gate14-field-version-outside-component", p -> {
            p.set("packageVersion", ints(1, 2));
            component(p).set("version", ints(1));
            field(p, "mode").set("version", ints(2));
        });
        m.put("gate14-when-field-unavailable-in-owner-versions", p -> {
            p.set("packageVersion", ints(1, 2));
            field(p, "solace.enabled").set("version", ints(1));
        });
        m.put("gate14-action-input-unavailable", p -> {
            p.set("packageVersion", ints(1, 2));
            field(p, "solace.host").set("version", ints(1));
        });
        m.put("multi-error-order", p -> {
            component(p).set("version", ints(3));
            ((ArrayNode) component(p).get("actions")).add(component(p).get("actions").get(0).deepCopy());
            ObjectNode f = field(p, "solace.password");
            f.put("type", "secret");
            f.put("secret", false);
            field(p, "solace.host").set("when", obj("solace.enabled", "yes"));
        });
        m.put("gate2-solace-author-empty-signature", p -> ((ObjectNode) p.get("provenance")).put("signature", ""));
        m.put("gate2-dev-mode-author-with-dev-mode-off", p -> ((ObjectNode) p.get("provenance")).put("author", "dev-mode"));
        // uniqueItems compares by Python == (1 == 1.0); networknt compares JsonNode.equals, so IntNode(1) and
        // DoubleNode(1.0) look distinct to it. S3 review: UniqueItemsGate closes the escape.
        m.put("gate1-packageVersion-int-and-float-duplicate", p -> {
            ArrayNode a = ints(1);
            a.add(1.0);
            p.set("packageVersion", a);
        });
        m.put("gate1-component-version-int-and-float-duplicate", p -> {
            ArrayNode a = ints(1);
            a.add(1.0);
            component(p).set("version", a);
        });
        m.put("gate1-field-version-int-and-float-duplicate", p -> {
            ArrayNode a = ints(1);
            a.add(1.0);
            field(p, "mode").set("version", a);
        });
        m.put("gate1-wrong-type", p -> p.put("solution", 7));
        m.put("gate1-before-semantic-gates", p -> {
            p.put("solution", 7);
            field(p, "solace.host").set("when", obj("no-such-field", "x"));
        });
        return m;
    }

    @Test
    void everyMutantIsRefusedWithThePythonsOwnGateSequence(@TempDir Path tmp) throws IOException {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        JsonNode base = ValidationFixtures.read(ValidationFixtures.positives().get(0));
        int compared = 0;
        for (Map.Entry<String, Consumer<ObjectNode>> e : mutants().entrySet()) {
            JsonNode mutant = mutate(base, e.getValue());
            Path file = tmp.resolve(e.getKey() + ".package.json");
            Files.writeString(file, ValidationFixtures.MAPPER.writeValueAsString(mutant));

            PythonGate.Verdict python = PythonGate.run(file, false);
            ValidationResult java = VALIDATOR.validate(mutant, false);
            assertThat(java.ok()).as(e.getKey() + " verdict").isEqualTo(python.passed());
            if (python.gateTokens().isEmpty()) {
                continue;
            }
            List<String> javaTokens = java.failures().stream().map(f -> "gate" + f.gate()).toList();
            if ("gate1".equals(python.first())) {
                assertThat(javaTokens).as(e.getKey() + " is gate1 only").containsOnly("gate1");
            } else {
                assertThat(javaTokens.subList(0, Math.min(12, javaTokens.size()))).as(e.getKey() + " gate sequence").isEqualTo(python.gateTokens());
            }
            compared++;
        }
        // 30 mutants; the no-collision / null-default... cases that the Python accepts add no tokens, so the
        // floor guards against a mutant silently becoming a no-op rather than pinning the exact accepted count.
        assertThat(compared).as("mutants that the Python refused, all compared").isGreaterThanOrEqualTo(27);
    }

    /**
     * W3-05 (OR-106): {@code valuePointer} is OPTIONAL for a count extraction ("valuePointer optional for it"), so a package
     * that declares one anyway is valid in BOTH validators; it is ignored, never read. The schema used to refuse it.
     */
    @Test
    void aCountWithAValuePointerIsAcceptedByBothValidatorsAndTheValuePointerIsOptionalForIt(@TempDir Path tmp)
            throws IOException {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        JsonNode base = ValidationFixtures.read(ValidationFixtures.positives().get(0));
        for (String pointer : new String[] {"/header/size", null}) {
            JsonNode mutant = mutate(base, p -> {
                ObjectNode metric = (ObjectNode) component(p).get("metrics").get(0);
                metric.put("metricType", "counter");
                ObjectNode source = ValidationFixtures.MAPPER.createObjectNode();
                source.put("kind", "solace_event");
                source.put("topicPattern", "a/b/*/heartbeat");
                ObjectNode key = source.putObject("instanceKey");
                key.put("configField", "namespace");
                key.put("pointer", "/header/adapterID");
                source.put("extract", "count");
                if (pointer != null) {
                    source.put("valuePointer", pointer);
                }
                source.put("onUnavailable", "degrade");
                metric.set("source", source);
            });
            Path file = tmp.resolve("count-" + (pointer == null ? "bare" : "pointer") + ".package.json");
            Files.writeString(file, ValidationFixtures.MAPPER.writeValueAsString(mutant));
            ValidationResult java = VALIDATOR.validate(mutant, false);
            assertThat(java.failures()).as("java, pointer=" + pointer).isEmpty();
            assertThat(PythonGate.run(file, false).passed()).as("python, pointer=" + pointer).isTrue();
        }
    }

    @Test
    void theMutantsAreNotAllRefusedForTheSameReason() {
        // a guard on the guard: the mutants must reach at least eight distinct gates, or the sequence test above
        // would pass while the port only ever fired gate 1.
        JsonNode base = ValidationFixtures.read(ValidationFixtures.positives().get(0));
        java.util.Set<String> gates = new java.util.TreeSet<>();
        for (Consumer<ObjectNode> change : mutants().values()) {
            ValidationResult r = VALIDATOR.validate(mutate(base, change), false);
            r.failures().forEach(f -> gates.add(f.gate()));
        }
        assertThat(gates).contains("1", "2", "4", "5", "5b", "9", "10", "12", "13", "14");
    }

    /**
     * A KNOWN, PINNED divergence (S3 review), in the safe direction. Python's {@code $} matches before a trailing
     * newline, so {@code "cache\n"} satisfies the schema's {@code solution} pattern there; ECMA-262 (and networknt)
     * anchor strictly and refuse it. Java is the stricter side and stays so. Pinned so a change to either side is
     * noticed; the Python-side quirk is routed to the contracts/validation owner with the two preserved quirks
     * (bool as int, {@code True == 1}) as ONE open item in plan 010's S3 Results.
     */
    @Test
    void aTrailingNewlineInSolutionIsRefusedHereAndAcceptedByThePythonByDesign(@TempDir Path tmp) throws IOException {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        JsonNode mutant = mutate(ValidationFixtures.read(ValidationFixtures.positives().get(0)),
                p -> p.put("solution", "cache\n"));
        Path file = tmp.resolve("newline.package.json");
        Files.writeString(file, ValidationFixtures.MAPPER.writeValueAsString(mutant));

        assertThat(PythonGate.run(file, false).passed()).as("the Python's $ matches before a trailing newline").isTrue();
        ValidationResult java = VALIDATOR.validate(mutant, false);
        assertThat(java.ok()).as("this port anchors strictly").isFalse();
        assertThat(java.failures()).extracting(GateFailure::gate).containsOnly("1");
    }
}
