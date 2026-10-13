package com.solace.sm.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Topic derivation against design §4 / {@code BE-D4}.
 *
 * <p>Two layers. The three hand-written cases pin the shapes a reader can check against the design
 * text. The manifest-driven case is the stronger one: it derives a topic for <b>every</b> fixture
 * and compares against {@code contracts/events/fixtures/expected-topics.json} — the same manifest
 * {@code validate-events.py} checks its own deriver against, so Java and Python are held to one
 * golden list and cannot drift apart silently.
 */
class NotifyTopicsTest {

    static final ObjectMapper M = EventJson.mapper();

    // ---- design §4's shapes, spelled out -----------------------------------------------------

    @Test
    void healthTopicMatchesDesignSection4() {
        assertEquals("sm/v1/acme/notify/instance/cache-prod-01/health",
                NotifyTopics.topicFor(fixture("health-transition.json")));
    }

    @Test
    void catalogTopicHasNoInstanceSegment() {
        String topic = NotifyTopics.topicFor(fixture("catalog-package.json"));
        assertEquals("sm/v1/acme/notify/catalog/package", topic);
        assertFalse(topic.contains("/instance/"), "catalog topics are not instance-addressed");
    }

    @Test
    void rolloutTopicCarriesApplyId() {
        assertEquals("sm/v1/acme/notify/instance/cache-prod-01/rollout/apply-7",
                NotifyTopics.topicFor(fixture("rollout-step.json")));
    }

    @Test
    void applyOutcomeTopicCarriesTheApplyId() {
        assertEquals("sm/v1/acme/notify/instance/cache-prod-01/apply-outcome/apply-8",
                NotifyTopics.topicFor(fixture("apply-outcome.json")));
    }

    // ---- the golden manifest -----------------------------------------------------------------

    @Test
    void everyFixtureDerivesItsManifestTopic() {
        Map<String, String> expected = manifest();
        List<Path> fixtures = ContractArtifacts.jsonFiles(
                EnvelopeGoldenTest.fixtureDir(), EnvelopeGoldenTest.TOPIC_MANIFEST);
        assertFalse(fixtures.isEmpty(), "no fixtures found — the suite must fail, not pass vacuously");

        for (Path f : fixtures) {
            String name = f.getFileName().toString();
            String want = expected.get(name);
            assertTrue(want != null, "fixture " + name + " has no expected-topics.json entry");
            assertEquals(want, NotifyTopics.topicFor(fixture(name)), "topic for " + name);
        }
        // Bidirectional, as the Python deriver's check 3 is: no stale manifest entries either.
        assertEquals(fixtures.stream().map(p -> p.getFileName().toString()).sorted().toList(),
                expected.keySet().stream().sorted().toList(),
                "expected-topics.json and the fixture set must cover each other exactly");
    }

    @Test
    void everyDerivedTopicIsVersioned() {
        for (Path f : ContractArtifacts.jsonFiles(
                EnvelopeGoldenTest.fixtureDir(), EnvelopeGoldenTest.TOPIC_MANIFEST)) {
            String topic = NotifyTopics.topicFor(fixture(f.getFileName().toString()));
            assertTrue(topic.startsWith("sm/v1/"), "v1 is load-bearing, missing in " + topic);
        }
    }

    /**
     * {@code negative/bad-topics.json} records addresses the deriver must never produce: an
     * {@code sm/v2} prefix, and a rollout topic with the {@code applyId} level dropped (R6 finding
     * 4). Asserting non-production is what makes those entries a gate rather than a comment.
     */
    @Test
    void badTopicsAreNotDerivable() {
        Map<String, String> bad = readMap(
                ContractArtifacts.dir("events/negative").resolve("bad-topics.json"));
        assertFalse(bad.isEmpty(), "bad-topics.json is empty — nothing would be proven");
        bad.forEach((fixtureName, forbidden) ->
                assertFalse(forbidden.equals(NotifyTopics.topicFor(fixture(fixtureName))),
                        "deriver produced the forbidden topic " + forbidden));
    }

    // ---- unknown events are rejected, not guessed at ------------------------------------------

