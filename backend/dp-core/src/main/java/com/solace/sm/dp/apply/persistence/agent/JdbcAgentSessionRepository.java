package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentFeature;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CloseReason;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.SessionOpening;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.agent_session} (plan 015 T2.1b): who owns an agent's connection right now. One row per agent; each
 * {@link #claim} takes the row over at a strictly higher fencing epoch ({@code agent_session_epoch} keeps it from falling), and
 * every other call is fenced on that epoch, inside the transaction of its own write ({@link AgentAuthority#underSession}).
 *
 * <p>{@code claim} is ONE statement: it reads the identity {@code FOR SHARE} and refuses a revoked one in the same statement as the
 * upsert, so a revocation cannot land between "not revoked" and "session opened" (a revocation takes the identity row's lock too).
 * It persists what the agent declared in {@link SessionOpening} ({@code V028} protocol and agent version, {@code V030} features and
 * clock offset). The clock offset is the DATA PLANE's clock minus the agent's, clamped to a day either way; {@code last_seen_at}
 * stays on the data plane's clock and an agent's clock never moves it ({@code OR-130} F-6). A claim carries no request id and the
 * table keeps none, so a claim is FENCED rather than replayed: the later one wins and the earlier stream is refused.
 */
public final class JdbcAgentSessionRepository implements AgentSessionPort {

    /** The most skew recorded either way: beyond it the offset says "the agent's clock is wrong", not by how much. */
    static final long MAX_OFFSET_MS = Duration.ofHours(24).toMillis();
    private static final int MAX_AGENT_VERSION = 64;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final AgentAuthority authority;
    private final JdbcAgentIdentityRepository identities;
    private final Duration liveWindow;

    /** The default {@link #liveWindow}: a few heartbeat intervals, long enough that a healthy session never reads stale. */
    public static final Duration DEFAULT_LIVE_WINDOW = Duration.ofMinutes(2);

    public JdbcAgentSessionRepository(NamedParameterJdbcTemplate jdbc, Clock clock, AgentAuthority authority,
            JdbcAgentIdentityRepository identities) {
        this(jdbc, clock, authority, identities, DEFAULT_LIVE_WINDOW);
    }

    /**
     * @param liveWindow how recently the session must have been heard from for {@link #liveFor} to say yes (plan 015 T3.1 fix
     *                   round, review finding 2). A session row that predates this process and whose agent has gone quiet is
     *                   not live: without the term, boot-time crash recovery would resume an agent step against a session that
     *                   cannot answer (the gateway is not listening yet) and wait out its whole ceiling, serially, inside boot.
     *                   It is a freshness term and not a boot-time close, so it stays right when the gateway is deployed
     *                   separately ({@code AGW-D2}). A restart faster than the window still waits, bounded by the ceiling.
     */
    public JdbcAgentSessionRepository(NamedParameterJdbcTemplate jdbc, Clock clock, AgentAuthority authority,
            JdbcAgentIdentityRepository identities, Duration liveWindow) {
        if (liveWindow == null || liveWindow.isZero() || liveWindow.isNegative()) {
            throw new IllegalArgumentException("the live window is a positive duration");
        }
        this.liveWindow = liveWindow;
        this.jdbc = jdbc;
        this.clock = clock;
        this.authority = authority;
        this.identities = identities;
    }

    static long offsetMillis(Instant dataPlaneNow, Instant agentClock) {
        long raw = Duration.between(agentClock, dataPlaneNow).toMillis();
        return Math.max(-MAX_OFFSET_MS, Math.min(MAX_OFFSET_MS, raw));
    }

    static String feature(AgentFeature f) {
        return f.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public AgentSessionRef claim(String certificateThumbprint, SessionOpening opening, AgentRequestOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(opening, "opening");
        if (opening.protocolVersion() <= 0) {
            throw AgentSql.refused(GatewayCode.PROTOCOL_VERSION_UNSUPPORTED, "protocol version is not supported");
        }
        AgentIdentity resolved = identities.resolveByThumbprint(certificateThumbprint)
                .orElseThrow(() -> AgentSql.refused(GatewayCode.UNAUTHENTICATED_IDENTITY, "certificate is not enrolled"));
        // The data plane's copy of the agent's authority was taken at enrolment. A revocation the control plane has made since
        // must govern this claim, so ask it now rather than trust the copy.
        AgentIdentity identity = identities.synced(resolved);
        if (identity.revoked()) {
            throw AgentSql.refused(GatewayCode.IDENTITY_REVOKED, "agent identity is revoked");
        }
        Instant now = clock.instant();
        String version = opening.agentVersion().isEmpty() ? "unknown"
                : opening.agentVersion().length() > MAX_AGENT_VERSION ? opening.agentVersion().substring(0, MAX_AGENT_VERSION)
                : opening.agentVersion();
        Set<AgentFeature> declared = opening.features().isEmpty() ? EnumSet.noneOf(AgentFeature.class)
                : EnumSet.copyOf(opening.features());
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("agent", identity.agentId())
                .addValue("now", AgentSql.at(now)).addValue("protocol", opening.protocolVersion())
                .addValue("version", version)
                .addValue("features", AgentSql.textArray(declared.stream().map(JdbcAgentSessionRepository::feature).sorted().toList()))
                .addValue("offset", offsetMillis(now, opening.agentClock()));
        List<Long> epoch = AgentSql.write(() -> jdbc.queryForList("WITH ident AS (SELECT tenant_id, agent_id"
                + " FROM sm_data.agent_identity WHERE agent_id = :agent AND revoked_at IS NULL FOR SHARE)"
                + " INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                + " features, clock_offset_ms, opened_at, last_seen_at)"
                + " SELECT tenant_id, agent_id, 1, :protocol, :version, CAST(:features AS text[]), :offset, :now, :now FROM ident"
                + " ON CONFLICT (tenant_id, agent_id) DO UPDATE SET fencing_epoch = agent_session.fencing_epoch + 1,"
                + " protocol_version = EXCLUDED.protocol_version, agent_version = EXCLUDED.agent_version,"
                + " features = EXCLUDED.features, clock_offset_ms = EXCLUDED.clock_offset_ms,"
                + " opened_at = :now, last_seen_at = :now, closed_at = NULL RETURNING fencing_epoch", p, Long.class));
        if (epoch.isEmpty()) {
            throw AgentSql.refused(GatewayCode.IDENTITY_REVOKED, "agent identity is revoked");
        }
        return new AgentSessionRef(identity.agentId(), epoch.get(0));
    }

    @Override
    public void heartbeat(AgentSessionRef ref, Instant agentClock) {
        Objects.requireNonNull(agentClock, "agentClock");
        authority.underSession(ref, standing -> {
            Instant now = clock.instant();
            int updated = AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_session SET last_seen_at = :at,"
                    + " clock_offset_ms = :offset WHERE tenant_id = :tenant AND agent_id = :agent AND fencing_epoch = :epoch"
                    + " AND closed_at IS NULL", new MapSqlParameterSource().addValue("tenant", standing.tenantId())
                    .addValue("agent", ref.agentId()).addValue("epoch", ref.fencingEpoch()).addValue("at", AgentSql.at(now))
                    .addValue("offset", offsetMillis(now, agentClock))));
            if (updated != 1) {
                throw AgentSql.refused(GatewayCode.STALE_EPOCH, "session is no longer current");
            }
            return null;
        });
    }

    @Override
    public void close(AgentSessionRef ref, CloseReason reason) {
        Objects.requireNonNull(reason, "reason");
        // Closing is idempotent and never an error: a superseded stream ending after its successor opened must not close the
        // successor, which the epoch predicate guarantees. The tenant is the one AgentAuthority derives for the epoch holder;
        // a ref that holds no epoch matches no row.
        AgentAuthority.Standing standing;
        try {
            standing = authority.requireEpochHolder(ref);
        } catch (com.solace.sm.dp.api.agw.GatewayPortException stale) {
            return;
        }
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_session SET closed_at = :now WHERE tenant_id = :tenant"
                + " AND agent_id = :agent AND fencing_epoch = :epoch AND closed_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("agent", ref.agentId())
                        .addValue("epoch", ref.fencingEpoch()).addValue("now", AgentSql.at(clock.instant()))));
    }

    private static final String LIVE = " FROM sm_data.agent_session s"
            + " JOIN sm_data.agent_identity i ON i.tenant_id = s.tenant_id AND i.agent_id = s.agent_id"
            + " JOIN sm_data.agent_lease l ON l.tenant_id = s.tenant_id AND l.agent_id = s.agent_id"
            + " JOIN sm_data.placement p ON p.tenant_id = s.tenant_id AND p.placement_id = :placement"
            + " AND p.target_id = i.target_id AND p.retired_at IS NULL"
            + " WHERE s.tenant_id = :tenant AND s.agent_id = :agent AND s.closed_at IS NULL AND s.last_seen_at > :seen"
            + " AND i.revoked_at IS NULL AND l.state = 'ACTIVE' AND l.expires_at > :now"
            + " AND l.agent_epoch = i.agent_epoch AND l.fleet_epoch = i.fleet_epoch"
            + " AND l.capabilities = i.capabilities AND :placement = ANY (l.placement_ids)";

    private MapSqlParameterSource live(UUID agentId, AgentPlacementRef placement) {
        // The placement ref carries the tenant the DATA PLANE's own caller derived (the apply engine, from its journal); the
        // agent's identity, lease and the placement's target must be that tenant's, or nothing matches.
        return new MapSqlParameterSource().addValue("tenant", placement.tenantId()).addValue("agent", agentId)
                .addValue("placement", placement.placementId()).addValue("now", AgentSql.at(clock.instant()))
                .addValue("seen", AgentSql.at(clock.instant().minus(liveWindow)));
    }

    @Override
    public boolean liveFor(UUID agentId, AgentPlacementRef placement) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1" + LIVE + ")", live(agentId, placement), Boolean.class));
    }

    @Override
    public Set<AgentFeature> featuresOf(UUID agentId, AgentPlacementRef placement) {
        List<List<String>> rows = jdbc.query("SELECT s.features" + LIVE, live(agentId, placement),
                (rs, n) -> AgentSql.strings(rs, "features"));
        Set<AgentFeature> out = EnumSet.noneOf(AgentFeature.class);
        if (!rows.isEmpty()) {
            rows.get(0).forEach(f -> out.add(AgentFeature.valueOf(f.toUpperCase(Locale.ROOT))));
        }
        return out;
    }
}
