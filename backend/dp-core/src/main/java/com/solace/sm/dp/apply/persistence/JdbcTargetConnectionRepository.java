package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.inventory.TargetConnectionEvent;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.target_connection} over the runtime credential (plan 010 T2.5). The envelope columns are in no
 * UPDATE grant (V007), so this class has no statement that could change them. {@code credential_ref} is read here
 * for the transport that opens the next session and is never carried past {@code dp.inventory}'s internal record.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}.
 */
public final class JdbcTargetConnectionRepository implements TargetConnectionRows {

    @Override
    public void upsertDeploymentTargetRuntime(UUID tenantId,
            com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec spec) {
        jdbc.update("INSERT INTO sm_data.deployment_target_runtime (tenant_id, target_id, connection_id, runtime, settings)"
                        + " VALUES (:tenant, :target, :connection, :runtime, CAST(:settings AS jsonb))"
                        + " ON CONFLICT (tenant_id, target_id) DO UPDATE SET connection_id = EXCLUDED.connection_id,"
                        + " runtime = EXCLUDED.runtime, settings = EXCLUDED.settings, retired_at = NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", spec.targetId())
                        .addValue("connection", spec.connectionId()).addValue("runtime", spec.runtime())
                        .addValue("settings", spec.settingsJson()));
    }

    @Override
    public void retireDeploymentTargetRuntime(UUID tenantId, UUID targetId) {
        jdbc.update("UPDATE sm_data.deployment_target_runtime SET retired_at = now()"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId));
    }

    @Override
    public Optional<com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec> findDeploymentTargetRuntime(
            UUID tenantId, UUID targetId) {
        return jdbc.query("SELECT connection_id, runtime, settings::text AS settings"
                        + " FROM sm_data.deployment_target_runtime"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId),
                (rs, row) -> new com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec(targetId,
                        rs.getObject("connection_id", UUID.class), rs.getString("runtime"), rs.getString("settings")))
                .stream().findFirst();
    }

    private static final String COLUMNS = "tenant_id, target_id, host, port, ssh_user, host_key_fingerprint,"
            + " credential_ref, allowed_config_root, allowed_container_prefix, created_by, created_at, retired_at,"
            + " credential_public_fingerprint, host_key_algorithm, agent_id";

    private static final String INSERT = "INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host,"
            + " port, ssh_user, host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
            + " created_by, created_at, credential_public_fingerprint, host_key_algorithm) VALUES (:tenant, :target,"
            + " 'ssh-docker', :host, :port, :user, :fingerprint, :credential, :root, :prefix, :createdBy, :createdAt,"
            + " :publicFingerprint, :hostKeyAlgorithm)";

    private static final RowMapper<TargetConnectionRecord> MAP = JdbcTargetConnectionRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTargetConnectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TargetConnectionRecord r) {
        Sql.write(() -> jdbc.update(INSERT, insertParameters(r)));
    }

    private static MapSqlParameterSource insertParameters(TargetConnectionRecord r) {
        return new MapSqlParameterSource().addValue("tenant", r.tenantId()).addValue("target", r.targetId())
                .addValue("host", r.host()).addValue("port", r.port()).addValue("user", r.sshUser())
                .addValue("fingerprint", r.hostKeyFingerprint()).addValue("credential", r.credentialRef())
                .addValue("root", r.allowedConfigRoot()).addValue("prefix", r.allowedContainerPrefix())
                .addValue("createdBy", r.createdBy()).addValue("createdAt", Sql.at(r.createdAt()))
                .addValue("publicFingerprint", r.credentialPublicFingerprint())
                .addValue("hostKeyAlgorithm", wire(r.hostKeyType()), Types.VARCHAR);
    }

    @Override
    public Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.target_connection"
                        + " WHERE tenant_id = :tenant AND target_id = :target",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId), MAP)
                .stream().findFirst();
    }

    @Override
    public void addHostKeys(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
        for (String fingerprint : fingerprints) {
            // The conflict target is the key's own primary key: a (tenant, fingerprint) clash with ANOTHER live
            // target must still raise target_host_key_once, which a bare DO NOTHING would swallow.
            Sql.write(() -> jdbc.update("INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint)"
                            + " VALUES (:tenant, :target, :fingerprint)"
                            + " ON CONFLICT (tenant_id, target_id, fingerprint) DO NOTHING",
                    new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId)
                            .addValue("fingerprint", fingerprint)));
        }
    }

    @Override
    public Set<String> hostKeys(UUID tenantId, UUID targetId) {
        return new LinkedHashSet<>(jdbc.queryForList("SELECT fingerprint FROM sm_data.target_host_key"
                        + " WHERE tenant_id = :tenant AND target_id = :target ORDER BY fingerprint",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId),
                String.class));
    }

    @Override
    public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef) {
        // W3C-5: the recorded public fingerprint describes the OLD key. Left in place it would answer a replay of the old key
        // as "the same" while the stored key is another; NULL makes the next compare derive it from the new secret and back-fill.
        return Sql.write(() -> jdbc.update("UPDATE sm_data.target_connection SET credential_ref = :credential,"
                        + " credential_public_fingerprint = NULL"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("credential", credentialRef).addValue("tenant", tenantId)
                        .addValue("target", targetId)));
    }

    // ---- plan 013 T1.4 --------------------------------------------------------------------------------------

    /** The two constraints that mean "another live target of this tenant holds a key of this machine". */
    private static final Set<String> HOST_KEY_CONSTRAINTS = Set.of("target_connection_host_once", "target_host_key_once");

    @Override
    public boolean insertOnce(TargetConnectionRecord r) {
        try {
            // The arbiter is the primary key only: a refusal because another target holds the pinned key still raises.
            return Sql.write(() -> jdbc.update(INSERT + " ON CONFLICT (tenant_id, target_id) DO NOTHING",
                    insertParameters(r))) == 1;
        } catch (PgConstraintViolation v) {
            throw heldElsewhereOrSame(v);
        }
    }

    @Override
    public void addHostKeysOnce(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
        try {
            addHostKeys(tenantId, targetId, fingerprints);
        } catch (PgConstraintViolation v) {
            throw heldElsewhereOrSame(v);
        }
    }

    private static RuntimeException heldElsewhereOrSame(PgConstraintViolation v) {
        // Classifiers only, and no cause: the driver's message can carry a row's text.
        return HOST_KEY_CONSTRAINTS.contains(v.constraint()) ? new HostKeyHeldElsewhereException() : v;
    }

    @Override
    public Optional<UUID> liveHolderOfAny(UUID tenantId, Collection<String> fingerprints, UUID exceptTargetId) {
        if (fingerprints.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("fingerprints", fingerprints);
        String sql = "SELECT target_id FROM sm_data.target_host_key WHERE tenant_id = :tenant"
                + " AND fingerprint IN (:fingerprints) AND retired_at IS NULL";
        if (exceptTargetId != null) {
            sql += " AND target_id <> :except";
            parameters.addValue("except", exceptTargetId);
        }
        return jdbc.queryForList(sql + " ORDER BY target_id LIMIT 1", parameters, UUID.class).stream().findFirst();
    }

    @Override
    public Set<String> liveHostKeys(UUID tenantId, UUID targetId) {
        return new LinkedHashSet<>(jdbc.queryForList("SELECT fingerprint FROM sm_data.target_host_key"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL ORDER BY fingerprint",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId),
                String.class));
    }

    @Override
    public void backfillCredentialFingerprint(UUID tenantId, UUID targetId, String fingerprint) {
        // Only a NULL is ever filled in: a value another writer recorded since is never overwritten.
        Sql.write(() -> jdbc.update("UPDATE sm_data.target_connection SET credential_public_fingerprint = :fingerprint"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND credential_public_fingerprint IS NULL",
                new MapSqlParameterSource().addValue("fingerprint", fingerprint).addValue("tenant", tenantId)
                        .addValue("target", targetId)));
    }

    @Override
    public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef, String publicFingerprint) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.target_connection SET credential_ref = :credential,"
                        + " credential_public_fingerprint = :fingerprint"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("credential", credentialRef)
                        .addValue("fingerprint", publicFingerprint).addValue("tenant", tenantId)
                        .addValue("target", targetId)));
    }

    @Override
    public void recordEvent(TargetConnectionEvent e) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.target_connection_event (tenant_id, target_id, event_id, kind,"
                        + " actor, correlation_id, client_address, changed_fields, public_key_fingerprint, recorded_at)"
                        + " VALUES (:tenant, :target, :event, :kind, :actor, :correlation, :address,"
                        + " CAST(:fields AS text[]), :publicFingerprint, :recordedAt)",
                new MapSqlParameterSource().addValue("tenant", e.tenantId()).addValue("target", e.targetId())
                        .addValue("event", e.eventId()).addValue("kind", e.kind().wire())
                        .addValue("actor", e.actor()).addValue("correlation", e.correlationId())
                        .addValue("address", e.clientAddress())
                        .addValue("fields", e.changedFields().toArray(String[]::new))
                        .addValue("publicFingerprint", e.publicKeyFingerprint())
                        .addValue("recordedAt", Sql.at(e.recordedAt()))));
    }

    @Override
    public Optional<Instant> credentialSetAt(UUID tenantId, UUID targetId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("target", targetId);
        // The newest event that set a key, else the record's own creation (a row registered before the events existed).
        return jdbc.query("SELECT coalesce((SELECT max(recorded_at) FROM sm_data.target_connection_event"
                        + " WHERE tenant_id = :tenant AND target_id = :target"
                        + " AND kind IN ('registered', 'credential_rotated')),"
                        + " (SELECT created_at FROM sm_data.target_connection"
                        + " WHERE tenant_id = :tenant AND target_id = :target)) AS set_at",
                parameters, (rs, i) -> Sql.instant(rs, "set_at")).stream().filter(java.util.Objects::nonNull)
                .findFirst();
    }

    // ---- plan 013 T1.5 --------------------------------------------------------------------------------------

    @Override
    public Optional<TargetConnectionRecord> findLiveForChange(UUID tenantId, UUID targetId) {
        // FOR NO KEY UPDATE, not FOR UPDATE: nothing here changes a key column, and a retirement's FOR UPDATE must be the
        // stronger lock (see TargetConnectionRows#findLiveForChange for how this meets a placement insert). The runtime
        // role may take it: it holds column-level UPDATE on this table (V007).
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.target_connection"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL FOR NO KEY UPDATE",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId), MAP)
                .stream().findFirst();
    }

    @Override
    public Optional<TargetConnectionRecord> findForRetirement(UUID tenantId, UUID targetId) {
        // FOR UPDATE, deliberately the strong lock (W4C-2): see TargetConnectionRows#findForRetirement. The runtime role
        // may take it: it holds column-level UPDATE on this table (V007).
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.target_connection"
                        + " WHERE tenant_id = :tenant AND target_id = :target FOR UPDATE",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId), MAP)
                .stream().findFirst();
    }

    @Override
    public boolean holdLive(UUID tenantId, UUID targetId) {
        // FOR UPDATE, not FOR KEY SHARE (W4C-2): see TargetConnectionRows#holdLive. The weaker lock then V016's FOR UPDATE
        // trigger deadlocks two concurrent placements on one target.
        return !jdbc.queryForList("SELECT 1 FROM sm_data.target_connection"
                        + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL FOR UPDATE",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId), Integer.class)
                .isEmpty();
    }

    @Override
    public Optional<com.solace.sm.dp.inventory.ConnectionRuntimeEnvelope> holdConnectionRuntimeLive(UUID tenantId,
            UUID connectionId) {
        // FOR UPDATE, as holdLive: the placement insert's foreign key then takes only a lock this transaction already holds.
        return jdbc.query("SELECT connection_id, allowed_config_root, allowed_container_prefix"
                        + " FROM sm_data.connection_runtime WHERE tenant_id = :tenant AND connection_id = :connection"
                        + " AND retired_at IS NULL FOR UPDATE",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", connectionId),
                (rs, i) -> new com.solace.sm.dp.inventory.ConnectionRuntimeEnvelope(
                        rs.getObject("connection_id", UUID.class), rs.getString("allowed_config_root"),
                        rs.getString("allowed_container_prefix")))
                .stream().findFirst();
    }

    @Override
    public boolean connectionRuntimeLive(UUID tenantId, UUID connectionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.connection_runtime"
                        + " WHERE tenant_id = :tenant AND connection_id = :connection AND retired_at IS NULL)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", connectionId),
                Boolean.class));
    }

    @Override
    public Optional<com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec> findConnectionRuntime(UUID tenantId,
            UUID connectionId) {
        return jdbc.query("SELECT connection_id, host, port, ssh_user, host_key_fingerprint, credential_ref, passphrase_ref,"
                        + " auth_method,"
                        + " allowed_config_root, allowed_container_prefix"
                        + " FROM sm_data.connection_runtime"
                        + " WHERE tenant_id = :tenant AND connection_id = :connection AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", connectionId),
                (rs, i) -> new com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec(
                        rs.getObject("connection_id", UUID.class), rs.getString("host"), rs.getInt("port"),
                        rs.getString("ssh_user"), rs.getString("host_key_fingerprint"),
                        rs.getObject("credential_ref", UUID.class), rs.getObject("passphrase_ref", UUID.class),
                        rs.getString("auth_method"), rs.getString("allowed_config_root"),
                        rs.getString("allowed_container_prefix")))
                .stream().findFirst();
    }

    @Override
    public void upsertConnectionRuntime(UUID tenantId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec) {
        jdbc.update("INSERT INTO sm_data.connection_runtime (tenant_id, connection_id, connection_type, host, port,"
                        + " ssh_user, host_key_fingerprint, credential_ref, passphrase_ref, auth_method, allowed_config_root,"
                        + " allowed_container_prefix, created_at)"
                        + " VALUES (:tenant, :connection, 'remote-server', :host, :port, :user, :fingerprint,"
                        + " :credential, :passphrase, :authMethod, :configRoot, :containerPrefix, now())"
                        + " ON CONFLICT (tenant_id, connection_id) DO UPDATE SET"
                        + " host = EXCLUDED.host, port = EXCLUDED.port, ssh_user = EXCLUDED.ssh_user,"
                        + " host_key_fingerprint = EXCLUDED.host_key_fingerprint,"
                        + " credential_ref = EXCLUDED.credential_ref,"
                        + " allowed_config_root = EXCLUDED.allowed_config_root,"
                        + " allowed_container_prefix = EXCLUDED.allowed_container_prefix, retired_at = NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", spec.connectionId())
                        .addValue("host", spec.host()).addValue("port", spec.port()).addValue("user", spec.sshUser())
                        .addValue("fingerprint", spec.hostKeyFingerprint())
                        .addValue("credential", spec.credentialRef())
                        .addValue("passphrase", spec.passphraseRef())
                        .addValue("authMethod", spec.authMethod())
                        .addValue("configRoot", spec.allowedConfigRoot())
                        .addValue("containerPrefix", spec.allowedContainerPrefix()));
    }

    @Override
    public void retireConnectionRuntime(UUID tenantId, UUID connectionId) {
        jdbc.update("UPDATE sm_data.connection_runtime SET retired_at = now()"
                        + " WHERE tenant_id = :tenant AND connection_id = :connection AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", connectionId));
    }

    @Override
    public int updateConnection(UUID tenantId, UUID targetId, String host, int port, String sshUser,
            String hostKeyFingerprint, HostKeyType hostKeyType) {
        try {
            // Only the columns V007 and V027 grant; the envelope has no statement here that could change it. V012's trigger
            // mirrors a changed pin into target_host_key, and the unique indexes refuse a pin or a machine another live
            // target of the tenant holds. The pin's type is written in the SAME statement as the pin (OR-125 (2)).
            return Sql.write(() -> jdbc.update("UPDATE sm_data.target_connection SET host = :host, port = :port,"
                            + " ssh_user = :user, host_key_fingerprint = :fingerprint, host_key_algorithm = :algorithm"
                            + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL",
                    new MapSqlParameterSource().addValue("host", host).addValue("port", port)
                            .addValue("user", sshUser).addValue("fingerprint", hostKeyFingerprint)
                            .addValue("algorithm", wire(hostKeyType), Types.VARCHAR)
                            .addValue("tenant", tenantId).addValue("target", targetId)));
        } catch (PgConstraintViolation v) {
            throw heldElsewhereOrSame(v);
        }
    }

    @Override
    public int recordHostKeyType(UUID tenantId, String fingerprint, HostKeyType type) {
        // Only while no type is recorded, only this tenant's LIVE row, and only for the pin that connected: a row re-pinned
        // meanwhile no longer matches, and a recorded type is never replaced by a later guess (OR-125 (2)).
        return Sql.write(() -> jdbc.update("UPDATE sm_data.target_connection SET host_key_algorithm = :algorithm"
                        + " WHERE tenant_id = :tenant AND host_key_fingerprint = :fingerprint AND retired_at IS NULL"
                        + " AND host_key_algorithm IS NULL",
                new MapSqlParameterSource().addValue("algorithm", type.wire()).addValue("tenant", tenantId)
                        .addValue("fingerprint", fingerprint)));
    }

    private static String wire(HostKeyType type) {
        return type == null ? null : type.wire();
    }

    @Override
    public int retireHostKeysNotIn(UUID tenantId, UUID targetId, Collection<String> keep, Instant at) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("at", Sql.at(at))
                .addValue("tenant", tenantId).addValue("target", targetId);
        String sql = "UPDATE sm_data.target_host_key SET retired_at = :at"
                + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL";
        if (!keep.isEmpty()) {
            sql += " AND fingerprint NOT IN (:keep)";
            parameters.addValue("keep", keep);
        }
        String statement = sql;
        return Sql.write(() -> jdbc.update(statement, parameters));
    }

    @Override
    public int reviveHostKeys(UUID tenantId, UUID targetId, Collection<String> offered) {
        if (offered.isEmpty()) {
            return 0;
        }
        try {
            return Sql.write(() -> jdbc.update("UPDATE sm_data.target_host_key SET retired_at = NULL"
                            + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NOT NULL"
                            + " AND fingerprint IN (:offered)",
                    new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId)
                            .addValue("offered", offered)));
        } catch (PgConstraintViolation v) {
            throw heldElsewhereOrSame(v);
        }
    }

    /** What V007's {@code target_connection_retire_guard} raises (SQLSTATE P0001); the text is its fixed message. */
    private static final String RETIRE_GUARD_MESSAGE = "a target cannot be retired while it has live placements";

    @Override
    public int retire(UUID tenantId, UUID targetId, Instant at) {
        try {
            return Sql.write(() -> jdbc.update("UPDATE sm_data.target_connection SET retired_at = :at"
                            + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL",
                    new MapSqlParameterSource().addValue("at", Sql.at(at)).addValue("tenant", tenantId)
                            .addValue("target", targetId)));
        } catch (DataAccessException e) {
            if (namesRetireGuard(e)) {
                // Classifier only, and no cause (the driver's message is not ours to forward).
                throw new TargetHasLivePlacementsException();
            }
            throw e;
        }
    }

    @Override
    public boolean agentRevoked(UUID tenantId, UUID agentId) {
        // an identity that does not exist cannot be shown revoked: a store that cannot answer must not retire (fail closed)
        return jdbc.queryForList("SELECT revoked_at IS NOT NULL FROM sm_data.agent_identity WHERE tenant_id = :tenant"
                        + " AND agent_id = :agent",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("agent", agentId), Boolean.class)
                .stream().findFirst().orElse(false);
    }

    @Override
    public java.util.Map<UUID, String> transportsOf(UUID tenantId, Collection<UUID> targetIds) {
        if (targetIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, String> out = new java.util.HashMap<>();
        jdbc.query("SELECT target_id, transport FROM sm_data.target_connection WHERE tenant_id = :tenant"
                        + " AND target_id IN (:targets)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("targets", targetIds),
                rs -> {
                    out.put(rs.getObject("target_id", UUID.class), rs.getString("transport"));
                });
        return out;
    }

    private static boolean namesRetireGuard(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.postgresql.util.PSQLException psql && "P0001".equals(psql.getSQLState())
                    && psql.getServerErrorMessage() != null
                    && RETIRE_GUARD_MESSAGE.equals(psql.getServerErrorMessage().getMessage())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static TargetConnectionRecord row(ResultSet rs, int i) throws SQLException {
        return new TargetConnectionRecord(rs.getObject("tenant_id", UUID.class), rs.getObject("target_id", UUID.class),
                rs.getString("host"), rs.getInt("port"), rs.getString("ssh_user"),
                rs.getString("host_key_fingerprint"), rs.getObject("credential_ref", UUID.class),
                rs.getString("allowed_config_root"), rs.getString("allowed_container_prefix"),
                rs.getObject("created_by", UUID.class), Sql.instant(rs, "created_at"), Sql.instant(rs, "retired_at"),
                rs.getString("credential_public_fingerprint"),
                HostKeyType.ofWire(rs.getString("host_key_algorithm")).orElse(null), rs.getObject("agent_id", UUID.class));
    }
}
