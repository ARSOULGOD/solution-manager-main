package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.GateVerdict;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T6.9 point 2: the SSH adapter's {@code status().health} comes from the placement's package-declared probe,
 * and from nothing else. {@code docker inspect} still supplies liveness and the start time, so "the baseline moved" is
 * unchanged; it never supplies health (no fallback, Q-G1 (a)).
 *
 * <p>The gate under test is the real {@link HealthGate}, over the real adapter, over a transport double - so these
 * assert the gate's verdict and classifier, not only the adapter's signal.
 */
class ProbeBackedHealthTest {

    private static final String UP = "{\"status\":\"UP\"}";
    private static final String DOWN = "{\"status\":\"DOWN\"}";

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final PlacementRef placement = new PlacementRef(tenant, new InstanceId(UUID.randomUUID()),
            new PlacementId(UUID.randomUUID()));
    private final TargetReplica replica = new TargetReplica(placement, new ReplicaRef("cache-solution"));
    private final Transport transport = new Transport();
    private final Direct direct = new Direct();

    private SshDockerPlacement binding(AcquisitionMode mode, HealthProbeSpec probe) {
        return new SshDockerPlacement(tenant, "cache-demo.example", 22, "ec2-user", UUID.randomUUID(),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10, mode, probe);
    }

    private static HealthProbeSpec actuator(ProbeStrength strength) {
        return new HealthProbeSpec("actuator-health", "/actuator/health", 18081, strength);
    }

    private SshDockerRuntimeAdapter adapter(SshDockerPlacement binding) {
        return new SshDockerRuntimeAdapter(ignored -> binding, transport, direct, TestHashers.keyed());
    }

