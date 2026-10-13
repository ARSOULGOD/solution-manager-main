package com.solace.sm.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Java half of the two-implementation event contract (`UI-D15` pattern, backend design §4):
 * one fixture suite in {@code contracts/events/}, a Python validator and this Java model, and any
 * divergence fails a build.
 *
 * <p>The fixtures are byte-frozen contract inputs. If a fixture will not round-trip, the model is
 * wrong — never the fixture.
 */
class EnvelopeGoldenTest {

    /** THE serialization configuration — test and production are one mapper (plan 001 invariant). */
    static final ObjectMapper M = EventJson.mapper();

    /** {@code expected-topics.json} lives beside the fixtures but is a manifest, not an event. */
    static final String TOPIC_MANIFEST = "expected-topics.json";

    static Path fixtureDir() {
        return ContractArtifacts.dir("events/fixtures");
    }

    static Stream<Path> fixtureFiles() {
        List<Path> files = ContractArtifacts.jsonFiles(fixtureDir(), TOPIC_MANIFEST);
        assertFalse(files.isEmpty(), "no event fixtures found under " + fixtureDir()
                + " — a golden suite that finds nothing must fail, never pass vacuously");
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void roundTripsEveryContractFixtureByteEquivalently(Path fixture) throws Exception {
        JsonNode expected = M.readTree(fixture.toFile());
        EventEnvelope env = M.treeToValue(expected, EventEnvelope.class);

        // Re-parse the produced text rather than comparing against `valueToTree`: both sides then
        // come off the same JSON parser, so numeric node types are decided by the wire form (what
        // the contract actually constrains) and not by the Java field's width. `valueToTree` emits
        // a LongNode for the `long stateVersion` while the fixture text parses to an IntNode, and
        // IntNode.equals(LongNode) is false — a false red about Java types, not about the contract.
        JsonNode actual = M.readTree(EventJson.write(env));

        assertEquals(expected, actual, "round-trip diverged for " + fixture.getFileName());
    }

    @Test
    void everyFixtureIsCoveredAndTheManifestIsNotTreatedAsAnEvent() {
        List<Path> files = ContractArtifacts.jsonFiles(fixtureDir(), TOPIC_MANIFEST);
        assertEquals(7, files.size(), "expected the seven v1 notify fixtures, found " + files);
        assertTrue(files.stream().noneMatch(p -> p.getFileName().toString().equals(TOPIC_MANIFEST)),
                "the topic manifest must be excluded from the round-trip set");
    }

    // ---- constructor validation: the schema's constraints, enforced in Java -------------------

    @Test
    void rejectsBlankOrganization() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> envelopeWith("   ", 41L));
        assertTrue(e.getMessage().contains("organization"), e.getMessage());
    }

    @Test
    void rejectsNullOrganization() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> envelopeWith(null, 41L));
        assertTrue(e.getMessage().contains("organization"), e.getMessage());
    }

    @Test
    void rejectsNegativeStateVersion() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> envelopeWith("acme", -1L));
        assertTrue(e.getMessage().contains("stateVersion"), e.getMessage());
    }

    /**
     * {@code envelope.schema.json} declares {@code "producer": { "enum": ["cp-core", "dp-core"] }},
     * and until R8 nothing in Java enforced it: {@code producer: "go-agent"} constructed, serialized
     * and published, while the Python validator rejected the identical document. The measured
     * divergence was on the one field that names who is permitted to emit SM events at all.
     */
    @Test
    void rejectsAProducerOutsideTheSchemasEnum() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EventEnvelope(
                        UUID.fromString("3f1c9a52-0000-4000-8000-000000000002"),
                        Instant.parse("2026-08-27T10:00:00Z"),
                        "go-agent",
                        "acme",
                        new EntityRef("placement", "cache-prod-01", "pl-eu-1", null),
                        41L,
                        "instance.health",
                        Map.of("state", "RED")));
        assertTrue(e.getMessage().contains("producer"), e.getMessage());
        assertTrue(e.getMessage().contains("go-agent"),
                "the message must name the value it refused: " + e.getMessage());
    }

    /** The same rule on the deserialization path — the one that sees untrusted bytes. */
    @Test
    void jacksonCannotConstructAnEnvelopeWithAProducerOutsideTheEnum() throws IOException {
        ObjectNode foreign = (ObjectNode) M.readTree(
                fixtureDir().resolve("health-transition.json").toFile());
        foreign.put("producer", "go-agent");

        ValueInstantiationException e = assertThrows(ValueInstantiationException.class,
                () -> EventJson.reader().readValue(M.writeValueAsString(foreign)));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("producer"), e.getCause().getMessage());
    }

    @Test
    void rejectsEntityWithoutKind() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntityRef(null, "cache-prod-01", null, null));
        assertTrue(e.getMessage().contains("kind"), e.getMessage());
    }

    /**
     * The {@code negative/} documents the Java model can speak to. {@code missing-state-version}
     * has no Java analogue — {@code stateVersion} is a primitive {@code long}, so "absent" is not
     * representable in a constructed envelope; Jackson defaults it to 0, which the schema accepts
     * (minimum 0). Absence is therefore a wire-level defect only, caught by the Python validator's
     * {@code required} check, and deliberately not re-asserted here.
     */
    @Test
    void rejectsTheNegativeCasesThatAreExpressibleInJava() throws IOException {
        Path negatives = ContractArtifacts.dir("events/negative");
        JsonNode healthWithoutPlacement =
                M.readTree(negatives.resolve("health-without-placement.json").toFile());
        // Deserializes fine (envelope-shaped) — its defect is the notify branch's placementId
        // requirement, which is schema-level, not envelope-level. Assert the model does not
        // silently invent the missing id.
        EventEnvelope env = M.treeToValue(healthWithoutPlacement, EventEnvelope.class);
        assertNull(env.entity().placementId());

        // missing-state-version: primitive long, so Jackson supplies 0 rather than failing.
        JsonNode missing = M.readTree(negatives.resolve("missing-state-version.json").toFile());
        assertEquals(0L, M.treeToValue(missing, EventEnvelope.class).stateVersion());
    }

    // ---- validation must survive the Jackson path, not just direct construction ---------------

    /**
     * The four tests above call the constructor directly. Records route Jackson through the
     * canonical constructor <i>today</i>, but nothing pins that: a later {@code @JsonCreator}, a
     * mixin, or a field-based creator would bypass the compact constructor with every one of those
     * tests still green — and the deserialization path is the one that sees untrusted bytes. This
     * test pins the property that actually matters: an invalid document does not become an
     * envelope.
     */
    @Test
    void jacksonCannotConstructAnInvalidEnvelope() throws IOException {
        ObjectNode blankOrganization = (ObjectNode) M.readTree(
                fixtureDir().resolve("health-transition.json").toFile());
        blankOrganization.put("organization", "   ");
        String json = M.writeValueAsString(blankOrganization);

        ValueInstantiationException e = assertThrows(ValueInstantiationException.class,
                () -> EventJson.reader().readValue(json));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("organization"), e.getCause().getMessage());
    }

    @Test
    void jacksonCannotConstructAnEnvelopeWithANegativeStateVersion() throws IOException {
        ObjectNode negative = (ObjectNode) M.readTree(
                fixtureDir().resolve("health-transition.json").toFile());
        negative.put("stateVersion", -1);

        ValueInstantiationException e = assertThrows(ValueInstantiationException.class,
                () -> EventJson.reader().readValue(M.writeValueAsString(negative)));
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("stateVersion"), e.getCause().getMessage());
    }

    // ---- the shared mapper cannot be reconfigured out from under a producer -------------------

    /**
     * {@code mapper()} hands out a copy, so a caller reconfiguring what it returns cannot change the
     * wire form for everyone else. Both mutations below were measured to be effective on a mapper
     * that owns its config (2.21.4): inclusion changes a type not yet in the serializer cache, and
     * {@code WRITE_DATES_AS_TIMESTAMPS} changes one already cached — so this is a real attempt, not
     * a no-op dressed up as a guarantee.
     */
    @Test
    @SuppressWarnings("deprecation") // the mutation under test IS the deprecated setter path
    void reconfiguringTheHandedOutMapperCannotChangeWhatWriteEmits() throws Exception {
        EventEnvelope e = envelopeWith("acme", 41L);
        String before = EventJson.write(e);

        ObjectMapper handedOut = EventJson.mapper();
        handedOut.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        handedOut.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, true);
        handedOut.enable(SerializationFeature.INDENT_OUTPUT);

        assertEquals(before, EventJson.write(e), "write() drifted after a caller mutated mapper()");
        assertEquals(before, EventJson.mapper().writeValueAsString(e).replace("\r\n", "\n"),
                "a freshly handed-out mapper drifted from the shared configuration");
        assertTrue(EventJson.write(e).contains("\"occurredAtUtc\":\"2026-08-27T10:00:00Z\""),
                "dates must stay ISO strings: " + EventJson.write(e));
        assertFalse(EventJson.write(e).contains("\"placementId\":null"),
                "NON_NULL inclusion must survive: " + EventJson.write(e));
    }

    static EventEnvelope envelopeWith(String organization, long stateVersion) {
        return new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-000000000001"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "dp-core",
                organization,
                new EntityRef("placement", "cache-prod-01", "pl-eu-1", null),
                stateVersion,
                "instance.health",
                Map.of("state", "RED"));
    }
}
