package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.5: the fixed command of the sshj probe, offline. The wire behaviour is
 * {@code TargetConnectionTestIT} (a real {@code sshd}); the daemon and the group are live gate L4.
 */
class SshjTargetSshProbeTest {

    @Test
    void theOnlyCommandRunIsDockerVersionWithAFixedFormatAndNoInputReachesIt() {
        assertEquals("docker version --format '{{.Server.Version}}'", SshjTargetSshProbe.DOCKER_VERSION_COMMAND);
    }

    @Test
    void theOutputBudgetIsSmallBecauseAVersionIsShort() {
        assertTrue(SshjTargetSshProbe.MAX_OUTPUT_BYTES <= 1024);
    }
}
