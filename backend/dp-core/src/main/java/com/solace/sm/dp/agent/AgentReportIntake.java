package com.solace.sm.dp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository.Processed;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.health.HealthRecorder;
import com.solace.sm.dp.health.Observation;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.health.ProbeResultMapper;
import com.solace.sm.dp.health.StartObservationRows;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The agent path's report intake (plan 015 T3.3, {@code AGW-D28}): the {@link ActualStatePort} bean. It takes an agent's
 * actual-state report into the tables SM already has, in ONE transaction per batch:
 *
 * <ol>
 *   <li>{@link JdbcAgentReportRepository#process} classifies every item against the lease and advances the per-placement
 *       watermark (ordering, replay, scope, {@code replica_ref}, trust);</li>
 *   <li>a REFUSED item is recorded in {@code agent_event} ({@code agent.report.refused}, {@code AGW-D29});</li>
 *   <li>an accepted, TRUSTED, newly applied item has its effects applied: <b>health</b> into {@code placement_health} (persisted on
 *       change only, {@code OR-39}; the declared probe's facts mapped by the dp-remote {@link ProbeResultMapper}, so a gate means
 *       the same on both paths; a placement with no declared probe is {@code NOT_APPLICABLE} whatever the agent says,
 *       {@code OR-101}), and the container <b>start</b> into {@code placement_start_observation} FORWARD ONLY, then the config ladder
 *       is settled so a start later than the write baseline reaches {@code restart-observed} ({@code OR-112});</li>
 *   <li>the observation is remembered for {@link AgentObservedState} after commit.</li>
 * </ol>
 *
 * <p><b>An UNTRUSTED item</b> (the lease expired, or its epochs no longer match) advances the watermark and is answered, but applies
 * no effect: no health, no start observation, no ladder movement ("no convergence gate satisfied", design 8 row 5).
 *
 * <p><b>Nothing here stores a digest, a rendered byte or agent free text</b> ({@code I6}, {@code OR-32}): {@code effectiveHash} goes
 * to the in-memory observation only; the health mapping reads closed enums and an actuator status that the mapper reads for the
 * literal {@code UP} and discards. The tenant, instance and placement of every effect come from the lease and the placement row,
 * never from {@code bindingId} or a label ({@code I5}).
 */
public final class AgentReportIntake implements ActualStatePort {

    private static final Logger log = LoggerFactory.getLogger(AgentReportIntake.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String REFUSED_EVENT = "agent.report.refused";

    private final JdbcAgentReportRepository reports;
    private final AgentAuditPort audit;
    private final HealthRecorder healthRecorder;
    private final PlacementHealthRows health;
    private final StartObservationRows starts;
    private final LadderRecorder ladder;
    private final ApplyEventEmitter events;
    private final AgentObservedState observed;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AgentReportIntake(JdbcAgentReportRepository reports, AgentAuditPort audit, HealthRecorder healthRecorder,
            PlacementHealthRows health, StartObservationRows starts, LadderRecorder ladder, ApplyEventEmitter events,
            AgentObservedState observed, TransactionTemplate transactions, Clock clock) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.healthRecorder = Objects.requireNonNull(healthRecorder, "healthRecorder");
        this.health = Objects.requireNonNull(health, "health");
        this.starts = Objects.requireNonNull(starts, "starts");
        this.ladder = Objects.requireNonNull(ladder, "ladder");
        this.events = Objects.requireNonNull(events, "events");
        this.observed = Objects.requireNonNull(observed, "observed");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ReportResult> report(AgentSessionRef session, List<ReportItem> items, AgentRequestOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        return transactions.execute(status -> {
            List<Processed> processed = reports.process(session, items);
            for (Processed one : processed) {
                if (one.refused()) {
                    audit.record(requestId(one), session, origin, REFUSED_EVENT, one.result().refusedReason(), null, clock.instant());
                } else if (one.effective()) {
                    apply(one);
                }
            }
            return processed.stream().map(Processed::result).toList();
        });
    }

    /** {@code agent_event.request_id} is unique per organization and at most 128 characters: agent + item, bounded. */
    private static String requestId(Processed one) {
        String item = one.result().itemId();
        // an id outside the item grammar (printable, at most 64) is never echoed: it is hashed
        String bounded = item.matches("[A-Za-z0-9._:-]{1,64}") ? item : sha256Prefix(item);
        return "report:" + one.agentId() + ":" + bounded;
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 64);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private void apply(Processed one) {
        ReportItem item = one.item();
        Instant now = clock.instant();
        recordHealth(one, item.health());
        Instant started = item.containerStartedAt();
        String incarnation = item.incarnation();
        if (started != null && incarnation != null && !incarnation.isEmpty()) {
            TenantId tenant = new TenantId(one.tenantId());
            InstanceId instance = new InstanceId(one.instanceId());
            starts.lockInstance(one.tenantId(), one.instanceId());
            if (reports.recordStartForward(one.tenantId(), one.placementId(), started, incarnation, now)) {
                events.settleLadder(ladder, tenant, instance);
            }
        }
        // AgentObservation.health is null when no probe is declared (its dp-api contract)
        observed.remember(one.tenantId(), one.placementId(), item.sequence(),
                one.healthKind().isEmpty() ? null : item.health(), item.effectiveHash(),
                item.persistedGeneration() > 0 ? item.persistedGeneration() : null, started, now);
    }

    private void recordHealth(Processed one, AgentHealthObservation observation) {
        if (one.healthKind().isEmpty()) {
            // OR-101: no declared probe is NOT_APPLICABLE whatever the agent reports; written once.
            boolean recorded = health.find(one.tenantId(), one.placementId())
                    .map(row -> row.state() == HealthState.NOT_APPLICABLE).orElse(false);
            if (!recorded) {
                healthRecorder.record(one.tenantId(), one.instanceId(), one.placementId(), HealthState.NOT_APPLICABLE);
            }
            return;
        }
        Optional<HealthState> state = map(one.healthKind().get(), observation);
        state.ifPresent(s -> healthRecorder.record(one.tenantId(), one.instanceId(), one.placementId(), s));
    }

    /**
     * The declared probe's facts as the dp-remote {@link Observation} vocabulary, so {@link ProbeResultMapper} is the one mapping
     * (OR-130 D-4). Nothing is said when the agent ran no probe: a missing observation is silence, never a verdict. The Docker
     * health check ({@code state} HEALTHY / UNHEALTHY / STARTING) is a hint and is never read as the gate.
     */
    static Optional<HealthState> map(String kind, AgentHealthObservation facts) {
        if (facts == null) {
            return Optional.empty();
        }
        Observation observation;
        if (facts.state() == AgentHealthObservation.State.UNREACHABLE) {
            observation = Observation.UNREACHABLE;       // the container is not running, or its inspect failed
        } else if (facts.failure() == AgentHealthObservation.Failure.REFUSED) {
            observation = Observation.REFUSED;
        } else if (facts.failure() == AgentHealthObservation.Failure.TIMEOUT) {
            observation = Observation.SILENT;
        } else if (facts.httpStatus() > 0) {
            observation = new Observation.Answered(facts.httpStatus(), actuatorBody(facts.actuatorStatus()));
        } else {
            return Optional.empty();
        }
        try {
            return Optional.of(ProbeResultMapper.map(kind, observation));
        } catch (IllegalArgumentException unknownKind) {
            log.warn("agent report: a placement declares a probe kind this build cannot map");
            return Optional.empty();
        }
    }

    private static byte[] actuatorBody(String status) {
        if (status == null || status.isEmpty()) {
            return new byte[0];
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("status", status);
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }
}
