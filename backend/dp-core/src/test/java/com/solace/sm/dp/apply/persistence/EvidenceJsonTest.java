package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.RestartObservation;
import com.solace.sm.dp.apply.model.RestartResolutionEvidence;
import com.solace.sm.dp.apply.model.RuntimeRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Evidence}'s three shapes, without a database.
 *
 * <p>Round-tripping is half of it. The other half is the top-level key set of
 * {@code resolution_observed}, asserted exactly: {@code resolution_is_evidence} reads
 * {@code -> 'stagedGeneration'} and {@code -> 'replicas'} BY NAME, so a rename here would compile,
 * pass every unit test that only checks round-tripping, and then refuse every operator resolution in
 * production with a 23514. This is the test that makes the coupling to the DDL visible.
 */
class EvidenceJsonTest {

    private static final JsonMapper READER = JsonMapper.builder().build();

    @Test
    void aPreBaselineRoundTripsAsItsTwoNamedFields() throws Exception {
        PreBaseline baseline =
                new PreBaseline(Instant.parse("2026-09-01T02:14:07Z"), "9f2c1e4b7a30");

        String json = Evidence.write(baseline);
        System.out.println("MEASURED: pre_baseline JSON = " + json);
        assertEquals("{\"startedAt\":\"2026-09-01T02:14:07Z\",\"processRef\":\"9f2c1e4b7a30\"}", json);
        assertEquals(baseline, Evidence.readPreBaseline(json));
        assertNull(Evidence.write((PreBaseline) null), "an absent baseline stays SQL NULL");
        assertNull(Evidence.readPreBaseline(null));
    }

    @Test
    void aRuntimeRefRoundTripsAsAPlainObjectKeyedByTheEnumsWireNames() throws Exception {
        RuntimeRef ref = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30",
                RuntimeRef.Key.UNIT_NAME, "solace-cache.service");

