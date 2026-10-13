package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.2 against a real PostgreSQL 14, as {@code sm_dp_runtime}: the table-backed catalog binds
 * {@code (tenant, instance, placement)} and builds each {@code SshDockerPlacement} from the placement row plus a fresh
 * {@code target_connection} read. (In the persistence test package, like {@code InventoryServiceIT}: the shared
 * Testcontainers harness is package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class TablePlacementCatalogIT {

    private static NamedParameterJdbcTemplate runtime;
    private static JdbcPlacementRepository placements;
    private static JdbcTargetConnectionRepository targets;
    private static TablePlacementCatalog catalog;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        placements = new JdbcPlacementRepository(runtime);
        targets = new JdbcTargetConnectionRepository(runtime);
        catalog = new TablePlacementCatalog(placements, targets, new JdbcInstanceStateRepository(runtime));
    }

    private record Fixture(UUID tenant, UUID instance, UUID target, UUID placement) {
        PlacementRef ref() {
            return ref(instance);
        }

        PlacementRef ref(UUID forInstance) {
            return new PlacementRef(new TenantId(tenant), new InstanceId(forInstance), new PlacementId(placement));
        }
    }

    private static Fixture fixture() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        return new Fixture(tenant, instance, target, InventoryFixtures.placement(tenant, instance, target));
    }

    private static void assertRefused(String token, PlacementRef ref) {
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class, () -> catalog.lookup(ref));
        assertEquals(token, e.errorClass().token());
    }

    @Test
    void aLivePlacementBecomesTheSshBindingItsRowsDescribe() {
        Fixture f = fixture();
        PlacementRecord row = placements.findLive(f.tenant, f.instance, f.placement).orElseThrow();
        TargetConnectionRecord connection = targets.find(f.tenant, f.target).orElseThrow();

        SshDockerPlacement p = catalog.lookup(f.ref());

        assertEquals(new TenantId(f.tenant), p.tenant(), "T6.1's TenantId field is set from the placement's own tenant");
        assertEquals(connection.host(), p.host());
        assertEquals(connection.port(), p.port());
        assertEquals(connection.sshUser(), p.user());
        assertEquals(connection.hostKeyFingerprint(), p.pinnedHostKeyFingerprint());
        assertEquals(connection.credentialRef(), p.credentialRef());
        assertEquals(connection.allowedConfigRoot(), p.allowedConfigRoot());
        assertEquals(row.containerName(), p.containerName());
        assertEquals(row.remoteConfigDirectory(), p.remoteConfigDirectory());
        assertEquals(row.containerConfigPath(), p.containerConfigPath());
        assertEquals(row.fileName(), p.fileName());
        assertEquals(ConfigLocation.Mechanism.ADDITIONAL_LOCATION, p.mechanism());
        assertEquals(row.retainedGenerations(), p.retainedGenerations());
    }

    @Test
    void theCatalogRefusesAPlacementOwnedByAnotherInstanceOrRetired() {
        Fixture f = fixture();
        UUID otherInstance = UUID.randomUUID();
        InventoryFixtures.declare(f.tenant, otherInstance);

        assertRefused("placement.unresolvable", f.ref(otherInstance));
        assertRefused("placement.unresolvable", f.ref(UUID.randomUUID()));

        // the same ids under another tenant are not found either (I5)
        assertRefused("placement.unresolvable", new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(f.instance), new PlacementId(f.placement)));

        // and a retired placement is refused although its row still exists
        catalog.lookup(f.ref());
        placements.retireAll(f.tenant, f.instance, Instant.now());
        assertRefused("placement.unresolvable", f.ref());
    }

    @Test
    void theTargetConnectionIsReadFreshSoARotatedCredentialIsSeenOnTheNextLookup() {
        Fixture f = fixture();
        UUID before = catalog.lookup(f.ref()).credentialRef();
        UUID rotated = UUID.randomUUID();
        assertEquals(1, targets.updateCredential(f.tenant, f.target, rotated));

        UUID after = catalog.lookup(f.ref()).credentialRef();

        assertNotEquals(before, after);
        assertEquals(rotated, after);
    }

    @Test
    void aDeclaredProbeAndTheInstancesModeAreReadFromTheRows() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);   // acquisition_mode 'via-ssh'
        UUID target = InventoryFixtures.target(tenant);
        UUID placement = UUID.randomUUID();
        placements.insert(new PlacementRecord(tenant, placement, instance, target, "sm-" + placement,
                "/srv/sm/" + placement, "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10,
                java.util.Optional.of("actuator-health"), java.util.Optional.of("/actuator/health"),
                java.util.Optional.of(18081),
                java.util.Optional.of(com.solace.sm.dp.api.inventory.ProbeStrength.LIVENESS), Instant.now()));

        SshDockerPlacement p = catalog.lookup(new PlacementRef(new TenantId(tenant), new InstanceId(instance),
                new PlacementId(placement)));

        assertEquals(com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH, p.acquisition());
        assertEquals(new com.solace.sm.dp.api.inventory.HealthProbeSpec("actuator-health", "/actuator/health", 18081,
                com.solace.sm.dp.api.inventory.ProbeStrength.LIVENESS), p.probe());
    }
}
