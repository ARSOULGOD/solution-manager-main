package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The five Engine API calls, against a real daemon.
 *
 * <p><b>The client is pointed at the daemon Testcontainers itself used</b>, read from
 * {@link DockerClientFactory}, rather than at {@code fromEnvironment()}. That is deliberate: on a
 * host running more than one daemon, {@code DOCKER_HOST}/{@code /var/run/docker.sock} and the daemon
 * Testcontainers started the probe on can be different machines, and a test that inspected the wrong
 * one would fail for a reason that has nothing to do with this client.
 */
@Testcontainers(disabledWithoutDocker = true)
class DockerEngineClientIT {

    private static GenericContainer<?> probe;
    private static DockerEngineClient client;

    @BeforeAll
    static void start() {
        probe = new GenericContainer<>(DockerImageName.parse("busybox:1.36"))
                .withCommand("sh", "-c", "while true; do sleep 3600; done");
        probe.start();
        client = DockerEngineClient.at(daemon(), Duration.ofSeconds(5), Duration.ofSeconds(20));
    }

    static URI daemon() {
        return URI.create(DockerClientFactory.instance().getTransportConfig().getDockerHost().toString());
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (probe != null) {
            probe.stop();
        }
    }

    /**
     * MEASURED, and the reason this client negotiates rather than pins: Colima's daemon is Docker
     * 29.2.1 with MinAPIVersion 1.44, so the /v1.43 the plan pinned is BELOW its floor and every
     * call is a flat 400. Docker Desktop 28.5.1 on the same host accepts 1.43 (its floor is 1.24).
     */
    @Test
    void theApiVersionIsNegotiatedIntoTheDaemonsOwnWindow() {
        String negotiated = client.negotiatedVersion();
        assertTrue(negotiated.matches("/v\\d+\\.\\d+"), negotiated);

        var daemonVersion = client.inspect(probe.getContainerId());   // proves the choice WORKS
        assertTrue(daemonVersion.path("Id").asText().startsWith(probe.getContainerId().substring(0, 8)));
    }

    @Test
    void inspectReturnsTheStateBlockTheAdapterReadsFrom() {
        var json = client.inspect(probe.getContainerId());
        assertTrue(json.path("State").path("Running").asBoolean());
        assertFalse(json.path("State").path("StartedAt").asText().isBlank());
        assertTrue(json.path("Image").asText().startsWith("sha256:"),
                "the local image id is the witness the I1 never-a-pull rule rests on");
    }

    @Test
    void inspectingAnAbsentContainerIsAClassifiedRefusal() {
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> client.inspect("sm-no-such-container"));
        assertEquals("docker.container.absent", e.errorClass().token());
        assertEquals("docker.container.absent", e.getMessage(),
                "the daemon's own 'No such container: …' text must not survive the boundary");
        assertNull(e.getCause());
    }

    @Test
    void listingByNameFindsExactlyTheProbe() {
        var found = client.list(probe.getContainerName().replaceFirst("^/", ""));
        assertEquals(1, found.size());
        assertEquals(probe.getContainerId(), found.get(0).path("Id").asText());
    }

    @Test
    void restartReturnsTrueAndStopIsIdempotent() {
        assertTrue(client.restart(probe.getContainerId(), 5));
        assertTrue(client.stop(probe.getContainerId(), 5), "204: the daemon acted");
        assertFalse(client.stop(probe.getContainerId(), 5),
                "304: already stopped — success without acting, per §2.2 rule 2");
        assertTrue(client.restart(probe.getContainerId(), 5), "restart also starts a stopped one");
    }

    @Test
    void statInContainerSeesAFileAndReportsAbsenceAsEmpty() {
        Optional<DockerEngineClient.PathStat> stat =
                client.statInContainer(probe.getContainerId(), "/etc/hostname");
        assertTrue(stat.isPresent());
        assertEquals("hostname", stat.orElseThrow().name());
        assertTrue(stat.orElseThrow().size() > 0);

        assertTrue(client.statInContainer(probe.getContainerId(), "/nope/nope").isEmpty());
    }

    @Test
    void anUnreachableDaemonIsAClassifiedRefusalRatherThanAStackTrace() {
        try (DockerEngineClient dead = DockerEngineClient.at(
                URI.create("unix:///nonexistent/docker.sock"),
                Duration.ofMillis(250), Duration.ofSeconds(1))) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> dead.inspect("anything"));
            assertEquals("docker.api.unreachable", e.errorClass().token());
            assertNull(e.getCause(), "the transport exception carries the socket path; it is dropped");
        }
    }
}
