package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.push.BrokerConnectionRecord;
import com.solace.sm.dp.metrics.push.BrokerConnectionRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.broker_connection} over the runtime credential (plan 011 P11-5.1; OR-107 OQ-3 (a)). The runtime role holds
 * SELECT and INSERT only (V017), so this class has no statement that could change a connection. {@code credential_ref} is a
 * reference into {@code sm_secret}; the credential itself is resolved by the subscription manager, in memory, at connect time.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}.
 */
public final class JdbcBrokerConnectionRepository implements BrokerConnectionRows {

    private static final String COLUMNS = "tenant_id, environment_id, broker_connection_id, scheme, host, port, vpn,"
            + " client_username, credential_ref, created_by, created_at, retired_at";

    private static final RowMapper<BrokerConnectionRecord> MAP = JdbcBrokerConnectionRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBrokerConnectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(BrokerConnectionRecord r) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.broker_connection (" + COLUMNS.replace(", retired_at", "")
                        + ") VALUES (:tenant, :environment, :id, :scheme, :host, :port, :vpn, :user, :credential,"
                        + " :createdBy, :createdAt)",
                new MapSqlParameterSource().addValue("tenant", r.tenantId()).addValue("environment", r.environmentId())
                        .addValue("id", r.brokerConnectionId()).addValue("scheme", r.scheme())
                        .addValue("host", r.host()).addValue("port", r.port()).addValue("vpn", r.vpn())
                        .addValue("user", r.clientUsername()).addValue("credential", r.credentialRef())
                        .addValue("createdBy", r.createdBy()).addValue("createdAt", Sql.at(r.createdAt()))));
    }

    @Override
    public Optional<BrokerConnectionRecord> find(UUID tenantId, UUID brokerConnectionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.broker_connection"
                        + " WHERE tenant_id = :tenant AND broker_connection_id = :id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("id", brokerConnectionId), MAP)
                .stream().findFirst();
    }

    @Override
    public Optional<BrokerConnectionRecord> live(UUID tenantId, UUID environmentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.broker_connection"
                        + " WHERE tenant_id = :tenant AND environment_id = :environment AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("environment", environmentId), MAP)
                .stream().findFirst();
    }

    @Override
    public List<BrokerConnectionRecord> liveOf(UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.broker_connection"
                        + " WHERE tenant_id = :tenant AND retired_at IS NULL ORDER BY environment_id",
                new MapSqlParameterSource().addValue("tenant", tenantId), MAP);
    }

    /**
     * <b>The operator command's own read, not application runtime</b> (OR-112 (2); {@code BE-D«next-or112b»}): is a LIVE
     * connection already registered on this broker and message VPN, by <b>any</b> organization of the install? The one
     * statement in this class that deliberately names no {@code tenant_id}: the rule is "one live connection per broker and VPN
     * across the whole install", and an organization-scoped read could not see the other organization's row. That is safe here
     * and only here because the caller is {@link BrokerConnectionMain}, run by the install operator with the DP runtime
     * credential on the host; the answer never leaves the command as a row, an id or an organization (it chooses one fixed
     * refusal sentence). <b>Package-private on purpose</b> and absent from {@link BrokerConnectionRows}, the interface the
     * application's push path holds, so no request or tick can reach an install-wide read ({@code I5}). The host is compared
     * case-insensitively, exactly as the {@code broker_connection_live_broker_once} index (V019) is built.
     */
    boolean liveAtBroker(String host, int port, String vpn) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.broker_connection"
                        + " WHERE lower(host) = lower(:host) AND port = :port AND vpn = :vpn AND retired_at IS NULL)",
                new MapSqlParameterSource().addValue("host", host).addValue("port", port).addValue("vpn", vpn),
                Boolean.class));
    }

    private static BrokerConnectionRecord row(ResultSet rs, int i) throws SQLException {
        return new BrokerConnectionRecord(rs.getObject("tenant_id", UUID.class),
                rs.getObject("environment_id", UUID.class), rs.getObject("broker_connection_id", UUID.class),
                rs.getString("scheme"), rs.getString("host"), rs.getInt("port"), rs.getString("vpn"),
                rs.getString("client_username"), rs.getObject("credential_ref", UUID.class),
                rs.getObject("created_by", UUID.class), Sql.instant(rs, "created_at"), Sql.instant(rs, "retired_at"));
    }
}
