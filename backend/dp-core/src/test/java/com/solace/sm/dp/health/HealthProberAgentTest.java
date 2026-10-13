package com.solace.sm.dp.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.DirectHttpProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T3.5: the dp-remote health prober meets agent rows (V028) and says nothing about them, so the agent's report is the only
 * writer of an agent placement's health; and an agent target has no address for the direct-HTTP route.
 */
class HealthProberAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();

    private static InstanceRecord instance(AcquisitionMode mode) {
        return new InstanceRecord(TENANT, INSTANCE, UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4", "orders",
                InstanceLifecycle.ACTIVE, mode, UUID.randomUUID(), Instant.now(), 1L);
    }

    private static PlacementRecord placement() {
        return new PlacementRecord(TENANT, UUID.randomUUID(), INSTANCE, TARGET, "sm-cache", "/srv/sm/p1",
                "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10, Optional.of("actuator-health"),
                Optional.of("/actuator/health"), Optional.of(18081), Optional.of(ProbeStrength.READINESS), Instant.now());
    }

    private static TargetConnectionRows targets(TargetConnectionRecord record) {
        return (TargetConnectionRows) Proxy.newProxyInstance(HealthProberAgentTest.class.getClassLoader(),
                new Class<?>[] {TargetConnectionRows.class}, (p, method, args) -> {
                    if (method.getName().equals("find")) {
                        return Optional.ofNullable(record);
                    }
                    throw new AssertionError(method.getName());
                });
    }

    private static TargetConnectionRecord agentTarget() {
        return new TargetConnectionRecord(TENANT, TARGET, null, 0, null, null, null, "/srv/sm", "sm-", UUID.randomUUID(),
                Instant.now(), null, null, null, UUID.randomUUID());
    }

    private static HostProbe noSsh() {
        return (binding, port, path, timeout) -> {
            throw new AssertionError("no SSH may be attempted");
        };
    }

    private static DirectHttpProbe noHttp() {
        return (DirectHttpProbe) Proxy.newProxyInstance(HealthProberAgentTest.class.getClassLoader(),
                new Class<?>[] {DirectHttpProbe.class}, (p, m, a) -> {
                    throw new AssertionError("no HTTP may be attempted");
                });
    }

    @Test
    void anAgentAcquisitionIsDeferredWithoutTouchingSshHttpOrTheCatalog() {
        HealthProber prober = new HealthProber(noSsh(), ref -> {
            throw new AssertionError("the catalog is not asked");
        }, targets(null), noHttp(), Duration.ofSeconds(1));

        assertEquals(Observation.DEFERRED, prober.observe(instance(AcquisitionMode.AGENT), placement()));
    }

    @Test
    void anAgentPlacementOnAnSshAcquisitionIsDeferredNotUnreachable() {
        HealthProber prober = new HealthProber(noSsh(), ref -> {
            throw new RuntimeAdapterException(new ErrorClass("agent.operation.unsupported"));
        }, targets(null), noHttp(), Duration.ofSeconds(1));

        assertEquals(Observation.DEFERRED, prober.observe(instance(AcquisitionMode.VIA_SSH), placement()),
                "an UNREACHABLE here would be a verdict that races the agent's own");
    }

    @Test
    void anAgentTargetHasNoAddressForTheDirectRoute() {
        HealthProber prober = new HealthProber(noSsh(), ref -> {
            throw new AssertionError();
        }, targets(agentTarget()), noHttp(), Duration.ofSeconds(1));

        assertEquals(Observation.UNREACHABLE, prober.observe(instance(AcquisitionMode.DIRECT_HTTP), placement()));
    }
}
