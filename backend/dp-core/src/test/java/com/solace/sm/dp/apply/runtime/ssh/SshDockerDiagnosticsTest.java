package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SshDockerDiagnosticsTest {
    private static final PlacementRef PLACEMENT = new PlacementRef(new TenantId(UUID.randomUUID()),
            new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));

    @Test
    void boundsAndRedactsCredentialBearingLines() {
        SshDockerTransport transport = new SshDockerTransport() {
            @Override public ContainerSnapshot inspect(SshDockerPlacement ignored) { throw new UnsupportedOperationException(); }
            @Override public void verifyConfigDirectoryWritable(SshDockerPlacement ignored) { }
            @Override public Optional<byte[]> readFile(SshDockerPlacement ignored, String path) { return Optional.empty(); }
            @Override public void writeFileAtomically(SshDockerPlacement ignored, String path, byte[] value) { }
            @Override public void deleteFile(SshDockerPlacement ignored, String path) { }
            @Override public boolean restart(SshDockerPlacement ignored) { return false; }
            @Override public boolean stop(SshDockerPlacement ignored) { return false; }
            @Override public String logs(SshDockerPlacement ignored, int limit) { return "ready\npassword=sentinel\n"; }
        };
        SshDockerPlacement placement = new SshDockerPlacement("host.example", 22, "demo",
                Path.of("/tmp/demo-key"), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config",
                "/app/config", "application.yml", com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
        SshDockerDiagnostics diagnostics = new SshDockerDiagnostics(value -> placement, transport);

        assertEquals(java.util.List.of("ready", "[REDACTED]"), diagnostics.recentLogs(PLACEMENT, 100).lines());
    }

    @Test
    void returnsDockerTimestampsAndAnOlderPageCursor() {
        SshDockerTransport transport = new StubTransport() {
            @Override
            public String logs(SshDockerPlacement ignored, int limit, Instant after, Instant before) {
                return """
                        2026-09-15T09:00:00.000000001Z oldest
                        2026-09-15T09:00:01.000000002Z password=sentinel
                        2026-09-15T09:00:02.000000003Z newest
                        """;
            }
        };
        SshDockerDiagnostics diagnostics = new SshDockerDiagnostics(value -> placement(), transport);

        SshDockerDiagnostics.LogPage page = diagnostics.logsPage(PLACEMENT, 2, null, null);

        assertEquals(2, page.lines().size());
        assertEquals(Instant.parse("2026-09-15T09:00:01.000000002Z"), page.lines().get(0).timestamp());
        assertEquals("[REDACTED]", page.lines().get(0).message());
        assertEquals(Instant.parse("2026-09-15T09:00:01.000000002Z"), page.nextBefore());
        assertTrue(page.hasOlder());
    }

    @Test
    void followPageReturnsTheOldestUnreadLinesFirstSoABurstDoesNotCreateAGap() {
        SshDockerTransport transport = new StubTransport() {
            @Override
            public String logs(SshDockerPlacement ignored, int limit, Instant after, Instant before) {
                return """
                        2026-09-15T09:00:01Z first-unread
                        2026-09-15T09:00:02Z second-unread
                        2026-09-15T09:00:03Z remains-for-next-poll
                        """;
            }
        };
        SshDockerDiagnostics diagnostics = new SshDockerDiagnostics(value -> placement(), transport);

        SshDockerDiagnostics.LogPage page = diagnostics.logsPage(
                PLACEMENT, 2, Instant.parse("2026-09-15T09:00:00Z"), null);

        assertEquals(java.util.List.of("first-unread", "second-unread"),
                page.lines().stream().map(SshDockerDiagnostics.LogLine::message).toList());
    }

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement("host.example", 22, "demo", Path.of("/tmp/demo-key"),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/app/config", "application.yml",
                com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private abstract static class StubTransport implements SshDockerTransport {
        @Override public ContainerSnapshot inspect(SshDockerPlacement ignored) { throw new UnsupportedOperationException(); }
        @Override public void verifyConfigDirectoryWritable(SshDockerPlacement ignored) { }
        @Override public Optional<byte[]> readFile(SshDockerPlacement ignored, String path) { return Optional.empty(); }
        @Override public void writeFileAtomically(SshDockerPlacement ignored, String path, byte[] value) { }
        @Override public void deleteFile(SshDockerPlacement ignored, String path) { }
        @Override public boolean restart(SshDockerPlacement ignored) { return false; }
        @Override public boolean stop(SshDockerPlacement ignored) { return false; }
    }
}
