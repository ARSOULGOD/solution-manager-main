package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.logs.PlacementLogRetention;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 016 task 9 against real PostgreSQL, as the runtime role: the cap is upserted under the column-level grant, the
 * placement is resolved by the DP's own tables, and the stored value is what a reconnecting agent session is handed.
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcPlacementLogRetentionRepositoryIT {

    private static NamedParameterJdbcTemplate runtime;
    private static JdbcPlacementLogRetentionRepository repo;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        repo = new JdbcPlacementLogRetentionRepository(runtime);
    }

    private record World(UUID tenant, UUID agent, UUID placement) {
    }

    private static World agentWorld() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID agent = UUID.randomUUID();
        UUID target = InventoryFixtures.agentTarget(runtime, tenant, agent);
        UUID placement = InventoryFixtures.agentPlacement(runtime, tenant, UUID.randomUUID(), target, UUID.randomUUID());
        return new World(tenant, agent, placement);
    }

    private static AgentSessionRef openSession(World w, long epoch) {
        runtime.update("INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                + " opened_at, last_seen_at) VALUES (:t, :a, :e, 1, '0.1.0', :now, :now)",
                new MapSqlParameterSource().addValue("t", w.tenant).addValue("a", w.agent).addValue("e", epoch)
                        .addValue("now", OffsetDateTime.now()));
        return new AgentSessionRef(w.agent, epoch);
    }

    private static final class RecordingPort implements AgentLogPort {
        final List<Object[]> sent = new ArrayList<>();
        Optional<AgentSessionRef> session = Optional.empty();

        @Override
        public Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId) {
            return session;
        }

        @Override
        public void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes) {
            sent.add(new Object[] {ref, placementId, maxBytes});
        }

        @Override
        public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit, Instant after,
                Instant before, Duration deadline) {
            throw new AssertionError();
        }

        @Override
        public LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes) {
            throw new AssertionError();
        }
    }

    @Test
    void setCapUpsertsUnderTheColumnGrantAndOtherOrganizationsPlacementIsUnresolvable() {
        World w = agentWorld();
        RecordingPort port = new RecordingPort();
        PlacementLogRetention service = new PlacementLogRetention(repo, port, Clock.systemUTC(), 1_000_000);
        service.setCap(w.tenant, w.placement, 500);
        service.setCap(w.tenant, w.placement, 0);                 // ON CONFLICT DO UPDATE as sm_dp_runtime
        assertEquals(Optional.of(0L), repo.find(w.tenant, w.placement));
        assertThrows(PlacementLogRetention.UnresolvablePlacementException.class,
                () -> service.setCap(UUID.randomUUID(), w.placement, 5));
        assertEquals(Optional.of(0L), repo.find(w.tenant, w.placement));
        // an SSH placement is not an agent placement
        UUID ssh = InventoryFixtures.target(runtime, w.tenant);
        UUID sshPlacement = InventoryFixtures.agentPlacement(runtime, w.tenant, UUID.randomUUID(), ssh, UUID.randomUUID());
        assertFalse(repo.isLiveAgentPlacement(w.tenant, sshPlacement));
        // retired: unresolvable
        runtime.update("UPDATE sm_data.placement SET retired_at = now(), retired_by = :u WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("u", UUID.randomUUID()).addValue("t", w.tenant).addValue("p", w.placement));
        assertThrows(PlacementLogRetention.UnresolvablePlacementException.class, () -> service.setCap(w.tenant, w.placement, 5));
    }

    @Test
    void reconnectResendsStoredCap() {
        World w = agentWorld();
        AgentSessionRef offline = new AgentSessionRef(w.agent, 1);
        // set while the agent has no session: stored, nothing pushed
        RecordingPort port = new RecordingPort();
        PlacementLogRetention service = new PlacementLogRetention(repo, port, Clock.systemUTC(), 1_000_000);
        assertEquals(PlacementLogRetention.Outcome.STORED_AGENT_NOT_CONNECTED, service.setCap(w.tenant, w.placement, 4242));
        assertTrue(port.sent.isEmpty());
        assertEquals(Map.of(), repo.capsFor(offline), "a session that is not open is handed nothing");

        // a new process (new repository instance) and the agent connects: the source the gateway reads answers the stored cap
        AgentSessionRef session = openSession(w, 1);
        AgentLogRetentionSource source = new JdbcPlacementLogRetentionRepository(runtime);
        assertEquals(Map.of(w.placement, 4242L), source.capsFor(session));
        // another agent's session, or a stale epoch, is handed nothing
        assertEquals(Map.of(), source.capsFor(new AgentSessionRef(w.agent, 2)));
        assertEquals(Map.of(), source.capsFor(new AgentSessionRef(UUID.randomUUID(), 1)));
        // another organization's caps are never in the answer
        World other = agentWorld();
        new PlacementLogRetention(repo, port, Clock.systemUTC(), 1_000_000).setCap(other.tenant, other.placement, 9);
        assertEquals(Map.of(w.placement, 4242L), source.capsFor(session));
    }
}
