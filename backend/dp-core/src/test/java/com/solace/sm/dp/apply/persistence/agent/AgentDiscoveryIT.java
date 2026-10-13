package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.agent.AgentDiscoveryService;
import com.solace.sm.dp.api.agw.AgentRegistrationCompletion;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.DiscoveredContainer;
import com.solace.sm.dp.api.agw.DiscoveryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld.Agent;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import com.solace.sm.dp.apply.persistence.AgentItSupport;
import com.solace.sm.dp.apply.persistence.JdbcPlacementRepository;
import com.solace.sm.dp.apply.persistence.JdbcRetirementRepository;
import com.solace.sm.dp.apply.persistence.JdbcInstanceStateRepository;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.dp.retirement.InstanceRetirementServiceImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T4.1 ({@code OR-27}, data plane side) against PostgreSQL 14 as the RUNTIME role, through the REAL
 * {@link AgentDiscoveryService}, the REAL {@code InventoryService} and the real tombstone repository: the placement table, the
 * cap, every refusal and its {@code agent_event}, the four tombstones and the release rules. The control plane's half (T4.2) is
 * the one fake here: a sink that calls the settlement exactly as {@code AgentRegistrationCompleter} does.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentDiscoveryIT {

    private static final String CONFIG_FILE = "/app/external/spring/config/application.yml";
    private static final String CONFIG_DIR = "/app/external/spring/config";

    private final AgentJdbcWorld world = new AgentJdbcWorld();
    private final NamedParameterJdbcTemplate owner = world.owner();

    // ---- fixtures ------------------------------------------------------------------------------------------------------

    private static final PackageDeclarationSource.Probe PROBE =
            new PackageDeclarationSource.Probe("actuator-health", "/actuator/health", 8080, ProbeStrength.READINESS);

    private static PackageDeclarationSource.PackageDeclaration declaration(PackageDeclarationSource.Probe probe, MetricsDeclaration metrics) {
        return new PackageDeclarationSource.PackageDeclaration(Optional.ofNullable(probe), metrics);
    }

    private static MetricsDeclaration metrics(String unit) {
        return new MetricsDeclaration("sha256:" + "a".repeat(64),
                List.of(new MetricDefinitionSpec("cache.hits", "cache-engine", "counter", unit, "http_json",
                        "{\"kind\":\"http_json\",\"port\":8080,\"path\":\"/stats\"}", 30, true)),
                List.of(), Map.of(8080, 8080), List.of("cache.hits"));
    }

    private void packageIs(PackageDeclarationSource.PackageDeclaration declared) {
        world.packages((tenant, solution, version) -> Optional.ofNullable(declared));
    }

    private static DiscoveredContainer container(String id, String name, String label, String sourceDir) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        if (label != null) {
            labels.put("com.solace.sm.instance", label);
            labels.put("com.solace.sm.config-file", CONFIG_FILE);
        }
        labels.put("sm.tenant", "another-organization");          // a fact like any other: decides nothing
        return new DiscoveredContainer(id, name, "registry.example/cache:2.4", labels,
                List.of(new DiscoveredContainer.Mount(sourceDir, CONFIG_DIR)),
                List.of(new DiscoveredContainer.PublishedPort(8080, 18080)));
    }

    private static DiscoveredContainer good(String idChar, String suffix) {
        return container(idChar.repeat(12), "sm-" + suffix, "cache-" + suffix, "/srv/sm/" + suffix);
    }

    /** As {@link #good} publishing the probe port on another host port (two live placements of a target may not share one). */
    private static DiscoveredContainer goodOn(String idChar, String suffix, int hostPort) {
        DiscoveredContainer base = good(idChar, suffix);
        return new DiscoveredContainer(base.containerId(), base.containerName(), base.imageRef(), base.labels(), base.mounts(),
                List.of(new DiscoveredContainer.PublishedPort(8080, hostPort)));
    }

    private record Ctx(Agent agent, AgentSessionRef session) {
    }

    private Ctx agent() {
        Agent agent = world.enrol();
        return new Ctx(agent, world.ready(agent));
    }

    private DiscoveryResult report(Ctx c, DiscoveredContainer item) {
        return report(world.discovery(), c, item);
    }

    private DiscoveryResult report(com.solace.sm.dp.api.agw.AgentDiscoveryPort port, Ctx c, DiscoveredContainer item) {
        List<DiscoveryResult> out = port.report(c.session(), "disc-" + UUID.randomUUID(), List.of(item), world.origin());
        assertEquals(1, out.size());
        return out.get(0);
    }

    private long count(String sql, Object... kv) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) {
            p.addValue((String) kv[i], kv[i + 1]);
        }
        Long n = owner.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private long instancesNamed(Ctx c, String name) {
        return count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t AND name = :n", "t", c.agent().tenantId(), "n", name);
    }

    private Map<String, Object> tombstone(Ctx c, String label, String containerName) {
        return owner.queryForMap("SELECT * FROM sm_data.agent_discovery WHERE tenant_id = :t AND agent_id = :a"
                + " AND instance_label = :l AND container_name = :c", new MapSqlParameterSource().addValue("t", c.agent().tenantId())
                .addValue("a", c.agent().agentId()).addValue("l", label).addValue("c", containerName));
    }

    private long events(Ctx c, String code, String detail) {
        return count("SELECT count(*) FROM sm_data.agent_event WHERE tenant_id = :t AND agent_id = :a AND code = :c"
                + " AND detail_code IS NOT DISTINCT FROM :d", "t", c.agent().tenantId(), "a", c.agent().agentId(), "c", code, "d", detail);
    }

    /** The sink of the happy path: what {@code AgentRegistrationCompleter} does after the control plane accepted the node. */
    private AtomicReference<AgentRegistrationCompletion.Pending> registering() {
        AtomicReference<AgentRegistrationCompletion.Pending> seen = new AtomicReference<>();
        world.completion((pending, settlement) -> {
            seen.set(pending);
            settlement.registered();
        });
        return seen;
    }

    // ---- the placement table -----------------------------------------------------------------------------------------------

    @Test
    void aNewItemMintsAPendingInstanceAndPlacementInTheEnrolledEnvironmentAndAnswersPending() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveryResult result = report(c, good("a", "one"));

        assertEquals(DiscoveryResult.State.PENDING, result.state());
        assertNull(result.code());
        assertNull(result.placementId(), "the placement id is told only once REGISTERED");
        Map<String, Object> instance = owner.queryForMap("SELECT * FROM sm_data.instance_state WHERE tenant_id = :t AND name = 'cache-one'",
                Map.of("t", c.agent().tenantId()));
        assertEquals("pending", instance.get("lifecycle"));
        assertEquals("agent", instance.get("acquisition_mode"));
        Map<String, Object> identity = owner.queryForMap("SELECT * FROM sm_data.agent_identity WHERE agent_id = :a", Map.of("a", c.agent().agentId()));
        assertEquals(identity.get("environment_id"), instance.get("environment_id"), "the environment the agent was ENROLLED into");
        assertEquals(identity.get("catalog_solution_id"), instance.get("catalog_solution_id"));
        assertEquals(identity.get("solution"), instance.get("solution"));
        assertEquals(identity.get("declared_package_version"), instance.get("declared_package_version"));
        assertEquals(owner.queryForObject("SELECT created_by FROM sm_data.target_connection WHERE target_id = :x",
                Map.of("x", c.agent().targetId()), UUID.class), instance.get("created_by"), "created_by is the issuing principal");

        Map<String, Object> placement = owner.queryForMap("SELECT * FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i",
                Map.of("t", c.agent().tenantId(), "i", instance.get("instance_id")));
        assertEquals(c.agent().targetId(), placement.get("target_id"));
        assertEquals("sm-one", placement.get("container_name"));
        assertEquals("/srv/sm/one", placement.get("remote_config_directory"));
        assertEquals(CONFIG_DIR, placement.get("container_config_path"));
        assertEquals("application.yml", placement.get("file_name"));
        assertEquals("ADDITIONAL_LOCATION", placement.get("mechanism"), "Step 0: the server default, as the operator's declare stores");
        assertEquals(10, placement.get("retained_generations"));
        assertEquals("actuator-health", placement.get("health_kind"));
        assertEquals("/actuator/health", placement.get("health_path"));
        assertEquals(18080, placement.get("health_port"), "the host port Docker published for the package's probe port");
        assertEquals("readiness", placement.get("health_strength"));
        assertEquals(c.agent().agentId(), placement.get("created_by"), "the agent is the placement's actor");

        Map<String, Object> row = tombstone(c, "cache-one", "sm-one");
        assertEquals("PENDING", row.get("state"));
        assertEquals(instance.get("instance_id"), row.get("instance_id"));
        assertEquals(placement.get("placement_id"), row.get("placement_id"));
    }

    @Test
    void theSameItemAgainIsIdempotent() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        report(c, good("a", "two"));
        long instances = count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId());
        DiscoveryResult again = report(c, good("a", "two"));
        assertEquals(DiscoveryResult.State.PENDING, again.state());
        assertEquals(instances, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId()));
        assertEquals(1, count("SELECT count(*) FROM sm_data.agent_discovery WHERE tenant_id = :t", "t", c.agent().tenantId()));
    }

    @Test
    void aPackageWithNoProbeStoresNoProbeAndNeedsNoHealthPort() {
        packageIs(declaration(null, MetricsDeclaration.none()));
        Ctx c = agent();
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "heartbeat")).state());
        Map<String, Object> placement = owner.queryForMap("SELECT * FROM sm_data.placement WHERE tenant_id = :t AND container_name = 'sm-heartbeat'",
                Map.of("t", c.agent().tenantId()));
        assertNull(placement.get("health_kind"));
        assertNull(placement.get("health_port"));
    }

    @Test
    void theHealthPortComesFromTheLabelWhenThePackageNamesNoPortOfItsOwn() {
        packageIs(declaration(new PackageDeclarationSource.Probe("http-status", "/ready", null, ProbeStrength.LIVENESS),
                MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveredContainer item = good("a", "labelled");
        Map<String, String> labels = new java.util.LinkedHashMap<>(item.labels());
        assertEquals(DiscoveryResult.State.REFUSED, report(c, item).state(), "no published probe port and no label: incomplete");
        labels.put("com.solace.sm.health-port", "19090");
        DiscoveredContainer withLabel = new DiscoveredContainer("b".repeat(12), "sm-labelled", item.imageRef(), labels, item.mounts(), List.of());
        assertEquals(DiscoveryResult.State.PENDING, report(c, withLabel).state(), "a re-created container is retried");
        assertEquals(19090, owner.queryForObject("SELECT health_port FROM sm_data.placement WHERE tenant_id = :t AND container_name = 'sm-labelled'",
                Map.of("t", c.agent().tenantId()), Integer.class));
    }

    @Test
    void metricsEndpointsAreMappedToThePublishedHostPorts() {
        packageIs(declaration(PROBE, metrics("hits")));
        Ctx c = agent();
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "metrics")).state());
        assertEquals(18080, owner.queryForObject("SELECT e.host_port FROM sm_data.placement_metrics_endpoint e JOIN sm_data.placement p"
                + " ON p.tenant_id = e.tenant_id AND p.placement_id = e.placement_id WHERE p.tenant_id = :t AND p.container_name = 'sm-metrics'",
                Map.of("t", c.agent().tenantId()), Integer.class));
    }

    @Test
    void anEmptyMetricUnitIsRefusedAtTheSourceAndWritesNothing() {
        packageIs(declaration(PROBE, metrics("")));
        Ctx c = agent();
        DiscoveryResult result = report(c, good("a", "nounit"));
        assertEquals(DiscoveryResult.State.REFUSED, result.state());
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, result.code());
        assertEquals(0, instancesNamed(c, "cache-nounit"));
    }

    // ---- refusals -------------------------------------------------------------------------------------------------------------

    @Test
    void aContainerOutsideThePrefixOrTheConfigRootIsRefusedAndNothingIsMinted() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveryResult prefix = report(c, container("a".repeat(12), "evil-1", "cache-evil", "/srv/sm/evil"));
        assertEquals(GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE, prefix.code());
        DiscoveryResult root = report(c, container("b".repeat(12), "sm-evil2", "cache-evil2", "/etc/evil"));
        assertEquals(GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE, root.code());
        DiscoveryResult sibling = report(c, container("c".repeat(12), "sm-evil3", "cache-evil3", "/srv/sm-evil"));
        assertEquals(GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE, sibling.code(), "a bare prefix match is not 'beneath the root'");
        assertEquals(0, count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND container_name LIKE 'evil%'", "t", c.agent().tenantId()));
        assertEquals(0, instancesNamed(c, "cache-evil") + instancesNamed(c, "cache-evil2") + instancesNamed(c, "cache-evil3"));
        assertEquals("REFUSED", tombstone(c, "cache-evil", "evil-1").get("state"));
        assertEquals(3, events(c, "agent.discovery.refused", "agent.discovery.outside.envelope"));
    }

    @Test
    void missingFactsAreRefusedIncomplete() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveredContainer noMount = new DiscoveredContainer("a".repeat(12), "sm-nomount", "img",
                Map.of("com.solace.sm.instance", "cache-nomount", "com.solace.sm.config-file", CONFIG_FILE), List.of(), List.of());
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, report(c, noMount).code());
        DiscoveredContainer noConfigLabel = new DiscoveredContainer("b".repeat(12), "sm-nocfg", "img",
                Map.of("com.solace.sm.instance", "cache-nocfg"), List.of(new DiscoveredContainer.Mount("/srv/sm/x", CONFIG_DIR)), List.of());
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, report(c, noConfigLabel).code());
        DiscoveredContainer traversal = new DiscoveredContainer("c".repeat(12), "sm-trav", "img",
                Map.of("com.solace.sm.instance", "cache-trav", "com.solace.sm.config-file", "/app/../etc/passwd"),
                List.of(new DiscoveredContainer.Mount("/srv/sm/x", "/app/..")), List.of());
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, report(c, traversal).code());
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, report(c, container("d".repeat(12), "sm-nolabel", null, "/srv/sm/y")).code(),
                "no instance label: refused with a code, not silently dropped");
    }

    @Test
    void theCapRefusesTheItemPastItAndALaterReportDoesNotRetryIt() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();                                           // one live placement already (the world seeds it)
        AgentDiscoveryService capped = world.discoveryWith(2);
        assertEquals(DiscoveryResult.State.PENDING, report(capped, c, goodOn("a", "cap1", 18081)).state(), "the second live placement is within the cap");
        DiscoveryResult over = report(capped, c, goodOn("b", "cap2", 18082));
        assertEquals(DiscoveryResult.State.REFUSED, over.state());
        assertEquals(GatewayCode.AGENT_PLACEMENT_CAP, over.code());
        assertEquals("agent.placement.cap", over.code().wire());
        assertEquals(0, instancesNamed(c, "cache-cap2"));
        assertEquals(1, events(c, "agent.discovery.refused", "agent.placement.cap"));
        // a larger cap on the next Connect does not re-mint the refused key: it is a tombstone until the container is re-created
        assertEquals(DiscoveryResult.State.REFUSED, report(world.discoveryWith(5), c, goodOn("b", "cap2", 18082)).state());
        assertEquals(0, instancesNamed(c, "cache-cap2"));
    }

    @Test
    void aLabelEqualToALiveInstanceNameIsRefusedNameTaken() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        String taken = owner.queryForObject("SELECT name FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                Map.of("t", c.agent().tenantId(), "i", c.agent().instanceId()), String.class);
        DiscoveryResult result = report(c, container("a".repeat(12), "sm-dup", taken, "/srv/sm/dup"));
        assertEquals(GatewayCode.INSTANCE_NAME_TAKEN, result.code());
        assertEquals("instance.name.taken", result.code().wire());
    }

    @Test
    void theIndexesAMintCouldTripAreAnsweredWithCodesNeverRaw23505() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "idx")).state());
        // another label, same container name
        assertEquals(GatewayCode.AGENT_DISCOVERY_CONTAINER_TAKEN,
                report(c, container("b".repeat(12), "sm-idx", "cache-idx-b", "/srv/sm/idx-b")).code());
        // another container, same config directory
        assertEquals(GatewayCode.AGENT_DISCOVERY_CONFIG_TAKEN,
                report(c, container("c".repeat(12), "sm-idx-c", "cache-idx-c", "/srv/sm/idx")).code());
        // another container, same published health port
        assertEquals(GatewayCode.AGENT_DISCOVERY_PORT_TAKEN,
                report(c, container("d".repeat(12), "sm-idx-d", "cache-idx-d", "/srv/sm/idx-d")).code());
    }

    @Test
    void aHealthPortThatIsAnotherPlacementsMetricsPortIsRefusedPortTakenAndRollsBackWhatWasWritten() {
        // item one probes on 8080 (published 18300) and serves metrics on 9090 (published 18301)
        MetricsDeclaration onNinety = new MetricsDeclaration("sha256:" + "b".repeat(64),
                List.of(new MetricDefinitionSpec("cache.size", "cache-engine", "gauge", "entries", "http_json",
                        "{\"kind\":\"http_json\",\"port\":9090,\"path\":\"/stats\"}", 30, true)),
                List.of(), Map.of(9090, 9090), List.of("cache.size"));
        packageIs(declaration(PROBE, onNinety));
        Ctx c = agent();
        DiscoveredContainer first = container("a".repeat(12), "sm-cross1", "cache-cross1", "/srv/sm/cross1");
        first = new DiscoveredContainer(first.containerId(), first.containerName(), first.imageRef(), first.labels(), first.mounts(),
                List.of(new DiscoveredContainer.PublishedPort(8080, 18300), new DiscoveredContainer.PublishedPort(9090, 18301)));
        assertEquals(DiscoveryResult.State.PENDING, report(c, first).state());
        // item two's health port (8080 published as 18301) is item one's metrics host port: the database trigger says no
        DiscoveredContainer second = container("b".repeat(12), "sm-cross2", "cache-cross2", "/srv/sm/cross2");
        second = new DiscoveredContainer(second.containerId(), second.containerName(), second.imageRef(), second.labels(), second.mounts(),
                List.of(new DiscoveredContainer.PublishedPort(8080, 18301), new DiscoveredContainer.PublishedPort(9090, 18302)));
        DiscoveryResult refused = report(c, second);
        assertEquals(GatewayCode.AGENT_DISCOVERY_PORT_TAKEN, refused.code(), "a code, never a raw 23505");
        assertEquals(0, instancesNamed(c, "cache-cross2"), "the instance, the placement and the endpoints rolled back together");
        assertEquals("REFUSED", tombstone(c, "cache-cross2", "sm-cross2").get("state"));
    }

    @Test
    void aReportCannotNameAnotherEnvironmentOrOrganization() {
        Set<String> components = new java.util.TreeSet<>();
        for (var component : DiscoveredContainer.class.getRecordComponents()) {
            components.add(component.getName());
        }
        assertEquals(Set.of("containerId", "containerName", "imageRef", "labels", "mounts", "publishedPorts"), components,
                "the fields do not exist: the placement's environment, organization and target are the enrolment's");
    }

    @Test
    void aStaleSessionWritesNothing() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        world.open(c.agent());
        try {
            world.discovery().report(c.session(), "disc-stale", List.of(good("a", "stale")), world.origin());
            throw new AssertionError("a stale session must be refused");
        } catch (com.solace.sm.dp.api.agw.GatewayPortException refused) {
            assertEquals(GatewayCode.STALE_EPOCH, refused.code());
        }
        assertEquals(0, instancesNamed(c, "cache-stale"));
    }

    // ---- the completion sink --------------------------------------------------------------------------------------------------

    /**
     * W4 integration (lanes A and D): a container that becomes REGISTERED activates its instance through {@code InventoryService#activate},
     * which tells the declarations-change sink, so the agent's gateway is prompted (to renew, the lease being a snapshot) at once rather
     * than at its next renewal.
     */
    @Test
    void aRegisteredContainerSignalsItsAgentThatItsDeclarationsChanged() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        registering();
        Ctx c = agent();
        List<UUID> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        world.declarations().onDeclarationsChanged(told::add);

        DiscoveryResult result = world.discovery().report(c.session(), "disc-signal", List.of(good("a", "signal")), world.origin()).get(0);

        assertEquals(DiscoveryResult.State.REGISTERED, result.state());
        assertTrue(told.contains(c.agent().agentId()), "the activation's signal reached the agent enrolled on the target: " + told);
    }

    @Test
    void thePendingItemIsHandedToTheSinkWithTheTenantFromTheIdentityAndTheRpcsOriginAndBecomesRegistered() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        AtomicReference<AgentRegistrationCompletion.Pending> seen = registering();
        Ctx c = agent();
        AgentRequestOrigin origin = world.origin();
        DiscoveryResult result = world.discovery().report(c.session(), "disc-sink", List.of(good("a", "sink")), origin).get(0);

        assertEquals(DiscoveryResult.State.REGISTERED, result.state());
        assertNotNull(result.placementId());
        AgentRegistrationCompletion.Pending pending = seen.get();
        assertEquals(c.agent().tenantId(), pending.organizationId());
        assertEquals(c.agent().agentId(), pending.agentId());
        assertEquals(origin, pending.origin(), "the RPC's real correlation id and client address");
        assertEquals(owner.queryForObject("SELECT environment_id FROM sm_data.agent_identity WHERE agent_id = :a",
                Map.of("a", c.agent().agentId()), UUID.class), pending.environmentId());
        assertEquals("active", owner.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE instance_id = :i",
                Map.of("i", pending.instanceId()), String.class));
        assertEquals("REGISTERED", tombstone(c, "cache-sink", "sm-sink").get("state"));
        assertEquals(1, events(c, "agent.discovery.registered", null));
    }

    @Test
    void aSinkThatThrowsLeavesThePendingRowAndTheNextReportCompletesIt() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        AtomicInteger calls = new AtomicInteger();
        world.completion((pending, settlement) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("the control plane was down");
            }
            settlement.registered();
        });
        Ctx c = agent();
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "retry")).state(), "the report still answers; the row is the contract");
        assertEquals("PENDING", tombstone(c, "cache-retry", "sm-retry").get("state"));
        DiscoveryResult next = report(c, good("a", "retry"));
        assertEquals(DiscoveryResult.State.REGISTERED, next.state(), "the next Connect's report finishes it");
        assertEquals(2, calls.get());
    }

    @Test
    void aRefusalFromTheSinkAbandonsTheReservationFreesTheNameAndTheSlotAndIsAudited() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        world.completion((pending, settlement) -> settlement.refused(GatewayCode.ENTITLEMENT_QUOTA));
        Ctx c = agent();
        DiscoveryResult result = report(c, good("a", "quota"));
        assertEquals(DiscoveryResult.State.REFUSED, result.state());
        assertEquals(GatewayCode.ENTITLEMENT_QUOTA, result.code());
        assertEquals("entitlement.quota", result.code().wire());
        assertEquals("abandoned", owner.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t AND name = 'cache-quota'",
                Map.of("t", c.agent().tenantId()), String.class));
        assertEquals(1, count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND container_name = 'sm-quota' AND retired_at IS NOT NULL",
                "t", c.agent().tenantId()), "the placement is retired: the slot is free");
        assertEquals("REFUSED", tombstone(c, "cache-quota", "sm-quota").get("state"));
        assertEquals("entitlement.quota", tombstone(c, "cache-quota", "sm-quota").get("refusal_code"));
        assertEquals(1, events(c, "agent.discovery.refused", "entitlement.quota"));
        world.completion(null);
        assertEquals(DiscoveryResult.State.REFUSED, report(c, good("a", "quota")).state(), "and the next Connect does not retry it");
        assertEquals(1, instancesNamed(c, "cache-quota"));
    }

    // ---- review fixes (W4 lane A fix round) -----------------------------------------------------------------------------------

    @Test
    void withNoPackageReaderWiredTheItemIsRefusedUnavailableAuditedWithNoRowAndRetriedOnTheNextReport() {
        Ctx c = agent();
        DiscoveryResult refused = report(world.discoveryWithoutPackageReader(), c, good("a", "noreader"));
        assertEquals(GatewayCode.AGENT_DISCOVERY_UNAVAILABLE, refused.code());
        assertEquals(0, count("SELECT count(*) FROM sm_data.agent_discovery WHERE tenant_id = :t", "t", c.agent().tenantId()),
                "a wiring fault is not a verdict on the container: no tombstone");
        assertEquals(0, instancesNamed(c, "cache-noreader"));
        assertEquals(1, events(c, "agent.discovery.refused", "agent.discovery.unavailable"));
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "noreader")).state(), "the same container is retried");
    }

    @Test
    void aMalformedItemReportedAgainIsOneAuditEventNotOnePerReport() {
        Ctx c = agent();
        DiscoveredContainer unlabelled = container("a".repeat(12), "sm-nolabel2", null, "/srv/sm/y");
        for (int i = 0; i < 3; i++) {
            assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, report(c, unlabelled).code());
        }
        assertEquals(1, events(c, "agent.discovery.refused", "agent.discovery.incomplete"));
    }

    @Test
    void itemsPastThePerReportBoundAreAnsweredCapAndNeverLookedAt() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        List<DiscoveryResult> out = world.discoveryWith(16, Duration.ofMinutes(10), 2).report(c.session(), "disc-bound",
                List.of(goodOn("a", "b1", 18401), goodOn("b", "b2", 18402), goodOn("c", "b3", 18403)), world.origin());
        assertEquals(List.of(DiscoveryResult.State.PENDING, DiscoveryResult.State.PENDING, DiscoveryResult.State.REFUSED),
                out.stream().map(DiscoveryResult::state).toList());
        assertEquals(GatewayCode.AGENT_PLACEMENT_CAP, out.get(2).code());
        assertEquals(0, instancesNamed(c, "cache-b3"));
    }

    @Test
    void aPendingRowOlderThanTheWindowIsNeverHandedToTheSink() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        report(c, good("a", "stale"));                                  // no sink: PENDING
        AtomicInteger calls = new AtomicInteger();
        world.completion((pending, settlement) -> calls.incrementAndGet());
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "stale")).state());
        assertEquals(1, calls.get(), "inside the window the sink is called");
        world.clock().advance(Duration.ofMinutes(11));
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "stale")).state());
        assertEquals(1, calls.get(), "past the window it is the sweep's, not the completer's");
    }

    @Test
    void aSettlementAfterTheSweepAbandonedTheInstanceIsANoOpNotAFailure() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        world.completion((pending, settlement) -> {
            world.clock().advance(Duration.ofMinutes(11));
            world.inventory().settlePending(c.agent().tenantId(), Set.of(), Duration.ofMinutes(10));
            settlement.registered();                                    // the CP committed, but the sweep got there first
        });
        assertEquals(DiscoveryResult.State.ABANDONED, report(c, good("a", "late")).state(), "the sweep's tombstone is the answer");
        assertEquals(0, events(c, "agent.discovery.registered", null));
    }

    @Test
    void aRefusalOfAnInstanceThatIsAlreadyActiveKeepsTheRefusedTombstoneItsEventAndLeavesTheInstanceActive() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        world.completion((pending, settlement) -> {
            world.inventory().activate(c.agent().tenantId(), pending.instanceId());     // the sweep's rule 1 got there first
            settlement.refused(GatewayCode.ENTITLEMENT_QUOTA);
        });
        assertEquals(DiscoveryResult.State.REFUSED, report(c, good("a", "active")).state());
        assertEquals("REFUSED", tombstone(c, "cache-active", "sm-active").get("state"), "the REFUSED write is not rolled back");
        assertEquals(1, events(c, "agent.discovery.refused", "entitlement.quota"));
        assertEquals("active", owner.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t AND name = 'cache-active'",
                Map.of("t", c.agent().tenantId()), String.class));
    }

    /**
     * The sweep's lock order is instance, placements, then the discovery row. A holder in that order is simulated by an open owner
     * transaction that locks the instance and then touches the discovery row while {@code refused} runs: if the settlement took the
     * discovery row first it would deadlock with the holder (PostgreSQL aborts one); taking the instance first, it waits and then
     * completes, and its REFUSED tombstone and event survive.
     */
    @Test
    void refusedTakesTheInstanceLockFirstSoAnInterleavedSweepOrderHolderCannotDeadlockIt() throws Exception {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        AtomicReference<AgentRegistrationCompletion.Settlement> held = new AtomicReference<>();
        world.completion((pending, settlement) -> held.set(settlement));
        report(c, good("a", "lockorder"));
        UUID instance = owner.queryForObject("SELECT instance_id FROM sm_data.agent_discovery WHERE tenant_id = :t AND instance_label = 'cache-lockorder'",
                Map.of("t", c.agent().tenantId()), UUID.class);
        org.springframework.transaction.support.TransactionTemplate ownerTx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getJdbcTemplate().getDataSource()));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?>[] settled = new java.util.concurrent.Future<?>[1];
            ownerTx.executeWithoutResult(status -> {
                owner.queryForList("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i FOR NO KEY UPDATE",
                        Map.of("t", c.agent().tenantId(), "i", instance));
                settled[0] = executor.submit(() -> held.get().refused(GatewayCode.ENTITLEMENT_QUOTA));
                long deadline = System.currentTimeMillis() + 15_000;
                while (count("SELECT count(*) FROM pg_locks WHERE NOT granted") == 0) {
                    if (System.currentTimeMillis() > deadline || settled[0].isDone()) {
                        String why = "still running";
                        if (settled[0].isDone()) {
                            try {
                                settled[0].get();
                                why = "finished without waiting";
                            } catch (Exception e) {
                                why = String.valueOf(e.getCause());
                            }
                        }
                        throw new AssertionError("the settlement should be waiting on the instance row: " + why);
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                owner.update("UPDATE sm_data.agent_discovery SET container_id = container_id WHERE tenant_id = :t AND instance_id = :i",
                        Map.of("t", c.agent().tenantId(), "i", instance));
            });
            settled[0].get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals("REFUSED", tombstone(c, "cache-lockorder", "sm-lockorder").get("state"));
        assertEquals(1, events(c, "agent.discovery.refused", "entitlement.quota"));
        assertEquals("abandoned", owner.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE instance_id = :i",
                Map.of("i", instance), String.class));
    }

    // ---- tombstones (AGW-D30, P15-9) -------------------------------------------------------------------------------------------

    private InstanceRetirementServiceImpl retirement() {
        var rt = world.runtime();
        return new InstanceRetirementServiceImpl(world.transactions(), new JdbcInstanceStateRepository(rt),
                new JdbcPlacementRepository(rt), new JdbcRetirementRepository(rt, new ApplyScopeRepository(rt)),
                world.clock());
    }

    private DiscoveryResult register(Ctx c, DiscoveredContainer item) {
        registering();
        DiscoveryResult result = report(c, item);
        assertEquals(DiscoveryResult.State.REGISTERED, result.state());
        return result;
    }

    @Test
    void undeclareThenReconnectDoesNotReRegister() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveryResult registered = register(c, good("a", "undecl"));
        UUID instance = owner.queryForObject("SELECT instance_id FROM sm_data.agent_discovery WHERE tenant_id = :t AND instance_label = 'cache-undecl'",
                Map.of("t", c.agent().tenantId()), UUID.class);
        InstanceRetirementServiceImpl retirement = retirement();
        retirement.beginRetire(c.agent().tenantId(), instance, UUID.randomUUID(), UUID.randomUUID(), WorkloadDisposition.LEAVE_RUNNING);
        retirement.completeRetire(c.agent().tenantId(), instance);

        assertEquals("UNDECLARED", tombstone(c, "cache-undecl", "sm-undecl").get("state"), "set in the transaction that retired it");
        long instances = count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId());
        DiscoveryResult again = report(c, good("a", "undecl"));
        assertEquals(DiscoveryResult.State.UNDECLARED, again.state());
        assertEquals(registered.placementId(), again.placementId(), "the placement id is echoed");
        assertEquals(instances, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId()));
        // a CI/CD redeploy re-creating the container must not silently re-register what an operator removed
        assertEquals(DiscoveryResult.State.UNDECLARED, report(c, good("b", "undecl")).state(), "undeclaredThenRecreatedStaysUndeclared");
        assertEquals(instances, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId()));
        // only a changed label value is a different key, and so a new registration
        DiscoveredContainer relabelled = container("b".repeat(12), "sm-undecl", "cache-undecl-v2", "/srv/sm/undecl-v2");
        assertEquals(DiscoveryResult.State.REGISTERED, report(c, relabelled).state());
    }

    @Test
    void retirePlacementThenReconnectDoesNotReRegister() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveryResult registered = register(c, good("a", "retire"));
        UUID instance = owner.queryForObject("SELECT instance_id FROM sm_data.agent_discovery WHERE tenant_id = :t AND instance_label = 'cache-retire'",
                Map.of("t", c.agent().tenantId()), UUID.class);
        // retirePlacement refuses the LAST live placement, so the instance is given a second one (an ordinary operator add elsewhere)
        UUID ssh = AgentItSupport.sshTarget(owner, c.agent().tenantId());
        AgentItSupport.placement(owner, c.agent().tenantId(), instance, ssh, UUID.randomUUID());
        world.inventory().retirePlacement(c.agent().tenantId(), instance, registered.placementId(), UUID.randomUUID(), UUID.randomUUID());

        assertEquals("RETIRED", tombstone(c, "cache-retire", "sm-retire").get("state"));
        long placements = count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", "t", c.agent().tenantId());
        DiscoveryResult again = report(c, good("a", "retire"));
        assertEquals(DiscoveryResult.State.RETIRED, again.state());
        assertEquals(placements, count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", "t", c.agent().tenantId()));
    }

    @Test
    void sweepThenReconnectDoesNotReMint() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();                                            // no sink: the item stays PENDING
        assertEquals(DiscoveryResult.State.PENDING, report(c, good("a", "sweep")).state());
        world.clock().advance(Duration.ofMinutes(11));
        world.inventory().settlePending(c.agent().tenantId(), Set.of(), Duration.ofMinutes(10));

        assertEquals("ABANDONED", tombstone(c, "cache-sweep", "sm-sweep").get("state"), "set in the sweep's transaction");
        long instances = count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId());
        DiscoveryResult next = report(c, good("a", "sweep"));
        assertEquals(DiscoveryResult.State.ABANDONED, next.state());
        assertEquals(instances, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t", "t", c.agent().tenantId()));
        assertEquals(1, count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND container_name = 'sm-sweep' AND retired_at IS NOT NULL",
                "t", c.agent().tenantId()));
    }

    @Test
    void anAbandonedKeyIsRetriedWhenTheContainerIsRecreated() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        report(c, good("a", "reabandon"));
        world.clock().advance(Duration.ofMinutes(11));
        world.inventory().settlePending(c.agent().tenantId(), Set.of(), Duration.ofMinutes(10));
        registering();
        DiscoveryResult retried = report(c, good("b", "reabandon"));
        assertEquals(DiscoveryResult.State.REGISTERED, retried.state());
        assertEquals("b".repeat(12), tombstone(c, "cache-reabandon", "sm-reabandon").get("container_id"));
    }

    @Test
    void refusedThenRecreatedIsRetried() {
        Ctx c = agent();
        packageIs(null);                                            // the version is not loadable: incomplete
        DiscoveryResult refused = report(c, good("a", "retry2"));
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, refused.code());
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        DiscoveryResult same = report(c, good("a", "retry2"));
        assertEquals(DiscoveryResult.State.REFUSED, same.state(), "the same container: the tombstone answers, nothing is retried");
        assertEquals(0, instancesNamed(c, "cache-retry2"));
        DiscoveryResult recreated = report(c, good("b", "retry2"));
        assertEquals(DiscoveryResult.State.PENDING, recreated.state(), "a re-created container is the operator's change");
        assertEquals(1, instancesNamed(c, "cache-retry2"));
        assertEquals("PENDING", tombstone(c, "cache-retry2", "sm-retry2").get("state"));
    }

    @Test
    void aRefusedKeyThatIsRefusedAgainAfterRecreationStaysRefused() {
        Ctx c = agent();
        packageIs(null);
        assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, report(c, good("a", "stuck")).code());
        DiscoveryResult again = report(c, good("b", "stuck"));
        assertEquals(DiscoveryResult.State.REFUSED, again.state());
        assertEquals("b".repeat(12), tombstone(c, "cache-stuck", "sm-stuck").get("container_id"));
        assertEquals("REFUSED", tombstone(c, "cache-stuck", "sm-stuck").get("state"));
    }

    @Test
    void aRecreatedContainerOfARegisteredKeyKeepsItsPlacement() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        DiscoveryResult first = register(c, good("a", "keep"));
        long placements = count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", "t", c.agent().tenantId());
        DiscoveryResult second = report(c, good("b", "keep"));
        assertEquals(DiscoveryResult.State.REGISTERED, second.state());
        assertEquals(first.placementId(), second.placementId());
        assertEquals("b".repeat(12), tombstone(c, "cache-keep", "sm-keep").get("container_id"), "the new container id is recorded");
        assertEquals(placements, count("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t", "t", c.agent().tenantId()));
    }

    @Test
    void theOperatorsOwnReserveStillRefusesAnAgentTarget() {
        Ctx c = agent();
        try {
            world.inventory().reserve(c.agent().tenantId(), new com.solace.sm.dp.api.inventory.DeclareInstanceCommand(UUID.randomUUID(),
                    UUID.randomUUID(), "cache", "2.4", "op-made", c.agent().targetId(), com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH,
                    new com.solace.sm.dp.api.inventory.PlacementSpec("sm-op", "/srv/sm/op", CONFIG_DIR, "application.yml", null),
                    UUID.randomUUID(), MetricsDeclaration.none()));
            throw new AssertionError("an operator must not reserve on an agent target");
        } catch (com.solace.sm.dp.api.inventory.TargetIsAgentManagedException expected) {
            assertTrue(true);
        }
    }

    @Test
    void severalItemsAreAnsweredInTheReportsOrderEachInItsOwnTransaction() {
        packageIs(declaration(PROBE, MetricsDeclaration.none()));
        Ctx c = agent();
        List<DiscoveredContainer> items = new ArrayList<>(List.of(goodOn("a", "m1", 18081), container("b".repeat(12), "other", "cache-m2", "/srv/sm/m2"), goodOn("c", "m3", 18082)));
        List<DiscoveryResult> out = world.discovery().report(c.session(), "disc-many", items, world.origin());
        assertEquals(List.of("a".repeat(12), "b".repeat(12), "c".repeat(12)), out.stream().map(DiscoveryResult::containerId).toList());
        assertEquals(List.of(DiscoveryResult.State.PENDING, DiscoveryResult.State.REFUSED, DiscoveryResult.State.PENDING),
                out.stream().map(DiscoveryResult::state).toList(), "one refusal does not undo its neighbours");
        assertFalse(out.get(1).code() == null);
    }
}
