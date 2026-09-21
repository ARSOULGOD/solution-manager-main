package com.solace.sm.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerConfigEditor;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerMetrics;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerDiagnostics;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Browser-declared SSH Docker target; private-key material remains server-only. */
@Service
final class DemoInstanceService {
    private final RuntimeAdapter adapter;
    private final SshDockerConfigEditor editor;
    private final SshDockerDiagnostics diagnostics;
    private final SshDockerMetrics metrics;
    private final DemoPlacementCatalog catalog;
    private final DemoKeyVault keys;
    private final JdbcTemplate jdbc;
    private final DemoStatusCache<InstanceView> recentStatus =
            new DemoStatusCache<>(Duration.ofSeconds(15), Clock.systemUTC());

    DemoInstanceService(RuntimeAdapter adapter, SshDockerConfigEditor editor, SshDockerDiagnostics diagnostics, SshDockerMetrics metrics,
            DemoPlacementCatalog catalog, DemoKeyVault keys, JdbcTemplate jdbc) {
        this.adapter = adapter;
        this.editor = editor;
        this.diagnostics = diagnostics;
        this.metrics = metrics;
        this.catalog = catalog;
        this.keys = keys;
        this.jdbc = jdbc;
    }

    synchronized InstanceView register(InstanceDeclaration input, byte[] vaultKey) throws IOException {
        if (input.label() == null || !input.label().matches("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}")) {
            throw new DemoBadRequestException("Instance name must be 1-64 safe display characters.");
        }
        keys.requireReference(input.sshKeyReference());
        UUID solutionId = parseSolutionId(input.solutionId());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from demo_control.solution where solution_id = ?)", Boolean.class, solutionId))) {
            throw new DemoBadRequestException("Select a created Cache Solution before declaring an instance.");
        }
        InstanceRecord saved = new InstanceRecord(UUID.randomUUID(), solutionId, input.label(), Instant.now(), input.host(), input.port(), input.user(),
                input.sshKeyReference(), input.pinnedHostKeyFingerprint(), input.containerName(),
                input.remoteConfigDirectory(), input.containerConfigPath(), input.configFileName());
        return withPlacement(saved, vaultKey, ready -> {
            jdbc.update("insert into demo_control.instance (instance_id, solution_id, display_name, ssh_host, ssh_port, ssh_user, ssh_key_reference, host_key_fingerprint, container_name, remote_config_directory, container_config_path, config_file_name, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    saved.instanceId(), saved.solutionId(), saved.label(), saved.host(), saved.port(), saved.user(), saved.sshKeyReference(),
                    saved.pinnedHostKeyFingerprint(), saved.containerName(), saved.remoteConfigDirectory(), saved.containerConfigPath(),
                    saved.configFileName(), Timestamp.from(saved.registeredAt()));
            jdbc.update("insert into demo_control.instance_configuration (instance_id, updated_at) values (?, ?)",
                    saved.instanceId(), Timestamp.from(saved.registeredAt()));
            appendAudit("instance.registered", saved.instanceId());
            InstanceView observed = view(saved);
            recentStatus.put(saved.instanceId(), observed);
            return observed;
        });
    }

    synchronized InstanceView status(byte[] vaultKey) throws IOException {
        return status(registered().instanceId(), vaultKey);
    }

    synchronized InstanceView status(UUID instanceId, byte[] vaultKey) throws IOException {
        InstanceRecord saved = registered(instanceId);
        return recentStatus.get(instanceId,
                () -> withRegisteredPlacement(saved, vaultKey, () -> view(saved)));
    }

    synchronized InstanceView restart(byte[] vaultKey) throws IOException {
        return restart(registered().instanceId(), vaultKey);
    }

    synchronized InstanceView restart(UUID instanceId, byte[] vaultKey) throws IOException {
        InstanceRecord saved = registered(instanceId);
        recentStatus.invalidate(instanceId);
        return withPlacement(saved, vaultKey, ready -> {
            adapter.startOrUpdate(ready.replicas().get(0), 1);
            appendAudit("instance.restarted", saved.instanceId());
            InstanceView observed = view(saved);
            recentStatus.put(instanceId, observed);
            return observed;
        });
    }

    ObjectNode validateConfiguration(JsonNode values) {
        SshDockerConfigEditor.validateCacheConfiguration(values);
        return ((ObjectNode) values).deepCopy();
    }

    synchronized InstanceView applyTtl(String ttl, byte[] vaultKey) throws IOException {
        ObjectNode values = JsonNodeFactory.instance.objectNode();
        values.putObject("cache-engine").put("ttl", ttl);
        return applyConfiguration(registered().instanceId(), values, vaultKey);
    }

    synchronized InstanceView applyConfiguration(UUID instanceId, JsonNode values, byte[] vaultKey) throws IOException {
        InstanceRecord saved = registered(instanceId);
        recentStatus.invalidate(instanceId);
        return withPlacement(saved, vaultKey, ready -> {
            RenderedConfig config = editor.withCacheConfiguration(DemoConfiguration.DEMO_PLACEMENT, values);
            TargetReplica replica = ready.replicas().get(0);
            adapter.materializeConfig(replica, 1, config);
            RuntimeStatus status = adapter.status(replica);
            if (!config.hash().equals(status.observedHash())) throw new DemoUnavailableException();
            appendAudit("config.applied", saved.instanceId());
            InstanceView observed = view(saved, status);
            recentStatus.put(instanceId, observed);
            return observed;
        });
    }

    synchronized com.solace.sm.dp.apply.runtime.RuntimeDiagnostics.DiagnosticLog logs(byte[] vaultKey) throws IOException {
        return logs(registered().instanceId(), vaultKey);
    }

    synchronized com.solace.sm.dp.apply.runtime.RuntimeDiagnostics.DiagnosticLog logs(UUID instanceId, byte[] vaultKey) throws IOException {
        InstanceRecord saved = registered(instanceId);
        return withRegisteredPlacement(saved, vaultKey,
                () -> diagnostics.recentLogs(DemoConfiguration.DEMO_PLACEMENT, 100));
    }

    synchronized SshDockerDiagnostics.LogPage logs(UUID instanceId, byte[] vaultKey, int limit,
            Instant after, Instant before) throws IOException {
        InstanceRecord saved = registered(instanceId);
        return withRegisteredPlacement(saved, vaultKey,
                () -> diagnostics.logsPage(DemoConfiguration.DEMO_PLACEMENT, limit, after, before));
    }

    synchronized SshDockerMetrics.MetricsSnapshot metrics(UUID instanceId, byte[] vaultKey) throws IOException {
        InstanceRecord saved = registered(instanceId);
        return withRegisteredPlacement(saved, vaultKey,
                () -> metrics.snapshot(DemoConfiguration.DEMO_PLACEMENT));
    }

    private InstanceRecord registered() {
        return registered(null);
    }

    private InstanceRecord registered(UUID instanceId) {
        String select = "select instance_id, solution_id, display_name, created_at, ssh_host, ssh_port, ssh_user, ssh_key_reference, host_key_fingerprint, container_name, remote_config_directory, container_config_path, config_file_name from demo_control.instance";
        var mapper = (org.springframework.jdbc.core.RowMapper<InstanceRecord>)
                (rs, ignored) -> new InstanceRecord(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13));
        return (instanceId == null
                ? jdbc.query(select + " order by created_at desc limit 1", mapper)
                : jdbc.query(select + " where instance_id = ?", mapper, instanceId))
                .stream().findFirst().orElseThrow(DemoNotFoundException::new);
    }

    private InstanceView view(InstanceRecord saved) {
        return view(saved, adapter.status(new TargetReplica(DemoConfiguration.DEMO_PLACEMENT,
                new ReplicaRef(saved.containerName()))));
    }

    private InstanceView view(InstanceRecord saved, RuntimeStatus status) {
        return new InstanceView(saved.instanceId(), saved.solutionId(), saved.label(), saved.registeredAt(), status.liveness().name(),
                status.health().name(), status.observedHash(), status.startBaseline()
                        .map(b -> b.startedAt()).orElse(null), status.health() != HealthSignal.NOT_DECLARED);
    }

    private void appendAudit(String action, UUID subjectId) {
        jdbc.update("insert into demo_control.audit_log (audit_id, action, subject_id, created_at) values (?, ?, ?, ?)",
                UUID.randomUUID(), action, subjectId, Timestamp.from(Instant.now()));
    }

    private <T> T withPlacement(InstanceRecord saved, byte[] vaultKey, CheckedFunction<T> operation) throws IOException {
        return withRegisteredPlacement(saved, vaultKey, () -> {
            PreflightResult preflight = adapter.prepare(DemoConfiguration.DEMO_PLACEMENT, 1);
            if (!(preflight instanceof PreflightResult.Ready ready)) throw new DemoUnavailableException();
            return operation.apply(ready);
        });
    }

    private <T> T withRegisteredPlacement(InstanceRecord saved, byte[] vaultKey,
            RegisteredOperation<T> operation) throws IOException {
        try (DemoKeyVault.KeyFile key = keys.materialize(saved.sshKeyReference(), vaultKey)) {
            catalog.register(new SshDockerPlacement(saved.host(), saved.port(), saved.user(), key.path(),
                    saved.pinnedHostKeyFingerprint(), saved.containerName(), saved.remoteConfigDirectory(),
                    saved.containerConfigPath(), saved.configFileName(),
                    com.solace.sm.dp.apply.runtime.ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 4));
            return operation.apply();
        }
    }

    record InstanceDeclaration(String solutionId, String label, String host, int port, String user, String sshKeyReference,
            String pinnedHostKeyFingerprint, String containerName, String remoteConfigDirectory,
            String containerConfigPath, String configFileName) {}
    private record InstanceRecord(UUID instanceId, UUID solutionId, String label, Instant registeredAt, String host, int port, String user,
            String sshKeyReference, String pinnedHostKeyFingerprint, String containerName,
            String remoteConfigDirectory, String containerConfigPath, String configFileName) {}
    record InstanceView(UUID instanceId, UUID solutionId, String label, Instant registeredAt, String liveness, String health,
            String observedHash, Instant startedAt, boolean healthSignalDeclared) {}
    @FunctionalInterface private interface CheckedFunction<T> { T apply(PreflightResult.Ready ready) throws IOException; }
    @FunctionalInterface private interface RegisteredOperation<T> { T apply() throws IOException; }

    private static UUID parseSolutionId(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { throw new DemoBadRequestException("Select a created Cache Solution before declaring an instance."); }
    }
}

final class DemoUnavailableException extends RuntimeException {}
final class DemoNotFoundException extends RuntimeException {}
