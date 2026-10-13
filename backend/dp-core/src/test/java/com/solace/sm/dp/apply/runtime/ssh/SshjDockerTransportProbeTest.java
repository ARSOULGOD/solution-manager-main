package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * T6.1 / M19 (measured with curl 8.7.1 against a stub answering 503, a closed port and a stalled listener):
 * without {@code --fail} a 503 exits 0 and prints its body then {@code \n503}; connection refused exits 7 and a
 * timeout exits 28, both printing {@code \n000}. With {@code --fail} a 503 exits 22, indistinguishable by exit
 * code from other failures — which is why the probe does not use it.
 */
class SshjDockerTransportProbeTest {

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement(new TenantId(UUID.randomUUID()), "host.example", 22, "demo",
                UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config",
                "/app/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aProbePathOutsideTheGrammarIsRefusedBeforeAnySession() {
        AtomicInteger credentialAsks = new AtomicInteger();
        SshjDockerTransport transport = new SshjDockerTransport((tenant, ref) -> {
            credentialAsks.incrementAndGet();
            throw new AssertionError("a session was attempted");
        }, new PermissiveTargetAddressPolicy());
        List<String> bad = List.of("", "relative", "/a;b", "/a b", "/a?x=1", "/$(id)", "/a`id`", "/a\nb", "/a'b",
                "/" + "a".repeat(256), "/a|b", "/a&b", "/é");
        for (String path : bad) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> transport.probe(placement(), 8081, path, Duration.ofSeconds(3)), path);
            assertEquals("ssh.probe.refused", e.errorClass().token(), path);
        }
        assertThrows(RuntimeAdapterException.class,
                () -> transport.probe(placement(), 8081, null, Duration.ofSeconds(3)));
        for (int port : new int[] {0, -1, 65536}) {
            assertThrows(RuntimeAdapterException.class,
                    () -> transport.probe(placement(), port, "/actuator/health", Duration.ofSeconds(3)));
        }
        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofMillis(500),
                Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS + 1)}) {
            assertThrows(RuntimeAdapterException.class,
                    () -> transport.probe(placement(), 8081, "/actuator/health", timeout));
        }
        assertEquals(0, credentialAsks.get(), "no credential was resolved, so no session could have opened");
    }

    @Test
    void theProbeIsOneFixedCurlCommandWithNoFail() {
        String command = SshjDockerTransport.probeCommand(8081, "/actuator/health", Duration.ofSeconds(3));

        assertEquals("curl --silent --show-error --max-time 3 --output - --write-out '\\n%{http_code}' "
                + "http://127.0.0.1:8081/actuator/health", command);
        assertFalse(command.contains("--fail"), "a DOWN 503 must stay distinguishable from a transport failure");
        assertFalse(command.contains(";") || command.contains("|") || command.contains("&"), command);
    }

    @Test
    void aSubSecondRemainderRoundsTheTimeoutUpNeverToZero() {
        assertTrue(SshjDockerTransport.probeCommand(80, "/", Duration.ofMillis(1500)).contains("--max-time 2 "));
    }

    @Test
    void aDownAnswerIsReportedAsItsHttpStatusNotAsATransportFailure() {
        // the measured output of: curl ... against a stub that answers 503 {"status":"DOWN"}
        HostProbe.ProbeResult down = SshjDockerTransport.parseProbe(0, bytes("{\"status\":\"DOWN\"}\n503"));

        assertEquals(0, down.exitCode(), "curl itself succeeded: it got an answer");
        assertEquals(503, down.httpStatus());
        assertEquals("{\"status\":\"DOWN\"}", new String(down.body(), StandardCharsets.UTF_8));
    }

    @Test
    void aClosedPortGivesANonZeroExitCodeAndNoStatus() {
        // measured: connection refused -> exit 7, stdout "\n000"; timeout -> exit 28, stdout "\n000"
        HostProbe.ProbeResult refused = SshjDockerTransport.parseProbe(7, bytes("\n000"));
        HostProbe.ProbeResult timedOut = SshjDockerTransport.parseProbe(28, bytes("\n000"));

        assertEquals(7, refused.exitCode());
        assertEquals(0, refused.httpStatus());
        assertEquals(0, refused.body().length);
        assertEquals(28, timedOut.exitCode());
        assertEquals(0, timedOut.httpStatus());
    }

    @Test
    void anHttp200KeepsItsBodyIncludingNewlines() {
        HostProbe.ProbeResult up = SshjDockerTransport.parseProbe(0, bytes("{\n \"status\": \"UP\"\n}\n200"));

        assertEquals(200, up.httpStatus());
        assertEquals("{\n \"status\": \"UP\"\n}", new String(up.body(), StandardCharsets.UTF_8));
    }

    @Test
    void anAnswerThatIsNotCurlsOutputIsAFailureWhenCurlClaimedSuccess() {
        for (String garbage : new String[] {"", "no newline", "body\nabc", "body\n20", "body\n2000"}) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> SshjDockerTransport.parseProbe(0, bytes(garbage)), garbage);
            assertEquals("ssh.probe.output.invalid", e.errorClass().token(), garbage);
        }
    }

    @Test
    void aMissingCurlOnTheHostIsAnExitCodeNotAnException() {
        // exit 127 (command not found) with empty stdout: reported, so the caller can classify it
        HostProbe.ProbeResult missing = SshjDockerTransport.parseProbe(127, new byte[0]);

        assertEquals(127, missing.exitCode());
        assertEquals(0, missing.httpStatus());
    }

    @Test
    void theResultDoesNotRenderTheBodyIntoALogLine() {
        HostProbe.ProbeResult r = new HostProbe.ProbeResult(0, 200, bytes("password: hunter2"));

        assertFalse(r.toString().contains("hunter2"), r.toString());
    }
}
