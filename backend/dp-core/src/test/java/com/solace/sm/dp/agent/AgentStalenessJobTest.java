package com.solace.sm.dp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 015 T4.6 (OR-140): the staleness job's pass isolation and lifecycle, over fakes (the database half is AgentReportIntakeIT). */
class AgentStalenessJobTest {

    private final List<String> calls = new ArrayList<>();
    private final List<UUID> tenants = new ArrayList<>();
    private RuntimeException enumerationFailure;
    private UUID failingTenant;
    private UUID failingInstance;

    private static UUID instanceOf(UUID tenant) {
        return new UUID(tenant.getMostSignificantBits(), 1);
    }

    private final AgentStalenessJob.Source source = new AgentStalenessJob.Source() {
        @Override
        public Instant cutoff() {
            return Instant.parse("2026-10-12T00:00:00Z");
        }

        @Override
        public List<UUID> tenants(Instant cutoff) {
            calls.add("tenants");
            if (enumerationFailure != null) {
                throw enumerationFailure;
            }
            return List.copyOf(tenants);
        }

        @Override
        public List<UUID> instances(UUID tenantId, Instant cutoff) {
            if (tenantId.equals(failingTenant)) {
                throw new IllegalStateException("db down");
            }
            return List.of(instanceOf(tenantId));
        }

        @Override
        public void mark(UUID organizationId, UUID instanceId) {
            calls.add("mark:" + organizationId);
            if (instanceId.equals(failingInstance)) {
                throw new IllegalStateException("db down");
            }
        }
    };

    private AgentStalenessJob job(boolean enabled) {
        return new AgentStalenessJob(source, enabled, Duration.ofMillis(50));
    }

    @Test
    void aPassMarksEveryOrganizationsSilentInstances() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        tenants.addAll(List.of(a, b));
        job(true).runOnce();
        assertTrue(calls.contains("mark:" + a) && calls.contains("mark:" + b), calls.toString());
    }

    @Test
    void aFailedEnumerationIsSwallowedAndTheNextPassAsksAgain() {
        enumerationFailure = new IllegalStateException("db down");
        AgentStalenessJob job = job(true);
        job.runOnce();
        enumerationFailure = null;
        tenants.add(UUID.randomUUID());
        job.runOnce();
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("mark:")), calls.toString());
    }

    @Test
    void oneTenantsFailureAndOneInstancesFailureDoNotStopTheRest() {
        UUID bad = UUID.randomUUID();
        UUID flaky = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        failingTenant = bad;
        failingInstance = instanceOf(flaky);
        tenants.addAll(List.of(bad, flaky, good));
        job(true).runOnce();
        assertFalse(calls.contains("mark:" + bad));
        assertTrue(calls.contains("mark:" + flaky) && calls.contains("mark:" + good), calls.toString());
    }

    @Test
    void aDisabledJobNeverRunsAndAnEnabledOneRunsAndStops() throws Exception {
        tenants.add(UUID.randomUUID());
        AgentStalenessJob disabled = job(false);
        disabled.start();
        assertFalse(disabled.isRunning());
        Thread.sleep(150);
        assertTrue(calls.isEmpty());
        AgentStalenessJob enabled = job(true);
        enabled.start();
        assertTrue(enabled.isRunning());
        long deadline = System.currentTimeMillis() + 5000;
        while (calls.stream().noneMatch(c -> c.startsWith("mark:")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        enabled.stop();
        assertFalse(enabled.isRunning());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("mark:")));
    }

    @Test
    void aNonPositiveIntervalIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new AgentStalenessJob(source, true, Duration.ZERO));
    }
}