    /** A gate with virtual time: it advances only when the gate sleeps. */
    private HealthGate gate(List<Duration> sleeps) {
        Clock[] now = {fixedAt(Instant.parse("2026-10-05T00:00:00Z"))};
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0].instant();
            }
        };
        return new HealthGate(clock, d -> {
            sleeps.add(d);
            now[0] = fixedAt(now[0].instant().plus(d));
        }, new ApplyEngineProperties(Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    private static Clock fixedAt(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    // ---- the probe IS the signal ---------------------------------------------------------------------------------

    @Test
    void aDeclaredProbeIsTheGatesHealthSignal() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.LIVENESS)));
        transport.dockerHealth = "not_declared";    // the image declares no HEALTHCHECK
        transport.probe = new HostProbe.ProbeResult(0, 200, UP.getBytes(StandardCharsets.UTF_8));
        var before = adapter.status(replica).baseline();
        transport.restart(null);

        GateVerdict verdict = gate(new ArrayList<>()).await(adapter, replica, before);

        assertInstanceOf(GateVerdict.Passed.class, verdict, "the probe said UP, so the gate passes: " + verdict);
        assertFalse(transport.probeCalls.isEmpty());
        assertEquals(List.of("127.0.0.1:18081/actuator/health"), transport.probeCalls.stream().distinct().toList(),
                "via-ssh reads the host's loopback at the placement's HOST-side port, and only that");
    }

    @Test
    void aDeclaredProbeIsNeverReplacedByDockerInspectHealth() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.LIVENESS)));
        transport.dockerHealth = "healthy";          // docker says healthy, the declared probe says DOWN
        transport.probe = new HostProbe.ProbeResult(0, 503, DOWN.getBytes(StandardCharsets.UTF_8));
        var before = adapter.status(replica).baseline();
        transport.restart(null);

        assertEquals(HealthSignal.UNHEALTHY, adapter.status(replica).health());
        assertInstanceOf(GateVerdict.Failed.class, gate(new ArrayList<>()).await(adapter, replica, before));

        // the probe cannot be READ (connection refused, exit 7): still not docker's "healthy"
        transport.probe = new HostProbe.ProbeResult(7, 0, new byte[0]);
        assertEquals(HealthSignal.UNREADABLE, adapter.status(replica).health());
        transport.probe = null;
        transport.probeFailure = new RuntimeAdapterException(new ErrorClass("ssh.docker.command.failed"));
        assertEquals(HealthSignal.UNREADABLE, adapter.status(replica).health(),
                "a probe that throws is unreadable, never docker inspect's health");
        GateVerdict unreadable = gate(new ArrayList<>()).await(adapter, replica, before);
        assertEquals("gate.health.unreadable", assertInstanceOf(GateVerdict.Failed.class, unreadable).why().token(),
                "the gate keeps polling to its deadline and then names the unreadable probe");
    }

    @Test
    void aProbeDownFailsTheGateAsGateUnhealthyNotATransportClassifier() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.READINESS)));
        transport.probe = new HostProbe.ProbeResult(0, 503, DOWN.getBytes(StandardCharsets.UTF_8));
        var before = adapter.status(replica).baseline();
        transport.restart(null);

        GateVerdict verdict = gate(new ArrayList<>()).await(adapter, replica, before);

        assertEquals("gate.unhealthy", assertInstanceOf(GateVerdict.Failed.class, verdict).why().token());
    }

    @Test
    void anActuatorAnswerIsHealthyOnlyForHttp200AndStatusUp() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.LIVENESS)));
        Map<String, HostProbe.ProbeResult> answers = new LinkedHashMap<>();
        answers.put("200 UP", new HostProbe.ProbeResult(0, 200, UP.getBytes(StandardCharsets.UTF_8)));
        answers.put("200 DOWN", new HostProbe.ProbeResult(0, 200, DOWN.getBytes(StandardCharsets.UTF_8)));
        answers.put("200 OUT_OF_SERVICE", new HostProbe.ProbeResult(0, 200,
                "{\"status\":\"OUT_OF_SERVICE\"}".getBytes(StandardCharsets.UTF_8)));
        answers.put("200 not json", new HostProbe.ProbeResult(0, 200, "<html>".getBytes(StandardCharsets.UTF_8)));
        answers.put("200 empty", new HostProbe.ProbeResult(0, 200, new byte[0]));
        answers.put("503 UP body", new HostProbe.ProbeResult(0, 503, UP.getBytes(StandardCharsets.UTF_8)));
        answers.put("404", new HostProbe.ProbeResult(0, 404, new byte[0]));
        for (var answer : answers.entrySet()) {
            transport.probe = answer.getValue();
            HealthSignal expected = answer.getKey().equals("200 UP") ? HealthSignal.HEALTHY : HealthSignal.UNHEALTHY;
            assertEquals(expected, adapter.status(replica).health(), answer.getKey());
        }
    }

    @Test
    void anHttpStatusProbeIsHealthyForAny2xxAndUnhealthyForAnythingElseAnswered() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH,
                new HealthProbeSpec("http-status", "/ready", 18081, ProbeStrength.READINESS)));
        for (int status : new int[] {200, 204, 299}) {
            transport.probe = new HostProbe.ProbeResult(0, status, new byte[0]);
            assertEquals(HealthSignal.HEALTHY, adapter.status(replica).health(), "status " + status);
        }
        for (int status : new int[] {301, 400, 500, 503}) {
            transport.probe = new HostProbe.ProbeResult(0, status, new byte[0]);
            assertEquals(HealthSignal.UNHEALTHY, adapter.status(replica).health(), "status " + status);
        }
    }

    @Test
    void aStoppedContainerIsUnhealthyAndTheProbeIsNotAsked() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.LIVENESS)));
        transport.stop(null);

        assertEquals(HealthSignal.UNHEALTHY, adapter.status(replica).health());
        assertTrue(transport.probeCalls.isEmpty(), "no session is spent probing a container that is down");
    }

    // ---- no executable probe: fail closed ------------------------------------------------------------------------

    @Test
    void noExecutableProbeFailsClosedEvenWithAHealthyDockerHealthcheck() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, null));
        transport.dockerHealth = "healthy";

        assertEquals(HealthSignal.NOT_DECLARED, adapter.status(replica).health(),
                "docker inspect's HEALTHCHECK is not a declared strength (Q-G1 (a))");
        PreflightResult.Ready ready = assertInstanceOf(PreflightResult.Ready.class, adapter.prepare(placement, 1));
        assertFalse(ready.healthSignalDeclared(),
                "pre-flight's healthSignalDeclared is false, so rolling-health-gated is refused before any write");
        assertTrue(transport.probeCalls.isEmpty() && direct.calls.isEmpty());
    }

    @Test
    void aDeclaredProbeMakesTheHealthSignalDeclaredAtPreflight() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.LIVENESS)));
        transport.dockerHealth = "not_declared";

        PreflightResult.Ready ready = assertInstanceOf(PreflightResult.Ready.class, adapter.prepare(placement, 1));

        assertTrue(ready.healthSignalDeclared(), "true exactly when an executable probe is declared");
    }

    // ---- direct-http ---------------------------------------------------------------------------------------------

    @Test
    void directHttpReadsTheTargetsRegisteredAddressAtThePlacementsPortAndNeverSpendsAnSshProbe() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.DIRECT_HTTP,
                actuator(ProbeStrength.LIVENESS)));
        direct.result = new HostProbe.ProbeResult(0, 200, UP.getBytes(StandardCharsets.UTF_8));

        assertEquals(HealthSignal.HEALTHY, adapter.status(replica).health());

        assertEquals(List.of("cache-demo.example:18081/actuator/health"), direct.calls,
                "OR-82: the target's registered host, the placement's health port");
        assertTrue(transport.probeCalls.isEmpty(), "the mode is instance-level: no fallback to SSH");
    }

    @Test
    void directHttpDoesNotFallBackToSshWhenTheDirectReadFails() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.DIRECT_HTTP,
                actuator(ProbeStrength.LIVENESS)));
        transport.probe = new HostProbe.ProbeResult(0, 200, UP.getBytes(StandardCharsets.UTF_8));
        direct.result = new HostProbe.ProbeResult(7, 0, new byte[0]);

        assertEquals(HealthSignal.UNREADABLE, adapter.status(replica).health());
        assertTrue(transport.probeCalls.isEmpty());
    }

    @Test
    void viaSshNeverReadsDirect() {
        SshDockerRuntimeAdapter adapter = adapter(binding(AcquisitionMode.VIA_SSH, actuator(ProbeStrength.LIVENESS)));
        transport.probe = new HostProbe.ProbeResult(0, 200, UP.getBytes(StandardCharsets.UTF_8));

        adapter.status(replica);

        assertTrue(direct.calls.isEmpty());
    }

    // ---- the command ---------------------------------------------------------------------------------------------

    @Test
    void theProbeCommandIsTheFixedHostProbeCommand() {
        assertEquals("curl --silent --show-error --max-time 5 --output - --write-out '\\n%{http_code}' "
                        + "http://127.0.0.1:18081/actuator/health",
                SshjDockerTransport.probeCommand(18081, "/actuator/health", Duration.ofSeconds(5)));
        // no --fail: with it a DOWN actuator (503) is indistinguishable from a refused connection (M19)
        assertFalse(SshjDockerTransport.probeCommand(18081, "/actuator/health", Duration.ofSeconds(5))
                .contains("--fail"));
        assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.probeCommand(18081, "/a;b", Duration.ofSeconds(5)));
        // and the adapter asks with exactly the bounded timeout the interface allows
        assertTrue(SshDockerRuntimeAdapter.PROBE_TIMEOUT.compareTo(
                Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) <= 0);
    }

    // ---- doubles -------------------------------------------------------------------------------------------------

    private static final class Direct implements DirectHttpProbe {
        final List<String> calls = new ArrayList<>();
        HostProbe.ProbeResult result = new HostProbe.ProbeResult(7, 0, new byte[0]);

        @Override
        public HostProbe.ProbeResult get(String host, int port, String path, Duration timeout) {
            calls.add(host + ":" + port + path);
            return result;
        }
    }

    private static final class Transport implements SshDockerTransport {
        final List<String> probeCalls = new ArrayList<>();
        final Map<String, byte[]> files = new LinkedHashMap<>();
        HostProbe.ProbeResult probe = new HostProbe.ProbeResult(7, 0, new byte[0]);
        RuntimeAdapterException probeFailure;
        String dockerHealth = "healthy";
        boolean running = true;
        Instant started = Instant.parse("2026-10-05T00:00:00Z");

        @Override
        public ContainerSnapshot inspect(SshDockerPlacement placement) {
            return new ContainerSnapshot("abcdef012345", "sha256:abcdef0123456789", running, started.toString(),
                    dockerHealth);
        }

        @Override
        public ProbeResult probe(SshDockerPlacement placement, int port, String path, Duration timeout) {
            probeCalls.add("127.0.0.1:" + port + path);
            if (probeFailure != null) {
                throw probeFailure;
            }
            return probe;
        }

        @Override
        public void verifyConfigDirectoryWritable(SshDockerPlacement placement) {
        }

        @Override
        public Optional<byte[]> readFile(SshDockerPlacement placement, String path) {
            return Optional.ofNullable(files.get(path)).map(byte[]::clone);
        }

        @Override
        public void writeFileAtomically(SshDockerPlacement placement, String path, byte[] content) {
            files.put(path, content.clone());
        }

        @Override
        public void deleteFile(SshDockerPlacement placement, String path) {
            files.remove(path);
        }

        @Override
        public boolean restart(SshDockerPlacement placement) {
            running = true;
            started = started.plusSeconds(60);
            return true;
        }

        @Override
        public boolean stop(SshDockerPlacement placement) {
            running = false;
            return true;
        }
    }
}
