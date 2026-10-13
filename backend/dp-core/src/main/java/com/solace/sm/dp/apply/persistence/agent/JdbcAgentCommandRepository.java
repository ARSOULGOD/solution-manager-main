package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.api.agw.AgentAck;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandAckResult;
import com.solace.sm.dp.api.agw.CommandCompletion;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.CommandOffer;
import com.solace.sm.dp.api.agw.CommandPayload;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.OperationCorrelation;
import com.solace.sm.dp.api.agw.AgentCommandRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code sm_data.agent_command} (plan 015 T2.1b, {@code BE-D66}, {@code AGW-D23}..{@code AGW-D26}): the durable command intent
 * the data plane persists BEFORE delivery, and the delivery state machine.
 *
 * <ul>
 *   <li><b>Identity.</b> A command is keyed on the apply journal intent's attempt-free key plus the operation. {@link #enqueue}
 *       returns the LIVE command for that key, else the latest SUCCEEDED one, else mints a new generation; it never mints while
 *       one is live ({@code agent_command_one_live_per_intent} is the backstop, this is the rule).</li>
 *   <li><b>No byte.</b> The row holds the keyed hash only ({@code OR-32}); {@link #pull} gets the bytes from the
 *       {@link AgentCommandPayloadSource}, hands them back and writes nothing.</li>
 *   <li><b>Fenced.</b> Every session-bearing call starts with {@link AgentAuthority#requireSession}: a stale epoch is refused
 *       before any read, and the tenant is the identity's, never a caller's value. A command is bound to its (tenant, agent).</li>
 * </ul>
 *
 * <p>Not persisted, by design of {@code V028}: {@code AgentCommandIntent.supersedesGeneration} (no column; {@code pull} offers
 * none) and the free-text ack message. {@code AgentAck.containerStartedAt} is not stored on the command row either; it is
 * recorded in the acknowledgement's own transaction as the attempt's {@code write_baseline} and as the placement's start
 * observation ({@code AGW-D28} item 2, {@code OR-139}; see {@link #ack}).
 */
public final class JdbcAgentCommandRepository implements AgentCommandPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentCommandRepository.class);

    private static final Pattern KEYED_HASH = Pattern.compile("^hmacsha256v[1-9][0-9]{0,8}:[0-9a-f]{64}$");
    private static final Pattern DETAIL_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9._]{0,63}$");
    private static final UUID NO_ATTEMPT = new UUID(0, 0);

    private static final String ROW = "SELECT c.command_id, c.agent_id, c.placement_id, p.instance_id, c.apply_id, c.intent_id,"
            + " c.intent_key, c.attempt_id, i.step, c.operation, c.wire_generation, c.config_hash, c.state, c.outcome,"
            + " c.detail_code, c.deadline, c.acked_at, c.offered_epoch FROM sm_data.agent_command c"
            + " JOIN sm_data.apply_step_intent i ON i.tenant_id = c.tenant_id AND i.apply_id = c.apply_id"
            + " AND i.intent_id = c.intent_id"
            + " JOIN sm_data.placement p ON p.tenant_id = c.tenant_id AND p.placement_id = c.placement_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentAuthority authority;
    private final AgentCommandPayloadSource payloads;
    private final AckStartSettler settler;

    public JdbcAgentCommandRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            AgentAuthority authority, AgentCommandPayloadSource payloads) {
        this(jdbc, tx, clock, authority, payloads, AckStartSettler.NONE);
    }

    public JdbcAgentCommandRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            AgentAuthority authority, AgentCommandPayloadSource payloads, AckStartSettler settler) {
        this.settler = Objects.requireNonNull(settler, "settler");
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.authority = authority;
        this.payloads = payloads;
    }

    /** A command row as read. */
    private record Row(UUID tenantId, CommandId id, UUID agentId, UUID placementId, UUID instanceId, UUID applyId,
            UUID intentId, String intentKey, UUID attemptId, String step, AgentOperation operation, long generation,
            String configHash, String state, String outcome, String detailCode, Instant deadline, Instant ackedAt,
            Long offeredEpoch) {

        OperationCorrelation correlation() {
            return new OperationCorrelation(applyId.toString(), intentId.toString(), intentKey,
                    (attemptId == null ? NO_ATTEMPT : attemptId).toString(), step);
        }

        AgentCommandPayloadSource.Command command() {
            return new AgentCommandPayloadSource.Command(id, agentId,
                    new AgentPlacementRef(tenantId, instanceId, placementId), operation, intentKey, configHash, generation);
        }
    }

    private Row row(java.sql.ResultSet rs, UUID tenant) throws java.sql.SQLException {
        return new Row(tenant, new CommandId(rs.getObject("command_id", UUID.class)), rs.getObject("agent_id", UUID.class),
                rs.getObject("placement_id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getObject("apply_id", UUID.class), rs.getObject("intent_id", UUID.class), rs.getString("intent_key"),
                rs.getObject("attempt_id", UUID.class), rs.getString("step"), AgentOperation.valueOf(rs.getString("operation")),
                rs.getLong("wire_generation"), rs.getString("config_hash"), rs.getString("state"), rs.getString("outcome"),
                rs.getString("detail_code"), AgentSql.instant(rs, "deadline"), AgentSql.instant(rs, "acked_at"),
                (Long) rs.getObject("offered_epoch"));
    }

    // ---- enqueue / supersede (called by the apply engine, which knows its own tenant) -----------------------------

    @Override
    public AgentCommandRef enqueue(AgentCommandIntent intent) {
        boolean config = intent.operation() == AgentOperation.APPLY || intent.operation() == AgentOperation.REMATERIALIZE;
        if (config != (intent.keyedConfigHash() != null)) {
            throw new IllegalArgumentException("a keyed config hash is required for exactly APPLY and REMATERIALIZE");
        }
        if (config && !KEYED_HASH.matcher(intent.keyedConfigHash()).matches()) {
            throw new IllegalArgumentException("only the keyed hmacsha256v<n>:<hex> config hash is stored (OR-32)");
        }
        OperationCorrelation correlation = java.util.Objects.requireNonNull(intent.correlation(), "correlation");
        UUID tenant = intent.placement().tenantId();
        return tx.execute(status -> {
            // Two locks, always in this order: the intent's, then the placement's (generation allocation). They keep the
            // read-then-insert below from racing; the partial unique index is the backstop, not the mechanism.
            lock("agw-cmd-intent|" + tenant + "|" + intent.intentKey() + "|" + intent.operation());
            lock("agw-cmd-gen|" + tenant + "|" + intent.agentId() + "|" + intent.placement().placementId());
            MapSqlParameterSource key = new MapSqlParameterSource().addValue("tenant", tenant)
                    .addValue("key", intent.intentKey()).addValue("op", intent.operation().name());
            Optional<AgentCommandRef> live = jdbc.query("SELECT command_id, wire_generation FROM sm_data.agent_command"
                    + " WHERE tenant_id = :tenant AND intent_key = :key AND operation = :op"
                    + " AND state IN ('QUEUED', 'OFFERED', 'DELIVERED') ORDER BY wire_generation DESC LIMIT 1", key,
                    (rs, n) -> new AgentCommandRef(new CommandId(rs.getObject(1, UUID.class)), rs.getLong(2), false))
                    .stream().findFirst();
            if (live.isPresent()) {
                // The deadline is set at mint and the lease stops admitting a command well before it passes (deadline minus the
                // verb ceiling and margin), so a live command a retry or a resume finds later could never be delivered: it would
                // be handed back, waited on for a whole ceiling and failed, forever. Refresh the deadline instead: the id and
                // the wire generation are unchanged, so the agent journal's dedupe still holds. Never earlier than it was.
                AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command SET deadline = :deadline WHERE tenant_id = :tenant"
                        + " AND command_id = :command AND state IN ('QUEUED', 'OFFERED', 'DELIVERED') AND deadline < :deadline",
                        new MapSqlParameterSource().addValue("deadline", AgentSql.at(intent.deadline()))
                                .addValue("tenant", tenant).addValue("command", live.get().commandId().value())));
                return live.get();
            }
            Optional<AgentCommandRef> done = jdbc.query("SELECT command_id, wire_generation FROM sm_data.agent_command"
                    + " WHERE tenant_id = :tenant AND intent_key = :key AND operation = :op AND state = 'ACKED'"
                    + " AND outcome IN ('APPLIED', 'PREFLIGHT_OK') ORDER BY wire_generation DESC LIMIT 1", key,
                    (rs, n) -> new AgentCommandRef(new CommandId(rs.getObject(1, UUID.class)), rs.getLong(2), false))
                    .stream().findFirst();
            if (done.isPresent()) {
                return done.get();
            }
            UUID id = UUID.randomUUID();
            Instant now = clock.instant();
            Long generation = jdbc.queryForObject("SELECT COALESCE(MAX(wire_generation), 0) + 1 FROM sm_data.agent_command"
                    + " WHERE tenant_id = :tenant AND agent_id = :agent AND placement_id = :placement",
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("agent", intent.agentId())
                            .addValue("placement", intent.placement().placementId()), Long.class);
            AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_command (tenant_id, command_id, agent_id,"
                    + " placement_id, apply_id, intent_id, intent_key, attempt_id, operation, wire_generation, config_hash,"
                    + " state, deadline, created_at) VALUES (:tenant, :id, :agent, :placement, :apply, :intent, :key,"
                    + " :attempt, :op, :generation, :hash, 'QUEUED', :deadline, :now)",
                    new MapSqlParameterSource().addValue("tenant", tenant).addValue("id", id)
                            .addValue("agent", intent.agentId()).addValue("placement", intent.placement().placementId())
                            .addValue("apply", UUID.fromString(correlation.applyId()))
                            .addValue("intent", UUID.fromString(correlation.intentId())).addValue("key", intent.intentKey())
                            .addValue("attempt", attempt(correlation.attemptId())).addValue("op", intent.operation().name())
                            .addValue("generation", generation).addValue("hash", intent.keyedConfigHash())
                            .addValue("deadline", AgentSql.at(intent.deadline())).addValue("now", AgentSql.at(now))));
            return new AgentCommandRef(new CommandId(id), generation, true);
        });
    }

    private static UUID attempt(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;            // correlation only, never identity
        }
    }

    private void lock(String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:key))", new MapSqlParameterSource("key", key), rs -> { });
    }

    @Override
    public void supersedeOutstanding(AgentPlacementRef placement, String keepIntentKey) {
        // QUEUED and OFFERED only: a DELIVERED command is held by the agent, which will ack it or let its deadline pass.
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command SET state = 'SUPERSEDED', acked_at = :now"
                + " WHERE tenant_id = :tenant AND placement_id = :placement AND intent_key <> :keep"
                + " AND state IN ('QUEUED', 'OFFERED')", new MapSqlParameterSource()
                .addValue("tenant", placement.tenantId()).addValue("placement", placement.placementId())
                .addValue("keep", keepIntentKey).addValue("now", AgentSql.at(clock.instant()))));
    }

    // ---- the session-bearing path ---------------------------------------------------------------------------------

    @Override
    public List<CommandNotice> pendingFor(AgentSessionRef ref, int limit, Duration renotifyAfter) {
        int bounded = Math.max(1, Math.min(limit, 100));
        Instant now = clock.instant();
        return tx.execute(status -> {
            // The standing check is the FIRST statement of the write's own transaction, FOR SHARE (finding 1).
            AgentAuthority.Standing standing = authority.requireSession(ref);
            AgentAuthority.StoredLease lease = authority.requireLease(standing);
            List<Row> rows = jdbc.query(ROW + " WHERE c.tenant_id = :tenant AND c.agent_id = :agent"
                    + " AND c.state IN ('QUEUED', 'OFFERED', 'DELIVERED') AND c.deadline > :now"
                    + " AND (c.offered_epoch IS NULL OR c.offered_epoch <> :epoch OR c.notified_at IS NULL"
                    + " OR c.notified_at < :before) ORDER BY c.wire_generation ASC LIMIT :limit"
                    + " FOR UPDATE OF c SKIP LOCKED", new MapSqlParameterSource().addValue("tenant", standing.tenantId())
                    .addValue("agent", ref.agentId()).addValue("epoch", ref.fencingEpoch())
                    .addValue("now", AgentSql.at(now)).addValue("before", AgentSql.at(now.minus(renotifyAfter)))
                    .addValue("limit", Math.min(100, bounded * 4)), (rs, n) -> row(rs, standing.tenantId()));
            List<CommandNotice> notices = new ArrayList<>();
            for (Row row : rows) {
                // A command the lease cannot AUTHORIZE (a capability it lacks, a placement it no longer names) is not skipped:
                // it is REFUSED here, before any notice, as a DP authorization outcome (design 4.6, T2.1b fix-round note (a)).
                // Skipping it left the apply waiting on a command nothing would ever deliver or end. The deadline/ceiling half
                // of admission is only "not yet", so that one stays a skip.
                GatewayCode refusal = authorizationRefusal(standing, lease, row);
                if (refusal != null) {
                    refuse(standing.tenantId(), row, refusal, now);
                    continue;
                }
                if (notices.size() >= bounded || !admitted(standing, lease, row, now)) {
                    continue;
                }
                // A command this session already holds (DELIVERED under the same epoch) stays DELIVERED: a repeat notice is a
                // reminder, not a new offer.
                jdbc.update("UPDATE sm_data.agent_command SET state = CASE WHEN state = 'DELIVERED' AND offered_epoch = :epoch"
                        + " THEN state ELSE 'OFFERED' END, offered_epoch = :epoch, notified_at = :now"
                        + " WHERE tenant_id = :tenant AND command_id = :command AND agent_id = :agent",
                        new MapSqlParameterSource().addValue("epoch", ref.fencingEpoch()).addValue("now", AgentSql.at(now))
                                .addValue("tenant", standing.tenantId()).addValue("command", row.id().value())
                                .addValue("agent", ref.agentId()));
                notices.add(new CommandNotice(row.id(), row.placementId(), row.generation(), row.operation(),
                        payloads.plainDigestFor(row.command()).orElse(null), row.deadline(), row.correlation()));
            }
            return notices;
        });
    }

    @Override
    public Set<UUID> agentsWithDeliverableWork(List<AgentSessionRef> sessions, Duration renotifyAfter) {
        if (sessions.isEmpty()) {
            return Set.of();
        }
        Instant now = clock.instant();
        // A cheap, set-based over-approximation of pendingFor's own predicate (it ignores lease admission, which pendingFor
        // applies in full). The pairs (agent, epoch) are joined to the session table, which carries the tenant: an agent whose
        // epoch is not the current open one is not named, and a command is matched on the SAME (tenant, agent) as that session.
        return new LinkedHashSet<>(jdbc.queryForList("SELECT DISTINCT c.agent_id FROM sm_data.agent_command c"
                + " JOIN unnest(CAST(:agents AS uuid[]), CAST(:epochs AS bigint[])) AS q(agent_id, epoch)"
                + " ON q.agent_id = c.agent_id"
                + " JOIN sm_data.agent_session ss ON ss.tenant_id = c.tenant_id AND ss.agent_id = q.agent_id"
                + " AND ss.fencing_epoch = q.epoch AND ss.closed_at IS NULL"
                + " WHERE c.state IN ('QUEUED', 'OFFERED', 'DELIVERED') AND c.deadline > :now"
                + " AND (c.offered_epoch IS NULL OR c.offered_epoch <> q.epoch OR c.notified_at IS NULL"
                + " OR c.notified_at < :before)", new MapSqlParameterSource()
                .addValue("agents", AgentSql.uuidArray(sessions.stream().map(AgentSessionRef::agentId).toList()))
                .addValue("epochs", "{" + String.join(",", sessions.stream().map(x -> Long.toString(x.fencingEpoch())).toList()) + "}")
                .addValue("now", AgentSql.at(now)).addValue("before", AgentSql.at(now.minus(renotifyAfter))), UUID.class));
    }

    @Override
    public CommandOffer pull(AgentSessionRef ref, CommandId id) {
        AgentAuthority.Standing standing = authority.requireSession(ref);
        AgentAuthority.StoredLease lease = authority.requireLease(standing);
        Row row = jdbc.query(ROW + " WHERE c.tenant_id = :tenant AND c.agent_id = :agent AND c.command_id = :command",
                new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("agent", ref.agentId())
                        .addValue("command", id.value()), (rs, n) -> row(rs, standing.tenantId())).stream().findFirst()
                .orElseThrow(() -> AgentSql.refused(GatewayCode.UNKNOWN_COMMAND, "command is not deliverable"));
        if (row.state().equals("SUPERSEDED")) {
            throw AgentSql.refused(GatewayCode.SUPERSEDED, "command was superseded");
        }
        boolean deliverable = !row.state().equals("REFUSED") && !row.state().equals("ACKED")
                && (row.state().equals("DELIVERED") || (row.offeredEpoch() != null && row.offeredEpoch() == ref.fencingEpoch()));
        if (!deliverable) {
            throw AgentSql.refused(GatewayCode.UNKNOWN_COMMAND, "command is not deliverable");
        }
        if (!admitted(standing, lease, row, clock.instant())) {
            throw AgentSql.refused(GatewayCode.CAPABILITY_MISMATCH, "lease does not authorize command");
        }
        CommandPayload payload;
        try {
            payload = payloads.payloadFor(row.command());      // in memory; nothing below this line writes
        } catch (GatewayPortException unavailable) {
            if (unavailable.code() == GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED) {
                // The bytes the journal promised are gone (SM restarted) and a re-render differs: the command ends REFUSED with
                // that code, so the apply step fails with a classifier that is not retried against a payload that cannot return.
                // fenced like every other write (T2.1b finding 1): the standing check is the first statement of THIS transaction
                // and the UPDATE joins the session on the epoch
                tx.executeWithoutResult(status -> {
                    AgentAuthority.Standing fenced = authority.requireSession(ref);
                    AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command c"
                            + " SET state = 'REFUSED', outcome = 'REFUSED', detail_code = :code, acked_at = :now"
                            + " WHERE c.tenant_id = :tenant AND c.command_id = :command AND c.agent_id = :agent"
                            + " AND c.state IN ('OFFERED', 'DELIVERED')" + SESSION_IS_CURRENT, new MapSqlParameterSource()
                            .addValue("code", GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED.wire())
                            .addValue("now", AgentSql.at(clock.instant())).addValue("tenant", fenced.tenantId())
                            .addValue("command", id.value()).addValue("agent", ref.agentId())
                            .addValue("epoch", ref.fencingEpoch())));
                });
            }
            throw unavailable;
        }
        return new CommandOffer(row.id(), row.generation(), row.intentKey(), row.placementId(), row.operation(), null,
                row.deadline(), row.correlation(), payload);
    }

    @Override
    public void received(AgentSessionRef ref, String requestId, CommandId id, long wireGeneration) {
        if (requestId == null || requestId.isEmpty() || requestId.length() > 64) {
            throw AgentSql.refused(GatewayCode.LABEL_INVALID, "a request id is 1 to 64 characters");
        }
        tx.executeWithoutResult(status -> {
            AgentAuthority.Standing standing = authority.requireSession(ref);
            int updated = AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command c SET state = 'DELIVERED',"
                    + " received_at = :now, received_request_id = :request"
                    + " WHERE c.tenant_id = :tenant AND c.command_id = :command AND c.agent_id = :agent AND c.state = 'OFFERED'"
                    + " AND c.offered_epoch = :epoch AND c.wire_generation = :generation" + SESSION_IS_CURRENT,
                    new MapSqlParameterSource().addValue("now", AgentSql.at(clock.instant())).addValue("request", requestId)
                            .addValue("tenant", standing.tenantId()).addValue("command", id.value())
                            .addValue("agent", ref.agentId()).addValue("epoch", ref.fencingEpoch())
                            .addValue("generation", wireGeneration)));
            if (updated == 1) {
                return;
            }
            Row row = jdbc.query(ROW + " WHERE c.tenant_id = :tenant AND c.agent_id = :agent AND c.command_id = :command",
                    new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("agent", ref.agentId())
                            .addValue("command", id.value()), (rs, n) -> row(rs, standing.tenantId())).stream().findFirst()
                    .orElseThrow(() -> AgentSql.refused(GatewayCode.UNKNOWN_COMMAND, "unknown command"));
            if (row.state().equals("SUPERSEDED")) {
                throw AgentSql.refused(GatewayCode.SUPERSEDED, "command was superseded");
            }
            if (row.generation() != wireGeneration) {
                throw AgentSql.refused(GatewayCode.STALE_GENERATION, "generation does not match the command");
            }
            // DELIVERED / ACKED / REFUSED: a duplicate announcement is not an error. QUEUED or OFFERED under another epoch is.
            if (row.state().equals("QUEUED")
                    || (row.state().equals("OFFERED") && !Long.valueOf(ref.fencingEpoch()).equals(row.offeredEpoch()))) {
                throw AgentSql.refused(GatewayCode.UNKNOWN_COMMAND, "command was not offered to this session");
            }
        });
    }

    /** The write also joins the session table: even without the row lock, a superseded epoch updates nothing (finding 1). */
    private static final String SESSION_IS_CURRENT = " AND EXISTS (SELECT 1 FROM sm_data.agent_session s"
            + " WHERE s.tenant_id = c.tenant_id AND s.agent_id = c.agent_id AND s.fencing_epoch = :epoch AND s.closed_at IS NULL)";

    @Override
    public CommandAckResult ack(AgentSessionRef ref, CommandId id, AgentAck ack) {
        String terminal = ack.outcome() == AgentOutcome.REFUSED ? "REFUSED" : "ACKED";
        String requestId = ack.requestId().length() > 64 ? ack.requestId().substring(0, 64) : ack.requestId();
        String detail = ack.detailCode().isEmpty() ? null
                : DETAIL_CODE.matcher(ack.detailCode()).matches() ? ack.detailCode() : "invalid.detail";
        CommandAckResult result = tx.execute(status -> {
            AgentAuthority.Standing standing = authority.requireSession(ref);
            int updated = AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command c SET state = :state,"
                    + " outcome = :outcome, detail_code = :detail, ack_request_id = :request, acked_at = :now"
                    + " WHERE c.tenant_id = :tenant AND c.command_id = :command AND c.agent_id = :agent"
                    + " AND c.wire_generation = :generation AND c.state IN ('OFFERED', 'DELIVERED')" + SESSION_IS_CURRENT,
                    new MapSqlParameterSource().addValue("state", terminal).addValue("outcome", ack.outcome().name())
                            .addValue("detail", detail).addValue("request", requestId)
                            .addValue("now", AgentSql.at(clock.instant())).addValue("tenant", standing.tenantId())
                            .addValue("command", id.value()).addValue("agent", ref.agentId())
                            .addValue("epoch", ref.fencingEpoch()).addValue("generation", ack.generation())));
            if (updated == 1) {
                if (ack.outcome() == AgentOutcome.APPLIED && ack.containerStartedAt() != null) {
                    recordAckedStart(standing.tenantId(), id, ack.containerStartedAt());
                }
                return CommandAckResult.COMMITTED;
            }
            Optional<String> state = jdbc.query("SELECT state FROM sm_data.agent_command WHERE tenant_id = :tenant"
                    + " AND command_id = :command AND agent_id = :agent AND wire_generation = :generation",
                    new MapSqlParameterSource().addValue("tenant", standing.tenantId()).addValue("command", id.value())
                            .addValue("agent", ref.agentId()).addValue("generation", ack.generation()),
                    (rs, n) -> rs.getString(1)).stream().findFirst();
            if (state.isPresent() && state.get().equals("SUPERSEDED")) {
                recordLateAck(standing.tenantId(), ref, id, ack, requestId);
                return null;                // answered SUPERSEDED below, AFTER the late ack is committed
            }
            return state.map(x -> x.equals("ACKED") || x.equals("REFUSED") ? CommandAckResult.DUPLICATE
                    : CommandAckResult.UNKNOWN_COMMAND).orElse(CommandAckResult.UNKNOWN_COMMAND);
        });
        if (result == null) {
            throw AgentSql.refused(GatewayCode.SUPERSEDED, "command was superseded");
        }
        return result;
    }

    /**
     * How far past the data plane's clock an acknowledged container start may lie: the same bound, and the same reasoning, as the
     * report intake's ({@link JdbcAgentReportRepository#START_SKEW}). A start beyond it is {@code CLOCK_SUSPECT}.
     */
    private static final Duration START_SKEW = JdbcAgentReportRepository.START_SKEW;

    /**
     * {@code AGW-D28} item 2: the host-clock container start an agent acknowledged an APPLIED APPLY, REMATERIALIZE or RESTART
     * with is recorded in THIS transaction (the acknowledgement's) as (a) for a write, the attempt's {@code write_baseline}
     * (first writer wins; V020 allows it only on a MATERIALIZE attempt), and (b) the placement's start observation, forward only,
     * after which the instance's ladder is settled. The ack's value is the baseline everywhere; a report only moves the observation
     * by the same rule. Fail closed: an ack without a start records neither (this method is not called), and a start beyond
     * {@link #START_SKEW} of the data plane's clock ({@code CLOCK_SUSPECT}) records neither either: the command itself is still
     * acknowledged, as its outcome is real, but nothing is learned from a start that cannot be believed. Process metadata only (I6).
     */
    private void recordAckedStart(UUID tenantId, CommandId id, Instant startedAt) {
        if (startedAt.isAfter(clock.instant().plus(START_SKEW))) {
            log.warn("agent acknowledgement carried a container start beyond the clock bound; no baseline recorded (CLOCK_SUSPECT)");
            return;
        }
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("tenant", tenantId).addValue("command", id.value());
        jdbc.query("SELECT c.attempt_id, c.placement_id, c.operation, p.instance_id FROM sm_data.agent_command c"
                + " JOIN sm_data.placement p ON p.tenant_id = c.tenant_id AND p.placement_id = c.placement_id"
                + " WHERE c.tenant_id = :tenant AND c.command_id = :command", key, rs -> {
            UUID attempt = rs.getObject("attempt_id", UUID.class);
            UUID placement = rs.getObject("placement_id", UUID.class);
            UUID instance = rs.getObject("instance_id", UUID.class);
            String operation = rs.getString("operation");
            // the same lock every landing and every start observation takes, before either row is written
            jdbc.queryForList("SELECT 1 FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                    new MapSqlParameterSource().addValue("t", tenantId).addValue("i", instance), Integer.class);
            if (attempt != null && (operation.equals("APPLY") || operation.equals("REMATERIALIZE"))) {
                AgentSql.write(() -> jdbc.update("UPDATE sm_data.apply_attempt SET write_baseline ="
                        + " coalesce(write_baseline, CAST(:baseline AS jsonb)) WHERE tenant_id = :tenant AND attempt_id = :attempt"
                        + " AND step = 'MATERIALIZE'", new MapSqlParameterSource().addValue("tenant", tenantId)
                        .addValue("attempt", attempt).addValue("baseline",
                                "{\"startedAt\":\"" + startedAt + "\",\"processRef\":\"" + placement + "\"}")));
            }
            int moved = AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.placement_start_observation"
                    + " (tenant_id, placement_id, started_at, process_ref, observed_at)"
                    + " VALUES (:t, :p, :started, :ref, :observed) ON CONFLICT (tenant_id, placement_id) DO UPDATE"
                    + " SET started_at = EXCLUDED.started_at, process_ref = EXCLUDED.process_ref,"
                    + "     observed_at = EXCLUDED.observed_at"
                    + " WHERE EXCLUDED.started_at > sm_data.placement_start_observation.started_at",
                    new MapSqlParameterSource().addValue("t", tenantId).addValue("p", placement)
                            .addValue("started", AgentSql.at(startedAt)).addValue("ref", "ack:" + id.value())
                            .addValue("observed", AgentSql.at(clock.instant()))));
            if (moved > 0) {
                settler.settle(tenantId, instance);
            }
        });
    }

    /**
     * T2.3 integrator note, decided in T3.1 (RECONCILE, not HOLD): an agent that resumes a RECEIVED command from its spool runs
     * it before the data plane can tell it SUPERSEDED, and its ack then arrives for a command the DP has already superseded.
     * The ack is still answered {@code SUPERSEDED} (the agent closes its journal record on that), but the fact that the operation
     * RAN is recorded on the row: {@code detail_code = late.<outcome>} and the ack's request id. Nothing else changes (the state
     * stays {@code SUPERSEDED}, as the outcome CHECK requires), and the newer command is delivered after it by the agent's own
     * serial journal, so the newest generation wins. A refusal or supersession ack says nothing ran and is not recorded.
     */
    private void recordLateAck(UUID tenant, AgentSessionRef ref, CommandId id, AgentAck ack, String requestId) {
        if (ack.outcome() == AgentOutcome.SUPERSEDED || ack.outcome() == AgentOutcome.REFUSED
                || ack.outcome() == AgentOutcome.PREFLIGHT_REFUSED) {
            return;
        }
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command SET detail_code = :late, ack_request_id = :request"
                + " WHERE tenant_id = :tenant AND command_id = :command AND agent_id = :agent AND wire_generation = :generation"
                + " AND state = 'SUPERSEDED' AND ack_request_id IS NULL", new MapSqlParameterSource()
                .addValue("late", "late." + ack.outcome().name().toLowerCase(java.util.Locale.ROOT))
                .addValue("request", requestId).addValue("tenant", tenant).addValue("command", id.value())
                .addValue("agent", ref.agentId()).addValue("generation", ack.generation())));
    }

    /**
     * The command's outcome so far, bound to the placement's organization AND the placement AND the command id: a caller that
     * names another organization's command, or the right command under the wrong placement, gets empty. Null outcome means the
     * command has not finished.
     */
    @Override
    public Optional<CommandCompletion> completion(AgentPlacementRef placement, CommandId id) {
        return jdbc.query("SELECT command_id, wire_generation, operation, state, outcome, detail_code, acked_at"
                        + " FROM sm_data.agent_command WHERE tenant_id = :tenant AND placement_id = :placement"
                        + " AND command_id = :command",
                new MapSqlParameterSource().addValue("tenant", placement.tenantId())
                        .addValue("placement", placement.placementId()).addValue("command", id.value()), (rs, n) -> {
                    String state = rs.getString("state");
                    String stored = rs.getString("outcome");
                    AgentOutcome outcome = state.equals("SUPERSEDED") ? AgentOutcome.SUPERSEDED
                            : stored == null ? null : AgentOutcome.valueOf(stored);
                    return new CommandCompletion(new CommandId(rs.getObject("command_id", UUID.class)),
                            rs.getLong("wire_generation"), AgentOperation.valueOf(rs.getString("operation")), outcome,
                            rs.getString("detail_code"), null, outcome == null ? null : AgentSql.instant(rs, "acked_at"));
                }).stream().findFirst();
    }

    private static AgentCapability required(AgentOperation operation) {
        return switch (operation) {
            case PREFLIGHT, APPLY, REMATERIALIZE -> AgentCapability.APPLY_CONFIG;
            case RESTART, STOP -> AgentCapability.LIFECYCLE;
        };
    }

    /** What the lease cannot authorize, or {@code null}: capability first, then scope (the placement is in the lease). */
    private static GatewayCode authorizationRefusal(AgentAuthority.Standing standing, AgentAuthority.StoredLease lease, Row row) {
        AgentCapability required = required(row.operation());
        if (!lease.capabilities().contains(required) || !standing.capabilities().contains(required)) {
            return GatewayCode.CAPABILITY_MISSING;
        }
        if (!lease.placementIds().contains(row.placementId())) {
            return GatewayCode.BINDING_NOT_IN_LEASE;
        }
        return null;
    }

    /** Ends a QUEUED/OFFERED command {@code REFUSED} with the authorization code; a command the agent already holds is left alone. */
    private void refuse(UUID tenant, Row row, GatewayCode code, Instant now) {
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_command SET state = 'REFUSED', outcome = 'REFUSED',"
                + " detail_code = :code, acked_at = :now WHERE tenant_id = :tenant AND command_id = :command"
                + " AND state IN ('QUEUED', 'OFFERED')", new MapSqlParameterSource().addValue("code", code.wire())
                .addValue("now", AgentSql.at(now)).addValue("tenant", tenant).addValue("command", row.id().value())));
    }

    private boolean admitted(AgentAuthority.Standing standing, AgentAuthority.StoredLease lease, Row row, Instant now) {
        if (authorizationRefusal(standing, lease, row) != null) {
            return false;
        }
        Duration ceiling = lease.verbCeilings().getOrDefault(row.operation(), Duration.ZERO);
        return !Duration.between(now, row.deadline()).minus(lease.admissionMargin()).minus(ceiling).isNegative();
    }
}
