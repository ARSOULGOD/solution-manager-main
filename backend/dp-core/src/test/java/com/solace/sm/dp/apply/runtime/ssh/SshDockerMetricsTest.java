package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SshDockerMetricsTest {
    private static final PlacementRef PLACEMENT = new PlacementRef(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));

    @Test
    void readsOnlyTheFixedCacheMetricResponses() {
        SshDockerTransport transport = new SshDockerTransport() {
            @Override public ContainerSnapshot inspect(SshDockerPlacement ignored) { throw new UnsupportedOperationException(); }
            @Override public void verifyConfigDirectoryWritable(SshDockerPlacement ignored) { }
            @Override public Optional<byte[]> readFile(SshDockerPlacement ignored, String path) { return Optional.empty(); }
            @Override public void writeFileAtomically(SshDockerPlacement ignored, String path, byte[] value) { }
            @Override public void deleteFile(SshDockerPlacement ignored, String path) { }
            @Override public boolean restart(SshDockerPlacement ignored) { return false; }
            @Override public boolean stop(SshDockerPlacement ignored) { return false; }
            @Override public String cacheStats(SshDockerPlacement ignored) {
                return "{\"hitCount\":2,\"missCount\":3,\"evictionCount\":4,\"hitRate\":0.4}";
            }
            @Override public String cacheSize(SshDockerPlacement ignored) { return "7\n"; }
            @Override public String heapUsed(SshDockerPlacement ignored) {
                return "{\"measurements\":[{\"value\":1234}]}";
            }
        };
        SshDockerPlacement placement = new SshDockerPlacement("host.example", 22, "demo", Path.of("/tmp/demo-key"),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/app/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
        SshDockerMetrics metrics = new SshDockerMetrics(value -> placement, transport);

        SshDockerMetrics.MetricsSnapshot snapshot = metrics.snapshot(PLACEMENT);

        assertEquals(2, snapshot.hitCount());
        assertEquals(3, snapshot.missCount());
        assertEquals(4, snapshot.evictionCount());
        assertEquals(0.4, snapshot.hitRate());
        assertEquals(7, snapshot.size());
        assertEquals(1234, snapshot.heapUsed());
    }
}
