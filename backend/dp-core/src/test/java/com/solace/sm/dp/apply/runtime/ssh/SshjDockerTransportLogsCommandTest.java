package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T9.1 / M11, MEASURED on Docker 28.5.1 (docs/tasks/t9-1-measured-facts.md): {@code --since} and {@code --until}
 * are inclusive; {@code --tail N} is applied to the WHOLE log BEFORE {@code --until}, so {@code --until X --tail N}
 * answers nothing unless the window lies in the last N lines; {@code --tail} before {@code --since} is the same as
 * after it; the container's stderr is written to the exec's stderr, so it is merged ({@code 2>&1}) or never read. The
 * exec string is built by a package-visible static so no SSH server is needed to read it.
 */
class SshjDockerTransportLogsCommandTest {

    private static final Instant A = Instant.parse("2026-10-06T06:25:19.038730088Z");
    private static final Instant B = Instant.parse("2026-10-06T06:25:21Z");

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement(new TenantId(UUID.randomUUID()), "host.example", 22, "demo", UUID.randomUUID(),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "sm-orders", "/srv/config", "/srv/config", "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    @Test
    void theLatestPageIsATailMergedWithStderr() {
        assertEquals("docker logs --timestamps --tail 200 -- sm-orders 2>&1",
                SshjDockerTransport.logsCommand(placement(), 200, null, null));
    }

    @Test
    void afterIsASinceAndKeepsTheTail() {
        assertEquals("docker logs --timestamps --tail 50 --since '2026-10-06T06:25:19.038730088Z' -- sm-orders 2>&1",
                SshjDockerTransport.logsCommand(placement(), 50, A, null));
    }

    @Test
    void beforeIsAnUntilWithNoTailBecauseTheTailWouldBeAppliedFirst() {
        String command = SshjDockerTransport.logsCommand(placement(), 50, null, B);

        assertEquals("docker logs --timestamps --until '2026-10-06T06:25:21Z' -- sm-orders 2>&1", command);
        assertFalse(command.contains("--tail"), "M11: --tail runs before --until, so it would empty the window");
    }

    @Test
    void aWindowIsASinceAndAnUntilWithNoTail() {
        assertEquals("docker logs --timestamps --since '2026-10-06T06:25:19.038730088Z' "
                        + "--until '2026-10-06T06:25:21Z' -- sm-orders 2>&1",
                SshjDockerTransport.logsCommand(placement(), 50, A, B));
    }

    @Test
    void theLimitIsBoundedAndTheWindowMustHaveAnInterior() {
        for (int limit : new int[] {0, -1, 1001}) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> SshjDockerTransport.logsCommand(placement(), limit, null, null), "limit " + limit);
            assertEquals("ssh.docker.logs.limit.refused", e.errorClass().token());
        }
        assertEquals("ssh.docker.logs.limit.refused", assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.logsCommand(placement(), 10, B, A)).errorClass().token());
        assertEquals("ssh.docker.logs.limit.refused", assertThrows(RuntimeAdapterException.class,
                () -> SshjDockerTransport.logsCommand(placement(), 10, A, A)).errorClass().token());
        assertTrue(SshjDockerTransport.logsCommand(placement(), 1000, null, null).contains("--tail 1000"));
    }

    @Test
    void itIsOneClosedCommandWithNoShellComposition() {
        String command = SshjDockerTransport.logsCommand(placement(), 200, A, B);

        for (String forbidden : new String[] {";", "|", "&&", "`", "$(", "\n"}) {
            assertFalse(command.contains(forbidden), forbidden + " in " + command);
        }
        assertTrue(command.startsWith("docker logs --timestamps "));
    }
}
