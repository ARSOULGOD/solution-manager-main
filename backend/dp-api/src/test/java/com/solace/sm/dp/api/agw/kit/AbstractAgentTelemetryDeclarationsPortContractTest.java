package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.TelemetryDeclaration;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentTelemetryDeclarationsPort} must do (plan 015 W4 lane D, answering AGW-D2's "every in-process port has contract
 * tests a future remote adapter must pass", owed since the W3 integration): declarations are read for a FENCED session with an ACTIVE
 * lease; only placements of {@code active} instances and still live are declared; a placement with nothing active is declared with the
 * EMPTY set (which clears what the agent holds), while a placement that is gone is not declared at all (the gateway clears it);
 * and the change signal tells a subscriber, once the change has committed, exactly the agents that hold the changed instance.
 */
public abstract class AbstractAgentTelemetryDeclarationsPortContractTest {

    protected abstract AgentKitWorld world();

    private List<TelemetryDeclaration> read(AgentSessionRef ref) {
        return world().declarations().forAgent(ref);
    }

    private static Set<UUID> placements(List<TelemetryDeclaration> declared) {
        return declared.stream().map(TelemetryDeclaration::placementId).collect(Collectors.toSet());
    }

    // ---- the fence -------------------------------------------------------------------------------------------------

    @Test
    void aStaleSessionIsRefusedAndASessionWithoutAnActiveLeaseNeedsOne() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().ready(agent);
        world().open(agent);                                      // a newer session supersedes the first
        refused(GatewayCode.STALE_EPOCH, () -> read(stale));

        AgentKitWorld.Agent other = world().enrol();
        AgentSessionRef noLease = world().open(other);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> read(noLease));
    }

    // ---- what is declared ------------------------------------------------------------------------------------------

    @Test
    void aLiveActivePlacementDeclaresItsActiveMetricsAtTheFixedFiveSecondCadence() {
        AgentKitWorld.Agent agent = world().enrol();
        world().declareMetrics(agent, "cache.hit_rate", "cache.size");
        AgentSessionRef session = world().ready(agent);

        List<TelemetryDeclaration> declared = read(session);

        assertEquals(1, declared.size());
        TelemetryDeclaration d = declared.get(0);
        assertEquals(agent.placementId(), d.placementId());
        assertEquals(Duration.ofSeconds(5), d.interval(), "OR-73: the agent path's cadence is fixed");
        assertEquals(Set.of("cache.hit_rate", "cache.size"),
                d.metrics().stream().map(TelemetryDeclaration.Metric::name).collect(Collectors.toSet()));
        assertTrue(d.metrics().stream().allMatch(m -> m.containerPort() == 8081), "the container side port, as the package wrote it");
    }

    @Test
    void anInstanceThatIsNotActiveDeclaresNothing() {
        AgentKitWorld.Agent agent = world().enrol();
        world().declareMetrics(agent, "cache.size");
        AgentSessionRef session = world().ready(agent);
        assertEquals(1, read(session).size());

        world().deactivateInstance(agent);

        assertEquals(List.of(), read(session), "the pull path plans only active instances; so does this one");
    }

    @Test
    void aPlacementWithNothingActiveIsDeclaredWithTheEmptySetAndAGonePlacementIsNotDeclaredAtAll() {
        AgentKitWorld.Agent agent = world().enrol();
        world().declareMetrics(agent, "cache.size");
        UUID quiet = world().addPlacement(agent);                 // another instance: no definitions, no active list
        UUID gone = world().addPlacement(agent);
        AgentSessionRef session = world().ready(agent);
        world().retirePlacement(agent, gone);

        List<TelemetryDeclaration> declared = read(session);

        assertEquals(Set.of(agent.placementId(), quiet), placements(declared), "the retired placement is absent");
        TelemetryDeclaration empty = declared.stream().filter(d -> d.placementId().equals(quiet)).findFirst().orElseThrow();
        assertEquals(List.of(), empty.metrics(), "the EMPTY complete set: it replaces whatever the agent holds, which clears it");
        assertNull(empty.healthProbe(), "no probe is declared: nothing is probed");
        assertEquals(1, declared.stream().filter(d -> d.placementId().equals(agent.placementId())).findFirst().orElseThrow()
                .metrics().size());
    }

    @Test
    void anotherOrganizationsPlacementIsNeverDeclared() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentKitWorld.Agent victim = world().enrol();
        world().declareMetrics(victim, "victim.metric");
        AgentSessionRef session = world().ready(agent);

        assertEquals(Set.of(agent.placementId()), placements(read(session)));
    }

    // ---- the change signal -----------------------------------------------------------------------------------------

    @Test
    void aCommittedChangeIsSignalledToASubscriberForExactlyTheAgentsHoldingTheInstanceAndTheReReadSeesIt() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        AgentSessionRef session = world().ready(agent);
        AgentKitWorld.Agent bystander = world().enrol();
        world().ready(bystander);
        assertEquals(Set.of(agent.placementId(), second), placements(read(session)));

        List<UUID> told = new CopyOnWriteArrayList<>();
        List<Set<UUID>> readAtNotice = new CopyOnWriteArrayList<>();
        Consumer<UUID> subscriber = id -> {
            told.add(id);
            if (id.equals(agent.agentId())) {
                // read on ANOTHER thread, as the gateway's poll pool does: a transaction on the committing thread would let an
                // early notice see its own uncommitted change, and the pre-commit set is what any other thread reads
                readAtNotice.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> placements(read(session))).join());
            }
        };
        world().declarations().onDeclarationsChanged(subscriber);
        world().declarations().onDeclarationsChanged(subscriber);           // the same consumer twice is one registration

        world().changeDeclarations(agent, second);

        assertEquals(List.of(agent.agentId()), told, "the agent that holds the instance, once; not the bystander");
        assertEquals(List.of(Set.of(agent.placementId())), readAtNotice,
                "the notice comes after the change committed, so a re-read already sees it");
    }

    @Test
    void aSignalReachesOnlyTheOrganizationOfTheChangeEvenWhenAnotherHasTheSameInstanceId() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        world().ready(agent);
        AgentKitWorld.Agent namesake = world().enrolWithInstance(agent.instanceId());   // instance keys are per organization
        world().ready(namesake);
        List<UUID> told = new CopyOnWriteArrayList<>();
        world().declarations().onDeclarationsChanged(told::add);

        world().changeDeclarations(agent, second);

        assertEquals(List.of(agent.agentId()), told, "never the other organization's agent");
    }

    @Test
    void aPlacementAddedAfterTheLeaseIsSignalledAndTheAgentIsToldToRenewAndTheRenewedLeaseDeclaresIt() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        var lease = world().lease(agent, session);                         // the stored lease names the one placement
        assertEquals(Set.of(agent.placementId()), placements(read(session)));
        assertEquals(com.solace.sm.dp.api.agw.IdentityReconciliation.Status.UNCHANGED,
                world().identities().reconcile(List.of(session)).get(0).status(), "an unchanged placement set asks for nothing");
        List<UUID> told = new CopyOnWriteArrayList<>();
        world().declarations().onDeclarationsChanged(told::add);

        UUID added = world().addPlacementThroughTheDataPlane(agent);

        assertEquals(List.of(agent.agentId()), told, "found from the placement's target, not from a lease that cannot hold it yet");
        assertEquals(Set.of(agent.placementId()), placements(read(session)), "the stored lease is a snapshot: not declared yet");
        assertEquals(com.solace.sm.dp.api.agw.IdentityReconciliation.Status.RENEWAL_REQUIRED,
                world().identities().reconcile(List.of(session)).get(0).status(), "the live set differs from the lease's");
        world().renewLease(agent, session, lease);
        assertEquals(Set.of(agent.placementId(), added), placements(read(session)), "the renewed lease and the declarations include it");
        assertEquals(com.solace.sm.dp.api.agw.IdentityReconciliation.Status.UNCHANGED,
                world().identities().reconcile(List.of(session)).get(0).status(), "and the renewal settles the question");
    }

    @Test
    void aRetiredPlacementAlsoAsksForARenewal() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        AgentSessionRef session = world().ready(agent);

        world().changeDeclarations(agent, second);

        assertEquals(com.solace.sm.dp.api.agw.IdentityReconciliation.Status.RENEWAL_REQUIRED,
                world().identities().reconcile(List.of(session)).get(0).status());
    }

    /**
     * A target holding more placements than a lease can name: the lease names a capped subset, and the placement-set judgement must
     * pick the SAME subset, or every pass asks for a renewal that can never settle it. Seventy random ids put placements on both
     * sides of any signed-versus-unsigned ordering difference.
     */
    @Test
    void aTargetWithMorePlacementsThanALeaseNamesStillSettlesAfterItsLeaseIsMinted() {
        AgentKitWorld.Agent agent = world().enrol();
        for (int i = 0; i < 70; i++) {
            world().addPlacement(agent);
        }
        AgentSessionRef session = world().ready(agent);

        assertEquals(com.solace.sm.dp.api.agw.IdentityReconciliation.Status.UNCHANGED,
                world().identities().reconcile(List.of(session)).get(0).status(),
                "a freshly minted lease names the capped live set: nothing to renew");
    }

    @Test
    void aSubscriberThatThrowsNeitherFailsTheChangeNorSilencesTheOthers() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        world().ready(agent);
        List<UUID> told = new CopyOnWriteArrayList<>();
        world().declarations().onDeclarationsChanged(id -> {
            throw new IllegalStateException("a broken subscriber");
        });
        world().declarations().onDeclarationsChanged(told::add);

        world().changeDeclarations(agent, second);

        assertTrue(told.contains(agent.agentId()), "the second subscriber was told although the first threw");
        assertFalse(told.isEmpty());
    }
}
