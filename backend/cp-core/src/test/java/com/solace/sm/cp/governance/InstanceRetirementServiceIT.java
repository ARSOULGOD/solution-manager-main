package com.solace.sm.cp.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcGovernanceRepository;
import com.solace.sm.cp.persistence.JdbcInstanceRetirementRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 012 T4: {@link InstanceRetirementService} against a real PostgreSQL 14 at {@code V017}, wired as
 * {@code GovernanceConfiguration} wires it, and every write made as {@code sm_cp_runtime}: a write by the migrator or
 * the owner is exempt from {@code OR-25} and would prove nothing about the role the application connects as.
 */
class InstanceRetirementServiceIT {

    private static final String KEY = "instance-retirement-service-it";
    private static final String CLIENT = "203.0.113.79";
    private static final Instant AT = Instant.parse("2026-10-07T10:15:30Z");

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "017");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
    private final JdbcInstanceRetirementRepository repository = new JdbcInstanceRetirementRepository(runtime);
    private final InstanceRetirementService retirement = new InstanceRetirementService(repository,
            new JdbcAuditContext(runtime), runtimeTx, Clock.fixed(AT, ZoneOffset.UTC));
    private final GovernanceService governance = new GovernanceService(new JdbcGovernanceRepository(runtime),
            new JdbcAuditContext(runtime), runtimeTx, UUID::randomUUID);

    @Test
    void deregisterRetiresTheNodeAndAuditsActorOperationCorrelationAndClientAddress() {
        World w = world(5);
        UUID instance = declare(w);
        RequestOrigin origin = origin();

        boolean wrote = retirement.deregister(w.actor(), origin, w.org, instance);

        assertThat(wrote).isTrue();
        Map<String, Object> node = migrator.queryForMap(
                "SELECT retired_at, retired_by FROM sm_control.instance WHERE instance_id = :i", Map.of("i", instance));
        assertThat(((java.sql.Timestamp) node.get("retired_at")).toInstant()).isEqualTo(AT);
        assertThat(node.get("retired_by")).isEqualTo(w.actorId);
        List<Map<String, Object>> rows = migrator.queryForList("""
                SELECT table_name, operation, audit_operation, actor_principal_id, correlation_id, client_address,
                       db_role FROM sm_control.change_audit WHERE correlation_id = :c
                """, Map.of("c", origin.correlationId()));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("table_name", "instance").containsEntry("operation", "UPDATE")
                .containsEntry("audit_operation", "INSTANCE_UNDECLARED")
                .containsEntry("actor_principal_id", w.actorId).containsEntry("client_address", CLIENT)
                .containsEntry("db_role", "sm_cp_runtime");
    }

    @Test
    void theRepositoryOnItsOwnCannotWriteWithoutAuditContextBecauseOr25RefusesIt() {
        World w = world(5);
        UUID instance = declare(w);

        assertThatThrownBy(() -> runtimeTx.executeWithoutResult(
                status -> repository.retire(w.org, instance, w.actorId, AT)))
                .hasMessageContaining("no known audit_operation");

        assertThat(retired(instance)).isFalse();
    }

    @Test
    void aSecondDeregisterIsAnIdempotentNoOpThatWritesNoAuditRowAndKeepsTheOriginalActor() {
        World w = world(5);
        UUID instance = declare(w);
        retirement.deregister(w.actor(), origin(), w.org, instance);
        long auditBefore = undeclaredAuditRows(instance);
        World other = w.withAnotherActor(this);

        boolean again = retirement.deregister(other.actor(), origin(), w.org, instance);

        assertThat(again).isFalse();
        assertThat(undeclaredAuditRows(instance)).isEqualTo(auditBefore).isEqualTo(1);
        assertThat(migrator.queryForObject("SELECT retired_by FROM sm_control.instance WHERE instance_id = :i",
                Map.of("i", instance), UUID.class)).isEqualTo(w.actorId);
    }

    @Test
    void anInstanceOfAnotherOrganizationOrOneThatDoesNotExistIsNotFoundAndNothingIsWritten() {
        World mine = world(5);
        World theirs = world(5);
        UUID theirInstance = declare(theirs);
        long auditBefore = migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit", Map.of(),
                Long.class);

        assertThatThrownBy(() -> retirement.deregister(mine.actor(), origin(), mine.org, theirInstance))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).doesNotContain(theirInstance.toString()));
        assertThatThrownBy(() -> retirement.deregister(mine.actor(), origin(), mine.org, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(retired(theirInstance)).isFalse();
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit", Map.of(), Long.class))
                .isEqualTo(auditBefore);
    }

    @Test
    void retiringFreesTheQuotaSlotSoTheOrganizationMayDeclareAnotherAndTheBindingCheckStillHolds() {
        World w = world(1);
        UUID first = declare(w);
        assertThatThrownBy(() -> governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()))
                .as("quota 1, one live instance: full").isInstanceOf(
                        com.solace.sm.cp.api.governance.QuotaExceededException.class);
        assertThat(governance.withinQuota(w.org, "cache")).isFalse();

        retirement.deregister(w.actor(), origin(), w.org, first);

        assertThat(governance.withinQuota(w.org, "cache")).as("the slot is free").isTrue();
        UUID second = declare(w);
        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()))
                .as("the slot is taken again: the retired one still does not count, the live one does")
                .isInstanceOf(com.solace.sm.cp.api.governance.QuotaExceededException.class);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o",
                Map.of("o", w.org), Long.class)).as("the retired row is still there, never deleted").isEqualTo(2);
    }

    @Test
    void retiredAnswersOnlyThisOrganizationsRetiredInstancesInOneBoundedBatch() {
        World w = world(5);
        World other = world(5);
        UUID live = declare(w);
        UUID gone = declare(w);
        UUID theirsGone = declare(other);
        retirement.deregister(w.actor(), origin(), w.org, gone);
        retirement.deregister(other.actor(), origin(), other.org, theirsGone);

        Set<UUID> answer = retirement.retired(w.org, List.of(live, gone, theirsGone, UUID.randomUUID()));

        assertThat(answer).containsExactly(gone);
        assertThat(retirement.retired(w.org, List.of())).isEmpty();
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= InstanceRetirementGovernance.MAX_BATCH; i++) {
            tooMany.add(UUID.randomUUID());
        }
        assertThatThrownBy(() -> retirement.retired(w.org, tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registeredStillAnswersTrueForARetiredNodeBecauseItIsTheDeclareSweepsQuestionNotALivenessOne() {
        World w = world(5);
        UUID instance = declare(w);
        retirement.deregister(w.actor(), origin(), w.org, instance);

        assertThat(governance.registered(w.org, List.of(instance)))
                .as("step 4 of declare was done; retirement is a later state of the same row")
                .containsExactly(instance);
        assertThat(InstanceRetirementGovernance.MAX_BATCH).isEqualTo(InstanceGovernance.MAX_BATCH);
    }

    @Test
    void twoConcurrentDeregistersOfOneInstanceWriteExactlyOnceAndAuditExactlyOnce() throws Exception {
        World w = world(5);
        UUID instance = declare(w);
        CyclicBarrier together = new CyclicBarrier(2);
        Callable<Boolean> racer = () -> {
            together.await(10, TimeUnit.SECONDS);
            return retirement.deregister(w.actor(), origin(), w.org, instance);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> outcomes = new ArrayList<>();
        try {
            List<Future<Boolean>> futures = List.of(pool.submit(racer), pool.submit(racer));
            for (Future<Boolean> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(undeclaredAuditRows(instance)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- harness

    private record World(UUID org, UUID actorId, UUID unit, UUID env, UUID entry) {
        PrincipalRef actor() {
            return new PrincipalRef(actorId);
        }

        World withAnotherActor(InstanceRetirementServiceIT it) {
            UUID another = UUID.randomUUID();
            it.migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source,"
                    + " username) VALUES (?, ?, 'idp', ?)", another, org, "retire-" + another);
            return new World(org, another, unit, env, entry);
        }
    }

    /** A fresh organization with one unit, one environment and a {@code cache} entry; {@code max} 0 = no entitlement. */
    private World world(int max) {
        UUID org = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        var jdbc = migrator.getJdbcTemplate();
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)", org);
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, org, "retire-" + principal);
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name)"
                + " VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)", org, unit);
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                + " VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", org, unit, env);
        jdbc.update("INSERT INTO sm_control.catalog_solution (organization_id, catalog_solution_id, solution,"
                + " created_at) VALUES (?, ?, 'cache', now())", org, entry);
        if (max > 0) {
            jdbc.update("INSERT INTO sm_control.entitlement (organization_id, solution, max_instances, set_by,"
                    + " set_at) VALUES (?, 'cache', ?, ?, now())", org, max, principal);
        }
        return new World(org, principal, unit, env, entry);
    }

    private UUID declare(World w) {
        GovernanceRegistration registered =
                governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID());
        return registered.instanceId();
    }

    private static RequestOrigin origin() {
        return new RequestOrigin(CLIENT, UUID.randomUUID());
    }

    private boolean retired(UUID instance) {
        return migrator.queryForObject("SELECT retired_at IS NOT NULL FROM sm_control.instance WHERE instance_id = :i",
                Map.of("i", instance), Boolean.class);
    }

    private long undeclaredAuditRows(UUID instance) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'instance'"
                + " AND audit_operation = 'INSTANCE_UNDECLARED' AND row_key ->> 'instance_id' = :i",
                Map.of("i", instance.toString()), Long.class);
    }
}
