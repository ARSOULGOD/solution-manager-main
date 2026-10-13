package com.solace.sm.cp.catalog.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every negative fixture is rejected by the gate its own file name names — a validator that rejects all of them
 * for one schema reason would pass a count test and prove nothing. Gates 6, 7, 8 and 11 are schema-level, so the
 * Python (and this port) report them as {@code gate1}.
 */
class NegativeFixtureParityTest {

    private static final PackageValidator VALIDATOR = ValidationFixtures.validator();

    static Stream<Path> negatives() {
        return ValidationFixtures.negatives().stream();
    }

    @Test
    void thereAreEightyOneNegativesIncludingTheIntegrationBranchSchemaFixtures() {
        // 59 before P11-5.1: 23 before P11-1.1 (seventeen measured, T0.1 as two, OR-103 as four) + the 35 P11-1.1 added
        // (18 queryParams grammar cases, 2 queryParams-on-the-wrong-kind, 2 intervalSeconds bounds, 1 port bound, 2 pointer,
        // 1 path+topicPattern, 2 restartSignal, 1 defaultChart, 4 metric names and 2 metric units) + the 1 W2-05 added
        // (a restartSignal without a port: it could never be fetched). Plus the 17 P11-5.1 left for the solace_event
        // branch (W3-05: 18 less the "count with a valuePointer" case, which OR-106 makes VALID - valuePointer is optional for
        // a count - and MutantParityTest now holds as a positive): 7 topicPattern (last level >, last level *, substitution in the last and in a middle level, #, +, an
        // empty level), 1 no instanceKey, 3 instanceKey shapes, 6 for the extraction (value without a pointer, count on a gauge, an unknown kind, path, a filter without equals, a filter pointer with ..). Gates 15 and 16
        // have NO shared negative: both are HELD (see generate.py HELD_CASES - the frontend's validate.test.ts would go
        // red); CrossComponentMetricGateTest and InstanceKeyGateTest prove them. Plus the 2 trailing-newline cases the combined
        // Fable review added (code-GW-2: logs.loggers.path and levelProperty, the Python gate's $ accepted both).
        assertThat(ValidationFixtures.negatives()).hasSize(81);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negatives")
    void everyNegativeIsRejectedByTheGateItsFileNameNames(Path negative) {
        String name = negative.getFileName().toString();
        ValidationResult result = VALIDATOR.validate(ValidationFixtures.read(negative), false);
        assertThat(result.ok()).as(name + " must be rejected").isFalse();
        assertThat(result.firstGate()).as(name + " first failing gate: " + result.failures()).isEqualTo(ValidationFixtures.expectedGate(name));
    }

    /** Same oracle as the Python's, applied to Java: dev mode is off for every negative, as in {@code run-checks.sh}. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("negatives")
    void theValidatorAndThePythonGateAgreeOnEveryNegative(Path negative) {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        String python = PythonGate.expectFailFirstToken(negative);
        ValidationResult java = VALIDATOR.validate(ValidationFixtures.read(negative), false);
        assertThat("gate" + java.firstGate()).as(negative.getFileName().toString()).isEqualTo(python);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.solace.sm.cp.catalog.validation.PositiveFixtureParityTest#positives")
    void theValidatorAndThePythonGateAgreeOnEveryPositive(Path positive) {
        String reason = PythonGate.unavailableReason();
        assumeTrue(reason == null, "python parity oracle unavailable: " + reason);
        boolean devMode = ValidationFixtures.DEV_MODE_FIXTURES.contains(positive.getFileName().toString());
        PythonGate.Verdict python = PythonGate.run(positive, devMode);
        ValidationResult java = VALIDATOR.validate(ValidationFixtures.read(positive), devMode);
        assertThat(python.passed()).isTrue();
        assertThat(java.ok()).isTrue();
    }

    // ---- I6: a gate message names structure, never a value ----------------------------------------------------

    private static final String SECRET = "hunter2-SECRET-VALUE";

    /**
     * Each case plants {@link #SECRET} in a value position that a gate inspects, in a package that fails exactly
     * through that value. The Python echoes these values; the port must not.
     */
    @Test
    void noGateFailureMessageEchoesAFieldValue() {
        JsonNode base = ValidationFixtures.read(ValidationFixtures.positives().get(0));
        int cases = 0;
        for (JsonNode broken : new JsonNode[] {
            // gate 13: a secret-typed field whose default is secret-shaped and which declares secret:false
            mutate(base, p -> {
                ObjectNode f = field(p, "solace.password");
                f.put("type", "secret");
                f.put("secret", false);
                f.put("default", SECRET);
            }),
            // gate 5b: a predicate value that is not one of the target's options
            mutate(base, p -> field(p, "solace.host").set("when", obj("provider", SECRET))),
            // gate 5: a predicate value contradicting the target's own predicate
            mutate(base, p -> {
                ObjectNode when = obj("solace.management.enabled", true);
                when.put("solace.enabled", SECRET);
                field(p, "solace.management.management-queue").set("when", when);
            }),
            // gate 5b (bool branch): a non-bool value against a bool target
            mutate(base, p -> field(p, "solace.host").set("when", obj("solace.enabled", SECRET))),
            // gate 1: a structurally wrong default carrying the secret (an object where a scalar is required)
            mutate(base, p -> field(p, "solace.password").set("default", obj("password", SECRET))),
            // gate 1: a wrong-typed secret-shaped value where a boolean is required
            mutate(base, p -> field(p, "solace.host").put("required", SECRET)),
            // gate 1: an undeclared property (additionalProperties:false) whose NAME and value are secret-shaped
            mutate(base, p -> field(p, "solace.host").put(SECRET, SECRET)),
            // gate 1: a closed-enum refusal of a secret-shaped scalar (provenance.author)
            mutate(base, p -> ((ObjectNode) p.get("provenance")).put("author", SECRET)),
        }) {
            cases++;
            ValidationResult result = VALIDATOR.validate(broken, false);
            assertThat(result.ok()).as("case " + cases + " must be refused").isFalse();
            assertThat(result.failures().toString()).as("case " + cases).doesNotContain(SECRET).doesNotContain("hunter2");
        }
        assertThat(cases).isEqualTo(8);
    }

    // ---- tiny tree helpers -------------------------------------------------------------------------------------

    static JsonNode mutate(JsonNode base, java.util.function.Consumer<ObjectNode> change) {
        ObjectNode copy = base.deepCopy();
        change.accept(copy);
        return copy;
    }

    static ObjectNode obj(String key, Object value) {
        ObjectNode o = ValidationFixtures.MAPPER.createObjectNode();
        if (value instanceof Boolean b) {
            o.put(key, b);
        } else if (value instanceof Integer i) {
            o.put(key, i);
        } else {
            o.put(key, String.valueOf(value));
        }
        return o;
    }

    static ObjectNode field(ObjectNode pkg, String key) {
        for (JsonNode c : pkg.get("components")) {
            for (JsonNode f : (ArrayNode) c.get("fields")) {
                if (key.equals(f.get("key").asText())) {
                    return (ObjectNode) f;
                }
            }
        }
        throw new IllegalArgumentException("no field " + key);
    }
}
