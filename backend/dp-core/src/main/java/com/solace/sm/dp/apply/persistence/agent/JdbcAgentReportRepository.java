package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code sm_data.agent_report_watermark} (plan 015 T2.1b, T3.3): the admission and ordering half of an actual-state report, plus the
 * few placement reads and the forward-only start write the intake needs. Trust is DP-derived at receive time from the lease, never
 * asserted by the agent; the disposition is orthogonal to it ({@code AGW-D4}, {@code AGW-D6}).
 *
 * <ul>
 *   <li>A placement the agent's lease does not name, that is retired, or that is on another target, is
 *       {@code UNTRUSTED}/{@code REFUSED} {@code BINDING_NOT_IN_LEASE} and NOTHING is written for it.
 *       {@code ReportItem.bindingId} is agent-supplied and is only ever compared with the lease's placement ids; it never
 *       resolves a tenant or an instance ({@code I5}).</li>
 *   <li>A {@code replica_ref} that is not the placement's {@code container_name}, or an incarnation outside its grammar, is
 *       {@code REFUSED} {@code REPLICA_REF_INVALID} with the trust the lease gives, and nothing is written ({@code AGW-D25} item 2).</li>
 *   <li>An item outside its own grammar (negative sequence or generation, no item id or binding) is refused by itself,
 *       {@code UNTRUSTED}, {@code LABEL_INVALID}: one malformed item never aborts the batch.</li>
 *   <li>A report that arrives after the lease EXPIRED (or whose epochs or capabilities no longer match the identity's) is still
 *       taken, on the session that authenticates it, and labelled {@code UNTRUSTED} (design 4.6); the intake applies no effect
 *       of an untrusted item. An agent that has NEVER held a lease is refused {@code LEASE_RENEW_REQUIRED}.</li>
 *   <li>An in-lease item above the placement's watermark advances it ({@code APPLIED}); the SAME item again (same sequence and
 *       item id) replays {@code APPLIED} without advancing; anything at or below the watermark is {@code STALE}, trusted, and
 *       moves nothing.</li>
 *   <li>{@code replica_id} is the placement id and {@code incarnation_id} a derived UUIDv5 ({@code AGW-D25}): no registry row.</li>
 * </ul>
 *
 * <p>This is a PERSISTENCE HELPER, not the {@code ActualStatePort} bean: {@code AgentReportIntake} owns that port, calls
 * {@link #process} for the watermark and the classification, and applies the health, start-time and ladder effects of an accepted
 * report in the SAME transaction. The watermark stores the LAST item id only, so an item id reused with different content is
 * detected only while it is still the last one. The helper stores no digest and no rendered byte ({@code I6}, {@code OR-32}).
 */
public final class JdbcAgentReportRepository {

    /** An agent-supplied item id: printable, at most 64 characters (the gateway's own ids are UUIDs). */
    private static final java.util.regex.Pattern ITEM_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * How far past the data plane's receipt time a reported container start may lie. A start is HOST-clock and forward-only, so one
     * future-dated value would read as {@code restart-observed} for any persisted generation and then no real start could ever move
     * the row. The bound is the clock window already ruled for the agent's data ({@code OR-130} F-6, plan 015 T3.4: five minutes of
     * receipt); a start beyond it is {@code CLOCK_SUSPECT} and nothing is written. Recorded against AGW-D28 item 2 / review R-8.
     */
    static final java.time.Duration START_SKEW = java.time.Duration.ofMinutes(5);

    /** The grammar of {@code placement_start_observation.process_ref} (V018) and of a replica ref: also the incarnation's. */
    private static final java.util.regex.Pattern INCARNATION = java.util.regex.Pattern.compile("[A-Za-z0-9._:/@+-]{1,255}");

    /** What {@link #process} decided for one item, with the DP-derived ids the intake needs and the agent never supplied. */
    public record Processed(ReportResult result, ReportItem item, UUID tenantId, UUID agentId, UUID instanceId,
            UUID placementId, Optional<String> healthKind, boolean advanced) {

        /** An accepted, TRUSTED item that moved the watermark: the only kind whose effects the intake applies. */
        public boolean effective() {
            return advanced && "TRUSTED".equals(result.trust()) && "APPLIED".equals(result.disposition());
        }

        public boolean refused() {
            return "REFUSED".equals(result.disposition());
        }
    }

    /** The namespace of derived incarnation ids, FROZEN by the 2026-10-10 clarification of {@code AGW-D25}: changing it changes every id an agent has labelled samples with. */
    private static final UUID INCARNATION_NAMESPACE = UUID.fromString("6f0f6a62-5a2c-5f43-9c3e-0d4e2f8f7a11");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentAuthority authority;

    public JdbcAgentReportRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            AgentAuthority authority) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.authority = authority;
    }

    /** Joins the caller's transaction when there is one. */
    public List<Processed> process(AgentSessionRef session, List<ReportItem> items) {
        return tx.execute(status -> {
            AgentAuthority.Standing standing = authority.requireSession(session);
            AgentAuthority.StoredLease lease = authority.activeRow(standing.tenantId(), standing.agentId())
                    .orElseThrow(() -> AgentSql.refused(GatewayCode.LEASE_RENEW_REQUIRED, "agent holds no lease"));
            boolean trusted = lease.expiresAt().isAfter(clock.instant()) && lease.matches(standing);
            List<Processed> results = new ArrayList<>();
            for (ReportItem item : items) {
                results.add(one(standing, lease, trusted, item));
            }
            return results;
        });
    }

    private Processed one(AgentAuthority.Standing standing, AgentAuthority.StoredLease lease, boolean leaseTrusted,
            ReportItem item) {
        String itemId = item.itemId() == null ? "" : item.itemId();
        // The item id becomes a request id in agent_event: bounded and printable, or the item is refused by itself. (A control
        // character would make the driver refuse the parameter and abort the whole batch, which the agent re-sends unchanged.)
        if (!ITEM_ID.matcher(itemId).matches() || item.sequence() < 0 || item.persistedGeneration() < 0
                || item.bindingId() == null || item.bindingId().isBlank()) {
            // One malformed item must not abort the batch: it is refused by itself, untrusted, with a code (T3.3).
            return refusal(standing, item, itemId, "UNTRUSTED", GatewayCode.LABEL_INVALID);
        }
        Optional<UUID> placement = parse(item.bindingId()).filter(lease.placementIds()::contains);
        Optional<PlacementFacts> facts = placement.flatMap(id -> placementFacts(standing, id));
        if (facts.isEmpty()) {
            return refusal(standing, item, itemId, "UNTRUSTED", GatewayCode.BINDING_NOT_IN_LEASE);
        }
        String trust = leaseTrusted ? "TRUSTED" : "UNTRUSTED";
        String incarnation = item.incarnation() == null ? "" : item.incarnation();
        if (!facts.get().containerName().equals(item.replicaRef())
                || (!incarnation.isEmpty() && !INCARNATION.matcher(incarnation).matches())) {
            Processed refused = refusal(standing, item, itemId, trust, GatewayCode.REPLICA_REF_INVALID);
            return new Processed(refused.result(), item, standing.tenantId(), standing.agentId(), facts.get().instanceId(),
                    placement.get(), facts.get().healthKind(), false);
        }
        if (item.containerStartedAt() != null && item.containerStartedAt().isAfter(clock.instant().plus(START_SKEW))) {
            Processed refused = refusal(standing, item, itemId, trust, GatewayCode.CLOCK_SUSPECT);
            return new Processed(refused.result(), item, standing.tenantId(), standing.agentId(), facts.get().instanceId(),
                    placement.get(), facts.get().healthKind(), false);
        }
        UUID itemUuid = AgentSql.uuidOf(itemId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", standing.tenantId())
                .addValue("agent", standing.agentId()).addValue("placement", placement.get())
                .addValue("sequence", item.sequence()).addValue("item", itemUuid).addValue("now", AgentSql.at(clock.instant()));
        int advanced = AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_report_watermark (tenant_id, agent_id,"
                + " placement_id, last_sequence, last_item_id, updated_at) VALUES (:tenant, :agent, :placement, :sequence,"
                + " :item, :now) ON CONFLICT (tenant_id, agent_id, placement_id) DO UPDATE SET"
                + " last_sequence = EXCLUDED.last_sequence, last_item_id = EXCLUDED.last_item_id,"
                + " updated_at = EXCLUDED.updated_at WHERE agent_report_watermark.last_sequence < EXCLUDED.last_sequence", p));
        String disposition = "APPLIED";
        if (advanced == 0) {
            boolean replay = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.agent_report_watermark"
                    + " WHERE tenant_id = :tenant AND agent_id = :agent AND placement_id = :placement"
                    + " AND last_sequence = :sequence AND last_item_id = :item)", p, Boolean.class));
            disposition = replay ? "APPLIED" : "STALE";
        }
        ReportResult result = new ReportResult(itemId, item.sequence(), placement.get().toString(),
                AgentSql.uuidV5(INCARNATION_NAMESPACE, placement.get() + "|" + item.incarnation()).toString(),
                trust, disposition, null);
        return new Processed(result, item, standing.tenantId(), standing.agentId(), facts.get().instanceId(), placement.get(),
                facts.get().healthKind(), advanced > 0);
    }

    private static Processed refusal(AgentAuthority.Standing standing, ReportItem item, String itemId, String trust,
            GatewayCode code) {
        return new Processed(new ReportResult(itemId, item.sequence(), null, null, trust, "REFUSED", code), item,
                standing.tenantId(), standing.agentId(), null, null, Optional.empty(), false);
    }

    private record PlacementFacts(UUID instanceId, String containerName, Optional<String> healthKind) {
    }

    /** The LIVE placement of this organization on THIS agent's target, or empty: scoped by the identity, never by the item. */
    private Optional<PlacementFacts> placementFacts(AgentAuthority.Standing standing, UUID placementId) {
        return jdbc.query("SELECT instance_id, container_name, health_kind FROM sm_data.placement WHERE tenant_id = :tenant"
                        + " AND placement_id = :placement AND target_id = :target AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("placement", placementId)
                        .addValue("target", standing.targetId()),
                (rs, n) -> new PlacementFacts(rs.getObject("instance_id", UUID.class), rs.getString("container_name"),
                        Optional.ofNullable(rs.getString("health_kind")))).stream().findFirst();
    }

    /**
     * Records a container start the AGENT reported, FORWARD ONLY ({@code AGW-D28} item 2): a start no later than the one held is
     * ignored, so a stale report can never lower what the ladder reads. Host clock throughout; no data-plane time is compared with
     * it. The caller holds the instance row ({@code StartObservationRows#lockInstance}). Returns whether the stored observation moved.
     */
    public boolean recordStartForward(UUID tenantId, UUID placementId, Instant startedAt, String processRef, Instant observedAt) {
        int written = AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.placement_start_observation"
                + " (tenant_id, placement_id, started_at, process_ref, observed_at)"
                + " VALUES (:t, :p, :started, :ref, :observed) ON CONFLICT (tenant_id, placement_id) DO UPDATE"
                + " SET started_at = EXCLUDED.started_at, process_ref = EXCLUDED.process_ref,"
                + "     observed_at = EXCLUDED.observed_at"
                + " WHERE EXCLUDED.started_at > sm_data.placement_start_observation.started_at",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("p", placementId)
                        .addValue("started", AgentSql.at(startedAt)).addValue("ref", processRef)
                        .addValue("observed", AgentSql.at(observedAt))));
        return written > 0;
    }

    /**
     * Whether an agent of this organization currently holds the placement: its identity is not revoked, its session is open and
     * its lease is ACTIVE, unexpired and still matches the identity. Read at the moment of the question, never cached.
     */
    public boolean live(UUID tenantId, UUID placementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.placement p"
                + " JOIN sm_data.agent_identity i ON i.tenant_id = p.tenant_id AND i.target_id = p.target_id"
                + " JOIN sm_data.agent_session s ON s.tenant_id = i.tenant_id AND s.agent_id = i.agent_id"
                + " JOIN sm_data.agent_lease l ON l.tenant_id = i.tenant_id AND l.agent_id = i.agent_id"
                + " WHERE p.tenant_id = :tenant AND p.placement_id = :placement AND p.retired_at IS NULL"
                + " AND i.revoked_at IS NULL AND s.closed_at IS NULL AND l.state = 'ACTIVE' AND l.expires_at > :now"
                + " AND l.agent_epoch = i.agent_epoch AND l.fleet_epoch = i.fleet_epoch AND l.capabilities @> i.capabilities AND l.capabilities <@ i.capabilities"
                + " AND p.placement_id = ANY (l.placement_ids))",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId)
                        .addValue("now", AgentSql.at(clock.instant())), Boolean.class));
    }

    /**
     * The live agent placements of ONE instance of ONE organization that declare a probe, whose agent last reported before
     * {@code cutoff} and whose persisted health is not already {@code unreachable} (AGW-D28 item 1). Bound by tenant and instance
     * (I5); a placement the agent never reported is not listed (nothing is known, which is {@code bootstrap}, not {@code unreachable}).
     */
    public List<UUID> staleProbedPlacements(UUID tenantId, UUID instanceId, Instant cutoff) {
        return jdbc.queryForList("SELECT p.placement_id FROM sm_data.placement p"
                        + " JOIN sm_data.target_connection t ON t.tenant_id = p.tenant_id AND t.target_id = p.target_id"
                        + " AND t.transport = 'agent'"
                        + " JOIN (SELECT tenant_id, placement_id, max(updated_at) AS last_report"
                        + "        FROM sm_data.agent_report_watermark WHERE tenant_id = :tenant"
                        + "        GROUP BY tenant_id, placement_id) w"
                        + " ON w.tenant_id = p.tenant_id AND w.placement_id = p.placement_id"
                        + " LEFT JOIN sm_data.placement_health h ON h.tenant_id = p.tenant_id AND h.placement_id = p.placement_id"
                        + " WHERE p.tenant_id = :tenant AND p.instance_id = :instance AND p.retired_at IS NULL"
                        + " AND p.health_kind IS NOT NULL AND w.last_report < :cutoff"
                        + " AND (h.state IS NULL OR h.state <> 'unreachable') ORDER BY p.placement_id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("cutoff", AgentSql.at(cutoff)), UUID.class);
    }

    /**
     * The organizations that have at least one placement {@link #staleProbedPlacements} would list (OR-140): the staleness job's
     * organization list, and <b>its ONLY caller</b> ({@code TenantEnumerationBoundaryTest}; {@code OR-140} amends
     * {@code OR-44}/{@code OR-49} to this extent). The predicate is the per-instance read's without the organization and instance.
     */
    public List<UUID> tenantsWithStaleProbedPlacements(Instant cutoff) {
        return AgentStalenessTenants.tenants(jdbc, cutoff);
    }

    /** One organization's instances that have a placement {@link #staleProbedPlacements} would list. Bound by tenant (I5). */
    public List<UUID> staleProbedInstances(UUID tenantId, Instant cutoff) {
        return jdbc.queryForList("SELECT DISTINCT p.instance_id FROM sm_data.placement p"
                        + " JOIN sm_data.target_connection t ON t.tenant_id = p.tenant_id AND t.target_id = p.target_id"
                        + " AND t.transport = 'agent'"
                        + " JOIN (SELECT tenant_id, placement_id, max(updated_at) AS last_report"
                        + "        FROM sm_data.agent_report_watermark WHERE tenant_id = :tenant"
                        + "        GROUP BY tenant_id, placement_id) w"
                        + " ON w.tenant_id = p.tenant_id AND w.placement_id = p.placement_id"
                        + " LEFT JOIN sm_data.placement_health h ON h.tenant_id = p.tenant_id AND h.placement_id = p.placement_id"
                        + " WHERE p.tenant_id = :tenant AND p.retired_at IS NULL AND p.health_kind IS NOT NULL"
                        + " AND w.last_report < :cutoff AND (h.state IS NULL OR h.state <> 'unreachable')"
                        + " ORDER BY p.instance_id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("cutoff", AgentSql.at(cutoff)), UUID.class);
    }

    /**
     * Whether the placement's agent still has not reported since {@code cutoff}, read fresh. The staleness
     * writers call it immediately before they record {@code unreachable}, so a report that committed after the listing is not
     * overwritten by the stale verdict (W3 integration, nit 8). Narrows the window to one statement; it does not close it.
     */
    public boolean stillStale(UUID tenantId, UUID placementId, Instant cutoff) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COALESCE((SELECT max(updated_at) < :cutoff FROM"
                + " sm_data.agent_report_watermark WHERE tenant_id = :tenant AND placement_id = :placement), false)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId)
                        .addValue("cutoff", AgentSql.at(cutoff)), Boolean.class));
    }

    private static Optional<UUID> parse(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
