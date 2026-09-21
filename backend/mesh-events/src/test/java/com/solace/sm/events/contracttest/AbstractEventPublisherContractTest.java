package com.solace.sm.events.contracttest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.events.EntityRef;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyEventShapes;
import com.solace.sm.events.publisher.EventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The publisher contract (design §5.2): EVERY {@link EventPublisher} implementation must have a test
 * class extending this kit. A publisher-specific suite that only exercises its own happy path is
 * how a future implementation bypasses {@code NotifyEventShapes.validate()} while the existing
 * suites stay green. {@code PublisherContractKitSelfTest} proves this kit fails a non-validating
 * publisher.
 *
 * <p><b>Overriding a kit test:</b> the {@code @Test} methods are {@code protected} so a subclass
 * can {@code @Disabled} one deliberately, with a reason. Overriding one <i>without</i> re-annotating
 * it silently deletes that contract test for your implementation — JUnit discovers the override,
 * which has no {@code @Test}. If you must opt out, do it loudly with {@code @Disabled}; never by
 * shadowing.
 *
 * <p>No broker, no mocks of any transport library. The implementation is supplied by
 * {@link #publisher(CapturingTransport)}, wired to a {@link CapturingTransport} that receives the
 * <b>derived</b> topic and the <b>serialized</b> body — so the publisher itself must call
 * {@code NotifyTopics.topicFor} and {@code EventJson.write}. A seam taking the raw
 * {@link EventEnvelope} would be a false green: the test's own stub would do the deriving and pass
 * no matter what the publisher did (plan 001 Task 10, Step 1).
 *
 * <p>Both halves of the emission are checked against {@code contracts/events/} rather than against
 * a hand-written expectation: the topic against the fixtures' {@code expected-topics.json} manifest
 * (the same golden input {@code validate-events.py} and {@code NotifyTopicsTest} use), and the body
 * by <b>tree equality with the fixture document itself</b>. Task 9's review left payload shape
 * unenforced on the Java side — a {@code contains("\"stateVersion\":41")} spot-check would pass on
 * a body that had dropped {@code placementId} or renamed {@code occurredAtUtc}. Whole-document
 * equality is what makes a publisher's output provably contract-conformant for the golden case.
 *
 * <p><b>Enforcement honesty (design §5.2).</b> "Every {@code EventPublisher} implementation extends this
 * kit" became executable when the second implementation landed (plan 003): {@code arch-tests} pins the
 * concrete implementation set ({@code PlaneBoundaryTest.PUBLISHER_IMPLEMENTATIONS}), so an implementation
 * cannot be added without editing the pinned inventory, and editing the inventory is the reviewed moment
 * at which the kit-subclass pairing is checked. {@code arch-tests} still sees other modules' main classes
 * but not their test classes, so the pairing itself remains a review obligation — now with a forced
 * review point instead of a hoped-for one. The self-test proves the kit <i>can</i> catch a bypass; the
 * inventory is what makes a new implementation impossible to add unnoticed.
 */
public abstract class AbstractEventPublisherContractTest {

    /** Fixture root, via the one shared resolver — never a hardcoded `../../`. */
    protected static final Path FIXTURES = ContractArtifacts.dir("events/fixtures");

    /** The contract's negative documents — the shape guard's inputs come from here, not from hand. */
    protected static final Path NEGATIVES = ContractArtifacts.dir("events/negative");

    private static final ObjectMapper JSON = EventJson.mapper();

    /** The implementation under test, wired to the given transport. */
    protected abstract EventPublisher publisher(CapturingTransport transport);

    /** The implementation's swallow counter (not on the interface by design). */
    protected abstract long droppedCount(EventPublisher publisher);

    /** The manifest's topic for one fixture — emitted-byte contract checks compare against it. */
    protected static String expectedTopicFor(String fixtureName) {
        return ContractArtifacts.json(FIXTURES.resolve("expected-topics.json"))
                .get(fixtureName).asText();
    }

    @Test
    protected void serializesViaMeshEventsAndDerivesTopicFromNotifyTopics() throws IOException {
        CapturingTransport transport = new CapturingTransport();
        EventPublisher p = publisher(transport);

        String fixtureText = Files.readString(FIXTURES.resolve("health-transition.json"));
        EventEnvelope health = EventJson.reader().readValue(fixtureText);

        p.publish(health);

        assertNotNull(transport.sent(), "publish() must hand (topic, body) to the transport");
        assertEquals(expectedTopicFor("health-transition.json"), transport.sent().getKey(),
                "topic must be the one contracts/events/fixtures/expected-topics.json declares");

        JsonNode emitted = JSON.readTree(transport.sent().getValue());
        JsonNode fixture = JSON.readTree(fixtureText);
        assertEquals(fixture, emitted,
                "emitted body must equal the contract fixture document, field for field");
        assertEquals(0L, droppedCount(p), "a successful publish must not count as a drop");
    }

    /**
     * {@code BE-D1}: a broker failure degrades freshness, never correctness. The publish site sits
     * inside a caller's transaction path, so a transport failure must be logged and dropped — never
     * rethrown.
     *
     * <p>The drop is also <b>counted</b>. A log-and-drop contract with nothing counting the drops
     * is a silent failure mode: an operator watching a console that has quietly stopped updating
     * has no number to look at. The metrics plan exposes the count through the metrics stack; this
     * assertion is what stops the counter being wired to only some of the drop paths.
     */
    @Test
    protected void transportFailureIsLoggedAndDroppedNeverThrownAtTheCaller() throws IOException {
        CapturingTransport transport = new CapturingTransport();
        transport.failWith(new IllegalStateException("broker is down"));
        EventPublisher p = publisher(transport);

        EventEnvelope health = EventJson.reader()
                .readValue(Files.readString(FIXTURES.resolve("health-transition.json")));

        assertDoesNotThrow(() -> p.publish(health));
        assertEquals(1L, droppedCount(p), "a swallowed transport failure must be counted");

        assertDoesNotThrow(() -> p.publish(health));
        assertEquals(2L, droppedCount(p), "the counter must accumulate, not latch");
    }

    /**
     * A serialization defect is an <b>SM-internal</b> failure — {@code EventJson.write}'s own
     * javadoc calls its {@link IllegalStateException} "a bug in this module's configuration, not a
     * condition a caller can act on". It carries no isolation consequence, so it must not abort the
     * caller's transaction: {@code BE-D1}'s "never correctness" outranks failing loud here. Pinned
     * because the alternative placement (serialize before the try) is one line away and would look
     * equally reasonable to a later reader.
     *
     * <p>Triggered with a payload value Jackson has no serializer for — the closest reachable stand-in
     * for a mapper-configuration defect, and a real way this can fire in production.
     *
     * <p><b>It has to be a {@code catalog.package} event, and that is not incidental.</b> Since
     * {@link com.solace.sm.events.NotifyEventShapes} runs ahead of serialization, every payload
     * field in five of the six branches is now typed or enumerated, so an unserializable value in
     * any of them is refused loudly before it can reach the serializer. The one hole left is
     * {@code catalog.package}'s {@code packageVersion}, which the contract declares as
     * {@code {}} — required, any JSON type ({@code contracts/events/README.md} Known gap 4). That
     * makes this envelope simultaneously the proof that the swallow path still exists and the
     * measure of how narrow it now is: <b>shape-valid but non-serializable</b> is the entire
     * remaining silent-drop-with-committed-state class, and the transactional-outbox upgrade the
     * design already records is what closes it.
     */
    @Test
    protected void aSerializationFailureIsLoggedAndDroppedNotThrownAtTheCaller() {
        CapturingTransport transport = new CapturingTransport();
        EventPublisher p = publisher(transport);

        EventEnvelope unserializable = new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000fe"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "cp-core",
                "acme",
                new EntityRef("solution", null, null, "cache"),
                1,
                "catalog.package",
                Map.of("solution", "cache", "packageVersion", new Object()));

        // Precondition: this envelope really does defeat the serializer, or the test proves nothing.
        assertThrows(IllegalStateException.class, () -> EventJson.write(unserializable),
                "fixture must actually be unserializable for this test to mean anything");
        // Second precondition: it must be SHAPE-VALID, or this test would be re-proving the loud
        // guard rather than the swallow path it exists for.
        assertDoesNotThrow(() -> NotifyEventShapes.validate(unserializable),
                "this case only measures the residual swallow path if the shape guard lets it past");

        assertDoesNotThrow(() -> p.publish(unserializable));
        assertNull(transport.sent(), "a body that could not be serialized must not reach the transport");
        assertEquals(1L, droppedCount(p), "a swallowed serialization failure must be counted");
    }

    /**
     * The F1 guard at the publish site: an event that violates its branch of
     * {@code contracts/events/notify-events.schema.json} must never reach the wire, and must reach
     * the <b>caller</b> instead of a log line.
     *
     * <p>The case is {@code negative/health-without-placement.json} — the contract's own document
     * for the R5 identity-scoping rule, loaded from disk rather than hand-built, so this test moves
     * with the contract. Its topic <i>derives fine</i> ({@code NotifyTopics} addresses health by
     * instance), which is exactly why the topic guard alone did not catch it: before this change
     * the publisher would have put a placement-less health event on the wire, and every consumer
     * would have let one site of a dual-bound instance overwrite the other.
     */
    @Test
    protected void aShapeInvalidEventPropagatesAndNeverReachesTheTransport() throws IOException {
        CapturingTransport transport = new CapturingTransport();
        EventPublisher p = publisher(transport);
        assertShapeInvalidEventNeverReachesTheTransport(p, transport);
        assertEquals(0L, droppedCount(p),
                "a refused event is not a drop — conflating the two would hide real drops");
    }

    /**
     * Static and public so {@code PublisherContractKitSelfTest} can turn it on a deliberately
     * non-validating publisher and watch it fail — the kit's own prove-it-fires.
     */
    public static void assertShapeInvalidEventNeverReachesTheTransport(
            EventPublisher p, CapturingTransport transport) throws IOException {
        EventEnvelope bad = EventJson.reader()
                .readValue(Files.readString(NEGATIVES.resolve("health-without-placement.json")));
        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> p.publish(bad));
        assertTrue(boom.getMessage().contains("entity.placementId is required"), boom.getMessage());
        assertNull(transport.sent(), "a shape-invalid event must never reach the transport");
    }

    /**
     * The one case that <b>does</b> propagate, and a deliberate asymmetry (argued on
     * {@code JcsmpEventPublisher}): an event whose topic cannot be derived is unaddressable. An
     * unknown {@code event} key would put it outside the taxonomy, and a separator or wildcard in a
     * level would put it outside the ACLs the design reasons about — an {@code I5} isolation defect.
     * That fails closed and loud; a security guard that logs and continues is not a guard.
     *
     * <p>Topic derivation is still the <b>first</b> of the two loud guards, which is why this
     * unknown-event case reports the addressing rule rather than the shape rule (which would also
     * reject it, as {@code NotifyEventShapesTest} shows). The order is deliberate: addressing is
     * the {@code I5} concern, and the message a caller sees for an unroutable event should name
     * the taxonomy it fell outside of.
     */
    @Test
    protected void anUnaddressableEventPropagatesRatherThanBeingSilentlyDropped() {
        CapturingTransport transport = new CapturingTransport();
        EventPublisher p = publisher(transport);

        EventEnvelope bogus = new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000ff"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "dp-core",
                "acme",
                new EntityRef("instance", "cache-prod-01", null, null),
                1,
                "instance.not-a-real-event",
                Map.of());

        IllegalArgumentException boom = assertThrows(IllegalArgumentException.class, () -> p.publish(bogus));
        assertTrue(boom.getMessage().contains("no topic rule for event"),
                "must fail for the unaddressable-event reason, not some other one. Was: " + boom.getMessage());
        assertNull(transport.sent(), "nothing may reach the transport");
        assertEquals(0L, droppedCount(p), "a refused event is not a drop");
    }

    // ---- wire honesty: what validate() accepted must be what write() emitted -------------------

    /**
     * <b>The validator and the serializer must agree about what "present" means.</b> They did not:
     * {@code NotifyEventShapes} counted a required payload key as present when the map held it,
     * while {@code EventJson}'s {@code NON_NULL} <i>content</i> inclusion deleted null map values on
     * the way out — so a document this pipeline had just declared valid could reach the wire with a
     * required key missing, and the Python validator rejected it. The Java fix is in
     * {@code NotifyEventShapes.payload}; <b>this is the assertion that keeps the two halves
     * agreeing</b>, because it looks at the emitted bytes rather than at either component's opinion.
     *
     * <p>Run over every fixture rather than the one golden case: a branch whose payload keys are
     * never emitted-and-checked is exactly where the next instance of this would hide. The
     * comparison is key-set equality against the envelope the publisher was handed — validate()
     * guarantees the required keys are in there and non-null, so equality proves the wire kept them.
     *
     * <p><b>R12 promotion: this is now the full per-fixture emission contract, not a key-survival
     * spot-check.</b> It previously asserted only that the emitted payload's KEY SET matched, which
     * left every fixture but {@code health-transition.json} unchecked on both the topic it went out
     * on and the values in its body — a publisher that emitted the right keys with the wrong values,
     * or put a {@code config-state} event on the {@code version} topic, stayed green here for five
     * of six branches. Each fixture now also asserts its manifest topic and whole-document tree
     * equality, the same two assertions
     * {@link #serializesViaMeshEventsAndDerivesTopicFromNotifyTopics} makes for the golden case —
     * which stays as the readable smoke test it is. The key-set assertion is kept and runs FIRST,
     * because when the defect is a dropped key its message names the key and tree equality only
     * prints two documents.
     *
     * <p>Every implementation extending this kit inherits all of it; {@code dp-core}'s subclass
     * needs no change.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureDocuments")
    protected void everyFixturePublishesOnItsManifestTopicWithItsExactBody(Path fixture)
            throws IOException {
        CapturingTransport transport = new CapturingTransport();
        EventPublisher p = publisher(transport);

        String fixtureText = Files.readString(fixture);
        EventEnvelope e = EventJson.reader().readValue(fixtureText);
        p.publish(e);

        assertNotNull(transport.sent(), "publish() must hand (topic, body) to the transport");
        JsonNode emitted = JSON.readTree(transport.sent().getValue());
        JsonNode emittedPayload = emitted.get("payload");
        assertNotNull(emittedPayload, "the emitted document must carry a payload object");

        Set<String> onTheWire = new TreeSet<>();
        emittedPayload.fieldNames().forEachRemaining(onTheWire::add);
        assertEquals(new TreeSet<>(e.payload().keySet()), onTheWire,
                fixture.getFileName() + ": the emitted payload keys must be exactly the keys the "
                        + "shape guard validated — a key the serializer drops is a schema-invalid "
                        + "document the guard said was fine");

        String name = fixture.getFileName().toString();
        assertEquals(expectedTopicFor(name), transport.sent().getKey(),
                name + ": topic must be the one contracts/events/fixtures/expected-topics.json "
                        + "declares — a body that is right on the wrong topic is undelivered");
        assertEquals(JSON.readTree(fixtureText), emitted,
                name + ": emitted body must equal the contract fixture document, field for field");
    }

    /**
     * The measured escape, end to end and at the publish site: a {@code catalog.package} whose
     * {@code packageVersion} is an explicit null used to validate, serialize to
     * {@code {"payload":{"solution":"cache"}}} and go out. It must now be refused as the caller bug
     * it is — a shape defect, on the loud side, with the transport seeing nothing.
     *
     * <p>{@code packageVersion} is the field this could happen to, because it is the one payload
     * field the contract leaves untyped ({@code {}} — Known gap 4); every other branch's fields are
     * typed or enumerated, so a null there fails a type check as well.
     */
    @Test
    protected void aNullValuedRequiredPayloadKeyIsRefusedAndNeverReachesTheTransport() {
        CapturingTransport transport = new CapturingTransport();
        EventPublisher p = publisher(transport);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("solution", "cache");
        payload.put("packageVersion", null);
        EventEnvelope nullVersion = new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000fd"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "cp-core",
                "acme",
                new EntityRef("solution", null, null, "cache"),
                1,
                "catalog.package",
                payload);

        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> p.publish(nullVersion));
        assertTrue(boom.getMessage().contains("payload.packageVersion"),
                "must name the null key. Was: " + boom.getMessage());
        assertNull(transport.sent(), "the transport must have seen nothing at all");
        assertEquals(0L, droppedCount(p), "a refused event is not a drop");
    }

    /**
     * Every fixture document except the topic manifest, via the one shared resolver — which also
     * carries the "empty means fail, never pass vacuously" guard (L7), so this method has no
     * hand-rolled filter, sort or emptiness check of its own.
     */
    protected static List<Path> fixtureDocuments() {
        return ContractArtifacts.jsonFiles(FIXTURES, "expected-topics.json");
    }
}
