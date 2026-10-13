package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RoutingRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerRuntimeAdapter;
import com.solace.sm.dp.apply.worker.ApplyRecoveryStatus;
import com.solace.sm.dp.apply.worker.ApplyWorkerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The production-wiring honesty proof, INVERTED by plan 010 T6.2 (it used to prove the worker reported itself NOT
 * running because no {@code ApplyExecutor} existed in production): {@code SolutionManagerApplication}, composed EXACTLY
 * as production composes it (no test executor supplied, no property set), boots with exactly ONE executor, over the SSH
 * Docker adapter, and the durable apply worker and the startup recovery sweep both report themselves running. The old
 * "no ApplyExecutor bean is configured" WARN must not appear.
 *
 * <p>The class keeps its name so the history of the claim it carries stays one file; the seven ITs that bring their own
 * executor withdraw the production one with {@code sm.dp.apply.production.enabled=false}.
 */
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ApplyWorkerAbsenceIT {

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.provision(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ApplyWorkerStatus workerStatus;

    @Autowired
    private ApplyRecoveryStatus recoveryStatus;

    @Test
    void theProductionBootHasExactlyOneExecutorAndTheWorkerRuns(CapturedOutput output) {
        assertEquals(1, context.getBeansOfType(ApplyExecutor.class).size(),
                "production supplies exactly one executor: two would make the worker's lookup ambiguous");
        assertEquals(1, context.getBeansOfType(RuntimeAdapter.class).size());
        // plan 015 T3.1: the one adapter bean is the per-placement router; behind it the dp-remote path is still the SSH Docker
        // adapter and the agent path is the agent adapter -- no fake and no demo adapter on either side
        RoutingRuntimeAdapter router = assertInstanceOf(RoutingRuntimeAdapter.class, context.getBean(RuntimeAdapter.class),
                "the production adapter is the router, not a fake and not the demo's");
        assertInstanceOf(SshDockerRuntimeAdapter.class, router.delegateFor("ssh-docker").orElseThrow(),
                "the dp-remote delegate is the SSH Docker one");
        assertInstanceOf(com.solace.sm.dp.apply.runtime.agent.AgentRuntimeAdapter.class, router.delegateFor("agent").orElseThrow());
        assertEquals(1, context.getBeansOfType(RenderedConfigSource.class).size());

        assertTrue(workerStatus.running(),
                () -> "the durable worker runs in production -- it reported: " + workerStatus.reason());
        assertTrue(recoveryStatus.ran(),
                () -> "the startup sweep ran in production -- it reported: " + recoveryStatus.reason());
        assertFalse(output.getAll().contains("no ApplyExecutor bean is configured"),
                "the inert-bean WARN belongs to a boot with no executor, and this boot has one");
    }
}
