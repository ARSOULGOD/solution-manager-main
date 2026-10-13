package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 010 T6.2: the branches the database makes unreachable (a retired target under a live placement) or refuses to hold. */
class TablePlacementCatalogTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final PlacementRef REF =
            new PlacementRef(new TenantId(TENANT), new InstanceId(INSTANCE), new PlacementId(PLACEMENT));
    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private static PlacementRecord placement(String directory, String mechanism) {
        return new PlacementRecord(TENANT, PLACEMENT, INSTANCE, TARGET, "sm-cache", directory,
                "/app/external/spring/config", "application.yml", mechanism, 10, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Instant.now());
    }

    private static TargetConnectionRecord target(Instant retiredAt) {
        return new TargetConnectionRecord(TENANT, TARGET, "vm-1.corp.example", 22, "ec2-user", FINGERPRINT,
                UUID.randomUUID(), "/srv/sm", "sm-", UUID.randomUUID(), Instant.now(), retiredAt);
    }

    private static PlacementRecord connectionPlacement() {
        return new PlacementRecord(TENANT, PLACEMENT, INSTANCE, null, "sm-cache", "/srv/sm/p1",
                "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.now(), null, CONNECTION);
    }

    private static com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec connectionRuntime() {
        return new com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec(CONNECTION, "vm-connection.example", 22,
                "ec2-user", FINGERPRINT, UUID.randomUUID(), "/srv/sm", "sm-");
    }

    private static PlacementRecord probed(String kind, String path, int port, ProbeStrength strength) {
        return new PlacementRecord(TENANT, PLACEMENT, INSTANCE, TARGET, "sm-cache", "/srv/sm/p1",
                "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10, Optional.of(kind),
                Optional.of(path), Optional.of(port), Optional.of(strength), Instant.now());
    }

    private static InstanceRows instance(AcquisitionMode mode) {
        InstanceRecord row = new InstanceRecord(TENANT, INSTANCE, UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4",
                "orders", InstanceLifecycle.ACTIVE, mode, UUID.randomUUID(), Instant.now(), 1L);
        return new InstanceRows() {
            @Override public void insert(InstanceRecord r) {
                throw new AssertionError();
            }

            @Override public Optional<InstanceRecord> find(UUID tenantId, UUID instanceId) {
                return tenantId.equals(TENANT) && instanceId.equals(INSTANCE) ? Optional.of(row) : Optional.empty();
            }

            @Override public List<InstanceRecord> list(UUID tenantId, InstanceLifecycle lifecycle,
                    Optional<UUID> catalogSolutionId) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingIds(UUID tenantId, int limit) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff, int limit) {
                throw new AssertionError();
            }

            @Override public int activate(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public int abandon(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public Optional<InstanceRecord> lockForPlacementChange(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public int touch(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }
        };
    }

    private static TablePlacementCatalog catalog(PlacementRecord placement, Optional<TargetConnectionRecord> target) {
        return catalog(placement, target, instance(AcquisitionMode.VIA_SSH));
    }

    private static TablePlacementCatalog catalog(PlacementRecord placement, Optional<TargetConnectionRecord> target,
            InstanceRows instances) {
        return catalog(placement, target, instances, Optional.empty());
    }

    private static TablePlacementCatalog catalog(PlacementRecord placement, Optional<TargetConnectionRecord> target,
            InstanceRows instances, Optional<com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec> runtime) {
        PlacementRows placements = new PlacementRows() {
            @Override public void insert(PlacementRecord row) {
                throw new AssertionError();
            }

            @Override public Optional<PlacementRecord> findLive(UUID tenantId, UUID instanceId, UUID placementId) {
                return Optional.of(placement);
            }

            @Override public Optional<PlacementRecord> find(UUID tenantId, UUID instanceId, UUID placementId) {
                return Optional.of(placement);
            }

            @Override public List<PlacementRecord> live(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public int retireAll(UUID tenantId, UUID instanceId, Instant at) {
                throw new AssertionError();
            }

            @Override public int retire(UUID tenantId, UUID instanceId, UUID placementId, Instant at, UUID by) {
                throw new AssertionError();
            }

            @Override public boolean lockApplyScope(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public boolean inFlightOn(UUID tenantId, UUID instanceId, UUID placementId) {
                throw new AssertionError();
            }

            @Override public boolean instanceWideInFlight(UUID t, UUID i) {
                throw new AssertionError();
            }
        };
        TargetConnectionRows targets = new TargetConnectionRows() {
            @Override public void insert(TargetConnectionRecord row) {
                throw new AssertionError();
            }

            @Override public Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId) {
                return target;
            }

            @Override public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef) {
                throw new AssertionError();
            }

            @Override public void addHostKeys(UUID tenantId, UUID targetId, java.util.Collection<String> fingerprints) {
                throw new AssertionError();
            }

            @Override public java.util.Set<String> hostKeys(UUID tenantId, UUID targetId) {
                throw new AssertionError();
            }

            @Override public Optional<com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec> findConnectionRuntime(
                    UUID tenantId, UUID connectionId) {
                return runtime.filter(row -> tenantId.equals(TENANT) && connectionId.equals(row.connectionId()));
            }
        };
        return new TablePlacementCatalog(placements, targets, instances);
    }

    @Test
    void aRetiredOrMissingTargetIsRefusedWithAClassifier() {
        for (Optional<TargetConnectionRecord> unusable : List.of(Optional.<TargetConnectionRecord>empty(),
                Optional.of(target(Instant.now())))) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> catalog(placement("/srv/sm/p1", "ADDITIONAL_LOCATION"), unusable).lookup(REF));
            assertEquals("target.unavailable", e.errorClass().token());
        }
    }

    /** Plan 015 T3.3 (V028): an agent target has no address, user, pin or credential, so no SSH binding is ever built for it. */
    @Test
    void anAgentTargetIsRefusedByNameAndNoSshBindingIsBuilt() {
        TargetConnectionRecord agent = new TargetConnectionRecord(TENANT, TARGET, null, 0, null, null, null, "/srv/sm", "sm-",
                UUID.randomUUID(), Instant.now(), null, null, null, UUID.randomUUID());
        assertEquals("agent", agent.transport());
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> catalog(placement("/srv/sm/p1", "ADDITIONAL_LOCATION"), Optional.of(agent)).lookup(REF));
        assertEquals("agent.operation.unsupported", e.errorClass().token());
        assertNull(e.getCause());
        assertEquals("ssh-docker", target(null).transport());
    }

    @Test
    void aRowThatBreaksTheBindingGrammarIsRefusedAndTheRefusalNamesNoValue() {
        // a table-backed catalog is a second writer: the record's own grammar is the last enforcer
        RuntimeAdapterException outside = assertThrows(RuntimeAdapterException.class,
                () -> catalog(placement("/etc/outside", "ADDITIONAL_LOCATION"), Optional.of(target(null))).lookup(REF));
        RuntimeAdapterException unknown = assertThrows(RuntimeAdapterException.class,
                () -> catalog(placement("/srv/sm/p1", "SOMETHING_ELSE"), Optional.of(target(null))).lookup(REF));

        for (RuntimeAdapterException e : List.of(outside, unknown)) {
            assertEquals("placement.invalid", e.errorClass().token());
            assertNull(e.getCause(), "no cause chain for a logger to print");
            assertFalse(String.valueOf(e.getMessage()).contains("/etc/outside"));
        }
    }

    @Test
    void thePlacementsDeclaredProbeAndTheInstancesAcquisitionModeReachTheBinding() {
        SshDockerPlacement viaSsh = catalog(probed("actuator-health", "/actuator/health", 18081, ProbeStrength.LIVENESS),
                Optional.of(target(null)), instance(AcquisitionMode.VIA_SSH)).lookup(REF);
        assertEquals(AcquisitionMode.VIA_SSH, viaSsh.acquisition());
        assertEquals("actuator-health", viaSsh.probe().kind());
        assertEquals("/actuator/health", viaSsh.probe().path());
        assertEquals(18081, viaSsh.probe().hostPort());
        assertEquals(ProbeStrength.LIVENESS, viaSsh.probe().strength());

        // the mode is the INSTANCE's: the same placement row, read under the other mode (OR-71)
        SshDockerPlacement direct = catalog(probed("http-status", "/ready", 18082, ProbeStrength.READINESS),
                Optional.of(target(null)), instance(AcquisitionMode.DIRECT_HTTP)).lookup(REF);
        assertEquals(AcquisitionMode.DIRECT_HTTP, direct.acquisition());
        assertEquals(ProbeStrength.READINESS, direct.probe().strength());
    }

    @Test
    void theTargetsRecordedHostKeyTypeReachesTheBindingAndNoRecordedTypeIsNone() {
        TargetConnectionRecord typed = target(null).withHostKeyType(com.solace.sm.dp.api.inventory.HostKeyType.RSA);

        SshDockerPlacement binding = catalog(placement("/srv/sm/p1", "ADDITIONAL_LOCATION"), Optional.of(typed))
                .lookup(REF);
        SshDockerPlacement unknown = catalog(placement("/srv/sm/p1", "ADDITIONAL_LOCATION"), Optional.of(target(null)))
                .lookup(REF);

        assertEquals(com.solace.sm.dp.api.inventory.HostKeyType.RSA, binding.pinnedHostKeyType(),
                "OR-125 (2): the connector negotiates the type the record holds");
        assertNull(unknown.pinnedHostKeyType(), "a row with no recorded type is discovered, not guessed");
    }

    @Test
    void aPlacementWithNoProbeRowsHasNoProbeInItsBinding() {
        SshDockerPlacement p = catalog(placement("/srv/sm/p1", "ADDITIONAL_LOCATION"), Optional.of(target(null)))
                .lookup(REF);

        assertNull(p.probe(), "no declared probe: the adapter then fails the gated strategy closed");
    }

    @Test
    void aConnectionBackedPlacementResolvesItsFreshRuntimeProjection() {
        com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec runtime = connectionRuntime();
        SshDockerPlacement binding = catalog(connectionPlacement(), Optional.empty(), instance(AcquisitionMode.DIRECT_HTTP),
                Optional.of(runtime)).lookup(REF);

        assertEquals(runtime.host(), binding.host());
        assertEquals(runtime.port(), binding.port());
        assertEquals(runtime.sshUser(), binding.user());
        assertEquals(runtime.hostKeyFingerprint(), binding.pinnedHostKeyFingerprint());
        assertEquals(runtime.credentialRef(), binding.credentialRef(),
                "the binding carries the secret reference, never the secret");
        assertEquals(runtime.allowedConfigRoot(), binding.allowedConfigRoot());
        assertEquals("/srv/sm/p1", binding.remoteConfigDirectory());
    }

    @Test
    void aMissingConnectionRuntimeProjectionIsRefusedWithoutAHostFallback() {
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                () -> catalog(connectionPlacement(), Optional.of(target(null)), instance(AcquisitionMode.DIRECT_HTTP),
                        Optional.empty()).lookup(REF));
        assertEquals("target.unavailable", e.errorClass().token());
    }

    @Test
    void anInstanceThatIsNotThereIsUnresolvableSoNoModeIsGuessed() {
        InstanceRows none = new InstanceRows() {
            @Override public void insert(InstanceRecord r) {
                throw new AssertionError();
            }

            @Override public Optional<InstanceRecord> find(UUID tenantId, UUID instanceId) {
                return Optional.empty();
            }

            @Override public List<InstanceRecord> list(UUID tenantId, InstanceLifecycle lifecycle,
                    Optional<UUID> catalogSolutionId) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingIds(UUID tenantId, int limit) {
                throw new AssertionError();
            }

            @Override public List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff, int limit) {
                throw new AssertionError();
            }

            @Override public int activate(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public int abandon(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public Optional<InstanceRecord> lockForPlacementChange(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }

            @Override public int touch(UUID tenantId, UUID instanceId) {
                throw new AssertionError();
            }
        };
        RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class, () -> catalog(
                probed("actuator-health", "/actuator/health", 18081, ProbeStrength.LIVENESS),
                Optional.of(target(null)), none).lookup(REF));
        assertEquals("placement.unresolvable", e.errorClass().token());
    }
}
