package com.solace.sm.cp.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcGovernanceRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Instance governance registration against a real PostgreSQL 14, wired as {@code GovernanceConfiguration} wires
 * it, and every write made as {@code sm_cp_runtime}: a write by the migrator or the owner is exempt from OR-25
 * and would prove nothing about the role the application connects as.
 *
 * <p>Fixture rows (organizations, hierarchy, catalog entries, entitlements) are seeded as the migrator, so the
 * registration is the only thing under test.
 */
class GovernanceServiceIT {

    private static final String KEY = "governance-service-it";
    private static final String CLIENT = "203.0.113.78";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final DataSource runtimeSource = runtime.getJdbcTemplate().getDataSource();
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtimeSource));
    private final JdbcGovernanceRepository repository = new JdbcGovernanceRepository(runtime);
    private final GovernanceService governance = new GovernanceService(repository, new JdbcAuditContext(runtime),
            runtimeTx, UUID::randomUUID);

    // ------------------------------------------------------------------------- the solution node (OR-41)

    @Test
    void theFirstDeclareInAnEnvironmentCreatesItsSolutionNode() {
        World w = world(5);
        UUID instance = UUID.randomUUID();

        GovernanceRegistration registered = governance.register(w.actor(), origin(), w.org, w.env, w.entry, instance);

        assertThat(registered.solutionNodeCreated()).isTrue();
        assertThat(registered.instanceId()).isEqualTo(instance);
        assertThat(count("solution", w)).isEqualTo(1);
        Map<String, Object> node = migrator.queryForMap(
                "SELECT solution_id, organization_unit_id, environment_id, catalog_solution_id"
                        + " FROM sm_control.solution WHERE organization_id = :o", Map.of("o", w.org));
        assertThat(node.get("solution_id")).isEqualTo(registered.solutionId());
        assertThat(node.get("organization_unit_id")).isEqualTo(w.unit);
        assertThat(node.get("environment_id")).isEqualTo(w.env);
        assertThat(node.get("catalog_solution_id")).isEqualTo(w.entry);
        Map<String, Object> inst = migrator.queryForMap(
                "SELECT solution_id, environment_id, organization_unit_id FROM sm_control.instance"
                        + " WHERE instance_id = :i", Map.of("i", instance));
        assertThat(inst.get("solution_id")).isEqualTo(registered.solutionId());
        assertThat(inst.get("environment_id")).isEqualTo(w.env);
        assertThat(inst.get("organization_unit_id")).isEqualTo(w.unit);
        assertThat(scopeKinds(w)).as("the canonical scope rows of both new nodes")
                .containsExactlyInAnyOrder("solution", "instance");
    }

    @Test
    void aSecondDeclareReusesTheNode() {
        World w = world(5);

        GovernanceRegistration first = governance.register(w.actor(), origin(), w.org, w.env, w.entry,
                UUID.randomUUID());
        GovernanceRegistration second = governance.register(w.actor(), origin(), w.org, w.env, w.entry,
                UUID.randomUUID());

        assertThat(second.solutionNodeCreated()).isFalse();
        assertThat(second.solutionId()).isEqualTo(first.solutionId());
        assertThat(count("solution", w)).isEqualTo(1);
        assertThat(count("instance", w)).isEqualTo(2);
    }

    @Test
    void twoConcurrentFirstDeclaresCreateExactlyOneNode() throws Exception {
        World w = world(5);

        List<GovernanceRegistration> results = runConcurrently(
                () -> governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()),
                () -> governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()));

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(GovernanceRegistration::solutionId).distinct()).hasSize(1);
        assertThat(results.stream().filter(GovernanceRegistration::solutionNodeCreated)).hasSize(1);
        assertThat(count("solution", w)).isEqualTo(1);
        assertThat(count("instance", w)).isEqualTo(2);
    }

    @Test
    void theNodeBackstopIsTheUniqueIndexNotJustTheEntitlementLock() {
        // Called without the service: a second transaction that raced past the lock inserts the same node.
        World w = world(5);
        UUID first = UUID.randomUUID();
        runtimeTx.executeWithoutResult(status -> {
            setAudit(w);
            assertThat(repository.insertSolutionNode(w.org, w.unit, w.env, first, w.entry)).isTrue();
        });

        boolean second = runtimeTx.execute(status -> {
            setAudit(w);
            return repository.insertSolutionNode(w.org, w.unit, w.env, UUID.randomUUID(), w.entry);
        });

        assertThat(second).as("ON CONFLICT DO NOTHING: the loser inserts nothing").isFalse();
        assertThat(count("solution", w)).isEqualTo(1);
        assertThat(repository.findSolutionNode(w.org, w.unit, w.env, w.entry)).contains(first);
    }

    // ------------------------------------------------------------------------------- the quota (OR-40)

    @Test
    void theQuotaIsEnforcedUnderTheEntitlementRowLockSoTwoRacingDeclaresCannotBothPass() throws Exception {
        World w = world(1);
        // Hold both racers after they have COUNTED and before either has written: without FOR UPDATE on the
        // entitlement row both count 0 and both write. With it the second racer is still blocked at the lock,
        // never reaches the barrier, and the first one's wait times out and carries on.
        CyclicBarrier afterCount = new CyclicBarrier(2);
        GovernanceRepository rendezvous = new DelegatingRepository(repository) {
            @Override
            public long countInstances(UUID organizationId, String solution) {
                long counted = super.countInstances(organizationId, solution);
                try {
                    afterCount.await(1500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException | BrokenBarrierException e) {
                    // the lock held the other racer back: carry on
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return counted;
            }
        };
        GovernanceService racing = new GovernanceService(rendezvous, new JdbcAuditContext(runtime), runtimeTx,
                UUID::randomUUID);

        List<Object> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        return racing.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID());
                    } catch (QuotaExceededException e) {
                        return e;
                    }
                }));
            }
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes.stream().filter(GovernanceRegistration.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(QuotaExceededException.class::isInstance)).hasSize(1);
        assertThat(count("instance", w)).as("quota 1: exactly one instance exists").isEqualTo(1);
    }

    @Test
    void aRefusedRegistrationWritesNothing() {
        World w = world(1);
        governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID());
        long auditBefore = auditRows(w);
        World elsewhere = w.withEnvironment(this);      // a second environment, so a node would be NEW

        assertThatThrownBy(() -> governance.register(w.actor(), origin(), w.org, elsewhere.env, w.entry,
                UUID.randomUUID())).isInstanceOf(QuotaExceededException.class);

        assertThat(count("solution", w)).as("no second solution node").isEqualTo(1);
        assertThat(count("instance", w)).isEqualTo(1);
        assertThat(auditRows(w)).as("no audit row").isEqualTo(auditBefore);
    }

    @Test
    void theQuotaCountsEveryEnvironmentOfTheOrganizationAndNoOtherOrganization() {
        World w = world(2);
        World other = world(1);
        UUID secondEnv = w.withEnvironment(this).env;
        governance.register(other.actor(), origin(), other.org, other.env, other.entry, UUID.randomUUID());

        governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID());
        governance.register(w.actor(), origin(), w.org, secondEnv, w.entry, UUID.randomUUID());

        assertThatThrownBy(() -> governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()))
                .as("two in this organization across two environments; the other organization's does not count")
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void anUnentitledSolutionIsRefused() {
        World w = world(0);        // an entry but no entitlement row

        assertThatThrownBy(() -> governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()))
                .isInstanceOf(EntitlementRefusedException.class);

        assertThat(count("solution", w)).isZero();
        assertThat(count("instance", w)).isZero();
        assertThat(governance.withinQuota(w.org, "cache")).isFalse();
    }

    @Test
    void withinQuotaIsThePreCheckOfTheSameCount() {
        World w = world(1);
        assertThat(governance.withinQuota(w.org, "cache")).isTrue();
        assertThat(governance.withinQuota(w.org, "engine-edge")).as("another solution: not entitled").isFalse();

        governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID());

        assertThat(governance.withinQuota(w.org, "cache")).isFalse();
        assertThat(governance.withinQuota(UUID.randomUUID(), "cache")).as("another organization").isFalse();
    }

    // ------------------------------------------------------------------------- audit, uniqueness, tenancy

    @Test
    void everyRowIsAuditedAsInstanceDeclared() {
        World w = world(5);
        RequestOrigin origin = origin();
        UUID instance = UUID.randomUUID();

        governance.register(w.actor(), origin, w.org, w.env, w.entry, instance);

        List<Map<String, Object>> rows = migrator.queryForList("""
                SELECT table_name, audit_operation, actor_principal_id, correlation_id, client_address, db_role
                  FROM sm_control.change_audit
                 WHERE table_name IN ('solution', 'instance', 'scope')
                   AND (new_values ->> 'organization_id') = :o
                """, Map.of("o", w.org.toString()));
        assertThat(rows).extracting(r -> r.get("table_name")).containsExactlyInAnyOrder(
                "solution", "instance", "scope", "scope");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("audit_operation")).isEqualTo("INSTANCE_DECLARED");
            assertThat(r.get("actor_principal_id")).isEqualTo(w.actorId);
            assertThat(r.get("correlation_id")).isEqualTo(origin.correlationId());
            assertThat(r.get("client_address")).isEqualTo(CLIENT);
            assertThat(r.get("db_role")).isEqualTo("sm_cp_runtime");
        });
    }

    @Test
    void aDuplicateInstanceIdIsRefusedByTheGlobalUnique() {
        World a = world(5);
        World b = world(5);
        UUID instance = UUID.randomUUID();
        governance.register(a.actor(), origin(), a.org, a.env, a.entry, instance);

        assertThatThrownBy(() -> governance.register(b.actor(), origin(), b.org, b.env, b.entry, instance))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("instance_id_unique");

        assertThat(count("instance", b)).isZero();
        assertThat(count("solution", b)).as("the refused registration rolled its node back too").isZero();
    }

    @Test
    void anEnvironmentOrACatalogEntryOfAnotherOrganizationIsNotFoundAndNothingIsWritten() {
        World mine = world(5);
        World theirs = world(5);

        assertThatThrownBy(() -> governance.register(mine.actor(), origin(), mine.org, theirs.env, mine.entry,
                UUID.randomUUID())).isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).doesNotContain(theirs.env.toString()));
        assertThatThrownBy(() -> governance.register(mine.actor(), origin(), mine.org, mine.env, theirs.entry,
                UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("instance", mine)).isZero();
        assertThat(count("solution", theirs)).isZero();
        assertThat(count("instance", theirs)).isZero();
    }

    @Test
    void registeredAnswersOnlyThisOrganizationsInstancesInOneBoundedBatch() {
        World w = world(5);
        World other = world(5);
        UUID mine = governance.register(w.actor(), origin(), w.org, w.env, w.entry, UUID.randomUUID()).instanceId();
        UUID theirs = governance.register(other.actor(), origin(), other.org, other.env, other.entry,
                UUID.randomUUID()).instanceId();
        UUID unknown = UUID.randomUUID();

        Set<UUID> answer = governance.registered(w.org, List.of(mine, theirs, unknown));

        assertThat(answer).containsExactly(mine);
        assertThat(governance.registered(w.org, List.of())).isEmpty();
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= InstanceGovernance.MAX_BATCH; i++) {
            tooMany.add(UUID.randomUUID());
        }
        assertThatThrownBy(() -> governance.registered(w.org, tooMany)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private record World(UUID org, UUID actorId, UUID unit, UUID env, UUID entry) {
        PrincipalRef actor() {
            return new PrincipalRef(actorId);
        }

        /** Another environment of the same organization unit. */
        World withEnvironment(GovernanceServiceIT it) {
            UUID second = UUID.randomUUID();
            it.migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id,"
                    + " organization_unit_id, environment_id, name) VALUES (?, ?, ?, 'fixture-' || gen_random_uuid()::text)", org, unit, second);
            return new World(org, actorId, unit, second, entry);
        }
    }

    /** A fresh organization with one unit, one environment and a {@code cache} catalog entry; {@code max} 0 = no entitlement. */
    private World world(int max) {
        UUID org = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        var jdbc = migrator.getJdbcTemplate();
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)", org);
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, org, "gov-" + principal);
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

    private void setAudit(World w) {
        new JdbcAuditContext(runtime).set(w.actorId, "INSTANCE_DECLARED", UUID.randomUUID(), CLIENT);
    }

    private static RequestOrigin origin() {
        return new RequestOrigin(CLIENT, UUID.randomUUID());
    }

    private long count(String table, World w) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control." + table + " WHERE organization_id = :o",
                Map.of("o", w.org), Long.class);
    }

    private List<String> scopeKinds(World w) {
        return migrator.queryForList("SELECT kind FROM sm_control.scope WHERE organization_id = :o"
                + " AND kind IN ('solution', 'instance')", Map.of("o", w.org), String.class);
    }

    private long auditRows(World w) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name IN"
                + " ('solution', 'instance', 'scope') AND (new_values ->> 'organization_id') = :o",
                Map.of("o", w.org.toString()), Long.class);
    }

    @SafeVarargs
    private static <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw e;
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Forwards everything; a test overrides the one call it wants to observe. */
    private static class DelegatingRepository implements GovernanceRepository {
        private final GovernanceRepository delegate;

        DelegatingRepository(GovernanceRepository delegate) {
            this.delegate = delegate;
        }

        @Override public Optional<String> solutionNameOf(UUID o, UUID c) { return delegate.solutionNameOf(o, c); }
        @Override public Optional<UUID> organizationUnitOf(UUID o, UUID e) { return delegate.organizationUnitOf(o, e); }
        @Override public OptionalInt lockEntitlement(UUID o, String s) { return delegate.lockEntitlement(o, s); }
        @Override public OptionalInt maxInstances(UUID o, String s) { return delegate.maxInstances(o, s); }
        @Override public long countInstances(UUID o, String s) { return delegate.countInstances(o, s); }
        @Override public Optional<UUID> findSolutionNode(UUID o, UUID u, UUID e, UUID c) {
            return delegate.findSolutionNode(o, u, e, c);
        }
        @Override public boolean insertSolutionNode(UUID o, UUID u, UUID e, UUID s, UUID c) {
            return delegate.insertSolutionNode(o, u, e, s, c);
        }
        @Override public void insertInstance(UUID o, UUID u, UUID e, UUID s, UUID i) {
            delegate.insertInstance(o, u, e, s, i);
        }
        @Override public void insertSolutionScope(UUID sc, UUID o, UUID u, UUID e, UUID s) {
            delegate.insertSolutionScope(sc, o, u, e, s);
        }
        @Override public void insertInstanceScope(UUID sc, UUID o, UUID u, UUID e, UUID s, UUID i) {
            delegate.insertInstanceScope(sc, o, u, e, s, i);
        }
        @Override public Set<UUID> registered(UUID o, Collection<UUID> ids) { return delegate.registered(o, ids); }
    }
}
