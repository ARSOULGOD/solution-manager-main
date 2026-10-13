package com.solace.sm.dp.apply.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link RoutingRuntimeAdapter} (plan 015 T3.1): the runtime-neutral kit THROUGH the router (so routing is transparent to the
 * engine), and the three routing obligations. Which adapter a placement reaches is the table's answer for that placement, per
 * call; nothing is defaulted.
 */
class RoutingRuntimeAdapterTest extends AbstractRuntimeAdapterContractTest {

    /** An adapter that records which operations reached it and delegates (or, with no delegate, fails the test). */
    private static final class Spy {
        final List<String> calls = new ArrayList<>();
        final RuntimeAdapter adapter;

        Spy(RuntimeAdapter delegate) {
            this.adapter = (RuntimeAdapter) Proxy.newProxyInstance(RuntimeAdapter.class.getClassLoader(),
                    new Class<?>[] {RuntimeAdapter.class}, (proxy, method, args) -> {
                        calls.add(method.getName());
                        if (delegate == null) {
                            throw new AssertionError("this adapter must not be reached: " + method.getName());
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    private static PlacementRef placement() {
        return new PlacementRef(new TenantId(UUID.randomUUID()), new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
    }

    private static PlacementTransports transports(PlacementRef known, String transport) {
        return new PlacementTransports() {
            @Override
            public Optional<String> transportOf(PlacementRef placement) {
                return placement.equals(known) ? Optional.of(transport) : Optional.empty();
            }

            @Override
            public boolean isAgentTarget(UUID tenantId, UUID targetId) {
                return false;
            }
        };
    }

    @Override
    protected Fixture newFixture() {
        PlacementRef placement = placement();
        InMemoryRuntimeAdapter inner = new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
        RuntimeAdapter router = new RoutingRuntimeAdapter(transports(placement, PlacementTransports.SSH_DOCKER), inner,
                new Spy(null).adapter);
        return new Fixture(router, placement, inner.replicas(),
                new ConfigLocation("/app/external/spring/config", new WriterAccess.LocalFilesystem(Path.of("/dev/null")),
                        "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION),
                true, TestHashers.SHA256);
    }

    private static RuntimeOperationContext context(TargetReplica replica) {
        ApplyId apply = ApplyId.next();
        return new RuntimeOperationContext(apply, UUID.randomUUID(),
                apply.value() + ":" + Step.LIFECYCLE + ":" + replica.ref().value(), UUID.randomUUID(), Step.LIFECYCLE);
    }

    private static void touchEveryOperation(RuntimeAdapter router, PlacementRef p, TargetReplica r) {
        RenderedConfig c = TestHashers.rendered("x: 1\n");
        RuntimeOperationContext ctx = context(r);
        router.replicas(p);
        router.prepare(p, 1);
        router.prepare(p, 1, ctx);
        router.materializeConfig(r, 1, c);
        router.materializeConfig(r, 1, c, ctx);
        router.rematerializeIfCurrent(r, 2, c, c.hash());
        router.rematerializeIfCurrent(r, 2, c, c.hash(), ctx);
        router.startOrUpdate(r, 1);
        router.startOrUpdate(r, 1, ctx);
        router.stop(r);
        router.stop(r, ctx);
        router.status(r);
        router.assessRollback(p, 1, c.hash());
    }

    @Test
    void anSshPlacementNeverReachesTheAgentAdapter() {
        PlacementRef p = placement();
        InMemoryRuntimeAdapter inner = new InMemoryRuntimeAdapter(p, true, "replica-1");
        Spy ssh = new Spy(inner);
        Spy agent = new Spy(null);
        RuntimeAdapter router = new RoutingRuntimeAdapter(transports(p, PlacementTransports.SSH_DOCKER), ssh.adapter, agent.adapter);

        touchEveryOperation(router, p, inner.replicas().get(0));

        assertTrue(agent.calls.isEmpty(), "an ssh-docker placement reached the agent adapter: " + agent.calls);
        assertTrue(ssh.calls.contains("startOrUpdate") && ssh.calls.contains("materializeConfig"), ssh.calls.toString());
    }

    @Test
    void anAgentPlacementNeverReachesSsh() {
        PlacementRef p = placement();
        InMemoryRuntimeAdapter inner = new InMemoryRuntimeAdapter(p, true, "replica-1");
        Spy ssh = new Spy(null);
        Spy agent = new Spy(inner);
        RuntimeAdapter router = new RoutingRuntimeAdapter(transports(p, PlacementTransports.AGENT), ssh.adapter, agent.adapter);

        touchEveryOperation(router, p, inner.replicas().get(0));
        assertTrue(ssh.calls.isEmpty(), "an agent placement reached SSH: " + ssh.calls);
        assertTrue(agent.calls.contains("startOrUpdate") && agent.calls.contains("rematerializeIfCurrent"), agent.calls.toString());
        router.readConfig(inner.replicas().get(0)).close();
        assertTrue(agent.calls.contains("readConfig"), "readConfig is routed by the placement too");
    }

    /** The mutation this guards: defaulting an unknown transport to SSH. */
    @Test
    void anUnknownTransportIsRefusedNotDefaulted() {
        PlacementRef p = placement();
        Spy ssh = new Spy(null);
        Spy agent = new Spy(null);
        RuntimeAdapter router = new RoutingRuntimeAdapter(transports(p, "kubernetes"), ssh.adapter, agent.adapter);
        TargetReplica r = new TargetReplica(p, new ReplicaRef("replica-1"));

        assertEquals("placement.transport.unknown",
                assertThrows(RuntimeAdapterException.class, () -> router.status(r)).errorClass().token());
        assertEquals("placement.transport.unknown",
                assertThrows(RuntimeAdapterException.class, () -> router.prepare(p, 1, context(r))).errorClass().token());
        assertTrue(ssh.calls.isEmpty() && agent.calls.isEmpty(), "neither adapter was called");
    }

    @Test
    void aPlacementTheRegistryDoesNotHoldIsUnresolvableForBothTransports() {
        Spy ssh = new Spy(null);
        Spy agent = new Spy(null);
        RuntimeAdapter router = new RoutingRuntimeAdapter(transports(placement(), PlacementTransports.AGENT), ssh.adapter,
                agent.adapter);
        PlacementRef other = placement();
        assertEquals("placement.unresolvable",
                assertThrows(RuntimeAdapterException.class, () -> router.replicas(other)).errorClass().token());
        assertTrue(ssh.calls.isEmpty() && agent.calls.isEmpty());
    }
}
