package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RuntimeSeamTypesTest {

    @Test
    void aPlacementRefRefusesTheInstanceWideSentinel() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new PlacementRef(new TenantId(UUID.randomUUID()),
                        new InstanceId(UUID.randomUUID()), PlacementId.INSTANCE_WIDE));
        assertTrue(e.getMessage().contains("instance-wide"),
                "an adapter is always addressed at a real placement; the sentinel is the engine's");
    }

    /**
     * BE-D21's one-owner rule, kept by construction: with a keyed hash a RenderedConfig can no longer re-hash its
     * own bytes to check a supplied hash, so nobody may supply one. There is no public constructor; the only way
     * in is {@code of(tenant, bytes, hasher)}.
     */
    @Test
    void aRenderedConfigCanOnlyBeMadeByAskingAHasher() {
        assertEquals(0, RenderedConfig.class.getConstructors().length,
                "a public constructor would let a caller supply a hash that describes nothing");
        byte[] bytes = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        RenderedConfig c = RenderedConfig.of(TestHashers.TENANT, bytes, TestHashers.SHA256);
        assertEquals(TestHashers.SHA256.hash(TestHashers.TENANT, bytes), c.hash());
    }

    @Test
    void aRenderedConfigCopiesItsBytesInBothDirections() {
        byte[] bytes = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        RenderedConfig c = TestHashers.rendered(bytes);
        bytes[0] = 'z';
        assertEquals('a', c.bytes()[0], "the record must not alias the caller's array");
        c.bytes()[0] = 'z';
        assertEquals('a', c.bytes()[0], "nor hand its own array out");
    }

    /**
     * I6, at the other boundary. PgConstraintViolation carries four classifiers and no cause because
     * Logback prints cause chains; an adapter exception that wrapped a Docker client exception would
     * reintroduce exactly that leak, one layer out.
     */
    @Test
    void aRuntimeAdapterExceptionCarriesAClassifierAndNoCause() {
        RuntimeAdapterException e =
                new RuntimeAdapterException(new ErrorClass("docker.api.unreachable"));
        assertNull(e.getCause(), "an adapter exception never carries a driver or client cause");
        assertEquals("docker.api.unreachable", e.errorClass().token());
        assertEquals("docker.api.unreachable", e.getMessage(),
                "the message is the classifier, so there is nothing else in it to leak");
    }

    /**
     * The operation set is pinned so that growing it is a decision, not an edit. It has grown twice, deliberately: this
     * test went red when {@code replicas} was added ({@code BE-D25}) and again when {@code rematerializeIfCurrent} was
     * added for the indeterminate reconciler (plan 010 T6.8, M-4), and each time it was changed only after the decision
     * that added the operation was written.
     */
    @Test
    void theTenOperationsAreExactlyTheTenTheDecisionsName() {
        assertEquals(Set.of("replicas", "prepare", "materializeConfig", "rematerializeIfCurrent", "startOrUpdate",
                        "stop", "status", "assessRollback", "restore", "readConfig"),
                Arrays.stream(RuntimeAdapter.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet()),
                "BE-D12 §2.1 fixed the protocol at six; BE-D22 split the sixth in two, because "
                        + "BE-D13 §3.1 already carried two journal steps for it; BE-D25 added "
                        + "`replicas` as a generation-free OBSERVATION, because prepare is "
                        + "generation-bearing and apply_has_generation makes a stop's generation "
                        + "NULL; plan 010 T6.8 added `rematerializeIfCurrent`, because the reconciler "
                        + "must not overwrite content SM did not write and materializeConfig re-checks "
                        + "nothing; plan 010 T10.4 added `readConfig` (BE-D39), the one READ-ONLY operation, because "
                        + "accept-runtime must read the host's file back and a parser belongs in one place, "
                        + "not once per adapter. An ELEVENTH is a design change, not an implementation detail - and a "
                        + "health-gate verb specifically was REJECTED, because a gate is a policy "
                        + "over observations, not a runtime action.");
    }

    /**
     * {@link WriteBaselinePolicy} is the one optional capability beside the ten operations (AGW-D28 item 2, OR-139): which
     * {@code write_baseline} the engine records for a write, chosen by {@code instanceof} on the adapter. It is NOT an operation
     * (it mutates nothing and reaches no host), so the set above stays at ten; but it is pinned here so that growing it, or the
     * list of adapters that implement it (pinned in arch-tests, {@code PlaneBoundaryTest}), is a decision and not an edit.
     */
    @Test
    void theWriteBaselinePolicyIsOneNonMutatingQuestion() {
        assertEquals(Set.of("writeBaseline", "of"),
                Arrays.stream(WriteBaselinePolicy.class.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet()));
    }

    /**
     * BE-D22 correction A: divergence is a lifecycle verdict the engine takes from its journal, so
     * an assessment cannot return it. A Diverged variant here would let the adapter classify the
     * ordinary partial-write mixture as divergence, which is the defect this correction removed.
     */
    @Test
    void aRollbackAssessmentHasNoDivergedVariant() {
        assertEquals(Set.of("Restorable", "NothingToRestore", "Unknowable"),
                Arrays.stream(RollbackAssessment.class.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .collect(Collectors.toSet()));
    }
}
