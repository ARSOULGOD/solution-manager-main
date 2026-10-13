package com.solace.sm.events;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.contracttest.ContractArtifacts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Java half of the notify <b>catalog</b> contract, golden-tested against the same
 * {@code contracts/events/} documents {@code validate-events.py} consumes — the {@code UI-D15}
 * pattern applied one level deeper than {@link EnvelopeGoldenTest}, which covers the envelope only.
 *
 * <p>Three layers, in the order they earn their keep:
 * <ol>
 *   <li><b>Golden positives</b> — every fixture the Python validator accepts,
 *       {@link NotifyEventShapes#validate} must accept. A divergence here is the two-implementation
 *       drift the contract folder exists to prevent.</li>
 *   <li><b>Contract negatives</b> — the {@code negative/} documents whose defect is a <i>shape</i>
 *       defect must be rejected, and rejected <b>for their own reason</b>: the assertion names the
 *       rule, so a right-red-for-the-wrong-reason cannot pass (the R6 false-green class).</li>
 *   <li><b>Java negatives</b> — the branch constraints the contract's own negatives do not reach.
 *       {@code negative/} carries one document per <i>load-bearing</i> rule, not one per rule; every
 *       other kind/id/payload/enum/type constraint in the six branches is covered below.</li>
 * </ol>
 */
class NotifyEventShapesTest {

    private static final UUID ID = UUID.fromString("3f1c9a52-0000-4000-8000-0000000000aa");
    private static final Instant AT = Instant.parse("2026-08-27T10:00:00Z");

    // ---- 1. golden positives -------------------------------------------------------------------

    static Stream<Path> fixtureFiles() {
        List<Path> files = ContractArtifacts.jsonFiles(
                ContractArtifacts.dir("events/fixtures"), EnvelopeGoldenTest.TOPIC_MANIFEST);
        assertFalse(files.isEmpty(), "no event fixtures found — a golden suite that finds nothing "
                + "must fail, never pass vacuously");
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureFiles")
    void everyContractFixturePassesShapeValidation(Path fixture) throws IOException {
        EventEnvelope e = read(fixture);
        assertDoesNotThrow(() -> NotifyEventShapes.validate(e),
                "the Python validator accepts " + fixture.getFileName()
                        + "; the Java mirror must too");
    }

    // ---- 2. the contract's own negatives --------------------------------------------------------

    /**
     * Sentinel value in {@link #NEGATIVE_REASON}: this contract negative is <b>not</b> a shape
     * defect, and its acceptance here is pinned rather than asserted away.
     *
     * <p>Only {@code missing-state-version.json} carries it. {@code stateVersion} is a primitive
     * {@code long}: Jackson supplies 0 for an absent field, 0 satisfies the schema's
     * {@code minimum: 0}, and the document is otherwise a perfectly shaped health event — so it
     * deserializes and {@code validate} accepts it. Absence is a <b>wire-level</b> defect caught
     * only by the Python validator's {@code required} check (the same conclusion
     * {@link EnvelopeGoldenTest} reaches from the envelope side). Asserted rather than skipped so
     * the gap stays visible: if a future change makes absence representable in Java, the assertion
     * goes red and the missing check gets written.
     */
    private static final String ACCEPTED_WIRE_LEVEL_DEFECT_ONLY = "<accepted: not a shape defect>";

    /**
     * Every document in {@code contracts/events/negative/} and the rule it exists to exercise —
     * <b>declared, not hand-picked</b>.
     *
     * <p>Before R8 the Java side named its negatives one {@code @Test} at a time while
     * {@code validate-events.py} globbed the directory, so a negative added to the contract was
     * silently untested in Java and nothing said so. This map plus the unmapped-file guard below is
     * the Java mirror of that script's own {@code NEGATIVE_REASON} table and its
     * <i>"no declared rejection reason — add one"</i> failure: adding a negative to the contract now
     * fails this build until its Java expectation is declared.
     *
     * <p>{@code bad-topics.json} is excluded for the same reason the Python validator excludes it
     * ({@code NEGATIVE_MANIFEST}) — it is a list of forbidden topic strings, not an event document.
     */
    private static final Map<String, String> NEGATIVE_REASON = Map.ofEntries(
            // OR-4: instance.apply-outcome is placement-scoped; entity.kind is a const.
            entry("apply-outcome-instance-kind.json", "entity.kind must be 'placement', was 'instance'"),
            // OR-4 / I6: no error detail — the payload is closed to applyId + state.
            entry("apply-outcome-with-error-detail.json", "payload key 'message' is not allowed"),
            // OR-4: placementId is required (the instance-wide sentinel is not a storable placement).
            entry("apply-outcome-without-placement.json", "entity.placementId is required"),
            // OR-4: instanceId is required — the topic derivation reads it (slice review, 2026-10-05).
            entry("apply-outcome-without-instance.json", "entity.instanceId is required"),
            // M10 / owner ruling 2026-09-29 (T0.2): stops are REST-observed, STOPPED is not an outcome state.
            entry("apply-outcome-stop-state.json", "payload.state must be one of"),

            // R5 finding 3: instance.health is placement-scoped.
            entry("health-without-placement.json", "entity.placementId is required"),
            // catalog.package is solution-scoped and must not carry instance identity.
            entry("catalog-with-instance-id.json", "entity.instanceId is forbidden"),
            // I6: a metric value smuggled into a notify payload.
            entry("value-in-notify.json", "payload key 'cpuPercent' is not allowed"),
            // B/D15's required ordering field — see the sentinel's javadoc.
            entry("missing-state-version.json", ACCEPTED_WIRE_LEVEL_DEFECT_ONLY),
            // the envelope half: EventEnvelope's compact constructor, reached via Jackson during read
            entry("blank-organization.json", "organization must be present and non-blank"),
            // UI-D14: a member outside the closed health vocabulary
            entry("health-bad-state.json", "payload.state must be one of"),
            // the schema minimum on quarantine's count — the bound SchemaMirrorLockTest pins schema-side
            entry("quarantine-bad-count.json", "payload.count must be an integer >= 1"),
            // R12: the OTHER end of that bound. Jackson binds 2**63 as a BigInteger, construction
            // succeeds, and `asIntegral` returns null at the 64-bit boundary — so the SAME frozen
            // message fires. It reads "must be an integer >= 1" for a value that is far above 1,
            // which is imperfect wording for an overflow; the message text is frozen, so the
            // imperfection is recorded here rather than fixed. What matters is that the value is
            // refused on both sides: the schema's new `maximum` is the Python half.
            entry("quarantine-overflow-count.json", "payload.count must be an integer >= 1"),
            // R12: the same class one level up. `EventEnvelope.stateVersion` is a primitive `long`,
            // so 2**63 never survives the read — Jackson's InputCoercionException travels the
            // construction-rejection path below, exactly as blank-organization does.
            entry("stateversion-overflow.json", "out of range of long"));

    /** Lists among the negatives but is not an event — mirrors {@code NEGATIVE_MANIFEST}. */
    private static final String NEGATIVE_MANIFEST = "bad-topics.json";

    static Stream<Path> negativeDocuments() {
        List<Path> files = ContractArtifacts.jsonFiles(
                ContractArtifacts.dir("events/negative"), NEGATIVE_MANIFEST);
        assertFalse(files.isEmpty(), "no negative documents found — a suite that finds nothing "
                + "must fail, never pass vacuously");
        return files.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeDocuments")
    void everyContractNegativeIsHandledForItsDeclaredReason(Path doc) throws IOException {
        String name = doc.getFileName().toString();
        String reason = NEGATIVE_REASON.get(name);
        assertNotNull(reason, "negative/" + name + " has no declared expected reason — add one to "
                + "NEGATIVE_REASON so 'any red' cannot pass as the right red, exactly as "
                + "validate-events.py requires on the Python side");

        EventEnvelope e;
        try {
            e = read(doc);
        } catch (IOException constructionFailure) {
            // Envelope-level negatives are refused by EventEnvelope's compact constructor during
            // deserialization — Jackson wraps the IllegalArgumentException. That is a valid rejection
            // shape, but only for the declared reason: assert it appears in the cause chain, then stop —
            // there is no envelope left to validate.
            assertTrue(causeMessages(constructionFailure).contains(reason),
                    "negative/" + name + " was refused at construction, but not for its declared reason '"
                            + reason + "'. Causes: " + causeMessages(constructionFailure));
            return;
        }
        if (ACCEPTED_WIRE_LEVEL_DEFECT_ONLY.equals(reason)) {
            assertEquals(0L, e.stateVersion(),
                    "absence is not representable: Jackson defaults it to 0");
            assertDoesNotThrow(() -> NotifyEventShapes.validate(e),
                    "the shape mirror does not — and cannot — see an absent primitive; the Python "
                            + "validator's `required` check is the only guard against this document");
            return;
        }
        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> NotifyEventShapes.validate(e),
                        "negative/" + name + " must be rejected");
        assertTrue(boom.getMessage().contains(reason),
                "negative/" + name + " must fail for the rule under test (\"" + reason
                        + "\"), not another. Was: " + boom.getMessage());
    }

    // ---- 3. Java negatives: every branch constraint the contract negatives do not reach ---------

    static Stream<Arguments> branchViolations() {
        return Stream.of(
                // ---- instance.health -------------------------------------------------------
                bad("health / wrong entity.kind",
                        ev("instance.health", ent("instance", "i-1", "p-1", null),
                                map("state", "RED")),
                        "entity.kind must be 'placement', was 'instance'"),
                bad("health / missing entity.instanceId",
                        ev("instance.health", ent("placement", null, "p-1", null),
                                map("state", "RED")),
                        "entity.instanceId is required"),
                bad("health / missing payload.state",
                        ev("instance.health", ent("placement", "i-1", "p-1", null), map()),
                        "payload.state is required"),
                bad("health / extra payload key",
                        ev("instance.health", ent("placement", "i-1", "p-1", null),
                                map("state", "RED", "note", "x")),
                        "payload key 'note' is not allowed"),
                bad("health / value outside the closed enum",
                        ev("instance.health", ent("placement", "i-1", "p-1", null),
                                map("state", "YELLOW")),
                        "payload.state must be one of"),
                bad("health / enum member of the wrong type",
                        ev("instance.health", ent("placement", "i-1", "p-1", null),
                                map("state", 7)),
                        "payload.state must be one of"),
                // A null `state` used to land here, reported as "must be one of". It no longer
                // does: `payload(...)` now rejects a null-valued required key before the enum check
                // ever runs, and the message says so. Covered branch-by-branch below, under
                // `nullRequiredPayloadKeys` — which is a stronger case than one hand-picked key.

                // ---- instance.config-state -------------------------------------------------
                bad("config-state / wrong entity.kind",
                        ev("instance.config-state", ent("placement", "i-1", null, null),
                                map("reached", "persisted")),
                        "entity.kind must be 'instance', was 'placement'"),
                bad("config-state / forbidden placementId present",
                        ev("instance.config-state", ent("instance", "i-1", "p-1", null),
                                map("reached", "persisted")),
                        "entity.placementId is forbidden"),
                bad("config-state / missing entity.instanceId",
                        ev("instance.config-state", ent("instance", null, null, null),
                                map("reached", "persisted")),
                        "entity.instanceId is required"),
                bad("config-state / missing payload.reached",
                        ev("instance.config-state", ent("instance", "i-1", null, null), map()),
                        "payload.reached is required"),
                bad("config-state / extra payload key",
                        ev("instance.config-state", ent("instance", "i-1", null, null),
                                map("reached", "persisted", "renderedYaml", "a: b")),
                        "payload key 'renderedYaml' is not allowed"),
                bad("config-state / value outside the closed enum",
                        ev("instance.config-state", ent("instance", "i-1", null, null),
                                map("reached", "applied")),
                        "payload.reached must be one of"),

                // ---- instance.version ------------------------------------------------------
                bad("version / wrong entity.kind",
                        ev("instance.version", ent("solution", "i-1", null, null),
                                map("state", "OK")),
                        "entity.kind must be 'instance', was 'solution'"),
                bad("version / forbidden placementId present",
                        ev("instance.version", ent("instance", "i-1", "p-1", null),
                                map("state", "OK")),
                        "entity.placementId is forbidden"),
                bad("version / missing payload.state",
                        ev("instance.version", ent("instance", "i-1", null, null), map()),
                        "payload.state is required"),
                bad("version / extra payload key",
                        ev("instance.version", ent("instance", "i-1", null, null),
                                map("state", "OK", "observedVersion", "2.4.0")),
                        "payload key 'observedVersion' is not allowed"),
                bad("version / health's enum is not version's enum",
                        ev("instance.version", ent("instance", "i-1", null, null),
                                map("state", "GREEN")),
                        "payload.state must be one of"),

                // ---- instance.rollout ------------------------------------------------------
                bad("rollout / wrong entity.kind",
                        ev("instance.rollout", ent("instance", "i-1", "p-1", null),
                                map("applyId", "a-1", "replicaId", "r-1", "step", "staged")),
                        "entity.kind must be 'placement', was 'instance'"),
                bad("rollout / missing entity.placementId",
                        ev("instance.rollout", ent("placement", "i-1", null, null),
                                map("applyId", "a-1", "replicaId", "r-1", "step", "staged")),
                        "entity.placementId is required"),
                bad("rollout / missing payload.replicaId",
                        ev("instance.rollout", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "step", "staged")),
                        "payload.replicaId is required"),
                bad("rollout / extra payload key",
                        ev("instance.rollout", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "replicaId", "r-1", "step", "staged",
                                        "exitCode", 1)),
                        "payload key 'exitCode' is not allowed"),
                bad("rollout / applyId of the wrong type",
                        ev("instance.rollout", ent("placement", "i-1", "p-1", null),
                                map("applyId", 7, "replicaId", "r-1", "step", "staged")),
                        "payload.applyId must be a string"),
                bad("rollout / replicaId of the wrong type",
                        ev("instance.rollout", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "replicaId", true, "step", "staged")),
                        "payload.replicaId must be a string"),
                bad("rollout / step outside the closed enum",
                        ev("instance.rollout", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "replicaId", "r-1", "step", "done")),
                        "payload.step must be one of"),

                // ---- instance.apply-outcome (OR-4) ----------------------------------------
                bad("apply-outcome / wrong entity.kind",
                        ev("instance.apply-outcome", ent("instance", "i-1", "p-1", null),
                                map("applyId", "a-1", "state", "APPLIED")),
                        "entity.kind must be 'placement', was 'instance'"),
                bad("apply-outcome / missing entity.placementId",
                        ev("instance.apply-outcome", ent("placement", "i-1", null, null),
                                map("applyId", "a-1", "state", "APPLIED")),
                        "entity.placementId is required"),
                bad("apply-outcome / missing payload.applyId",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("state", "APPLIED")),
                        "payload.applyId is required"),
                bad("apply-outcome / missing payload.state",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1")),
                        "payload.state is required"),
                bad("apply-outcome / error detail is not carried (OR-4, I6)",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "state", "FAILED_NO_EFFECT",
                                        "errorClass", "SSH_UNREACHABLE")),
                        "payload key 'errorClass' is not allowed"),
                bad("apply-outcome / applyId of the wrong type",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", 7, "state", "APPLIED")),
                        "payload.applyId must be a string"),
                bad("apply-outcome / a non-terminal state that is not APPLY_INDETERMINATE",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "state", "RUNNING")),
                        "payload.state must be one of"),
                bad("apply-outcome / REQUESTED is not an outcome",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "state", "REQUESTED")),
                        "payload.state must be one of"),
                bad("apply-outcome / STOPPED is REST-observed, not an outcome notice (M10)",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "state", "STOPPED")),
                        "payload.state must be one of"),
                bad("apply-outcome / STOP_FAILED is REST-observed, not an outcome notice (M10)",
                        ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                                map("applyId", "a-1", "state", "STOP_FAILED")),
                        "payload.state must be one of"),

                // ---- catalog.package -------------------------------------------------------
                bad("catalog / wrong entity.kind",
                        ev("catalog.package", ent("instance", null, null, "cache"),
                                map("solution", "cache", "packageVersion", "2.4.0")),
                        "entity.kind must be 'solution', was 'instance'"),
                bad("catalog / forbidden placementId present",
                        ev("catalog.package", ent("solution", null, "p-1", "cache"),
                                map("solution", "cache", "packageVersion", "2.4.0")),
                        "entity.placementId is forbidden"),
                bad("catalog / missing entity.solution",
                        ev("catalog.package", ent("solution", null, null, null),
                                map("solution", "cache", "packageVersion", "2.4.0")),
                        "entity.solution is required"),
                bad("catalog / missing payload.packageVersion",
                        ev("catalog.package", ent("solution", null, null, "cache"),
                                map("solution", "cache")),
                        "payload.packageVersion is required"),
                bad("catalog / extra payload key",
                        ev("catalog.package", ent("solution", null, null, "cache"),
                                map("solution", "cache", "packageVersion", "2.4.0",
                                        "schemaBytes", 1024)),
                        "payload key 'schemaBytes' is not allowed"),
                bad("catalog / payload.solution of the wrong type",
                        ev("catalog.package", ent("solution", null, null, "cache"),
                                map("solution", 42, "packageVersion", "2.4.0")),
                        "payload.solution must be a string"),

                // ---- metrics.quarantine ----------------------------------------------------
                bad("quarantine / wrong entity.kind",
                        ev("metrics.quarantine", ent("placement", "i-1", null, null),
                                map("count", 3)),
                        "entity.kind must be 'instance', was 'placement'"),
                bad("quarantine / missing entity.instanceId",
                        ev("metrics.quarantine", ent("instance", null, null, null),
                                map("count", 3)),
                        "entity.instanceId is required"),
                bad("quarantine / missing payload.count",
                        ev("metrics.quarantine", ent("instance", "i-1", null, null), map()),
                        "payload.count is required"),
                bad("quarantine / extra payload key",
                        ev("metrics.quarantine", ent("instance", "i-1", null, null),
                                map("count", 3, "metricName", "cache.hits")),
                        "payload key 'metricName' is not allowed"),
                bad("quarantine / count below the schema minimum",
                        ev("metrics.quarantine", ent("instance", "i-1", null, null),
                                map("count", 0)),
                        "payload.count must be an integer >= 1"),
                bad("quarantine / count as a string",
                        ev("metrics.quarantine", ent("instance", "i-1", null, null),
                                map("count", "3")),
                        "payload.count must be an integer >= 1"),
                bad("quarantine / count with a fractional part",
                        ev("metrics.quarantine", ent("instance", "i-1", null, null),
                                map("count", 3.5)),
                        "payload.count must be an integer >= 1"),

                // ---- not an event at all ---------------------------------------------------
                bad("unknown event key",
                        ev("instance.not-a-real-event", ent("instance", "i-1", null, null), map()),
                        "unknown event 'instance.not-a-real-event'"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("branchViolations")
    void rejectsEveryBranchViolationForItsOwnReason(String name, EventEnvelope e, String reason) {
        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> NotifyEventShapes.validate(e),
                        name + " must be rejected");
        assertTrue(boom.getMessage().contains(reason),
                name + " must fail for the rule under test (\"" + reason + "\"), not another. Was: "
                        + boom.getMessage());
    }

    // ---- 3b. a required payload key present but NULL — the R8/F1 wire escape --------------------

    /**
     * One case per <b>required payload key of every branch</b>, each with an explicit {@code null}
     * value. Generated rather than hand-listed for the reason F6 gives about the negatives: a key
     * added to a branch must not be able to slip in untested.
     *
     * <p>Each baseline is asserted valid before a key is nulled, so a case that goes red is red for
     * the null and not because the baseline drifted out of its branch.
     */
    static Stream<Arguments> nullRequiredPayloadKeys() {
        return Stream.of(
                eachRequiredKeyNulled("instance.health", ent("placement", "i-1", "p-1", null),
                        map("state", "RED")),
                eachRequiredKeyNulled("instance.config-state", ent("instance", "i-1", null, null),
                        map("reached", "persisted")),
                eachRequiredKeyNulled("instance.version", ent("instance", "i-1", null, null),
                        map("state", "OK")),
                eachRequiredKeyNulled("instance.rollout", ent("placement", "i-1", "p-1", null),
                        map("applyId", "a-1", "replicaId", "r-1", "step", "staged")),
                eachRequiredKeyNulled("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                        map("applyId", "a-1", "state", "APPLIED")),
                eachRequiredKeyNulled("catalog.package", ent("solution", null, null, "cache"),
                        map("solution", "cache", "packageVersion", "2.4.0")),
                eachRequiredKeyNulled("metrics.quarantine", ent("instance", "i-1", null, null),
                        map("count", 3)))
                .flatMap(s -> s);
    }

    /**
     * {@code containsKey} is not presence. {@link EventJson}'s mapper strips null <b>map values</b>
     * (content inclusion), so a required key that validated as "present" because the map held the
     * key was deleted on the way to the wire, and the emitted document was schema-invalid — the
     * measured R8/F1 escape, whose worked example was
     * {@code catalog.package} with {@code packageVersion: null}.
     *
     * <p>The message must name the key <i>and</i> say the value was null: "missing" would send a
     * caller looking for a line that is right there in its own code.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nullRequiredPayloadKeys")
    void aNullValuedRequiredPayloadKeyIsRejectedAndTheMessageSaysNullNotMissing(
            String name, EventEnvelope e, String key) {
        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> NotifyEventShapes.validate(e),
                        name + " must be rejected — the serializer would delete it");
        assertTrue(boom.getMessage().contains("payload." + key),
                name + " must name the offending key. Was: " + boom.getMessage());
        assertTrue(boom.getMessage().contains("must not be null"),
                name + " must distinguish null from missing. Was: " + boom.getMessage());
    }

    /**
     * The premise of the rule above, measured rather than assumed: a null map value really is
     * deleted by {@code EventJson}'s configuration. If Jackson's content inclusion ever stopped
     * doing this, the rule would still be defensible but its stated <i>reason</i> would be wrong —
     * this test is what makes that visible instead of leaving a stale justification in the javadoc.
     *
     * <p>Built through the envelope's own {@code payload} map, and deliberately <b>not</b> routed
     * through {@code validate}, which now refuses it.
     */
    @Test
    void theSerializerReallyDoesDeleteANullPayloadValueWhichIsWhyValidateRefusesOne() {
        EventEnvelope withNull = ev("catalog.package", ent("solution", null, null, "cache"),
                map("solution", "cache", "packageVersion", null));

        String wire = EventJson.write(withNull);

        assertTrue(wire.contains("\"solution\":\"cache\""), wire);
        assertFalse(wire.contains("packageVersion"),
                "premise of the null rule: NON_NULL content inclusion strips the key entirely. "
                        + "Was: " + wire);
    }

    // ---- instance.apply-outcome: the catalogued shape, pinned (OR-4, OR-58, M10) -----------------

    /**
     * The terminal states of the {@code apply} operation only — {@code RequestState.isTerminal()}
     * minus the two stop states — plus {@code APPLY_INDETERMINATE} (owner ruling 2026-09-29 (T0.2),
     * {@code OR-58}). A literal on purpose: mesh-events cannot import dp-core's enum, so this is the
     * one place the vocabulary is written out Java-side, and {@code SchemaMirrorLockTest} locks the
     * schema's enum equal to the branch table it is read from.
     */
    private static final Set<String> APPLY_OUTCOME_STATES = Set.of(
            "STAGED", "APPLIED", "FAILED_NO_EFFECT", "FAILED_ROLLED_BACK", "CONFIG_DIVERGED",
            "SUPERSEDED", "INDETERMINATE_RESOLVED", "APPLY_INDETERMINATE");

    @Test
    void applyOutcomeIsCatalogued() {
        assertTrue(NotifyEventShapes.handledEvents().contains("instance.apply-outcome"),
                "the seventh v1 event must be in the Java branch table");
        NotifyEventShapes.BranchRule rule = NotifyEventShapes.BRANCHES.get("instance.apply-outcome");
        assertNotNull(rule);
        assertEquals("placement", rule.kind());
        assertEquals(Set.of("instanceId", "placementId"), rule.requiredEntity(),
                "placementId is REQUIRED (OR-4) — the instance-wide sentinel is not a placement");
        assertEquals(Set.of(), rule.forbiddenEntity());
        assertEquals(Set.of("applyId", "state"), rule.payload().keySet(),
                "OR-4: applyId + state, nothing else — no message, no ErrorClass token");
        assertTrue(rule.payload().get("applyId") instanceof NotifyEventShapes.IsString);
        assertEquals(new NotifyEventShapes.EnumOf(APPLY_OUTCOME_STATES), rule.payload().get("state"));
        assertFalse(((NotifyEventShapes.EnumOf) rule.payload().get("state")).members().contains("STOPPED"));
        assertFalse(((NotifyEventShapes.EnumOf) rule.payload().get("state")).members().contains("STOP_FAILED"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "STAGED", "APPLIED", "FAILED_NO_EFFECT", "FAILED_ROLLED_BACK", "CONFIG_DIVERGED",
            "SUPERSEDED", "INDETERMINATE_RESOLVED", "APPLY_INDETERMINATE"})
    void everyApplyOutcomeStateIsAccepted(String state) {
        assertDoesNotThrow(() -> NotifyEventShapes.validate(
                ev("instance.apply-outcome", ent("placement", "i-1", "p-1", null),
                        map("applyId", "a-1", "state", state))));
    }

    // ---- 4. the two places the schema is deliberately permissive, pinned ------------------------

    /**
     * {@code payload.packageVersion} is {@code {}} in the schema — required, any JSON type — so a
     * non-string version validates. Pinned so nobody "tightens" it here by accident: the type and
     * its canonical home are Known gap 4, owned by the {@code BE-D4} amendment, and a Java-only
     * narrowing would be the two-implementation drift this suite exists to catch.
     */
    @Test
    void catalogPackageVersionIsUntypedByContractAndThisMirrorMustNotInventAType() {
        EventEnvelope numeric = ev("catalog.package", ent("solution", null, null, "cache"),
                map("solution", "cache", "packageVersion", 42));
        assertDoesNotThrow(() -> NotifyEventShapes.validate(numeric));
    }

    /**
     * {@code metrics.quarantine} does not forbid {@code placementId}, although the other two
     * instance-scoped events do. Pinned for the same reason as above: the asymmetry is the schema's,
     * and closing it is a contract change, not a Java change.
     */
    @Test
    void quarantineDoesNotForbidPlacementIdBecauseTheSchemaDoesNot() {
        EventEnvelope withPlacement =
                ev("metrics.quarantine", ent("instance", "i-1", "p-1", null), map("count", 3));
        assertDoesNotThrow(() -> NotifyEventShapes.validate(withPlacement));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static EventEnvelope read(Path p) throws IOException {
        return EventJson.reader().readValue(Files.readString(p));
    }

    /** Every message in a throwable's cause chain — Jackson buries the real reason one level down. */
    private static String causeMessages(Throwable t) {
        StringBuilder all = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            // A message-less cause (Jackson wraps some of these) would otherwise render a literal
            // "null" line and read as a bug in the assertion rather than as an unhelpful cause.
            all.append(c.getMessage() == null ? "(" + c.getClass().getName() + ", no message)"
                    : c.getMessage()).append('\n');
        }
        return all.toString();
    }

    /** One {@code Arguments} per required key of {@code valid}, that key set to {@code null}. */
    private static Stream<Arguments> eachRequiredKeyNulled(
            String event, EntityRef entity, Map<String, Object> valid) {
        assertDoesNotThrow(() -> NotifyEventShapes.validate(ev(event, entity, valid)),
                event + ": the baseline payload must itself be valid, or nulling one key proves "
                        + "nothing");
        return valid.keySet().stream().map(key -> {
            Map<String, Object> broken = new LinkedHashMap<>(valid);
            broken.put(key, null);
            return Arguments.of(event + " / payload." + key + " present but null",
                    ev(event, entity, broken), key);
        });
    }

    private static Arguments bad(String name, EventEnvelope e, String reason) {
        return Arguments.of(name, e, reason);
    }

    private static EventEnvelope ev(String event, EntityRef entity, Map<String, Object> payload) {
        return new EventEnvelope(ID, AT, "dp-core", "acme", entity, 1L, event, payload);
    }

    private static EntityRef ent(String kind, String instanceId, String placementId, String solution) {
        return new EntityRef(kind, instanceId, placementId, solution);
    }

    /** {@code Map.of} rejects null values; several negatives need exactly that. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
