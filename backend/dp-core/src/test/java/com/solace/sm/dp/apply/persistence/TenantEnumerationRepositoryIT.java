package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Task 4b's tenant enumerator — {@code I5}'s one stated exception, and a superset of "tenants with
 * RECOVERABLE work", proven both ways.
 */
@Testcontainers(disabledWithoutDocker = true)
class TenantEnumerationRepositoryIT {

    private TenantEnumerationRepository repo() {
        return new TenantEnumerationRepository(ApplyEngineHarness.jdbc());
    }

    @Test
    void enumeratesATenantWithARequestedRow() {
        UUID tenant = UUID.randomUUID();
        seed(tenant, "REQUESTED");

        assertTrue(tenants().contains(tenant));
    }

    @Test
    void enumeratesATenantWithARunningRow() {
        UUID tenant = UUID.randomUUID();
        seed(tenant, "RUNNING");

        assertTrue(tenants().contains(tenant));
    }

    /**
     * The superset property, asserted rather than glossed: {@code resume}/{@code resumeRunning}
     * still do not resume {@code APPLY_INDETERMINATE} (plan 010 T6.8 hands it to the reconciler instead - see
     * {@code IndeterminateReconcilerIT.aTenantWhoseOnlyNonTerminalRowIsApplyIndeterminateIsEnumeratedAndThenReconciled},
     * which is what this enumeration now exists to feed), and the ENUMERATOR must surface that
     * tenant, because it is a stated superset of the recoverable set, not the recoverable set
     * itself.
     */
    @Test
    void enumeratesATenantWhoseOnlyNonTerminalRowIsApplyIndeterminate() {
        UUID tenant = UUID.randomUUID();
        seed(tenant, "APPLY_INDETERMINATE");

        assertTrue(tenants().contains(tenant));
    }

    @Test
    void aTenantWithOnlyTerminalWorkIsNotEnumerated() {
        UUID tenant = UUID.randomUUID();
        seed(tenant, "APPLIED");

        assertFalse(tenants().contains(tenant));
    }

