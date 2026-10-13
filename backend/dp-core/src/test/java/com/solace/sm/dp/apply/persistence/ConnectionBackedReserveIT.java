package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.DeploymentMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration plan 014 step 2 against a real PostgreSQL 14, as {@code sm_dp_runtime}: a {@code dp-remote} declare bound
 * to a remote-server Connection. The data plane resolves the connection ONLY through its own
 * {@code sm_data.connection_runtime} projection (V038; {@code I7}), with the runtime gate temporarily disabled for
 * creation, keeps OR-29's uniqueness per connection, and stores
 * {@code deployment_mode} on the instance and {@code connection_id} on the placement (V039). The projection rows are
 * written here as the superuser: the gateway seam that populates them is not built yet (plan 014).
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectionBackedReserveIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final AtomicInteger PORTS = new AtomicInteger(24000);
    private static final SshTestKeys.Key KEY = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    private static final String DIGEST = "sha256:" + "c".repeat(64);

    private static NamedParameterJdbcTemplate superuser;
    private static InventoryService service;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        NamedParameterJdbcTemplate runtime = SmDataPostgres.runtimeJdbc();
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        service = new InventoryService(tx, new JdbcInstanceStateRepository(runtime),
                new JdbcPlacementRepository(runtime), new JdbcTargetConnectionRepository(runtime),
                SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 61), null)),
                Clock.systemUTC(), new JdbcMetricDefinitionRepository(runtime));
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    /** What the (unbuilt) gateway seam would write: the non-secret projection of one remote-server Connection. */
    private static UUID project(UUID tenant) {
        UUID connection = UUID.randomUUID();
        superuser.update("INSERT INTO sm_data.connection_runtime (tenant_id, connection_id, connection_type, host, port,"
                        + " ssh_user, host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_at) VALUES (:t, :c, 'remote-server', 'vm-9.corp.example', 22, 'sm', :fp, :cred,"
                        + " '/srv/sm', 'sm-', now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", connection)
                        .addValue("fp", "SHA256:" + "Q".repeat(43)).addValue("cred", UUID.randomUUID()));
        return connection;
    }

    private static void retireProjection(UUID tenant, UUID connection) {
        superuser.update("UPDATE sm_data.connection_runtime SET retired_at = now() WHERE tenant_id = :t"
                + " AND connection_id = :c", new MapSqlParameterSource().addValue("t", tenant).addValue("c", connection));
    }

    private static PlacementSpec spec(String container, String directory, int port) {
        return new PlacementSpec(container, directory, "/app/external/spring/config", "application.yml",
                new HealthProbeSpec("actuator-health", "/actuator/health", port, ProbeStrength.READINESS));
    }

    private static PlacementSpec freshSpec() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return spec("sm-" + u, "/srv/sm/" + u, PORTS.incrementAndGet());
    }

    private static DeclareInstanceCommand onConnection(UUID connection, PlacementSpec placement,
            MetricsDeclaration metrics) {
        return new DeclareInstanceCommand(UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4",
                "inst-" + UUID.randomUUID().toString().substring(0, 8), null, AcquisitionMode.DIRECT_HTTP, placement,
                ACTOR, metrics, connection, DeploymentMode.DP_REMOTE);
    }

    private static DeclareInstanceCommand onConnection(UUID connection, PlacementSpec placement) {
        return onConnection(connection, placement, MetricsDeclaration.none());
    }

    private static MetricsDeclaration metricsOn8081(int hostPort) {
        MetricDefinitionSpec hitRate = new MetricDefinitionSpec("cache.hit_rate", "cache", "gauge", "ratio", "http_json",
                "{\"kind\":\"http_json\",\"path\":\"/api/cache/stats\",\"port\":8081,\"onUnavailable\":\"degrade\"}", 30,
                true);
        return new MetricsDeclaration(DIGEST, List.of(hitRate), List.of(new RestartSignalSpec("cache",
                "{\"kind\":\"http_json\",\"path\":\"/actuator/info\",\"port\":9091,\"onUnavailable\":\"degrade\"}")),
                Map.of(8081, hostPort), List.of("cache.hit_rate"));
    }

    private static long count(String table, UUID tenant) {
        Long n = superuser.queryForObject("SELECT count(*) FROM sm_data." + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> placementRow(UUID tenant, UUID instance) {
        return superuser.queryForMap("SELECT p.instance_id, p.target_id, p.connection_id, i.deployment_mode FROM sm_data.placement p"
                + " JOIN sm_data.instance_state i ON i.tenant_id = p.tenant_id AND i.instance_id = p.instance_id"
                + " WHERE p.tenant_id = :t AND p.instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance));
    }

    // ---- the happy path ------------------------------------------------------------------------------------

    @Test
    void aConnectionBackedDeclareStoresTheConnectionOnThePlacementAndDpRemoteOnTheInstance() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);

        InstanceView reserved = service.reserve(tenant, onConnection(connection, freshSpec()));
        InstanceView active = service.activate(tenant, reserved.instanceId());

        assertEquals(InstanceLifecycle.ACTIVE, active.lifecycle());
        assertEquals(1, active.placements().size());
        PlacementView placement = active.placements().get(0);
        assertEquals(connection, placement.connectionId());
        assertNull(placement.targetId(), "a connection-backed placement has no target");
        Map<String, Object> row = placementRow(tenant, reserved.instanceId());
        assertNull(row.get("target_id"));
        assertEquals(connection, row.get("connection_id"));
        assertEquals("dp-remote", row.get("deployment_mode"));
        assertTrue(service.connectionRuntimeLive(tenant, connection));
    }

    @Test
    void aTargetBackedDeclareIsUnchangedAndReadsDpRemoteWithNoConnection() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        String fp = "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        service.register(tenant, target, new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", fp, "/srv/sm",
                "sm-"), KEY.chars(), ACTOR);

        InstanceView reserved = service.reserve(tenant, new DeclareInstanceCommand(UUID.randomUUID(), UUID.randomUUID(),
                "cache", "2.4", "legacy", target, AcquisitionMode.DIRECT_HTTP, freshSpec(), ACTOR));

        Map<String, Object> row = placementRow(tenant, reserved.instanceId());
        assertEquals(target, row.get("target_id"));
        assertNull(row.get("connection_id"));
        assertEquals("dp-remote", row.get("deployment_mode"), "the legacy path is dp-remote, as every row before V039");
        assertEquals(target, reserved.placements().get(0).targetId());
        assertNull(reserved.placements().get(0).connectionId());
    }

    @Test
    void theMetricsDefinitionsAndActiveListAreWrittenButNoEndpointUntilStepThree() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);

        service.reserve(tenant, onConnection(connection, freshSpec(), metricsOn8081(PORTS.incrementAndGet())));

        assertEquals(1, count("generation_active_metrics", tenant), "the generation-0 list is born with the instance");
        assertEquals(0, count("placement_metrics_endpoint", tenant),
                "an endpoint proves its TARGET by a foreign key; a connection-backed placement records none (step 3)");
    }

    // ---- fail closed ---------------------------------------------------------------------------------------

    @Test
    void noProjectionIsAllowedDuringCreation() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID unprojected = UUID.randomUUID();

        assertFalse(service.connectionRuntimeLive(tenant, unprojected));
        service.reserve(tenant, onConnection(unprojected, freshSpec()));
        assertEquals(1, count("instance_state", tenant));
        assertEquals(1, count("placement", tenant));
    }

    @Test
    void anotherTenantsProjectionDoesNotBlockCreationWhileTheGateIsDisabled() {
        UUID owner = SmDataPostgres.freshTenant();
        UUID other = SmDataPostgres.freshTenant();
        UUID connection = project(owner);

        assertFalse(service.connectionRuntimeLive(other, connection), "I5");
        service.reserve(other, onConnection(connection, freshSpec()));
        assertEquals(1, count("instance_state", other));
    }

    @Test
    void aRetiredProjectionDoesNotBlockCreationWhileTheGateIsDisabled() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);
        retireProjection(tenant, connection);

        assertFalse(service.connectionRuntimeLive(tenant, connection));
        service.reserve(tenant, onConnection(connection, freshSpec()));
        assertEquals(1, count("placement", tenant));
    }

    // ---- the envelope and OR-29, per connection --------------------------------------------------------------

    @Test
    void connectionBackedPlacementGrammarIsDeferred() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);

        InstanceView directory = service.reserve(tenant, onConnection(connection,
                spec("file-mesh", "/home/ec2-user/.ssh", 24999)));
        InstanceView container = service.reserve(tenant, onConnection(connection,
                spec("evil", "/srv/sm/evil", 24998)));

        assertEquals(2, count("instance_state", tenant));
        assertEquals(directory.instanceId(), placementRow(tenant, directory.instanceId()).get("instance_id"));
        assertEquals(container.instanceId(), placementRow(tenant, container.instanceId()).get("instance_id"));
    }

    @Test
    void theDatabaseDefersConnectionBackedPlacementEnvelopeChecks() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);
        InstanceView declared = service.reserve(tenant, onConnection(connection, freshSpec()));

        // Bypass the service: a placement row outside the envelope, straight into the table as the runtime role.
        int inserted = SmDataPostgres.runtimeJdbc().update(
                "INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, connection_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :c, 'evil', '/srv/sm/evil', '/app/c', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", UUID.randomUUID())
                        .addValue("i", declared.instanceId()).addValue("c", connection));
        assertEquals(1, inserted);
    }

    @Test
    void aPlacementBoundToNeitherOrToBothIsRefusedByTheDatabase() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);
        InstanceView declared = service.reserve(tenant, onConnection(connection, freshSpec()));
        String insert = "INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, connection_id,"
                + " container_name, remote_config_directory, container_config_path, file_name, mechanism,"
                + " retained_generations, created_at) VALUES (:t, :p, :i, :g, :c, 'sm-z', '/srv/sm/z', '/app/c',"
                + " 'application.yml', 'ADDITIONAL_LOCATION', 10, now())";

        DataAccessException neither = assertThrows(DataAccessException.class, () -> superuser.update(insert,
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", UUID.randomUUID())
                        .addValue("i", declared.instanceId()).addValue("g", null, java.sql.Types.OTHER)
                        .addValue("c", null, java.sql.Types.OTHER)));
        assertTrue(neither.getMessage().contains("placement_one_host_binding"), neither.getMessage());
    }

    @Test
    void anUnprojectedConnectionIsRefusedByTheForeignKeyEvenPastTheService() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);
        InstanceView declared = service.reserve(tenant, onConnection(connection, freshSpec()));

        DataAccessException refused = assertThrows(DataAccessException.class, () -> superuser.update(
                "INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, connection_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :c, 'sm-y', '/srv/sm/y', '/app/c', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", UUID.randomUUID())
                        .addValue("i", declared.instanceId()).addValue("c", UUID.randomUUID())));
        assertTrue(refused.getMessage().contains("placement_connection_has_runtime"), refused.getMessage());
    }

    @Test
    void containerDirectoryAndHealthPortAreUniquePerConnectionAmongLivePlacements() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);
        PlacementSpec first = freshSpec();
        service.reserve(tenant, onConnection(connection, first));
        String u = UUID.randomUUID().toString().substring(0, 8);

        PlacementCollisionException byName = assertThrows(PlacementCollisionException.class, () -> service.reserve(
                tenant, onConnection(connection, spec(first.containerName(), "/srv/sm/" + u, PORTS.incrementAndGet()))));
        PlacementCollisionException byDirectory = assertThrows(PlacementCollisionException.class, () -> service.reserve(
                tenant, onConnection(connection, spec("sm-" + u, first.configDirectory(), PORTS.incrementAndGet()))));
        PlacementCollisionException byPort = assertThrows(PlacementCollisionException.class, () -> service.reserve(
                tenant, onConnection(connection, spec("sm-" + u, "/srv/sm/" + u, first.probe().hostPort()))));

        assertEquals(PlacementCollisionException.Kind.CONTAINER_NAME, byName.kind());
        assertEquals(PlacementCollisionException.Kind.CONFIG_DIRECTORY, byDirectory.kind());
        assertEquals(PlacementCollisionException.Kind.HEALTH_PORT, byPort.kind());
        assertEquals(1, count("instance_state", tenant), "each refused declare rolled its instance back");

        // a SECOND connection is a different binding: the same container name is free there
        UUID second = project(tenant);
        service.reserve(tenant, onConnection(second, spec(first.containerName(), "/srv/sm/" + u, PORTS.incrementAndGet())));
        assertEquals(2, count("instance_state", tenant));
    }

    @Test
    void aProjectionIsNotRetiredWhileALivePlacementSitsOnIt() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID connection = project(tenant);
        InstanceView declared = service.reserve(tenant, onConnection(connection, freshSpec()));

        DataAccessException refused = assertThrows(DataAccessException.class, () -> retireProjection(tenant, connection));
        assertTrue(refused.getMessage().contains("cannot be retired while it has live placements"), refused.getMessage());

        service.abandon(tenant, declared.instanceId());   // retires the placement with the instance
        retireProjection(tenant, connection);              // and now the projection may go
        assertFalse(service.connectionRuntimeLive(tenant, connection));
    }

    @Test
    void theCommandRefusesNeitherOrBothBindingsAndAReservedMode() {
        PlacementSpec spec = freshSpec();
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new DeclareInstanceCommand(id, id, "cache", "2.4", "n", null,
                AcquisitionMode.DIRECT_HTTP, spec, ACTOR, MetricsDeclaration.none(), null, DeploymentMode.DP_REMOTE));
        assertThrows(IllegalArgumentException.class, () -> new DeclareInstanceCommand(id, id, "cache", "2.4", "n", id,
                AcquisitionMode.DIRECT_HTTP, spec, ACTOR, MetricsDeclaration.none(), id, DeploymentMode.DP_REMOTE));
        assertThrows(IllegalArgumentException.class, () -> new DeclareInstanceCommand(id, id, "cache", "2.4", "n", null,
                AcquisitionMode.DIRECT_HTTP, spec, ACTOR, MetricsDeclaration.none(), id, DeploymentMode.HOST_LOCAL));
    }
}
