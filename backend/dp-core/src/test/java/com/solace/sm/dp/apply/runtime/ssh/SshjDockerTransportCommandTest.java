package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * OR-32 on the PRODUCTION transport, not a fake (B1). The status snapshot used to run {@code sha256sum} on the
 * customer host, which both reports an unkeyed digest of secret-bearing bytes and cannot be keyed without
 * putting the key on the host. The exec strings are built by package-visible statics so no SSH server is needed
 * to read them.
 */
class SshjDockerTransportCommandTest {

    private static final List<String> HASHING_PROGRAMS = List.of("sha256sum", "sha1sum", "sha512sum", "md5sum",
            "shasum", "openssl", "cksum", "b2sum", "xxhsum");

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "host.example", 22, "demo", java.util.UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "cache", "/srv/config", "/srv/config", "/app/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    @Test
    void theStatusCommandRunsNoHashingProgramOnTheHost() {
        String command = SshjDockerTransport.statusCommand(placement()).toLowerCase(java.util.Locale.ROOT);

        for (String program : HASHING_PROGRAMS) {
            assertFalse(command.contains(program), "the status exec string must not run " + program + ": " + command);
        }
        assertTrue(command.startsWith("docker inspect "), "the status exec string is the closed inspect: " + command);
    }

    @Test
    void theInspectFormatReadsAnOptionalHealthKeyThroughIndexSoNoHealthcheckIsNotDeclared() {
        // MEASURED on a real daemon: State.Health is absent for a container with no HEALTHCHECK, and a bare
        // `.State.Health` (if or with) fails "map has no entry for key Health"; `index .State "Health"` is nil-safe.
        String command = SshjDockerTransport.inspectCommand(placement());

        assertEquals("docker inspect --format '{{.Id}}|{{.Image}}|{{.State.Running}}|{{.State.StartedAt}}|"
                + "{{if index .State \"Health\"}}{{.State.Health.Status}}{{else}}not_declared{{end}}' -- cache", command);
        assertFalse(command.contains("{{if .State.Health}}") || command.contains("{{with .State.Health}}"), command);
    }

    @Test
    void theStatusCommandNamesOnlyTheGrammarCheckedContainer() {
        String command = SshjDockerTransport.statusCommand(placement());

        assertTrue(command.endsWith(" -- cache"), command);
        assertFalse(command.contains(";"), "one command, no shell chaining: " + command);
    }

    @Test
    void everyExecStringTheStatusSnapshotRunsIsRecordedAndNoneRunsAHashingProgram() {
        // statusSnapshot() routes its host exec through readStatusContainer; a second exec added there (a sha256sum
        // beside the inspect, say) reaches this recorder and fails the test, which the static string check cannot see.
        List<String> ran = new ArrayList<>();

        SshjDockerTransport.readStatusContainer(placement(), (command, limit) -> {
            ran.add(command);
            return "abc123|sha256:img|true|2026-10-05T00:00:00Z|healthy";
        });

        assertEquals(List.of(SshjDockerTransport.statusCommand(placement())), ran);
        for (String command : ran) {
            for (String program : HASHING_PROGRAMS) {
                assertFalse(command.toLowerCase(java.util.Locale.ROOT).contains(program),
                        "the status snapshot must not run " + program + ": " + command);
            }
        }
    }

    @Test
    void theDockerAvailabilityCommandIsTheClosedVersionCheck() {
        assertEquals("docker version --format '{{.Server.Version}}'", SshjDockerTransport.dockerVersionCommand());
    }

    @Test
    void ensureDockerAvailableIsANoOpForASystemdPlacement() {
        // systemd has no Docker-daemon dependency to check, and this must not need an SSH server to prove it:
        // reaching `command()` for a systemd placement would attempt a real connection and hang this test.
        SshDockerPlacement unit = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "host.example", 22, "demo",
                java.util.UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "file-connector-01.service",
                "/etc/file-connector", "/etc/file-connector", "/etc/file-connector", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH,
                null, null, "systemd");
        SshjDockerTransport transport = new SshjDockerTransport(
                (tenant, ref) -> { throw new AssertionError("no credential should be resolved for a skipped check"); },
                new com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy());

        transport.ensureDockerAvailable(unit);
    }

    @Test
    void systemdUsesTheClosedSystemctlInspectionCommand() {
        SshDockerPlacement unit = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "host.example", 22, "demo",
                java.util.UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "file-connector-01.service",
                "/etc/file-connector", "/etc/file-connector", "/etc/file-connector", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH,
                null, null, "systemd");

        assertEquals("systemctl show --property=Id --property=ActiveState --property=ActiveEnterTimestamp --value -- "
                + "file-connector-01.service", SshjDockerTransport.inspectCommand(unit));
        assertTrue(SshjDockerTransport.inspectCommand(unit).startsWith("systemctl show "));
    }

    @Test
    void systemdInspectionMapsActiveStateToTheRuntimeSnapshot() {
        SshDockerPlacement unit = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "host.example", 22, "demo",
                java.util.UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "file-connector-01.service",
                "/etc/file-connector", "/etc/file-connector", "/etc/file-connector", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH,
                null, null, "systemd");

        SshDockerTransport.ContainerSnapshot snapshot = SshjDockerTransport.readStatusContainer(unit,
                (command, limit) -> "file-connector-01.service\nactive\nMon 2026-10-12 17:00:00 UTC");

        assertTrue(snapshot.running());
        assertEquals("file-connector-01.service", snapshot.containerId());
        assertEquals("systemd", snapshot.imageId());
    }
}
