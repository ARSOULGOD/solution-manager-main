package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 4a's claim, in isolation from the worker that drives it — the whole correctness argument is
 * "exactly one {@code UPDATE} affects a given row", and this class measures that against the real
 * journal rather than assuming it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyClaimRepositoryIT {

    private ApplyClaimRepository claims() {
        return new ApplyClaimRepository(ApplyEngineHarness.transactions(), ApplyEngineHarness.jdbc(),
                ApplyEngineHarness.requests());
    }

    private UUID seedRequestedRow(UUID tenant, UUID instance, UUID placement) {
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, instance);
        return ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, instance, placement,
                "REQUESTED");
    }

    @Test
    void winningTheClaimIsWhatMakesARequestRunning() {
        UUID tenant = UUID.randomUUID();
        UUID applyId = seedRequestedRow(tenant, UUID.randomUUID(), UUID.randomUUID());
        TenantId t = new TenantId(tenant);

        Optional<ApplyId> won = claims().claimNextAsStarted(t);

        assertTrue(won.isPresent());
        assertEquals(applyId, won.get().value());
        assertEquals("RUNNING", stateOf(tenant, applyId));
    }

    @Test
    void aTenantWithNoRequestedRowClaimsNothing() {
        TenantId t = new TenantId(UUID.randomUUID());
        SmDataPostgres.bootstrap();

        assertTrue(claims().claimNextAsStarted(t).isEmpty());
    }

    @Test
    void aRunningRowIsNeverClaimableAgainByARunningProcess() {
        UUID tenant = UUID.randomUUID();
        seedRequestedRow(tenant, UUID.randomUUID(), UUID.randomUUID());
        TenantId t = new TenantId(tenant);

        assertTrue(claims().claimNextAsStarted(t).isPresent());
        assertTrue(claims().claimNextAsStarted(t).isEmpty(),
                "the guarded UPDATE's WHERE clause refuses a row that is no longer REQUESTED");
    }

    @Test
    void theClaimTouchesOnlyStateAndTerminalAtLeavingEveryOtherColumnUntouched() {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        UUID applyId = seedRequestedRow(tenant, instance, placement);

        claims().claimNextAsStarted(new TenantId(tenant));

        var row = ApplyEngineHarness.requests().find(new com.solace.sm.dp.apply.model.TenantId(tenant),
                new ApplyId(applyId)).orElseThrow();
        assertEquals(com.solace.sm.dp.apply.model.RequestState.RUNNING, row.state());
        assertNull(row.terminalAt(), "terminal_has_timestamp: RUNNING carries no timestamp");
        assertEquals(instance, row.scope().instance().value());
        assertEquals(placement, row.scope().placement().value());
        assertEquals("operator-1", row.requestedBy());
    }

    /** The claim is the whole correctness argument: run N concurrent claimers against one row. */
    @Test
    void eightConcurrentClaimersProduceExactlyOneWinner() throws InterruptedException {
        UUID tenant = UUID.randomUUID();
        seedRequestedRow(tenant, UUID.randomUUID(), UUID.randomUUID());
        TenantId t = new TenantId(tenant);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Optional<ApplyId>>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ApplyClaimRepository repo = claims();
                        ready.countDown();
                        go.await();
                        return repo.claimNextAsStarted(t);
                    }))
                    .toList();
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            long winners = futures.stream().map(f -> {
                try {
                    return f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).filter(Optional::isPresent).count();

            assertEquals(1, winners, "exactly one of eight concurrent claimers may win the row");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * MINOR fix (post-review): {@code AdmissionService.admit} is public, and {@code admit} is the
     * only thing standing between it and a {@code restart} row today -- this proves the claim
     * itself also refuses to touch a shape {@code ApplyExecutor.runAdmitted} cannot execute, rather
     * than relying solely on that upstream refusal.
     */
    @Test
    void aRestartRowIsNeverClaimed() {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, instance);
        UUID applyId = ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, instance, placement,
                "restart", "REQUESTED");

        assertTrue(claims().claimNextAsStarted(new TenantId(tenant)).isEmpty(),
                "a restart row is a shape runAdmitted cannot execute -- the claim must leave it "
                        + "REQUESTED rather than start it");
        assertEquals("REQUESTED", stateOf(tenant, applyId));
    }

    /** As {@link #aRestartRowIsNeverClaimed}, for the other shape {@code resumeRunning} also skips. */
    @Test
    void anInstanceWideApplyRowIsNeverClaimed() {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, instance);
        UUID applyId = ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, instance,
                ApplyRows.INSTANCE_WIDE, "apply", "REQUESTED");

        assertTrue(claims().claimNextAsStarted(new TenantId(tenant)).isEmpty(),
                "an instance-wide apply row is a shape runAdmitted cannot execute -- the claim must "
                        + "leave it REQUESTED rather than start it");
        assertEquals("REQUESTED", stateOf(tenant, applyId));
    }

    /** The filter narrows the candidate SET; it must not make an otherwise-claimable row unreachable. */
    @Test
    void anOrdinaryRowIsStillClaimedEvenWhenAnOlderUnclaimableRestartRowExists() {
        UUID tenant = UUID.randomUUID();
        UUID unclaimableInstance = UUID.randomUUID();
        UUID claimableInstance = UUID.randomUUID();
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, unclaimableInstance);
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, claimableInstance);
        ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, unclaimableInstance,
                UUID.randomUUID(), "restart", "REQUESTED");
        UUID claimableApplyId = ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant,
                claimableInstance, UUID.randomUUID(), "apply", "REQUESTED");

        Optional<ApplyId> won = claims().claimNextAsStarted(new TenantId(tenant));

        assertTrue(won.isPresent(), "an ordinary claimable row must still be won");
        assertEquals(claimableApplyId, won.get().value());
    }

    @Test
    void claimingIsScopedByTenantSoOneTenantsClaimNeverTouchesAnothers() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID requestB = seedRequestedRow(tenantB, UUID.randomUUID(), UUID.randomUUID());
        SmDataPostgres.bootstrap();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenantA, UUID.randomUUID());
        // tenantA has a scope row but no REQUESTED apply_request row at all.

        assertTrue(claims().claimNextAsStarted(new TenantId(tenantA)).isEmpty());
        assertEquals("REQUESTED", stateOf(tenantB, requestB),
                "tenant A's empty claim must not disturb tenant B's untouched row");
    }

    private String stateOf(UUID tenant, UUID applyId) {
        return ApplyEngineHarness.jdbc().queryForObject(
                "SELECT state FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("t", tenant).addValue("a", applyId),
                String.class);
    }
}
