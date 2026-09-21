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

    @Test
    void aRenderedConfigRefusesAHashThatDoesNotDescribeItsBytes() {
        byte[] bytes = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> new RenderedConfig(bytes, "sha256:" + "0".repeat(64)));
    }

    @Test
    void aRenderedConfigCopiesItsBytesInBothDirections() {
        byte[] bytes = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        RenderedConfig c = RenderedConfig.of(bytes);
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
     * The operation set is pinned so that growing it is a decision, not an edit. It has grown
     * once, deliberately: this test went red when {@code replicas} was added, which is the guard
     * working, and it was changed only after {@code BE-D25} was written.
     */
    @Test
    void theEightOperationsAreExactlyTheEightTheDecisionsName() {
        assertEquals(Set.of("replicas", "prepare", "materializeConfig", "startOrUpdate", "stop",
                        "status", "assessRollback", "restore"),
                Arrays.stream(RuntimeAdapter.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet()),
                "BE-D12 §2.1 fixed the protocol at six; BE-D22 split the sixth in two, because "
                        + "BE-D13 §3.1 already carried two journal steps for it; BE-D25 added "
                        + "`replicas` as a generation-free OBSERVATION, because prepare is "
                        + "generation-bearing and apply_has_generation makes a stop's generation "
                        + "NULL. A NINTH is a design change, not an implementation detail — and a "
                        + "health-gate verb specifically was REJECTED, because a gate is a policy "
                        + "over observations, not a runtime action.");
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
