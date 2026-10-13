package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentRetentionRepository;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeOperationContext;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.agent.SimulatedAgent;
import com.solace.sm.dp.metrics.RunwayDegradation;
import com.solace.sm.dp.metrics.rollup.PartitionRunway;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T4.5 (OR-77 pattern; OR-130 F-5; OR-134): agent retention, driven the way production drives it, through
 * {@link PartitionRunway#keep()} (the daily loop that also calls {@code ensure_metric_partitions()}) and the runtime-role
 * {@link JdbcAgentRetentionRepository}. The function's SQL shapes are pinned in {@code AgentSchemaIT}; this proves the LOOP
 * runs them, with real engine-minted commands, and that the live rows retention must never touch survive a run.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentMaintenanceIT {

    private static AgentEngineWorld w;
    private static ScheduledThreadPoolExecutor executor;
    private static PartitionRunway loop;

    @BeforeAll
    static void world() {
        SmDataPostgres.bootstrap();
        w = new AgentEngineWorld("agent_maintenance");
        executor = new ScheduledThreadPoolExecutor(1);
        // never start()ed: keep() is the loop body, called by hand, so no wall clock is waited on
        loop = new PartitionRunway(() -> LocalDate.of(2099, 1, 1), executor, RollupProperties.defaults(), Clock.systemUTC(),
                new RunwayDegradation() {
                    @Override
                    public void runwayCouldNotBeExtended() {
                        throw new AssertionError("the runway keep must not fail here");
                    }
                }, List.of(new JdbcAgentRetentionRepository(w.runtime)));
    }

    @AfterAll
    static void stop() {
        executor.shutdownNow();
    }

    private NamedParameterJdbcTemplate owner() {
        return new NamedParameterJdbcTemplate(w.db.superuser());
    }

    private static MapSqlParameterSource p(Object... kv) {
        MapSqlParameterSource s = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) {
            s.addValue((String) kv[i], kv[i + 1]);
        }
        return s;
    }

    private int count(String sql, MapSqlParameterSource args) {
        return owner().queryForObject(sql, args, Integer.class);
    }

    private List<Long> generations(UUID tenant, UUID agent) {
        return owner().queryForList("SELECT wire_generation FROM sm_data.agent_command WHERE tenant_id = :t AND agent_id = :a"
                + " ORDER BY wire_generation", p("t", tenant, "a", agent), Long.class);
    }

    @Test
    void theDailyLoopDeletesOldAckedCommandsExceptTheHighestGenerationAndNumbersNewOnesAboveIt() {
        UUID tenantId = UUID.randomUUID();
        TenantId tenant = new TenantId(tenantId);
        AgentEngineWorld.Agent agent = w.enrol(tenantId);
        ScopeKey scope = w.agentScope(agent);
        AgentSessionRef session = w.connect(agent);
        SimulatedAgent sim = new SimulatedAgent(w.agentSide, agent.agentId(), AgentEngineWorld.agentRef(scope), session);
        AgentEngineWorld.Renderer renderer = new AgentEngineWorld.Renderer(w.hasher);
        AgentEngineWorld.Path path = w.path(renderer, sim);
        PlacementRef ref = AgentEngineWorld.ref(scope);
        TargetReplica replica = new TargetReplica(ref, new ReplicaRef(scope.placement().value().toString()));
        RenderedConfig config = RenderedConfig.of(tenant, "retained: config\n".getBytes(StandardCharsets.UTF_8), w.hasher);
        ApplyId apply = w.runningApply(scope, new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.RESTART_ALL, "operator-1",
                "operator-1"));
        RuntimeOperationContext materialize = w.context(tenant, apply, Step.MATERIALIZE, scope, replica, config.hash());
        RuntimeOperationContext restart = w.context(tenant, apply, Step.LIFECYCLE, scope, replica, null);

        try (AgentEngineWorld.Driver driver = new AgentEngineWorld.Driver(sim)) {
            path.adapter.materializeConfig(replica, 7, config, materialize);             // generation 1
            path.adapter.startOrUpdate(replica, 7, restart);                             // generation 2
            assertEquals(List.of(1L, 2L), generations(tenantId, agent.agentId()));

            // a recent run changes nothing
            loop.keep();
            assertEquals(List.of(1L, 2L), generations(tenantId, agent.agentId()), "nothing is thirty days old yet");

            // 31 days later BOTH are final and old; only generation 1 has a higher sibling
            owner().update("UPDATE sm_data.agent_command SET acked_at = now() - interval '31 days', created_at = now() - interval '31 days'"
                    + " WHERE tenant_id = :t AND agent_id = :a", p("t", tenantId, "a", agent.agentId()));
            loop.keep();
            assertEquals(List.of(2L), generations(tenantId, agent.agentId()),
                    "the old acked command went; the highest wire_generation of the (agent, placement) stayed, however old");

            // the same intent again is a NEW command, numbered above the old maximum
            path.adapter.materializeConfig(replica, 7, config, materialize);
            assertEquals(List.of(2L, 3L), generations(tenantId, agent.agentId()), "numbered above the old maximum, never reusing 1");
        }
    }

    @Test
    void theLoopKeepsAnOldActiveLeaseEveryEventEveryTombstoneAndEverySessionAndDeletesOnlyOldInactiveLeases() {
        UUID tenantId = UUID.randomUUID();
        AgentEngineWorld.Agent agent = w.enrol(tenantId);
        AgentSessionRef session = w.connect(agent);                     // a session row and an ACTIVE lease, as production makes them
        OffsetDateTime old = OffsetDateTime.now().minusDays(90);

        UUID expired = lease(agent, "EXPIRED", old);
        UUID revoked = lease(agent, "REVOKED", OffsetDateTime.now().minusDays(31));
        UUID superseded = lease(agent, "SUPERSEDED", OffsetDateTime.now().minusDays(29));      // inside the window
        // the connect()'s ACTIVE lease, aged 90 days (one ACTIVE lease per agent is a unique index): however old, it is kept
        owner().update("UPDATE sm_data.agent_lease SET issued_at = :i, expires_at = :o, server_clock = :i WHERE tenant_id = :t"
                + " AND agent_id = :a AND state = 'ACTIVE'", p("t", tenantId, "a", agent.agentId(), "o", old,
                        "i", old.minusHours(1)));
        UUID activeOld = owner().queryForObject("SELECT lease_id FROM sm_data.agent_lease WHERE tenant_id = :t AND agent_id = :a"
                + " AND state = 'ACTIVE'", p("t", tenantId, "a", agent.agentId()), UUID.class);
        owner().update("UPDATE sm_data.agent_session SET opened_at = :o, last_seen_at = :o, closed_at = :o"
                + " WHERE tenant_id = :t AND agent_id = :a", p("t", tenantId, "a", agent.agentId(), "o", old));
        UUID event = UUID.randomUUID();
        owner().update("INSERT INTO sm_data.agent_event (tenant_id, event_id, agent_id, request_id, code, actor_kind,"
                + " correlation_id, client_address, occurred_at) VALUES (:t, :e, :a, 'old', 'gateway.drain', 'SYSTEM', :c,"
                + " '198.51.100.7', :o)", p("t", tenantId, "e", event, "a", agent.agentId(), "c", UUID.randomUUID(), "o", old));
        owner().update("INSERT INTO sm_data.agent_discovery (tenant_id, agent_id, instance_label, container_name, container_id,"
                + " state, first_seen_at, settled_at) VALUES (:t, :a, 'cache-old', 'sm-old', :cid, 'ABANDONED', :o, :o)",
                p("t", tenantId, "a", agent.agentId(), "cid", "a".repeat(12), "o", old));

        loop.keep();

        assertEquals(0, leases(expired), "OR-134: a non-ACTIVE lease expired more than 30 days ago is deleted");
        assertEquals(0, leases(revoked));
        assertEquals(1, leases(superseded), "expired 29 days ago: kept");
        assertEquals(1, leases(activeOld), "an ACTIVE lease is never deleted");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_session WHERE tenant_id = :t AND closed_at IS NOT NULL",
                p("t", tenantId)), "a closed agent_session row is never deleted");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_event WHERE tenant_id = :t", p("t", tenantId)),
                "agent_event is never deleted");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_discovery WHERE tenant_id = :t", p("t", tenantId)),
                "a tombstone is never deleted");
        assertTrue(session != null);
    }

    @Test
    void theFunctionTakesNoArgument() {
        assertEquals(0, count("SELECT p.pronargs::int FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = 'sm_data' AND p.proname = 'agent_gateway_maintenance'", p()), "no argument reaches a DELETE (I5)");
    }

    /** The advisory lock, for real: a second connection holds it, so a run with rows due is a no-op, and runs again once released. */
    @Test
    void aRunWhileAnotherHoldsTheAdvisoryLockDeletesNothingAndTheNextRunDoes() throws Exception {
        JdbcAgentRetentionRepository repo = new JdbcAgentRetentionRepository(w.runtime);
        repo.runOnce();                                                          // flush what other cases left
        AgentEngineWorld.Agent agent = w.enrol(UUID.randomUUID());
        UUID due = lease(agent, "EXPIRED", OffsetDateTime.now().minusDays(45));

        try (java.sql.Connection other = w.db.superuser().getConnection()) {
            other.setAutoCommit(false);
            try (java.sql.Statement st = other.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(hashtextextended('sm.agent-gateway-maintenance', 0))");
            }
            assertEquals(new JdbcAgentRetentionRepository.Deleted(0, 0), repo.runOnce(), "a concurrent run holds it: a no-op");
            assertEquals(1, leases(due), "and nothing was deleted");
            other.rollback();                                                    // releases the transaction-level lock
        }

        assertEquals(1, repo.runOnce().leases(), "once the lock is free the same call deletes the due lease");
        assertEquals(0, leases(due));
    }

    private UUID lease(AgentEngineWorld.Agent a, String state, OffsetDateTime expires) {
        UUID id = UUID.randomUUID();
        String digest = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        owner().update("INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_id, certificate_thumbprint, agent_epoch,"
                + " fleet_epoch, issued_at, expires_at, audience, renewal_nonce_digest, admission_margin_ms, server_clock, state)"
                + " VALUES (:t, :a, :l, :th, 1, 1, :iss, :exp, 'gw', :nd, 0, :iss, :s)",
                p("t", a.tenant(), "a", a.agentId(), "l", id, "th", a.thumbprint(), "iss", expires.minusHours(1), "exp", expires,
                        "nd", digest, "s", state));
        return id;
    }

    private int leases(UUID lease) {
        return count("SELECT count(*)::int FROM sm_data.agent_lease WHERE lease_id = :l", p("l", lease));
    }
}