        String json = Evidence.write(ref);
        System.out.println("MEASURED: runtime_ref JSON = " + json);
        assertEquals("{\"containerId\":\"9f2c1e4b7a30\",\"unitName\":\"solace-cache.service\"}", json);
        assertEquals(ref, Evidence.readRuntimeRef(json));
        assertNull(Evidence.write((RuntimeRef) null));
        assertNull(Evidence.readRuntimeRef(null));
    }

    /**
     * The closed key set on the READ side, which is the half a Java enum alone does not give.
     *
     * <p>{@code RuntimeRef.Key} makes an unknown key uncompilable for this build's adapters. It says
     * nothing about a document that is already in the column — written by an older build, by a
     * hand-edit, or by an adapter that reached the {@code jsonb} some other way. Without this refusal
     * the enum would be a convention about who calls the constructor rather than a property of the
     * stored contract, which is exactly the shape of argument finding 1 rejected about the migrator
     * credential.
     */
    @Test
    void aStoredRuntimeRefWithAKeyOutsideTheEnumIsRefusedRatherThanCarriedThrough() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Evidence.readRuntimeRef("{\"apiToken\":\"x\"}"));

        System.out.println("MEASURED: unknown stored key refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("containerId"),
                "the refusal names the closed set. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("apiToken"),
                "…and never the key it refused — a stored key is data, and data may be anything. "
                        + "Was: " + e.getMessage());
        assertNotNull(Evidence.readRuntimeRef("{\"containerId\":\"9f2c1e4b7a30\"}"),
                "the positive control: a document written by this build still reads back");
    }

    /**
     * A corrupt column must not travel into the exception that reports it — neither through Jackson's
     * own message (which quotes the source document unless {@code INCLUDE_SOURCE_IN_LOCATION} is off —
     * the 2.16+ default, but a default is not a guarantee, so {@code Evidence} disables it explicitly)
     * nor through a cause chain, which Logback's {@code log.error(msg, ex)} prints in full.
     *
     * <p>The sentinel is placed AT the failure position — the first byte Jackson cannot parse — rather
     * than after some earlier malformed token. Jackson stops at the first problem it finds, so a
     * sentinel placed later in the document is never reached and a test built that way cannot exercise
     * this property at all (measured: with the malformed token first, Jackson's own message names only
     * that earlier token and never gets to the sentinel — a false-positive pass). The sentinel is also
     * hyphen-free (and holds no other character Jackson treats as a token terminator), so the token
     * Jackson echoes is the sentinel whole rather than cut off at a punctuation boundary — measured:
     * {@code Unrecognized token 'SNTLjson5a1d': was expecting …}. That makes {@code assertFalse} below a
     * live check on its own, not an echo of {@code assertNull}: a mutation that re-attaches the caught
     * exception as a formal cause fails {@code assertNull} first, but a mutation that folds the caught
     * message into the thrown message's own text — the same shape an unguarded {@code log.error} call
     * would produce — leaves {@code getCause()} null and is caught only by {@code assertFalse} (measured
     * against both mutations). The property the test pins is that {@link Evidence#read} neither attaches
     * a cause nor repeats the caught message.
     */
    @Test
    void aCorruptJsonColumnIsReportedWithoutQuotingItsContent() {
        String sentinel = "SNTLjson5a1d";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Evidence.readPreBaseline(sentinel));

        String chain = e.getMessage() + " | " + (e.getCause() == null ? "" : e.getCause().getMessage());
        System.out.println("MEASURED: corrupt jsonb report = " + chain);
        assertTrue(e.getMessage().contains("pre_baseline"), "names the column. Was: " + e.getMessage());
        assertNull(e.getCause(), "no cause: Jackson's own message can quote the failing token "
                + "verbatim when the token IS the sensitive text. Was: " + chain);
        assertFalse(chain.contains(sentinel),
                "the column's content reached the exception chain. Was: " + chain);
    }

    /**
     * A second corruption shape, matching the finding's other measured leak: the malformed token sits
     * inside an otherwise well-formed object, immediately after a key. Same property, same fix.
     */
    @Test
    void aCorruptJsonColumnWithTheSentinelAfterAKeyIsReportedWithoutQuotingItsContent() {
        String sentinel = "SNTLjson9c31";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Evidence.readPreBaseline("{\"a\":" + sentinel + "}"));

        String chain = e.getMessage() + " | " + (e.getCause() == null ? "" : e.getCause().getMessage());
        System.out.println("MEASURED: corrupt jsonb report (after key) = " + chain);
        assertTrue(e.getMessage().contains("pre_baseline"), "names the column. Was: " + e.getMessage());
        assertNull(e.getCause(), "no cause. Was: " + chain);
        assertFalse(chain.contains(sentinel),
                "the column's content reached the exception chain. Was: " + chain);
    }

    /** {@code DateTimeParseException} quotes the text it could not parse — so it is not carried. */
    @Test
    void aCorruptInstantInsideAValidDocumentIsReportedWithoutQuotingIt() {
        String sentinel = "SNTL-time-7e2c";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Evidence.readPreBaseline(
                        "{\"startedAt\":\"" + sentinel + "\",\"processRef\":\"p1\"}"));

        System.out.println("MEASURED: corrupt instant report = " + e.getMessage() + " | cause="
                + e.getCause());
        assertTrue(e.getMessage().contains("pre_baseline.startedAt"), e.getMessage());
        assertFalse(e.getMessage().contains(sentinel), e.getMessage());
        assertNull(e.getCause(), "no cause: DateTimeParseException's message IS the text");
    }

    @Test
    void applyResolutionEvidenceRoundTripsAndItsTopLevelKeysAreExactlyTheOnesTheCheckReads()
            throws Exception {
        ApplyResolutionEvidence evidence = new ApplyResolutionEvidence(20, List.of(
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true,
                        "sha256:3333333333333333333333333333333333333333"),
                new ReplicaObservation(new ReplicaRef("cache-engine-2"), false, null)));

        String json = Evidence.write(evidence);
        System.out.println("MEASURED: resolution_observed JSON (apply) = " + json);
        assertEquals(evidence, Evidence.readResolutionEvidence(json));

        JsonNode root = READER.readTree(json);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            keys.add(it.next());
        }
        assertEquals(List.of("stagedGeneration", "replicas"), keys,
                "resolution_is_evidence's apply branch reads these two names out of the jsonb; "
                        + "renaming either would refuse every apply resolution with a 23514 and "
                        + "compile cleanly");
        assertTrue(root.path("stagedGeneration").isNumber(),
                "the apply branch compares jsonb-to-jsonb against to_jsonb(generation), which "
                        + "requires a NUMBER, not a quoted string, to ever compare equal");
        assertTrue(root.path("replicas").isArray() && root.path("replicas").size() == 2,
                "…and a NON-EMPTY array");
        assertTrue(root.path("replicas").get(1).path("observedHash").isNull(),
                "an unreadable hash is JSON null, not an absent key: absence would make the "
                        + "element's shape depend on what the adapter managed to read");
        assertNull(Evidence.write((ResolutionEvidence) null));
        assertNull(Evidence.readResolutionEvidence(null));
    }

    /**
     * {@code BE-D18} (design §3.4, R6): the one required Java-side change. {@code
     * Evidence.writeApply} always emits {@code observedHash} — as {@code putNull} when the hash is
     * unavailable — so an element that omits the key entirely is a shape this build never wrote, and
     * the reader must refuse it rather than silently coalesce it to the same Java {@code null} a
     * present-and-{@code null} value maps to. No existing test depended on the old coalescing (the
     * javadoc on {@code Evidence.readResolutionEvidence} said as much) — this is additive.
     */
    @Test
    void readResolutionEvidenceRefusesAReplicaElementWithObservedHashEntirelyAbsent() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Evidence.readResolutionEvidence(
                        "{\"stagedGeneration\": 20, \"replicas\": "
                                + "[{\"ref\": \"cache-engine-1\", \"restarted\": true}]}"));

        System.out.println("MEASURED: absent observedHash key refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("resolution_observed"),
                "names the column. Was: " + e.getMessage());
        assertNull(e.getCause(), "no cause — corruption report, not a caller-recoverable failure. "
                + "Was: " + e.getMessage());
    }

    /**
     * The distinguishing positive control: a PRESENT {@code observedHash} whose value is JSON
     * {@code null} must still read back as Java {@code null} — {@code BE-D18} tightens KEY
     * mandatoriness, not value nullability, and the two must not be conflated by the fix.
     */
    @Test
    void readResolutionEvidenceMapsAPresentJsonNullObservedHashToJavaNull() {
        ApplyResolutionEvidence evidence = (ApplyResolutionEvidence) Evidence.readResolutionEvidence(
                "{\"stagedGeneration\": 20, \"replicas\": "
                        + "[{\"ref\": \"cache-engine-1\", \"restarted\": true, "
                        + "\"observedHash\": null}]}");

        assertEquals(1, evidence.replicas().size());
        assertNull(evidence.replicas().get(0).observedHash(),
                "a present-but-null observedHash is not the absent-key shape BE-D18 refuses");
    }

    /**
     * {@code BE-D17}'s other shape: a restart resolution's top-level key is {@code restartsObserved},
     * deliberately not {@code replicas}, and there is no {@code stagedGeneration} key at all — the
     * key's mere ABSENCE is what {@code resolution_is_evidence}'s restart branch requires, not a null
     * or zero value.
     */
    @Test
    void restartResolutionEvidenceRoundTripsAsRestartsObservedWithNoStagedGenerationKeyAtAll()
            throws Exception {
        RestartResolutionEvidence evidence = new RestartResolutionEvidence(List.of(
                new RestartObservation(new ReplicaRef("cache-engine-1"), true),
                new RestartObservation(new ReplicaRef("cache-engine-2"), false)));

        String json = Evidence.write(evidence);
        System.out.println("MEASURED: resolution_observed JSON (restart) = " + json);
        assertEquals(evidence, Evidence.readResolutionEvidence(json));

        JsonNode root = READER.readTree(json);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = root.fieldNames(); it.hasNext();) {
            keys.add(it.next());
        }
        assertEquals(List.of("restartsObserved"), keys,
                "exactly one top-level key, and it is not 'replicas' or 'stagedGeneration'");
        assertTrue(root.path("restartsObserved").isArray()
                        && root.path("restartsObserved").size() == 2,
                "…and a NON-EMPTY array");
        assertFalse(root.has("stagedGeneration"),
                "the restart branch refuses the KEY'S PRESENCE, not merely a bad value — this "
                        + "writer must never emit it at all");
    }
}
