package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The lazy undeclare sweep (plan 012 T5, {@code ops-501.md} section 4.4), against fakes of the two ports it asks. What
 * this pins: the sweep READS the control plane and never writes it; it completes an undeclare whose control-plane step is
 * done at any age; it rolls one back only when the control plane did not release it AND the window has passed; and it is
 * for ONE organization, asking for nothing else.
 */
class UndeclareSweepTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private final UUID organization = UUID.randomUUID();
    private final Retirements retirements = new Retirements();
    private final Governance governance = new Governance();
    private final GatewayTestSupport.MutableClock clock = new GatewayTestSupport.MutableClock(NOW);
    private final UndeclareSweep sweep = new UndeclareSweep(retirements, governance, clock, WINDOW);

    private UUID retiring(Duration age) {
        UUID id = UUID.randomUUID();
        retirements.retiring.put(id, NOW.minus(age));
        return id;
    }

    @Test
    void nothingRetiringIsOneBoundedReadAndNothingElse() {
        sweep.sweep(organization);

        assertEquals(List.of("dp.retiring"), retirements.calls);
        assertTrue(governance.asked.isEmpty(), "the control plane is not asked when there is nothing to settle");
    }

    @Test
    void anUndeclareWhoseControlPlaneStepIsDoneIsCompletedAtAnyAge() {
        UUID fresh = retiring(Duration.ofSeconds(1));
        UUID old = retiring(Duration.ofHours(3));
        governance.retired.add(fresh);
        governance.retired.add(old);

        sweep.sweep(organization);

        assertEquals(List.of(fresh, old), retirements.completed, "step 5 for each, oldest-first as the port lists them");
        assertTrue(retirements.rolledBack.isEmpty());
        assertEquals(List.of(organization, organization), retirements.organizations.subList(1, 3), "I5");
    }

    @Test
    void anUndeclareTheControlPlaneNeverReleasedIsRolledBackOnlyOncePastTheWindow() {
        retiring(WINDOW.minusSeconds(1));
        retiring(WINDOW);
        UUID old = retiring(WINDOW.plusSeconds(1));

        sweep.sweep(organization);

        assertEquals(List.of(old), retirements.rolledBack, "strictly older than the window; a DELETE may be between steps 3 and 4");
        assertTrue(retirements.completed.isEmpty(), "nothing is completed unless the control plane released it");
    }

    @Test
    void theSweepReadsTheControlPlaneAndNeverWritesIt() {
        retiring(Duration.ofHours(1));
        UUID done = retiring(Duration.ofHours(2));
        governance.retired.add(done);

        sweep.sweep(organization);

        assertEquals(0, governance.deregistered, "the sweep runs inside some other session: no audit context to write under");
        assertEquals(List.of("cp.retired"), governance.calls);
    }

    @Test
    void theControlPlaneIsAskedInBatchesWithinItsBound() {
        int count = InstanceRetirementGovernance.MAX_BATCH + 5;
        for (int i = 0; i < count; i++) {
            retiring(Duration.ofSeconds(1));
        }

        sweep.sweep(organization);

        assertEquals(2, governance.askedSizes.size());
        assertTrue(governance.askedSizes.stream().allMatch(n -> n <= InstanceRetirementGovernance.MAX_BATCH),
                governance.askedSizes.toString());
        assertEquals(count, governance.askedSizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void anUndeclareFinishedOrRolledBackByAnotherRequestIsNotAnError() {
        UUID raced = retiring(Duration.ofHours(1));
        UUID other = retiring(Duration.ofHours(2));
        governance.retired.add(raced);
        governance.retired.add(other);
        retirements.refuseComplete.add(raced);

        sweep.sweep(organization);

        assertEquals(List.of(raced, other), retirements.completeAttempts, "the loser of a race does not stop the rest");
        assertEquals(List.of(other), retirements.completed);
    }

    @Test
    void anyOtherFailureIsNotSwallowed() {
        UUID id = retiring(Duration.ofHours(1));
        governance.retired.add(id);
        retirements.failComplete = new IllegalStateException("database down");

        assertThrows(IllegalStateException.class, () -> sweep.sweep(organization));
    }

    @Test
    void theWindowMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new UndeclareSweep(retirements, governance, clock, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new UndeclareSweep(retirements, governance, clock, Duration.ofSeconds(-1)));
    }

    // ------------------------------------------------------------------------------------------------ fakes

    static final class Retirements implements InstanceRetirementService {
        final List<String> calls = new ArrayList<>();
        final List<UUID> organizations = new ArrayList<>();
        final Map<UUID, Instant> retiring = new LinkedHashMap<>();
        final List<UUID> completed = new ArrayList<>();
        final List<UUID> completeAttempts = new ArrayList<>();
        final List<UUID> rolledBack = new ArrayList<>();
        final Set<UUID> refuseComplete = new HashSet<>();
        RuntimeException failComplete;

        @Override
        public RetirementBegun beginRetire(UUID organizationId, UUID instanceId, UUID requestedBy, UUID correlationId,
                WorkloadDisposition workload) {
            throw new AssertionError("the sweep never begins an undeclare");
        }

        @Override
        public RetirementView completeRetire(UUID organizationId, UUID instanceId) {
            calls.add("dp.completeRetire");
            organizations.add(organizationId);
            completeAttempts.add(instanceId);
            if (failComplete != null) {
                throw failComplete;
            }
            if (refuseComplete.contains(instanceId)) {
                throw new RetirementRefusedException(RetirementRefusedException.Reason.NOT_RETIRING);
            }
            completed.add(instanceId);
            return null;
        }

        @Override
        public boolean rollBackRetire(UUID organizationId, UUID instanceId) {
            calls.add("dp.rollBackRetire");
            organizations.add(organizationId);
            rolledBack.add(instanceId);
            return true;
        }

        @Override
        public List<RetiringInstance> retiring(UUID organizationId) {
            calls.add("dp.retiring");
            organizations.add(organizationId);
            List<RetiringInstance> list = new ArrayList<>();
            retiring.forEach((id, at) -> list.add(new RetiringInstance(id, UUID.randomUUID(), at)));
            return list;
        }
    }

    static final class Governance implements InstanceRetirementGovernance {
        final List<String> calls = new ArrayList<>();
        final Set<UUID> retired = new HashSet<>();
        final Set<UUID> asked = new HashSet<>();
        final List<Integer> askedSizes = new ArrayList<>();
        int deregistered;

        @Override
        public boolean deregister(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID instanceId) {
            deregistered++;
            throw new AssertionError("the sweep must never write the control plane");
        }

        @Override
        public Set<UUID> retired(UUID organizationId, Collection<UUID> instanceIds) {
            calls.add("cp.retired");
            asked.addAll(instanceIds);
            askedSizes.add(instanceIds.size());
            Set<UUID> answer = new HashSet<>(instanceIds);
            answer.retainAll(retired);
            return answer;
        }
    }
}
