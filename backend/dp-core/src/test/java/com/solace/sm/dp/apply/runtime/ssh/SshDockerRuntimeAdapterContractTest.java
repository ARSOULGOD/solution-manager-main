package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.AbstractRuntimeAdapterContractTest;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The runtime-neutral contract kit, over a deterministic SSH/SFTP transport double. */
class SshDockerRuntimeAdapterContractTest extends AbstractRuntimeAdapterContractTest {

    @Test
    void statusUsesTheCombinedRemoteSnapshotContract() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = new SshDockerPlacement("cache-demo.example", 22, "ec2-user",
                Path.of("/run/secrets/cache-demo.pem"),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
        FakeTransport transport = new FakeTransport(binding);
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport);

        adapter.status(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution")));

        assertEquals(1, transport.statusSnapshotReads);
    }

    @Override
    protected Fixture newFixture() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = new SshDockerPlacement("cache-demo.example", 22, "ec2-user",
                Path.of("/run/secrets/cache-demo.pem"),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
        FakeTransport transport = new FakeTransport(binding);
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport);
        TargetReplica replica = new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"));
        return new Fixture(adapter, placement, java.util.List.of(replica), binding.location(), true);
    }

    private static final class FakeTransport implements SshDockerTransport {
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final SshDockerPlacement placement;
        private boolean running = true;
        private Instant started = Instant.parse("2026-09-14T00:00:00Z");
        private int statusSnapshotReads;

        FakeTransport(SshDockerPlacement placement) {
            this.placement = placement;
        }

        @Override
        public ContainerSnapshot inspect(SshDockerPlacement ignored) {
            return new ContainerSnapshot("abcdef012345", "sha256:abcdef0123456789", running,
                    started.toString(), "healthy");
        }

        @Override
        public StatusSnapshot statusSnapshot(SshDockerPlacement ignored) {
            statusSnapshotReads++;
            return SshDockerTransport.super.statusSnapshot(ignored);
        }

        @Override
        public void verifyConfigDirectoryWritable(SshDockerPlacement ignored) {
            // The in-memory fixture is intrinsically writable; this keeps the contract focused on
            // runtime semantics while SshjDockerTransport covers the real remote preflight path.
        }

        @Override
        public Optional<byte[]> readFile(SshDockerPlacement ignored, String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        @Override
        public void writeFileAtomically(SshDockerPlacement ignored, String path, byte[] content) {
            files.put(path, content.clone());
        }

        @Override
        public void deleteFile(SshDockerPlacement ignored, String path) {
            files.remove(path);
        }

        @Override
        public boolean restart(SshDockerPlacement ignored) {
            running = true;
            started = started.plusSeconds(1);
            return true;
        }

        @Override
        public boolean stop(SshDockerPlacement ignored) {
            boolean wasRunning = running;
            running = false;
            return wasRunning;
        }
    }
}
