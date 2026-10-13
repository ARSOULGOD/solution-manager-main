package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.StrictTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressProperties;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.2, OQ-3 (d), D-REG section 7.3: every connector that reaches a target runs the address policy on the
 * STORED host, immediately before it connects. The proof has two halves. Refusal: a stored name that now resolves to
 * loopback (a re-pointed DNS record, simulated by a stub resolver and, for {@code localhost}, by the real one) is
 * refused with the fixed classifier {@code target.address.refused}, and a real listener on that address sees no
 * connection at all. Control: with the permissive policy the same listener DOES see a connection, so the absence in
 * the first half is a measurement and not a listener that cannot be reached.
 */
class TargetAddressAtUseTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final String REFUSED = "target.address.refused";

    private ServerSocket listener;
    private final AtomicInteger connections = new AtomicInteger();
    private Thread acceptor;

    @BeforeEach
    void listen() throws IOException {
        listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(2_000);
        acceptor = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    try (Socket accepted = listener.accept()) {
                        connections.incrementAndGet();   // the peer is dropped at once: the handshake fails, which is fine
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // nothing connected within the window
            } catch (IOException ignored) {
                // closed by the test
            }
        }, "target-address-listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws Exception {
        listener.close();
        acceptor.join(3_000);
    }

    private int port() {
        return listener.getLocalPort();
    }

    /** Waits for the acceptor to give up (or see a connection), so "no connection" is read after the window. */
    private int connectionsSeenAfterTheListenerWindow() throws InterruptedException {
        acceptor.join(3_000);
        return connections.get();
    }

    private static TargetAddressPolicy strictWithStubbedNames() {
        return new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted(), host -> {
            if (host.equals("stored.example")) {
                return new InetAddress[] {InetAddress.getLoopbackAddress()};
            }
            return InetAddress.getAllByName(host);
        });
    }

    private static TargetAddressPolicy strict() {
        return new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted());
    }

    private static final SshCredentialSource NO_CREDENTIAL = (tenant, ref) -> {
        throw new AssertionError("no connection was made, so no credential may be asked for");
    };

    private SshDockerPlacement placement(String host) {
        return new SshDockerPlacement(TENANT, host, port(), "demo", UUID.randomUUID(),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config", "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private static String tokenOf(RuntimeAdapterException e) {
        return e.errorClass().token();
    }

    // ---- SshjDockerTransport ------------------------------------------------------------------------------

    @Test
    void theDockerTransportRefusesAStoredNameThatNowResolvesToLoopbackAndConnectsNowhere() throws Exception {
        SshjDockerTransport transport = new SshjDockerTransport(NO_CREDENTIAL, strictWithStubbedNames());

        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class,
                () -> transport.inspect(placement("stored.example")));

        assertEquals(REFUSED, tokenOf(refused));
        assertEquals(0, connectionsSeenAfterTheListenerWindow(), "a refused address must not be connected to");
    }

    @Test
    void theDockerTransportRefusesTheRealLocalhostToo() throws Exception {
        SshjDockerTransport transport = new SshjDockerTransport(NO_CREDENTIAL, strict());

        assertEquals(REFUSED, tokenOf(assertThrows(RuntimeAdapterException.class,
                () -> transport.inspect(placement("localhost")))));
        assertEquals(0, connectionsSeenAfterTheListenerWindow());
    }

    @Test
    void controlTheDockerTransportWithThePermissivePolicyDoesConnect() throws Exception {
        SshjDockerTransport transport = new SshjDockerTransport(NO_CREDENTIAL, new PermissiveTargetAddressPolicy());

        RuntimeAdapterException failed = assertThrows(RuntimeAdapterException.class,
                () -> transport.inspect(placement("localhost")));

        assertEquals("ssh.connection.failed", tokenOf(failed), "the listener hangs up, so the handshake fails");
        assertEquals(1, connectionsSeenAfterTheListenerWindow(), "the listener must have seen the connection");
    }

    // ---- SshjHostConnector (the pool) ---------------------------------------------------------------------

    @Test
    void thePoolConnectorRefusesAStoredNameThatNowResolvesToLoopbackAndConnectsNowhere() throws Exception {
        HostConnector connector = new SshjHostConnector(NO_CREDENTIAL, Duration.ofSeconds(30),
                strictWithStubbedNames());

        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class,
                () -> connector.connect(placement("stored.example")));

        assertEquals(REFUSED, tokenOf(refused));
        assertEquals(0, connectionsSeenAfterTheListenerWindow());
    }

    @Test
    void controlThePoolConnectorWithThePermissivePolicyDoesConnect() throws Exception {
        HostConnector connector = new SshjHostConnector(NO_CREDENTIAL, Duration.ofSeconds(30),
                new PermissiveTargetAddressPolicy());

        RuntimeAdapterException failed = assertThrows(RuntimeAdapterException.class,
                () -> connector.connect(placement("localhost")));

        assertEquals("ssh.connection.failed", tokenOf(failed));
        assertEquals(1, connectionsSeenAfterTheListenerWindow());
    }

    // ---- DirectHttpProbe -----------------------------------------------------------------------------------

    @Test
    void theDirectProbeRefusesAStoredNameThatNowResolvesToLoopbackAndConnectsNowhere() throws Exception {
        DirectHttpProbe probe = DirectHttpProbe.jdk(strictWithStubbedNames());

        RuntimeAdapterException refused = assertThrows(RuntimeAdapterException.class,
                () -> probe.get("stored.example", port(), "/actuator/health", Duration.ofSeconds(2)));

        assertEquals(REFUSED, tokenOf(refused));
        assertEquals(0, connectionsSeenAfterTheListenerWindow());
    }

    @Test
    void theDirectProbeRefusesTheRealLocalhostToo() throws Exception {
        DirectHttpProbe probe = DirectHttpProbe.jdk(strict());

        assertEquals(REFUSED, tokenOf(assertThrows(RuntimeAdapterException.class,
                () -> probe.get("localhost", port(), "/up", Duration.ofSeconds(2)))));
        assertEquals(0, connectionsSeenAfterTheListenerWindow());
    }

    @Test
    void controlTheDirectProbeWithThePermissivePolicyDoesConnect() throws Exception {
        DirectHttpProbe probe = DirectHttpProbe.jdk(new PermissiveTargetAddressPolicy());

        probe.get("localhost", port(), "/up", Duration.ofSeconds(2));

        assertTrue(connectionsSeenAfterTheListenerWindow() >= 1, "the listener must have seen the connection");
    }

    @Test
    void aNameThatDoesNotResolveIsStillUnreachableNotARefusal() throws Exception {
        DirectHttpProbe probe = DirectHttpProbe.jdk(new StrictTargetAddressPolicy(
                TargetAddressProperties.unrestricted(), host -> {
                    throw new java.net.UnknownHostException(host);
                }));

        HostProbe.ProbeResult result = probe.get("gone.example", 8081, "/up", Duration.ofSeconds(2));

        assertEquals(DirectHttpProbe.EXIT_UNREACHABLE, result.exitCode());
    }
}