    @Test
    void unknownEventIsRejected() {
        EventEnvelope e = new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000ff"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "dp-core", "acme",
                new EntityRef("instance", "cache-prod-01", null, null),
                1L, "instance.invented", Map.of());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> NotifyTopics.topicFor(e));
        assertTrue(ex.getMessage().contains("instance.invented"), ex.getMessage());
    }

    @Test
    void rolloutWithoutApplyIdIsRejected() {
        EventEnvelope e = new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000fe"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "dp-core", "acme",
                new EntityRef("placement", "cache-prod-01", "pl-eu-1", null),
                1L, "instance.rollout", Map.of("replicaId", "replica-2", "step", "staged"));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> NotifyTopics.topicFor(e));
        assertTrue(ex.getMessage().contains("applyId"), ex.getMessage());
    }

    // ---- interpolated values must not change the topic's SHAPE --------------------------------

    /**
     * A {@code /} adds levels; {@code *} and {@code >} are Solace subscription wildcards. Organization is
     * a topic level so broker ACLs can prefix-match on it (the mesh half of {@code I5}), so a
     * organization that can inject levels is an isolation defect. Every interpolated value is checked,
     * and the message must name the field so a failure is diagnosable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"acme/evil", "acme>", "acme*", "/", ">", "*", "a/b/c"})
    void organizationCannotInjectTopicLevels(String organization) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NotifyTopics.topicFor(envelope(organization, "cache-prod-01", "apply-7",
                        "instance.health")));
        assertTrue(e.getMessage().contains("organization"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cache/prod", "cache-prod-01>", "*", "..%2f/x"})
    void instanceIdCannotInjectTopicLevels(String instanceId) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NotifyTopics.topicFor(envelope("acme", instanceId, "apply-7",
                        "instance.version")));
        assertTrue(e.getMessage().contains("entity.instanceId"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply/7", "apply-7>", "apply-*"})
    void applyIdCannotInjectTopicLevels(String applyId) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NotifyTopics.topicFor(envelope("acme", "cache-prod-01", applyId,
                        "instance.rollout")));
        assertTrue(e.getMessage().contains("payload.applyId"), e.getMessage());
    }

    @Test
    void applyOutcomeWithoutApplyIdIsRejected() {
        EventEnvelope e = new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000fc"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "dp-core", "acme",
                new EntityRef("placement", "cache-prod-01", "pl-eu-1", null),
                1L, "instance.apply-outcome", Map.of("state", "APPLIED"));
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> NotifyTopics.topicFor(e));
        assertTrue(ex.getMessage().contains("applyId"), ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"apply/8", "apply-8>", "apply-*"})
    void applyOutcomeApplyIdCannotInjectTopicLevels(String applyId) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NotifyTopics.topicFor(envelope("acme", "cache-prod-01", applyId,
                        "instance.apply-outcome")));
        assertTrue(e.getMessage().contains("payload.applyId"), e.getMessage());
    }

    @Test
    void catalogTopicAlsoRejectsAnUnsafeTenant() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NotifyTopics.topicFor(envelope("acme/evil", null, null, "catalog.package")));
        assertTrue(e.getMessage().contains("organization"), e.getMessage());
    }

    @Test
    void ordinaryValuesStillPass() {
        assertEquals("sm/v1/acme-eu_1/notify/instance/cache.prod-01/version",
                NotifyTopics.topicFor(envelope("acme-eu_1", "cache.prod-01", null,
                        "instance.version")));
    }

    // ---- helpers ------------------------------------------------------------------------------

    static EventEnvelope envelope(String organization, String instanceId, String applyId, String event) {
        boolean outcome = "instance.apply-outcome".equals(event);
        boolean rollout = "instance.rollout".equals(event) || outcome;
        boolean catalog = "catalog.package".equals(event);
        return new EventEnvelope(
                UUID.fromString("3f1c9a52-0000-4000-8000-0000000000fd"),
                Instant.parse("2026-08-27T10:00:00Z"),
                "dp-core", organization,
                catalog ? new EntityRef("solution", null, null, "cache")
                        : new EntityRef(rollout ? "placement" : "instance", instanceId,
                                rollout ? "pl-eu-1" : null, null),
                1L, event,
                outcome ? Map.of("applyId", applyId, "state", "APPLIED")
                        : rollout ? Map.of("applyId", applyId, "replicaId", "replica-2", "step", "staged")
                        : Map.of());
    }

    static EventEnvelope fixture(String name) {
        try {
            return M.readValue(EnvelopeGoldenTest.fixtureDir().resolve(name).toFile(),
                    EventEnvelope.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> manifest() {
        return readMap(EnvelopeGoldenTest.fixtureDir().resolve(EnvelopeGoldenTest.TOPIC_MANIFEST));
    }

    static Map<String, String> readMap(Path p) {
        try {
            return M.readValue(p.toFile(), new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