    /**
     * The same predicate exercised two ways: every tenant this repository returns has a non-empty
     * {@link ApplyRequestRepository#nonTerminal}, and every tenant it omits has an empty one — the
     * behavioural proof that this class reuses that state set rather than a second, driftable copy.
     *
     * <p><b>MINOR fix (post-review): every blocking state, not just two.</b> The version of this
     * test the review found seeded only {@code REQUESTED} and {@code APPLIED} — a version of
     * {@code ApplyRequestRepository.BLOCKING_STATES} that dropped {@code RUNNING} or
     * {@code APPLY_INDETERMINATE} would have stayed green. All three blocking states, plus a second
     * terminal state, are seeded now — closing that gap on top of the structural fix
     * ({@code BLOCKING_STATES} is derived from {@link RequestState#blocksScope()} directly, the same
     * method this class filters {@link RequestState#values()} by, rather than a second hand-typed
     * literal — see that field's own javadoc).
     */
    @Test
    void everyEnumeratedTenantHasNonEmptyNonTerminalWorkPerTheRequestRepository() {
        UUID requested = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        UUID indeterminate = UUID.randomUUID();
        UUID applied = UUID.randomUUID();
        UUID failedNoEffect = UUID.randomUUID();
        seed(requested, "REQUESTED");
        seed(running, "RUNNING");
        seed(indeterminate, "APPLY_INDETERMINATE");
        seed(applied, "APPLIED");
        // Terminal, but a state 'apply' may actually hold -- state_fits_operation's ELSE branch
        // forbids only STOPPED/STOP_FAILED for this operation, so a second terminal example needs
        // one of the others rather than either of those two.
        seed(failedNoEffect, "FAILED_NO_EFFECT");

        List<UUID> enumerated = tenants();
        assertTrue(enumerated.contains(requested));
        assertTrue(enumerated.contains(running));
        assertTrue(enumerated.contains(indeterminate));
        assertFalse(enumerated.contains(applied));
        assertFalse(enumerated.contains(failedNoEffect));

        assertFalse(ApplyEngineHarness.requests().nonTerminal(new TenantId(requested)).isEmpty());
        assertFalse(ApplyEngineHarness.requests().nonTerminal(new TenantId(running)).isEmpty());
        assertFalse(ApplyEngineHarness.requests().nonTerminal(new TenantId(indeterminate)).isEmpty());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(new TenantId(applied)).isEmpty());
        assertTrue(ApplyEngineHarness.requests().nonTerminal(new TenantId(failedNoEffect)).isEmpty());
    }

    // ---- the second tenant list (OR-49, plan 010 T2.4 Step 3b) -----------------------------------------------

    /**
     * {@code OR-49}: the second list yields the tenants with an ACTIVE instance and no other. A tenant whose only
     * instances are {@code pending} or {@code abandoned} is not on it (D-KEY 5.4 prices the consequence).
     */
    @Test
    void theSecondListReadsOnlyTheTenantIdOfActiveInstances() {
        UUID pendingOnly = UUID.randomUUID();
        UUID abandonedOnly = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID mixed = UUID.randomUUID();
        seedInstance(pendingOnly, "pending");
        seedInstance(abandonedOnly, "abandoned");
        seedInstance(active, "active");
        seedInstance(mixed, "pending");
        seedInstance(mixed, "active");
        seedInstance(mixed, "abandoned");

        List<UUID> yielded = repo().tenantsWithActiveInstances().stream().map(TenantId::value)
                .collect(Collectors.toList());

        assertFalse(yielded.contains(pendingOnly), "pending is not active");
        assertFalse(yielded.contains(abandonedOnly), "abandoned is not active");
        assertTrue(yielded.contains(active));
        assertTrue(yielded.contains(mixed));
        assertEquals(1, yielded.stream().filter(mixed::equals).count(), "DISTINCT: a tenant once");
    }

    /** The B4 case: an idle tenant, with an active instance and no apply in flight, is reachable. */
    @Test
    void anIdleTenantWithAnActiveInstanceIsYielded() {
        UUID idle = UUID.randomUUID();
        seedInstance(idle, "active");

        assertFalse(tenants().contains(idle), "the FIRST list sees no apply_request row for it");
        assertTrue(repo().tenantsWithActiveInstances().stream().map(TenantId::value).anyMatch(idle::equals),
                "the second list reaches it");
    }

    private void seedInstance(UUID tenant, String lifecycle) {
        SmDataPostgres.bootstrap();
        UUID instance = UUID.randomUUID();
        var jdbc = ApplyEngineHarness.jdbc();
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id,"
                        + " catalog_solution_id, solution, declared_package_version, name, lifecycle,"
                        + " acquisition_mode, created_by, created_at) VALUES (:t, :i, :e, :c, 'cache', '2.4', :n,"
                        + " 'pending', 'via-ssh', :u, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("e", UUID.randomUUID()).addValue("c", UUID.randomUUID())
                        .addValue("n", "i-" + instance).addValue("u", UUID.randomUUID()));
        if (!"pending".equals(lifecycle)) {
            jdbc.update("UPDATE sm_data.instance_state SET lifecycle = :l WHERE tenant_id = :t AND instance_id = :i",
                    new MapSqlParameterSource().addValue("l", lifecycle).addValue("t", tenant)
                            .addValue("i", instance));
        }
    }

    /**
     * {@link ApplyRows#insertRequest} only ever births a row {@code REQUESTED} — every other state
     * is reached by {@link ApplyRequestRepository#transition}, exactly as production requires
     * ({@code apply_request.insert}'s own precondition). {@code terminal_has_timestamp} then forces
     * a non-null {@code terminalAt} for a terminal target and forbids one otherwise.
     */
    private void seed(UUID tenant, String state) {
        SmDataPostgres.bootstrap();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ApplyRows.insertScope(ApplyEngineHarness.jdbc(), tenant, instance);
        UUID applyId = ApplyRows.insertRequest(ApplyEngineHarness.jdbc(), tenant, instance, placement,
                "REQUESTED");
        RequestState target = RequestState.valueOf(state);
        if (target != RequestState.REQUESTED) {
            ApplyEngineHarness.requests().transition(new TenantId(tenant), new ApplyId(applyId),
                    RequestState.REQUESTED, target, target.isTerminal() ? Instant.now() : null);
        }
    }

    private List<UUID> tenants() {
        return repo().tenantsWithNonTerminalWork().stream().map(TenantId::value)
                .collect(Collectors.toList());
    }
}
