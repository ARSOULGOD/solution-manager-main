package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceNameTakenException;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
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
 * Plan 010 T2.5 against a real PostgreSQL 14, as {@code sm_dp_runtime}: the inventory service over the three JDBC
 * repositories and the real secret store. (In the persistence test package, not {@code dp.inventory}: the shared
 * Testcontainers harness {@code SmDataPostgres} is package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class InventoryServiceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final AtomicInteger PORTS = new AtomicInteger(20000);
    /** Plan 013 T1.1: a key must now OPEN as an unencrypted private key before it is stored (hand-built, no tool). */
    private static final SshTestKeys.Key KEY_ONE = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    private static final SshTestKeys.Key KEY_TWO = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());

    private static NamedParameterJdbcTemplate superuser;
    private static EnvelopeSecretStore secrets;
    private static InventoryService service;
    private static final SkewClock CLOCK = new SkewClock();

    /** The real clock plus a skew a test can advance, so "older than" needs no sleeping and no race. */
    static final class SkewClock extends Clock {
        private volatile Duration skew = Duration.ZERO;

        void advance(Duration d) {
            skew = skew.plus(d);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return Instant.now().plus(skew);
        }
    }

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 21), null));
        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
        service = new InventoryService(tx, new JdbcInstanceStateRepository(jdbc), new JdbcPlacementRepository(jdbc),
                new JdbcTargetConnectionRepository(jdbc), secrets, CLOCK);
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static UUID registerTarget(UUID tenant) {
        UUID target = UUID.randomUUID();
        String fp = "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        service.register(tenant, target,
                new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", fp, "/srv/sm", "sm-"),
                KEY_ONE.chars(), ACTOR);
        return target;
    }

    private static PlacementSpec spec(String container, String directory, int port) {
        return new PlacementSpec(container, directory, "/app/external/spring/config", "application.yml",
                new HealthProbeSpec("actuator-health", "/actuator/health", port, ProbeStrength.READINESS));
    }

    private static PlacementSpec freshSpec() {
        String u = UUID.randomUUID().toString().substring(0, 8);
        return spec("sm-" + u, "/srv/sm/" + u, PORTS.incrementAndGet());
    }

    private static DeclareInstanceCommand command(UUID target, PlacementSpec placement) {
        return new DeclareInstanceCommand(UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4",
                "inst-" + UUID.randomUUID().toString().substring(0, 8), target, AcquisitionMode.DIRECT_HTTP,
                placement, ACTOR);
    }

    private static long count(String table, UUID tenant) {
        Long n = superuser.queryForObject("SELECT count(*) FROM sm_data." + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
        return n == null ? 0 : n;
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void reserveMintsAUuidV7AndLeavesTheInstancePending() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);

        InstanceView v = service.reserve(tenant, command(target, freshSpec()));

        assertEquals(7, v.instanceId().version(), "the DP mints a time-ordered id");
        assertEquals(InstanceLifecycle.PENDING, v.lifecycle());
        assertEquals(AcquisitionMode.DIRECT_HTTP, v.acquisition());
        assertEquals(1, v.placements().size());
        PlacementView p = v.placements().get(0);
        assertEquals(target, p.targetId());
        assertEquals(Optional.of(ProbeStrength.READINESS), p.probeStrength());
        assertTrue(v.stateVersion() > 0);
        assertEquals("pending", superuser.queryForObject(
                "SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", v.instanceId()), String.class));
        assertEquals("ADDITIONAL_LOCATION", superuser.queryForObject(
                "SELECT mechanism FROM sm_data.placement WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", p.placementId()), String.class));
    }

    @Test
    void aPlacementWithNoProbeStoresNoProbeAndReportsNoStrength() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        String u = UUID.randomUUID().toString().substring(0, 8);

        InstanceView v = service.reserve(tenant, command(target,
                new PlacementSpec("sm-" + u, "/srv/sm/" + u, "/app/external/spring/config", "application.yml", null)));

        assertEquals(Optional.empty(), v.placements().get(0).probeStrength());
    }

    @Test
    void aPendingInstanceIsNotListedNorFindable() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        DeclareInstanceCommand c = command(target, freshSpec());
        InstanceView v = service.reserve(tenant, c);

        assertEquals(Optional.empty(), service.findActive(tenant, v.instanceId()));
        assertEquals(List.of(), service.listActive(tenant, c.catalogSolutionId()));

        service.activate(tenant, v.instanceId());
        assertEquals(v.instanceId(), service.findActive(tenant, v.instanceId()).orElseThrow().instanceId());
        assertEquals(1, service.listActive(tenant, c.catalogSolutionId()).size());
        assertEquals(List.of(), service.listActive(tenant, UUID.randomUUID()), "narrowed by catalog solution");
    }

    @Test
    void activateIsIdempotentAndStampsAHigherStateVersion() {
        UUID tenant = SmDataPostgres.freshTenant();
        InstanceView reserved = service.reserve(tenant, command(registerTarget(tenant), freshSpec()));

        InstanceView first = service.activate(tenant, reserved.instanceId());
        assertEquals(InstanceLifecycle.ACTIVE, first.lifecycle());
        assertTrue(first.stateVersion() > reserved.stateVersion(), "activation is an observable change");

        InstanceView second = service.activate(tenant, reserved.instanceId());
        assertEquals(first.stateVersion(), second.stateVersion(), "a no-op re-activation must not write or stamp");
    }

    @Test
    void anAbandonedInstanceCannotBeActivatedAndAnActiveOneCannotBeAbandoned() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView gone = service.reserve(tenant, command(target, freshSpec()));
        service.abandon(tenant, gone.instanceId());
        assertThrows(IllegalStateException.class, () -> service.activate(tenant, gone.instanceId()));

        InstanceView live = service.activate(tenant,
                service.reserve(tenant, command(target, freshSpec())).instanceId());
        assertThrows(IllegalStateException.class, () -> service.abandon(tenant, live.instanceId()));
        assertThrows(NoSuchElementException.class, () -> service.activate(tenant, UUID.randomUUID()));
    }

    @Test
    void aPlacementCollisionIsReportedAsItsClassWithoutNamingTheOtherInstance() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        PlacementSpec first = freshSpec();
        DeclareInstanceCommand firstCommand = command(target, first);
        InstanceView holder = service.reserve(tenant, firstCommand);
        long instancesBefore = count("instance_state", tenant);
        long placementsBefore = count("placement", tenant);

        String u = UUID.randomUUID().toString().substring(0, 8);
        PlacementCollisionException byName = assertThrows(PlacementCollisionException.class, () -> service.reserve(
                tenant, command(target, spec(first.containerName(), "/srv/sm/" + u, PORTS.incrementAndGet()))));
        PlacementCollisionException byDirectory = assertThrows(PlacementCollisionException.class, () -> service
                .reserve(tenant, command(target, spec("sm-" + u, first.configDirectory(), PORTS.incrementAndGet()))));
        PlacementCollisionException byPort = assertThrows(PlacementCollisionException.class, () -> service.reserve(
                tenant, command(target, spec("sm-" + u, "/srv/sm/" + u, first.probe().hostPort()))));

        assertEquals(PlacementCollisionException.Kind.CONTAINER_NAME, byName.kind());
        assertEquals(PlacementCollisionException.Kind.CONFIG_DIRECTORY, byDirectory.kind());
        assertEquals(PlacementCollisionException.Kind.HEALTH_PORT, byPort.kind());
        for (PlacementCollisionException e : List.of(byName, byDirectory, byPort)) {
            String text = e + " " + e.getMessage();
            assertFalse(text.contains(holder.instanceId().toString()), text);
            assertFalse(text.contains(firstCommand.name()), text);
            assertFalse(text.contains(first.containerName()), text);
            assertFalse(text.contains(first.configDirectory()), text);
        }
        assertEquals(instancesBefore, count("instance_state", tenant), "a refused declare leaves no instance row");
        assertEquals(placementsBefore, count("placement", tenant));
    }

    /** T4.4: the gateway cannot name {@code PgConstraintViolation}, so a live name collision is a dp-api exception. */
    @Test
    void aSecondLiveInstanceUnderOneNameInOneEnvironmentIsInstanceNameTakenAndWritesNothing() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        UUID environment = UUID.randomUUID();
        DeclareInstanceCommand first = new DeclareInstanceCommand(environment, UUID.randomUUID(), "cache", "2.4",
                "orders-cache", target, AcquisitionMode.DIRECT_HTTP, freshSpec(), ACTOR);
        service.reserve(tenant, first);
        long instancesBefore = count("instance_state", tenant);
        long placementsBefore = count("placement", tenant);

        InstanceNameTakenException refused = assertThrows(InstanceNameTakenException.class,
                () -> service.reserve(tenant, new DeclareInstanceCommand(environment, UUID.randomUUID(), "cache",
                        "2.4", "orders-cache", target, AcquisitionMode.DIRECT_HTTP, freshSpec(), ACTOR)));

        assertFalse(refused.getMessage().contains("orders-cache"), refused.getMessage());
        assertEquals(instancesBefore, count("instance_state", tenant));
        assertEquals(placementsBefore, count("placement", tenant), "the refused declare's placement rolled back too");
        // the same name in ANOTHER environment is a different scope
        service.reserve(tenant, new DeclareInstanceCommand(UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4",
                "orders-cache", target, AcquisitionMode.DIRECT_HTTP, freshSpec(), ACTOR));
    }

    @Test
    void aPlacementOutsideTheEnvelopeIsRefusedBeforeAnyRowAndNamesOnlyTheField() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        String u = UUID.randomUUID().toString().substring(0, 8);

        PlacementOutsideEnvelopeException container = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> service.reserve(tenant, command(target, spec("evil-" + u, "/srv/sm/" + u, 21001))));
        PlacementOutsideEnvelopeException ssh = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> service.reserve(tenant, command(target, spec("sm-" + u, "/home/ec2-user/.ssh", 21002))));
        PlacementOutsideEnvelopeException sibling = assertThrows(PlacementOutsideEnvelopeException.class,
                () -> service.reserve(tenant, command(target, spec("sm-" + u, "/srv/sm-evil", 21003))));

        assertEquals("containerName", container.field());
        assertEquals("configDirectory", ssh.field());
        assertEquals("configDirectory", sibling.field());
        assertFalse(ssh.getMessage().contains(".ssh"), ssh.getMessage());
        assertFalse(sibling.getMessage().contains("evil"), sibling.getMessage());
        assertFalse(container.getMessage().contains("evil"), container.getMessage());
        assertEquals(0, count("instance_state", tenant), "refused before any row — the trigger never ran");
        assertEquals(0, count("placement", tenant));
    }

    @Test
    void theRootItselfIsInsideTheEnvelope() {
        UUID tenant = SmDataPostgres.freshTenant();
        InstanceView v = service.reserve(tenant,
                command(registerTarget(tenant), spec("sm-root-" + PORTS.incrementAndGet(), "/srv/sm", 21101)));
        assertEquals(1, v.placements().size());
    }

    @Test
    void reserveAgainstATargetThisTenantHasNotRegisteredWritesNothing() {
        UUID owner = SmDataPostgres.freshTenant();
        UUID other = SmDataPostgres.freshTenant();
        UUID target = registerTarget(owner);

        assertThrows(NoSuchElementException.class, () -> service.reserve(other, command(target, freshSpec())));
        assertThrows(NoSuchElementException.class, () -> service.reserve(owner, command(UUID.randomUUID(), freshSpec())));
        assertEquals(0, count("instance_state", other));
        assertEquals(0, count("instance_state", owner));
    }

    @Test
    void abandonRetiresThePlacementsSoTheirNamesAreFree() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        PlacementSpec taken = freshSpec();
        InstanceView abandoned = service.reserve(tenant, command(target, taken));
        PlacementCollisionException held = assertThrows(PlacementCollisionException.class,
                () -> service.reserve(tenant, command(target, taken)));
        assertEquals(PlacementCollisionException.Kind.CONTAINER_NAME, held.kind());

        service.abandon(tenant, abandoned.instanceId());
        long versionAfter = abandonedVersion(tenant, abandoned.instanceId());
        service.abandon(tenant, abandoned.instanceId());   // idempotent
        assertEquals(versionAfter, abandonedVersion(tenant, abandoned.instanceId()), "a repeat abandon writes nothing");

        InstanceView reused = service.reserve(tenant, command(target, taken));
        assertEquals(1, reused.placements().size());
        assertEquals(0L, superuser.queryForObject(
                "SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t AND instance_id = :i AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", abandoned.instanceId()), Long.class));
    }

    private static long abandonedVersion(UUID tenant, UUID instance) {
        Long v = superuser.queryForObject(
                "SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Long.class);
        return v == null ? -1 : v;
    }

    @Test
    void pendingOlderThanListsOnlyStalePendingInstances() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        InstanceView stale = service.reserve(tenant, command(target, freshSpec()));
        CLOCK.advance(Duration.ofHours(2));
        InstanceView fresh = service.reserve(tenant, command(target, freshSpec()));
        InstanceView activated = service.reserve(tenant, command(target, freshSpec()));
        service.activate(tenant, activated.instanceId());

        assertEquals(List.of(stale.instanceId()), service.pendingOlderThan(tenant, Duration.ofHours(1)),
                "an old pending instance only: not the fresh one, not the activated one");
        assertEquals(List.of(), service.pendingOlderThan(tenant, Duration.ofHours(3)));
        assertTrue(service.pendingOlderThan(tenant, Duration.ofSeconds(0)).contains(fresh.instanceId()));
        assertEquals(List.of(), service.pendingOlderThan(SmDataPostgres.freshTenant(), Duration.ofSeconds(0)));
    }

    @Test
    void aCredentialRotationIsReadByTheNextSessionNotTheCurrentOne() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        TargetConnectionRecord before = service.connectionOf(tenant, target).orElseThrow();

        try (SecretMaterial currentSession = secrets.resolve(tenant, before.credentialRef(),
                SecretPurpose.SSH_PRIVATE_KEY)) {
            service.rotateCredential(tenant, target, KEY_TWO.chars(), ACTOR);

            assertEquals(KEY_ONE.text(), new String(currentSession.chars()), "the open session keeps its key");
            TargetConnectionRecord after = service.connectionOf(tenant, target).orElseThrow();
            assertNotEquals(before.credentialRef(), after.credentialRef());
            try (SecretMaterial next = secrets.resolve(tenant, after.credentialRef(), SecretPurpose.SSH_PRIVATE_KEY)) {
                assertEquals(KEY_TWO.text(), new String(next.chars()), "the next session reads the new key");
            }
            assertEquals(before.allowedConfigRoot(), after.allowedConfigRoot(), "the envelope is untouched");
        }
        assertThrows(NoSuchElementException.class,
                () -> service.rotateCredential(tenant, UUID.randomUUID(), KEY_ONE.chars(), ACTOR));
    }

    @Test
    void registerIsIdempotentAndRefusesADifferingReRegistration() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        String fp = "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        TargetConnectionSpec spec = new TargetConnectionSpec("vm-2.corp.example", 22, "ec2-user", fp, "/srv/sm", "sm-");
        service.register(tenant, target, spec, KEY_ONE.chars(), ACTOR);
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();
        long secretsBefore = count("target_connection", tenant);

        service.register(tenant, target, spec, KEY_ONE.chars(), ACTOR);

        assertEquals(ref, service.connectionOf(tenant, target).orElseThrow().credentialRef(), "no new secret stored");
        assertEquals(secretsBefore, count("target_connection", tenant));
        TargetConnectionSpec wider = new TargetConnectionSpec("vm-2.corp.example", 22, "ec2-user", fp, "/srv", "sm-");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.register(tenant, target, wider, KEY_ONE.chars(), ACTOR));
        assertFalse(e.getMessage().contains("/srv"), e.getMessage());
    }

    @Test
    void registerRefusesARetiredRecordRatherThanReportingItRegistered() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        TargetConnectionRecord live = service.connectionOf(tenant, target).orElseThrow();
        superuser.update("UPDATE sm_data.target_connection SET retired_at = now()"
                        + " WHERE tenant_id = :t AND target_id = :g",
                new MapSqlParameterSource().addValue("t", tenant).addValue("g", target));
        TargetConnectionSpec same = new TargetConnectionSpec(live.host(), live.port(), live.sshUser(),
                live.hostKeyFingerprint(), live.allowedConfigRoot(), live.allowedContainerPrefix());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.register(tenant, target, same, KEY_ONE.chars(), ACTOR));

        assertTrue(e.getMessage().contains("retired"), e.getMessage());
    }

    @Test
    void noViewOrExceptionCarriesTheCredentialRef() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = registerTarget(tenant);
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();
        InstanceView v = service.activate(tenant, service.reserve(tenant, command(target, freshSpec())).instanceId());

        assertFalse(v.toString().contains(ref.toString()), v.toString());
        for (PlacementView p : v.placements()) {
            assertFalse(p.toString().contains(ref.toString()), p.toString());
        }
        PlacementCollisionException collision = assertThrows(PlacementCollisionException.class, () -> service
                .reserve(tenant, command(target, spec(v.placements().get(0).containerName(), "/srv/sm/zz1", 22001))));
        assertFalse(collision.toString().contains(ref.toString()));
        assertFalse(collision.getMessage().toLowerCase().contains("credential"));
        // structurally: no dp-api inventory type has a member that could carry it
        for (Class<?> type : List.of(InstanceView.class, PlacementView.class, DeclareInstanceCommand.class,
                PlacementSpec.class, HealthProbeSpec.class, TargetConnectionSpec.class,
                PlacementCollisionException.class, PlacementOutsideEnvelopeException.class)) {
            for (var f : type.getDeclaredFields()) {
                assertFalse(f.getName().toLowerCase().contains("credential"), type + "." + f.getName());
            }
            for (Method m : type.getDeclaredMethods()) {
                assertFalse(m.getName().toLowerCase().contains("credential"), type + "." + m.getName());
            }
        }
    }

    @Test
    void tenantBCannotSeeTenantAsInstance() {
        UUID a = SmDataPostgres.freshTenant();
        UUID b = SmDataPostgres.freshTenant();
        InstanceView v = service.activate(a, service.reserve(a, command(registerTarget(a), freshSpec())).instanceId());

        assertEquals(Optional.empty(), service.findActive(b, v.instanceId()));
        assertEquals(List.of(), service.pendingOlderThan(b, Duration.ofSeconds(0)));
        assertThrows(NoSuchElementException.class, () -> service.activate(b, v.instanceId()));
        assertThrows(NoSuchElementException.class, () -> service.abandon(b, v.instanceId()));
    }
}
