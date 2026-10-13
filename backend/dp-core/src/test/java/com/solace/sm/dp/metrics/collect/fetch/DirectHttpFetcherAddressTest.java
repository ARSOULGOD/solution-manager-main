package com.solace.sm.dp.metrics.collect.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.StrictTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressProperties;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.2, OQ-3 (d): the direct fetcher runs the address policy on the target's registered host, read fresh,
 * immediately before it connects. A host that now resolves to loopback is a closed-vocabulary transport failure,
 * {@code target.address.refused}, and a listener on that address sees nothing; the control shows the same listener
 * does see a connection when the policy lets it through.
 */
class DirectHttpFetcherAddressTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID TARGET = UUID.randomUUID();
    private static final PlacementRef PLACEMENT = new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()),
            new PlacementId(UUID.randomUUID()));

    private ServerSocket listener;
    private final AtomicInteger connections = new AtomicInteger();
    private Thread acceptor;
    private volatile String registeredHost = "stored.example";

    private final TargetConnectionRows rows = new TargetConnectionRows() {
        @Override
        public void insert(TargetConnectionRecord row) {
            throw new AssertionError();
        }

        @Override
        public Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId) {
            return Optional.of(new TargetConnectionRecord(tenantId, targetId, registeredHost, 22, "demo",
                    "SHA256:AAAAAAAAAAAAAAAAAAAAAA", UUID.randomUUID(), "/srv/config", "cache", UUID.randomUUID(),
                    Instant.EPOCH, null));
        }

        @Override
        public void addHostKeys(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
            throw new AssertionError();
        }

        @Override
        public Set<String> hostKeys(UUID tenantId, UUID targetId) {
            throw new AssertionError();
        }

        @Override
        public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef) {
            throw new AssertionError();
        }
    };

    @BeforeEach
    void listen() throws IOException {
        listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(1_500);
        acceptor = new Thread(() -> {
            try {
                while (!listener.isClosed()) {
                    try (Socket accepted = listener.accept()) {
                        connections.incrementAndGet();
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // nothing connected within the window
            } catch (IOException ignored) {
                // closed by the test
            }
        }, "fetcher-address-listener");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stop() throws Exception {
        listener.close();
        acceptor.join(3_000);
    }

    private DirectHttpFetcher fetcher(TargetAddressPolicy policy) {
        return new DirectHttpFetcher(rows, FetchProperties.defaults(), Clock.systemUTC(), policy);
    }

    private FetchRequest request() {
        return new FetchRequest(PLACEMENT, TARGET, listener.getLocalPort(), "/api/cache/stats", Map.of());
    }

    private int connectionsAfterTheWindow() throws InterruptedException {
        acceptor.join(3_000);
        return connections.get();
    }

    private static TargetAddressPolicy strictWithStoredNameOnLoopback() {
        return new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted(), host -> {
            if (host.equals("stored.example")) {
                return new InetAddress[] {InetAddress.getLoopbackAddress()};
            }
            return InetAddress.getAllByName(host);
        });
    }

    @Test
    void aRegisteredNameThatNowResolvesToLoopbackIsRefusedAndNothingConnects() throws Exception {
        FetchResult result = fetcher(strictWithStoredNameOnLoopback()).fetch(request());

        assertEquals(new FetchResult.Transport("target.address.refused"), result);
        assertEquals(0, connectionsAfterTheWindow());
    }

    @Test
    void aRegisteredLiteralLoopbackAddressIsRefusedToo() throws Exception {
        registeredHost = "127.0.0.1";

        FetchResult result = fetcher(new StrictTargetAddressPolicy(TargetAddressProperties.unrestricted()))
                .fetch(request());

        assertEquals(new FetchResult.Transport("target.address.refused"), result);
        assertEquals(0, connectionsAfterTheWindow());
    }

    @Test
    void theRestartTokenReadIsRefusedTheSameWayAndIsAFailedReadNeverAnInferredReset() throws Exception {
        MetricFetcher.RestartRead read = fetcher(strictWithStoredNameOnLoopback()).restartToken(
                new MetricFetcher.RestartRequest(PLACEMENT, TARGET,
                        new MetricFetcher.RestartRequest.Signal(listener.getLocalPort(), "/info", "/boot")));

        assertEquals(new MetricFetcher.RestartRead.Failed("target.address.refused"), read);
        assertEquals(0, connectionsAfterTheWindow());
    }

    @Test
    void controlThePermissivePolicyLetsTheSameFetchConnect() throws Exception {
        registeredHost = "127.0.0.1";

        FetchResult result = fetcher(new PermissiveTargetAddressPolicy()).fetch(request());

        assertInstanceOf(FetchResult.Transport.class, result);
        assertTrue(connectionsAfterTheWindow() >= 1, "the listener must have seen the connection");
    }
}
