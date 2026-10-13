package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.AgentStillEnrolledException;
import com.solace.sm.dp.api.inventory.ConnectionRetirement;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import com.solace.sm.dp.api.inventory.TargetIsAgentManagedException;
import com.solace.sm.dp.api.inventory.TargetTransportUnsupportedException;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T3.5 against a real PostgreSQL 14, as {@code sm_dp_runtime}: every reader of {@code target_connection} meets a set
 * holding BOTH transports (T1.1 review finding 12). V028 made {@code host}, {@code port}, {@code ssh_user}, the pin and the
 * credential nullable, so a reader that assumed an SSH row would break on the first agent row; here the listing, the read, the
 * envelope, the placement view, the edit, the rotation, the retirement and the declare each meet one.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentTargetInventoryIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final TargetChangeOrigin ORIGIN = new TargetChangeOrigin(UUID.randomUUID(), "198.51.100.7");
    private static final AtomicInteger PORTS = new AtomicInteger(30000);

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;
    private static InventoryService service;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
        EnvelopeSecretStore secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 43), null));
        HostKeyScanService scanner = (host, port) -> {
            throw new AssertionError("an agent target must never be scanned");
        };
        service = new InventoryService(new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())),
                new JdbcInstanceStateRepository(runtime), new JdbcPlacementRepository(runtime),
                new JdbcTargetConnectionRepository(runtime), secrets, secrets, Clock.systemUTC(),
                new JdbcMetricDefinitionRepository(runtime), LadderRecorder.none(), ApplyEventEmitter.silent(), scanner);
    }

    /** One organization holding an SSH target and an agent target. */
    private record World(UUID tenant, UUID ssh, UUID agentTarget, UUID agent) {
    }

    private static World world() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID ssh = InventoryFixtures.target(tenant);
        UUID agent = UUID.randomUUID();
        UUID agentTarget = InventoryFixtures.agentTarget(runtime, tenant, agent);
        return new World(tenant, ssh, agentTarget, agent);
    }

    private static PlacementSpec spec() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return new PlacementSpec("sm-" + u, "/srv/sm/" + u, "/app/external/spring/config", "application.yml",
                new HealthProbeSpec("actuator-health", "/actuator/health", PORTS.incrementAndGet(), ProbeStrength.READINESS));
    }

    private static long rows(String table, UUID tenant) {
        Long n = superuser.queryForObject("SELECT count(*) FROM sm_data." + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        return n == null ? 0 : n;
    }

    // ---- the readers meet an agent row -------------------------------------------------------------------------------

    @Test
    void theRecordsOfBothTransportsAreReadAndTheAgentOneIsTheAgentVariant() {
        World w = world();

        TargetConnectionView ssh = service.connection(w.tenant(), w.ssh()).orElseThrow();
        TargetConnectionView agent = service.connection(w.tenant(), w.agentTarget()).orElseThrow();

        assertFalse(ssh.isAgent());
        assertEquals(22, ssh.port());
        assertTrue(agent.isAgent());
        assertEquals(w.agent(), agent.agentId());
        assertNull(agent.host());
        assertNull(agent.sshUser());
        assertNull(agent.hostKeyFingerprint());
        assertNull(agent.credential(), "an agent target has no credential");
        assertEquals(List.of(), agent.offeredHostKeys());
        assertEquals("/srv/sm", agent.allowedConfigRoot());
        assertEquals("sm-", agent.allowedContainerPrefix());
        Map<UUID, TargetEnvelope> envelopes = service.liveEnvelopes(w.tenant(), List.of(w.ssh(), w.agentTarget()));
        assertEquals(Set.of(w.ssh(), w.agentTarget()), envelopes.keySet(), "the envelope read serves both transports");
        assertEquals("agent", service.connectionOf(w.tenant(), w.agentTarget()).orElseThrow().transport());
        assertEquals("ssh-docker", service.connectionOf(w.tenant(), w.ssh()).orElseThrow().transport());
    }

    @Test
    void aPlacementViewCarriesItsTargetsTransport() {
        World w = world();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(runtime, w.tenant(), instance, "active");
        UUID onSsh = InventoryFixtures.placement(runtime, w.tenant(), instance, w.ssh(), UUID.randomUUID());
        UUID onAgent = InventoryFixtures.placement(runtime, w.tenant(), instance, w.agentTarget(), UUID.randomUUID());

        InstanceView view = service.findActive(w.tenant(), instance).orElseThrow();

        Map<UUID, String> transports = new java.util.HashMap<>();
        for (PlacementView p : view.placements()) {
            transports.put(p.placementId(), p.transport());
        }
        assertEquals(PlacementView.SSH_DOCKER, transports.get(onSsh));
        assertEquals(PlacementView.AGENT, transports.get(onAgent));
    }

    // ---- the operations that address an SSH record ------------------------------------------------------------------------

    @Test
    void editRotateAndPlanAnAgentTargetAreRefusedByNameAndNothingIsReadWrittenOrScanned() {
        World w = world();
        long events = rows("target_connection_event", w.tenant());
        long secrets = superuser.queryForObject("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource("t", w.tenant()), Long.class);
        TargetConnectionEdit edit = new TargetConnectionEdit("somewhere.example.test", null, null, null);

        assertThrows(TargetTransportUnsupportedException.class, () -> service.planEdit(w.tenant(), w.agentTarget(), edit));
        assertThrows(TargetTransportUnsupportedException.class,
                () -> service.editConnection(w.tenant(), w.agentTarget(), edit, ACTOR, ORIGIN));
        // refused before the key is even opened: a key that does not parse is still the transport refusal
        assertThrows(TargetTransportUnsupportedException.class, () -> service.rotateConnectionCredential(w.tenant(),
                w.agentTarget(), "not a key".toCharArray(), ACTOR, ORIGIN));
        assertThrows(TargetTransportUnsupportedException.class, () -> service.rotateCredential(w.tenant(), w.agentTarget(),
                SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair()).chars(), ACTOR));

        assertEquals(events, rows("target_connection_event", w.tenant()), "no event");
        assertEquals(secrets, superuser.queryForObject("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource("t", w.tenant()), Long.class), "no secret stored");
        // and the SSH target of the same organization is edited as ever (planEdit makes no scan for an unchanged address)
        assertEquals(false, service.planEdit(w.tenant(), w.ssh(), new TargetConnectionEdit(null, null, "deploy", null)).rescan());
    }

    @Test
    void registeringAnAgentTargetsIdIsADifferentRecord() {
        World w = world();
        TargetConnectionSpec spec = new TargetConnectionSpec("h.example.test", 22, "u",
                "SHA256:" + "A".repeat(43), "/srv/sm", "sm-", Set.of());
        assertThrows(IllegalStateException.class, () -> service.register(w.tenant(), w.agentTarget(), spec,
                SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair()).chars(), ACTOR));
    }

    // ---- retirement ----------------------------------------------------------------------------------------------------

    @Test
    void anAgentTargetIsRetiredOnlyAfterItsAgentIsRevoked() {
        World w = world();

        assertThrows(AgentStillEnrolledException.class,
                () -> service.retireConnection(w.tenant(), w.agentTarget(), ACTOR, ORIGIN));
        assertTrue(service.connection(w.tenant(), w.agentTarget()).isPresent(), "nothing changed");

        superuser.update("UPDATE sm_data.agent_identity SET revoked_at = now() WHERE tenant_id = :t AND agent_id = :a",
                new MapSqlParameterSource().addValue("t", w.tenant()).addValue("a", w.agent()));

        assertEquals(ConnectionRetirement.RETIRED, service.retireConnection(w.tenant(), w.agentTarget(), ACTOR, ORIGIN));
        assertEquals(ConnectionRetirement.ALREADY_RETIRED, service.retireConnection(w.tenant(), w.agentTarget(), ACTOR, ORIGIN));
        assertTrue(service.connection(w.tenant(), w.agentTarget()).isEmpty(), "retired is not live");
    }

    @Test
    void anSshTargetStillRetiresWithoutAnyAgent() {
        World w = world();
        assertEquals(ConnectionRetirement.RETIRED, service.retireConnection(w.tenant(), w.ssh(), ACTOR, ORIGIN));
    }

    // ---- placements come only from discovery ---------------------------------------------------------------------------------

    @Test
    void aDeclareOrAnAddPlacementNamingAnAgentTargetIsRefusedAndWritesNothing() {
        World w = world();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(runtime, w.tenant(), instance, "active");
        long instances = rows("instance_state", w.tenant());
        long placements = rows("placement", w.tenant());

        assertThrows(TargetIsAgentManagedException.class, () -> service.reserve(w.tenant(), new DeclareInstanceCommand(
                UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4", "inst-" + UUID.randomUUID().toString().substring(0, 8),
                w.agentTarget(), AcquisitionMode.VIA_SSH, spec(), ACTOR)));
        assertThrows(TargetIsAgentManagedException.class, () -> service.addPlacement(w.tenant(), instance,
                new AddPlacementCommand(w.agentTarget(), spec(), ACTOR, MetricsDeclaration.none())));

        assertEquals(instances, rows("instance_state", w.tenant()));
        assertEquals(placements, rows("placement", w.tenant()));
        // the same declare on the SSH target of the same organization is unchanged
        service.reserve(w.tenant(), new DeclareInstanceCommand(UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4",
                "inst-" + UUID.randomUUID().toString().substring(0, 8), w.ssh(), AcquisitionMode.VIA_SSH, spec(), ACTOR));
    }

    @Test
    void aCommittedSshEditInTheMixedSetChangesTheSshRecordAndLeavesTheAgentRecordAlone() {
        World w = world();
        long events = rows("target_connection_event", w.tenant());

        TargetConnectionView edited = service.editConnection(w.tenant(), w.ssh(), new TargetConnectionEdit(null, null, "deploy", null),
                ACTOR, ORIGIN);

        assertEquals("deploy", edited.sshUser());
        assertEquals("deploy", service.connection(w.tenant(), w.ssh()).orElseThrow().sshUser());
        assertEquals(events + 1, rows("target_connection_event", w.tenant()), "exactly one edited event, none for the agent target");
        assertTrue(service.connection(w.tenant(), w.agentTarget()).orElseThrow().isAgent());
    }

    /** V028's third acquisition mode: the reader of instance_state must meet it as a value, not as a parse failure. */
    @Test
    void anInstanceRegisteredByAnAgentReadsAsAgentAcquisition() {
        World w = world();
        UUID instance = UUID.randomUUID();
        superuser.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id, solution,"
                        + " declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, 'active', 'agent', :u, now())",
                new MapSqlParameterSource().addValue("t", w.tenant()).addValue("i", instance).addValue("e", UUID.randomUUID())
                        .addValue("c", UUID.randomUUID()).addValue("n", "agent-" + instance).addValue("u", UUID.randomUUID()));
        InventoryFixtures.placement(runtime, w.tenant(), instance, w.agentTarget(), UUID.randomUUID());

        InstanceView view = service.findActive(w.tenant(), instance).orElseThrow();

        assertEquals(AcquisitionMode.AGENT, view.acquisition());
        assertEquals(PlacementView.AGENT, view.placements().get(0).transport());
    }
}
