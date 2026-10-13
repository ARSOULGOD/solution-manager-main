package com.solace.sm.dp.metrics.collect.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricExec;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRead;
import com.solace.sm.dp.metrics.collect.fetch.MetricFetcher.RestartRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.2b (M-Q2, M-Q9): the SSH-to-loopback fetcher, driven by a SPY session. There is no SSH server in this
 * module; what is proven here is what the fetcher asks the session to run (nothing, for a refused request; exactly the
 * fixed command otherwise) and what it makes of the answer. The pooled session itself is {@code SshSessionPoolTest}'s.
 */
class SshMetricFetcherTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID TARGET = UUID.randomUUID();
    private static final PlacementRef PLACEMENT = new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()),
            new PlacementId(UUID.randomUUID()));
    private static final String ID = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9";

    // ---- doubles ---------------------------------------------------------------------------------------------

    private static final class Spy implements HostMetricExec {
        final List<String> commands = new ArrayList<>();
        final List<SshDockerPlacement> placements = new ArrayList<>();
        ProbeResult curlAnswer = new ProbeResult(0, 200, "7".getBytes(StandardCharsets.UTF_8));
        InspectResult inspectAnswer = new InspectResult(0, ID + " 2026-09-29T07:04:39.123456789Z\n");
        RuntimeAdapterException failure;

        @Override
        public ProbeResult curl(SshDockerPlacement placement, HostMetricProbe.Curl command) {
            commands.add(command.command());
            placements.add(placement);
            if (failure != null) {
                throw failure;
            }
            return curlAnswer;
        }

        @Override
        public InspectResult inspect(SshDockerPlacement placement, HostMetricProbe.Inspect command) {
            commands.add(command.command());
            placements.add(placement);
            if (failure != null) {
                throw failure;
            }
            return inspectAnswer;
        }
    }

    private int lookups;
    private AcquisitionMode mode = AcquisitionMode.VIA_SSH;
    private RuntimeAdapterException lookupFailure;
    private final Spy spy = new Spy();

    private SshDockerPlacement placement() {
        return new SshDockerPlacement(TENANT, "host.example", 22, "demo", UUID.randomUUID(),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache-1", "/srv/config", "/srv/config", "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, mode, null);
    }

    private SshMetricFetcher fetcher(Clock clock, FetchProperties properties) {
        return new SshMetricFetcher(ref -> {
            lookups++;
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            return placement();
        }, spy, properties, clock);
    }

    private SshMetricFetcher fetcher() {
        return fetcher(Clock.systemUTC(), FetchProperties.defaults());
    }

    private static FetchRequest request(String path, Map<String, String> query) {
        return new FetchRequest(PLACEMENT, TARGET, 8081, path, query);
    }

    private static RuntimeAdapterException failure(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }

    private static final class SteppingClock extends Clock {
        private final Instant[] instants;
        private int next;

        SteppingClock(Instant... instants) {
            this.instants = instants;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instants[Math.min(next++, instants.length - 1)];
        }
    }

    // ---- the metric fetch ------------------------------------------------------------------------------------

    @Test
    void theFetcherRunsExactlyTheFixedCurlOnTheHostLoopbackAtTheMappedPort() {
        FetchResult result = fetcher().fetch(request("/api/cache/size", Map.of("filterByNamespace", "false")));

        FetchResult.Ok ok = assertInstanceOf(FetchResult.Ok.class, result);
        assertEquals("7", new String(ok.body(), StandardCharsets.UTF_8));
        assertEquals(List.of("curl --silent --show-error --max-time 5 --output - --write-out '\\n%{http_code}' "
                + "'http://127.0.0.1:8081/api/cache/size?filterByNamespace=false'"), spy.commands);
    }

    @Test
    void aShellMetacharacterInQueryParamsNeverReachesExec() {
        // Review Focus 3: $(id) & ' ; ` space \n % - each refused; the spy sees no exec and the catalog no lookup
        for (String hostile : new String[] {"$(id)", "a&b", "a'b", "a;b", "a`id`", "a b", "a\nb", "a%20b", "a|b",
                "a\"b", "a\\b", "${HOME}"}) {
            assertEquals(new FetchResult.Transport("fetch.refused"),
                    fetcher().fetch(request("/x", Map.of("k", hostile))), hostile);
            assertEquals(new FetchResult.Transport("fetch.refused"),
                    fetcher().fetch(request("/x", Map.of(hostile, "v"))), hostile);
        }
        assertTrue(spy.commands.isEmpty(), "no exec: " + spy.commands);
        assertEquals(0, lookups, "no binding was even looked up");
    }

    @Test
    void aPathOutsideTheGrammarIsRefusedBeforeAnySession() {
        for (String path : new String[] {"", "x", "/a;b", "/a b", "/a?x=1", "/$(id)", "/a`id`", "/a'b", "/é"}) {
            assertEquals(new FetchResult.Transport("fetch.refused"), fetcher().fetch(request(path, Map.of())), path);
        }
        assertEquals(new FetchResult.Transport("fetch.refused"),
                fetcher().fetch(new FetchRequest(PLACEMENT, TARGET, 70000, "/x", Map.of())));
        assertTrue(spy.commands.isEmpty());
        assertEquals(0, lookups);
    }

    @Test
    void aDownAnswerIsItsHttpStatusNotATransportFailure() {
        // M19, measured: without --fail a 503 exits 0 with its status; refused exits 7; timeout 28
        spy.curlAnswer = new ProbeResult(0, 503, "{\"status\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(new FetchResult.Http(503), fetcher().fetch(request("/actuator/health", Map.of())));

        spy.curlAnswer = new ProbeResult(0, 404, new byte[0]);
        assertEquals(new FetchResult.Http(404), fetcher().fetch(request("/nope", Map.of())));

        spy.curlAnswer = new ProbeResult(7, 0, new byte[0]);
        assertEquals(new FetchResult.Transport("connect.failed"), fetcher().fetch(request("/x", Map.of())));

        spy.curlAnswer = new ProbeResult(28, 0, new byte[0]);
        assertEquals(new FetchResult.Transport("timeout"), fetcher().fetch(request("/x", Map.of())));

        spy.curlAnswer = new ProbeResult(56, 0, new byte[0]);
        assertEquals(new FetchResult.Transport("io.failed"), fetcher().fetch(request("/x", Map.of())));
    }

    @Test
    void metricsSharingAUrlShareOneFetch() {
        List<FetchRequest> requests = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(request("/api/cache/stats", Map.of()));
        }
        requests.add(request("/api/cache/size", Map.of("filterByNamespace", "false")));

        Map<FetchRequest, FetchResult> answers = fetcher().fetchAll(requests);

        assertEquals(2, answers.size());
        assertEquals(2, spy.commands.size(), "one exec per distinct URL: " + spy.commands);
        assertEquals(2, lookups);
    }

    @Test
    void theTimestampIsTheMidpointAndReceivedAtIsKept() {
        Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
        Instant t1 = t0.plusMillis(400);
        FetchResult.Ok ok = assertInstanceOf(FetchResult.Ok.class,
                fetcher(new SteppingClock(t0, t1), FetchProperties.defaults()).fetch(request("/x", Map.of())));

        assertEquals(t0.plusMillis(200), ok.ts());
        assertEquals(t1, ok.receivedAt());
    }

    @Test
    void aBodyLargerThanTheCapIsATransportFailureNeverATruncatedBody() {
        spy.curlAnswer = new ProbeResult(0, 200, "x".repeat(200).getBytes(StandardCharsets.UTF_8));
        FetchProperties small = new FetchProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), 100);

        assertEquals(new FetchResult.Transport("body.too.large"),
                fetcher(Clock.systemUTC(), small).fetch(request("/x", Map.of())));

        spy.failure = failure("ssh.probe.output.too.large");
        assertEquals(new FetchResult.Transport("body.too.large"), fetcher().fetch(request("/x", Map.of())));
    }

    @Test
    void aPoolOrConnectionFailureIsATransportClassifierNeverAnException() {
        for (String token : new String[] {"ssh.connection.failed", "ssh.credential.unavailable", "ssh.connection.lost"}) {
            spy.failure = failure(token);
            assertEquals(new FetchResult.Transport(token), fetcher().fetch(request("/x", Map.of())), token);
        }
        spy.failure = null;
        lookupFailure = failure("placement.unresolvable");
        assertEquals(new FetchResult.Transport("placement.unresolvable"), fetcher().fetch(request("/x", Map.of())));
    }

    @Test
    void thePoolsLoadTokensAreADeferralNeverAHostFailure() {
        // W3-02: busy and closed say the DP had no slot, not that the host failed
        for (String token : new String[] {"ssh.pool.busy", "ssh.pool.closed"}) {
            spy.failure = failure(token);
            assertEquals(new FetchResult.Deferred(token), fetcher().fetch(request("/x", Map.of())), token);
        }
    }

    @Test
    void aDirectModePlacementIsNeverScrapedOverSsh() {
        mode = AcquisitionMode.DIRECT_HTTP;

        assertEquals(new FetchResult.Transport("mode.mismatch"), fetcher().fetch(request("/x", Map.of())));
        assertEquals(new RestartRead.Failed("mode.mismatch"),
                fetcher().restartToken(new RestartRequest(PLACEMENT, TARGET, null)));
        assertTrue(spy.commands.isEmpty(), "no automatic fallback, either way (M-Q2)");
    }

    // ---- the restart token ----------------------------------------------------------------------------------

    private RestartRead token() {
        return fetcher().restartToken(new RestartRequest(PLACEMENT, TARGET, null));
    }

    @Test
    void theSshRestartTokenIsTheContainerIdAndStartedAtFromTheFixedInspect() {
        assertEquals(new RestartRead.Token(ID + " 2026-09-29T07:04:39.123456789Z"), token());
        assertEquals(List.of("timeout --kill-after=2 5 docker inspect --format '{{.Id}} {{.State.StartedAt}}' -- cache-1"),
                spy.commands, "the container name is the placement row's, nothing else; bounded by the fetch timeout (W2-03)");
    }

    @Test
    void aDeclaredRestartSignalIsIgnoredInSshModeWhereTheSignalIsTheContainersOwn() {
        RestartRead read = fetcher().restartToken(new RestartRequest(PLACEMENT, TARGET,
                new RestartRequest.Signal(8081, "/actuator/metrics/process.start.time", "/measurements/0/value")));

        assertInstanceOf(RestartRead.Token.class, read);
        assertEquals(1, spy.commands.size());
        assertTrue(spy.commands.get(0).contains("docker inspect"), spy.commands.toString());
    }

    @Test
    void aFailedInspectIsAFailedReadNeverAnInferredReset() {
        spy.inspectAnswer = new HostMetricExec.InspectResult(1, "");
        assertEquals(new RestartRead.Failed("docker.inspect.failed"), token());

        for (String garbage : new String[] {"", "no spaces\n", ID + "\n", "zz " + "2026-09-29T07:04:39Z\n",
                ID + " not-a-time\n", ID + " 2026-09-29T07:04:39Z extra\n", ID + " 2026-09-29T07:04:39Z\nsecond\n",
                ID.toUpperCase() + " 2026-09-29T07:04:39Z\n"}) {
            spy.inspectAnswer = new HostMetricExec.InspectResult(0, garbage);
            assertEquals(new RestartRead.Failed("docker.inspect.invalid"), token(), garbage);
        }

        spy.failure = failure("ssh.pool.busy");
        assertEquals(new RestartRead.Failed("ssh.pool.busy"), token());
    }

    @Test
    void anInspectTheHostTimedOutOnIsATimeoutNeverAnInferredReset() {
        // W2-03: timeout(1) exits 124 when the command ran out of time and 137 when it had to be killed after the grace
        for (int exit : new int[] {124, 137}) {
            spy.inspectAnswer = new HostMetricExec.InspectResult(exit, "");
            assertEquals(new RestartRead.Failed("timeout"), token(), "exit " + exit);
        }
        spy.failure = failure("timeout"); // the pool's own deadline (a transport that stopped answering)
        assertEquals(new RestartRead.Failed("timeout"), token());
    }

    @Test
    void aContainerNameOutsideTheGrammarNeverReachesExec() {
        // SshDockerPlacement's own grammar already refuses this; the builder is the second line
        assertTrue(spy.commands.isEmpty());
        RuntimeAdapterException e = org.junit.jupiter.api.Assertions.assertThrows(RuntimeAdapterException.class,
                () -> HostMetricProbe.inspect("a;rm -rf /", Duration.ofSeconds(5)));
        assertEquals("ssh.inspect.refused", e.errorClass().token());
    }
}
