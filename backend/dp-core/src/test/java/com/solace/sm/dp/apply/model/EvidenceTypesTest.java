package com.solace.sm.dp.apply.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code I6} types of design §3.1's residual table, tested the way that table asks: "content, not
 * shape — engine classification tests".
 *
 * <p>Every refusal case below is a SENTINEL: a string that looks like the thing the column must never
 * hold — {@code password=hunter2}, a JWT-shaped bearer token, a multi-line exception message with a
 * filesystem path and quotes, a value with a space. The point is not that these particular strings are
 * refused, it is that the grammar has no way to express any of them, so a caller cannot smuggle one in
 * by being slightly different.
 *
 * <p>The other half of each test is the positive control: the legal examples the design writes must be
 * accepted, or the type is merely strict rather than correct.
 *
 * <p><b>And one test is a NEGATIVE positive control</b> —
 * {@link #aBareCompactTokenIsAcceptedByShapeWhichIsWhyTheContentObligationIsTheAdaptersOwn} measures
 * what these grammars do NOT catch. They are shape controls: they exclude environment lines,
 * {@code Bearer} headers, padded base64, PEM, JSON blobs and stack traces, and they admit any bare
 * identifier-shaped token. Writing the limit down as an executable assertion is the difference between
 * a documented boundary and an overclaim that the next reader trusts.
 *
 * <p>One assertion recurs and is load-bearing on its own: a refusal must never echo the value it
 * refused. A type that rejects {@code password=hunter2} and then prints it into an
 * {@code IllegalArgumentException} has moved the secret from the database to the log.
 */
class EvidenceTypesTest {

    private static final String PASSWORD_LINE = "password=hunter2";
    private static final String BEARER =
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc";
    private static final String STACK_TRACE =
            "java.io.IOException: cannot read \"/etc/sm/secrets.yml\"\n\tat Foo.bar(Foo.java:12)";
    private static final String WITH_SPACE = "cache engine 1";

    // ----------------------------------------------------------------------------------------
    // ErrorClass
    // ----------------------------------------------------------------------------------------

    @Test
    void anErrorClassAcceptsTheDesignsOwnClassificationsAndRefusesEverySentinelSecret() {
        for (String legal : List.of("io.timeout", "docker.api.unreachable", "hash.mismatch",
                "k8s", "a1.b2.c3")) {
            assertEquals(legal, new ErrorClass(legal).token());
        }
        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE)) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[a-z]", () -> new ErrorClass(sentinel));
        }
        // Grammar-only cases, asserted without the no-echo check: the refusal message names
        // io.timeout as the canonical EXAMPLE, so "io.timeout " (trailing space) is a substring of
        // it. Measured — the first run of this test failed on exactly that, which is the no-echo
        // assertion being strict rather than the type leaking anything.
        for (String malformed : List.of("IO.TIMEOUT", "io..timeout", ".io.timeout", "io.timeout ",
                "io.time_out", "io-timeout.")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ErrorClass(malformed), "must be refused: " + malformed);
            assertTrue(e.getMessage().contains("[a-z]"), e.getMessage());
        }
    }

    @Test
    void anErrorClassLongerThanItsCapIsRefusedWithoutEchoingIt() {
        String tooLong = "a".repeat(ErrorClass.MAX_LENGTH + 1);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new ErrorClass(tooLong));
        assertTrue(e.getMessage().contains(String.valueOf(ErrorClass.MAX_LENGTH)), e.getMessage());
        assertFalse(e.getMessage().contains(tooLong), e.getMessage());
        assertNotNull(new ErrorClass("a".repeat(ErrorClass.MAX_LENGTH)),
                "the boundary itself is legal — the cap is at most, not fewer than");
    }

    // ----------------------------------------------------------------------------------------
    // RuntimeRef
    // ----------------------------------------------------------------------------------------

    @Test
    void aRuntimeRefAcceptsAdapterIdentifiersAndRefusesEverySentinelSecretAsAValue() {
        RuntimeRef legal = RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30",
                RuntimeRef.Key.UNIT_NAME, "solace-cache.service",
                RuntimeRef.Key.CONFIG_MAP_NAME, "cache-config-g17",
                RuntimeRef.Key.NAMESPACE, "prod/cache-engine");
        assertEquals(4, legal.fields().size());
        assertEquals("9f2c1e4b7a30", legal.fields().get(RuntimeRef.Key.CONTAINER_ID));

        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE,
                "\"quoted\"", "a\\b")) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[A-Za-z0-9._:/@+-]",
                    () -> RuntimeRef.of(RuntimeRef.Key.HOST, sentinel));
        }
    }

    /**
     * The honest limit of the shape control, asserted rather than admitted in prose.
     *
     * <p>A compact JWT is base64url and dots; every character of it is legal in an adapter identifier,
     * so {@link RuntimeRef} accepts it. That is not a defect in the grammar — a grammar that excluded
     * it would also exclude {@code sha256:…} digests and dotted unit names — it is the boundary between
     * a SHAPE control and a CONTENT control. The content control is each adapter's own contract test
     * against secrets drawn from its real inputs, which Plan 005 carries.
     */
    @Test
    void aBareCompactTokenIsAcceptedByShapeWhichIsWhyTheContentObligationIsTheAdaptersOwn() {
        String compactJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc";
        RuntimeRef accepted = assertDoesNotThrow(
                () -> RuntimeRef.of(RuntimeRef.Key.CONTAINER_ID, compactJwt),
                "the grammar admits this, and pretending otherwise is how an I6 claim becomes false");
        assertEquals(compactJwt, accepted.fields().get(RuntimeRef.Key.CONTAINER_ID),
                "MEASURED: the type stored it verbatim — only the ADAPTER can stop this value being "
                        + "put here in the first place");
    }

    @Test
    void aRuntimeRefKeyOutsideTheClosedEnumIsRefusedAndTheEnumIsExactlyDesignTwoPointThrees() {
        assertEquals(List.of("containerId", "imageDigest", "unitName", "host", "namespace",
                        "workload", "podName", "configMapName", "mountPath"),
                Arrays.stream(RuntimeRef.Key.values()).map(RuntimeRef.Key::wire).toList(),
                "the wire names are the stored contract: renaming one changes every jsonb document "
                        + "this build reads");

        // A free-String key is now a compile-time impossibility, so the only remaining door is the
        // wire name — a document written by another build, or a hand-edit.
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> RuntimeRef.Key.ofWire("apiToken"));
        assertTrue(unknown.getMessage().contains("containerId"),
                "the refusal names the closed set. Was: " + unknown.getMessage());
        assertFalse(unknown.getMessage().contains("apiToken"),
                "…and not the key it refused. Was: " + unknown.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new RuntimeRef(Map.of()),
                "an empty reference references nothing");
    }

    @Test
    void aRuntimeRefIsDefensivelyCopiedAndUnmodifiable() {
        Map<RuntimeRef.Key, String> mutable = new LinkedHashMap<>();
        mutable.put(RuntimeRef.Key.CONTAINER_ID, "9f2c1e4b7a30");
        RuntimeRef ref = new RuntimeRef(mutable);

        mutable.put(RuntimeRef.Key.HOST, "some-other-host");
        assertEquals(1, ref.fields().size(),
                "a caller keeping the map it passed must not be able to add a field afterwards");
        assertThrows(UnsupportedOperationException.class,
                () -> ref.fields().put(RuntimeRef.Key.HOST, "some-other-host"));
    }

    // ----------------------------------------------------------------------------------------
    // PreBaseline
    // ----------------------------------------------------------------------------------------

    @Test
    void aPreBaselineAcceptsAProcessIdentifierAndRefusesEverySentinelSecret() {
        PreBaseline legal = new PreBaseline(Instant.parse("2026-09-01T00:00:00Z"), "9f2c1e4b7a30");
        assertEquals("9f2c1e4b7a30", legal.processRef());

        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE)) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[A-Za-z0-9._:/@+-]",
                    () -> new PreBaseline(Instant.EPOCH, sentinel));
        }
        assertThrows(NullPointerException.class, () -> new PreBaseline(null, "9f2c1e4b7a30"));
    }

    // ----------------------------------------------------------------------------------------
    // ReplicaObservation / ResolutionEvidence
    // ----------------------------------------------------------------------------------------

    @Test
    void aReplicaObservationRefusesAnythingThatIsNotAnAlgorithmColonHexHash() {
        assertEquals("sha256:3333333333333333333333333333333333333333",
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true,
                        "sha256:3333333333333333333333333333333333333333").observedHash());
        assertNull(new ReplicaObservation(new ReplicaRef("cache-engine-1"), false, null)
                        .observedHash(),
                "an adapter that could not read a hash says so with null, not with prose");

        for (String sentinel : List.of(PASSWORD_LINE, BEARER, STACK_TRACE, WITH_SPACE,
                "sha256:short", "SHA256:3333333333333333333333333333333333333333",
                "3333333333333333333333333333333333333333")) {
            assertRuleNamedAndValueNotEchoed(sentinel, "[0-9a-f]",
                    () -> new ReplicaObservation(new ReplicaRef("r1"), true, sentinel));
        }
    }

    @Test
    void applyResolutionEvidenceRefusesAnEmptyReplicaListAndANonPositiveGeneration() {
        ApplyResolutionEvidence legal = new ApplyResolutionEvidence(20, List.of(
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true, null)));
        assertEquals(20, legal.stagedGeneration());
        assertEquals(1, legal.replicas().size());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new ApplyResolutionEvidence(20, List.of()));
        assertTrue(empty.getMessage().contains("resolution_is_evidence"), empty.getMessage());

        for (long bad : new long[] {0, -1}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new ApplyResolutionEvidence(bad, List.of(
                            new ReplicaObservation(new ReplicaRef("r1"), true, null))));
            assertTrue(e.getMessage().contains("stagedGeneration"), e.getMessage());
        }
    }

    /**
     * {@code BE-D17}'s other shape, mirroring the test above: a restart resolution's evidence refuses
     * an empty {@code restartsObserved} array (rejection class (iii)) the same way an apply's refuses an
     * empty {@code replicas} — and, structurally rather than by a runtime check, {@code
     * RestartResolutionEvidence} has no {@code stagedGeneration} component at all to invent one in
     * (rejection class (iv)): asserted here by reflection, so a future edit that added the field back
     * would fail this test rather than silently reopening the hole {@code BE-D17} closed.
     */
    @Test
    void restartResolutionEvidenceRefusesAnEmptyObservationListAndHasNoStagedGenerationComponent() {
        RestartResolutionEvidence legal = new RestartResolutionEvidence(
                List.of(new RestartObservation(new ReplicaRef("cache-engine-1"), true)));
        assertEquals(1, legal.restartsObserved().size());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> new RestartResolutionEvidence(List.of()));
        assertTrue(empty.getMessage().contains("resolution_is_evidence"), empty.getMessage());

        List<String> components =
                Arrays.stream(RestartResolutionEvidence.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("restartsObserved"), components,
                "no stagedGeneration component may be added here — a restart request has no "
                        + "generation to certify (apply_has_generation), and this is the type-level "
                        + "half of rejection class (iv)");
    }

    @Test
    void aRestartObservationRequiresARefAndCarriesNoObservedHash() {
        RestartObservation observation = new RestartObservation(new ReplicaRef("cache-engine-1"), true);
        assertEquals("cache-engine-1", observation.ref().value());

        assertThrows(NullPointerException.class, () -> new RestartObservation(null, true));

        List<String> components = Arrays.stream(RestartObservation.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("ref", "restarted"), components,
                "two fields, not three — no observedHash: a restart resolution verifies no "
                        + "generation and reads back no config hash (design §3.4)");
    }

    /**
     * A {@code Resolution} carries typed evidence and NOTHING free-text. An earlier version had an
     * operator {@code note}; an external review pointed out it was the one column a human could paste
     * a secret into during an incident, persisted and then printed by JDBC's TRACE bind-value line.
     * Pinned by shape: the record's components are exactly the three below, none of them a remark.
     */
    @Test
    void aResolutionCarriesTypedEvidenceAndNoFreeTextComponent() {
        ApplyResolutionEvidence evidence = new ApplyResolutionEvidence(20, List.of(
                new ReplicaObservation(new ReplicaRef("cache-engine-1"), true, null)));

        Resolution r = new Resolution("duty-operator", ResolutionOutcome.APPLIED, evidence);
        assertEquals(evidence, r.evidence());
        assertThrows(NullPointerException.class,
                () -> new Resolution("duty-operator", ResolutionOutcome.APPLIED, null));

        List<String> components = java.util.Arrays.stream(Resolution.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("resolvedBy", "outcome", "evidence"), components,
                "no free-text component may be added to a Resolution without its own I6 design "
                        + "(sensitive content, retention, access control, logging) — see the javadoc");
    }

    // ----------------------------------------------------------------------------------------

    /** Refused, with the RULE in the message and the offending value nowhere in it. */
    private static void assertRuleNamedAndValueNotEchoed(String sentinel, String ruleFragment,
            org.junit.jupiter.api.function.Executable e) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, e,
                "must be refused: " + sentinel.replaceAll("\\s+", " "));
        assertTrue(thrown.getMessage().contains(ruleFragment),
                "the refusal must name the rule (" + ruleFragment + "). Was: " + thrown.getMessage());
        assertFalse(thrown.getMessage().contains(sentinel),
                "the refusal must NOT echo the value it refused — that copies the secret into every "
                        + "log the exception reaches. Was: " + thrown.getMessage());
    }
}
