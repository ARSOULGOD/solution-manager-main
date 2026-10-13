package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import com.solace.sm.dp.secret.SecretPurpose;
import java.util.Objects;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Production's {@link SshDockerPlacementCatalog} (plan 010 T6.2): the placement a {@code PlacementRef} names, read from
 * the tables, as the SSH binding the transport connects with.
 *
 * <p><b>Bound by {@code (tenant, instance, placement)}</b> (the v2 paper's F20; the earlier {@code find(TenantId,
 * PlacementId)} lacked the instance). The lookup is {@link PlacementRows#findLive}, which keys all three and filters
 * out a retired placement, so a placement id that belongs to another instance, to another tenant, or to a retired row
 * is simply not found: the engine can never be answered with a host it did not ask about (I5).
 *
 * <p><b>The target connection is read fresh on every lookup</b> (T2.1: it is mutable for credential rotation, OR-28), so
 * a rotated credential is seen by the very next SSH session, and a retired target is refused (the database already
 * forbids retiring a target under a live placement; this is the second line).
 *
 * <p><b>A table-backed catalog is a second writer</b>, so {@link SshDockerPlacement}'s own grammar is applied to what
 * it reads and a row that breaks it is refused, not trusted. Every refusal is a {@link RuntimeAdapterException} with a
 * fixed classifier, no message and no cause: the engine records the classifier and never a path or a host (I6).
 */
public final class TablePlacementCatalog implements SshDockerPlacementCatalog {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final ErrorClass UNRESOLVABLE = new ErrorClass("placement.unresolvable");
    static final ErrorClass TARGET_UNAVAILABLE = new ErrorClass("target.unavailable");
    static final ErrorClass INVALID = new ErrorClass("placement.invalid");
    /** The placement's target is an agent (V028): it has no SSH binding, and none is ever built for it (plan 015 T3.3). */
    static final ErrorClass AGENT_PLACEMENT = new ErrorClass("agent.operation.unsupported");

    private final PlacementRows placements;
    private final TargetConnectionRows targets;
    private final InstanceRows instances;

    public TablePlacementCatalog(PlacementRows placements, TargetConnectionRows targets, InstanceRows instances) {
        this.placements = Objects.requireNonNull(placements, "placements");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.instances = Objects.requireNonNull(instances, "instances");
    }

    @Override
    public SshDockerPlacement lookup(PlacementRef ref) {
        Objects.requireNonNull(ref, "placement");
        PlacementRecord placement = placements
                .findLive(ref.tenant().value(), ref.instance().value(), ref.placement().value())
                .orElseThrow(() -> new RuntimeAdapterException(UNRESOLVABLE));
        // The acquisition mode is the INSTANCE's (OR-71): read with the placement, never guessed. An instance the tenant
        // does not have is as unresolvable as a placement it does not have.
        InstanceRecord instance = instances.find(ref.tenant().value(), ref.instance().value())
                .orElseThrow(() -> new RuntimeAdapterException(UNRESOLVABLE));
        if (placement.connectionBacked()) {
            // A Connection is resolved only through the DP-owned projection. The control-plane Connection directory is
            // deliberately not reachable from this catalog, and a missing/retired projection never falls back to a
            // Target or to values from the declare request.
            com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec connection = targets
                    .findConnectionRuntime(ref.tenant().value(), placement.connectionId())
                    .orElseThrow(() -> new RuntimeAdapterException(TARGET_UNAVAILABLE));
            try {
                return new SshDockerPlacement(ref.tenant(), connection.host(), connection.port(), connection.sshUser(),
                        connection.credentialRef(), connection.hostKeyFingerprint(), placement.containerName(),
                        connection.allowedConfigRoot(), placement.remoteConfigDirectory(),
                        placement.containerConfigPath(), placement.fileName(),
                        ConfigLocation.Mechanism.valueOf(placement.mechanism()), placement.retainedGenerations(),
                        instance.acquisition(), probe(placement), SecretPurpose.CONNECTION_CREDENTIAL,
                        connection.passphraseRef(), connection.authMethod());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new RuntimeAdapterException(INVALID);
            }
        }
        // New deployment targets reference a reusable Connection. Resolve the access projection and target envelope
        // separately; the browser never supplies SSH details to Apply.
        Optional<com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec> linked = targets
                .findDeploymentTargetRuntime(ref.tenant().value(), placement.targetId());
        if (linked.isPresent()) {
            com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec connection = targets
                    .findConnectionRuntime(ref.tenant().value(), linked.get().connectionId())
                    .orElseThrow(() -> new RuntimeAdapterException(TARGET_UNAVAILABLE));
            try {
                JsonNode settings = JSON.readTree(linked.get().settingsJson());
                String root = text(settings, "configRoot");
                String resourceName = placement.containerName();
                if ("systemd".equals(linked.get().runtime())) {
                    resourceName = placement.containerName();
                } else {
                    text(settings, "containerPrefix");
                }
                return new SshDockerPlacement(ref.tenant(), connection.host(), connection.port(), connection.sshUser(),
                        connection.credentialRef(), connection.hostKeyFingerprint(), resourceName, root,
                        placement.remoteConfigDirectory(), placement.containerConfigPath(), placement.fileName(),
                        ConfigLocation.Mechanism.valueOf(placement.mechanism()), placement.retainedGenerations(),
                        instance.acquisition(), probe(placement), null, linked.get().runtime(),
                        SecretPurpose.CONNECTION_CREDENTIAL, connection.passphraseRef(), connection.authMethod());
            } catch (Exception e) {
                throw new RuntimeAdapterException(INVALID);
            }
        }
        Optional<TargetConnectionRecord> found = targets.find(ref.tenant().value(), placement.targetId());
        if (found.isEmpty() || found.get().retiredAt() != null) {
            throw new RuntimeAdapterException(TARGET_UNAVAILABLE);
        }
        TargetConnectionRecord target = found.get();
        if (target.isAgent()) {
            throw new RuntimeAdapterException(AGENT_PLACEMENT);
        }
        try {
            return new SshDockerPlacement(ref.tenant(), target.host(), target.port(), target.sshUser(),
                    target.credentialRef(), target.hostKeyFingerprint(), placement.containerName(),
                    target.allowedConfigRoot(), placement.remoteConfigDirectory(), placement.containerConfigPath(),
                    placement.fileName(), ConfigLocation.Mechanism.valueOf(placement.mechanism()),
                    placement.retainedGenerations(), instance.acquisition(), probe(placement), target.hostKeyType());
        } catch (IllegalArgumentException | NullPointerException e) {
            // the record's grammar message names the field and not the value, but the cause is dropped regardless
            throw new RuntimeAdapterException(INVALID);
        }
    }

    private static String text(JsonNode settings, String key) {
        JsonNode value = settings == null ? null : settings.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing target setting");
        }
        return value.asText();
    }

    /** The placement's declared probe, or {@code null} when the package declares none SM executes (the columns are all-or-none). */
    private static HealthProbeSpec probe(PlacementRecord placement) {
        if (placement.healthKind().isEmpty()) {
            return null;
        }
        return new HealthProbeSpec(placement.healthKind().get(), placement.healthPath().orElseThrow(),
                placement.healthPort().orElseThrow(), placement.healthStrength().orElseThrow());
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "TablePlacementCatalog";
    }
}
