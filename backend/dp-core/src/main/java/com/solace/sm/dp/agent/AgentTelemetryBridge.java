package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.AgentTelemetryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentTelemetryWatermarkRepository;
import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AgentTelemetryPort} bean (plan 015 T3.4, {@code AGW-D27}): an agent's batch is re-sunk through the BUILT
 * {@link TelemetryIngestPort} with {@link SourceDiscriminator#AGENT}, and acknowledged on commit only.
 *
 * <p><b>Identity ({@code I5}).</b> The organization, agent and placement come from the session and its ACTIVE lease
 * ({@link JdbcAgentTelemetryWatermarkRepository#admit}); the stream id is compared with the lease's placements and a label is
 * validated against a closed list, but neither ever chooses a tenant, an instance or a placement. The sample type the agent sends is
 * ignored: {@link IngestSample} has no type, so the data plane's own per-version definition decides it.
 *
 * <p><b>Acknowledgement.</b> The stream watermark rises only after the ingest port reports {@code Committed} (or the batch is
 * permanently refused and counted), in a transaction of its own that re-checks the session; before that, a failure leaves the
 * watermark where it was and the batch is redelivered, which the ingest port absorbs as an exact duplicate (D-2).
 * {@code RetryAfter} and {@code Pending} are {@code DO_NOT_ACK}: the result carries the UNMOVED watermark, so even a transport that
 * only echoes {@code highWatermark} leaves the batch in the agent's disk buffer.
 *
 * <p><b>Residual (fencing).</b> Admission and the watermark are fenced on the session; the sample write itself is not: a revocation
 * or lease change landing after {@link JdbcAgentTelemetryWatermarkRepository#admit} does not stop a commit already handed to the ingest
 * port, nor a batch it is holding in its bounded buffer. The data carries no authority and the window is bounded by that buffer.
 *
 * <p><b>Clock window ({@code OR-134}).</b> The ±5 minute window runs on the agent's measured clock offset, not on a sample's age:
 * while {@code |offset|} exceeds it every sample of the batch is refused and counted {@code clock_skew}; otherwise a sample is kept
 * whatever its age, so the 24-hour disk buffer keeps its value. Timestamps are never rewritten. An offset that was never measured
 * is not a skew. The ingest port still drops a sample far in the future ({@code parse_failed}).
 *
 * <p><b>Cadence.</b> {@code OR-73}'s fixed 5 s collection and 1 minute push belong to the agent and to the declarations; nothing
 * here reads a package {@code intervalSeconds}.
 */
public final class AgentTelemetryBridge implements AgentTelemetryPort {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTelemetryBridge.class);

    /** OR-130 F-6: the window on the agent's measured clock offset. */
    public static final Duration CLOCK_WINDOW = Duration.ofMinutes(5);

    /** The closed label list of AGW-D9 / AGW-D27: every value is a DP-minted id, and none is an agent-chosen string. */
    private static final Set<String> LABELS = Set.of("binding_id", "replica_id", "incarnation_id");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int CREDITS = 1;

    private final JdbcAgentTelemetryWatermarkRepository watermarks;
    private final TelemetryIngestPort ingest;
    private final IngestRows.Counters counters;
    private final AgentTelemetryCounts counts;
    private final Clock clock;
    private final Duration window;

    public AgentTelemetryBridge(JdbcAgentTelemetryWatermarkRepository watermarks, TelemetryIngestPort ingest,
            IngestRows.Counters counters, AgentTelemetryCounts counts, Clock clock) {
        this(watermarks, ingest, counters, counts, clock, CLOCK_WINDOW);
    }

    AgentTelemetryBridge(JdbcAgentTelemetryWatermarkRepository watermarks, TelemetryIngestPort ingest,
            IngestRows.Counters counters, AgentTelemetryCounts counts, Clock clock, Duration window) {
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.counters = Objects.requireNonNull(counters, "counters");
        this.counts = Objects.requireNonNull(counts, "counts");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.window = Objects.requireNonNull(window, "window");
    }

    @Override
    public AgentTelemetryResult ingest(AgentSessionRef ref, AgentTelemetryBatch batch, AgentRequestOrigin origin) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(origin, "origin");
        JdbcAgentTelemetryWatermarkRepository.Scope scope = watermarks.admit(ref, batch.streamId());

        if (scope.watermark().isPresent() && batch.batchSeq() <= scope.watermark().getAsLong()) {
            long mark = scope.watermark().getAsLong();
            if (batch.batchSeq() == mark) {
                counts.add(AgentTelemetryCounts.DUPLICATE_BATCH, 1);       // the one legitimate redelivery: the batch in flight
            } else {
                // The agent sends one batch per stream in order, so a sequence BELOW the watermark is not a redelivery: the agent's
                // sequence was reset (its stream-state file lost) or it is a bug. Acknowledged as before (the rule is the owner's,
                // plan 015 section 10), but never silently: a distinct count and a WARN that names only the placement.
                counts.add(AgentTelemetryCounts.SEQUENCE_BEHIND_WATERMARK, 1);
                LOG.warn("agent telemetry batch sequence is behind the stream watermark (placement {}): acknowledged unforwarded",
                        scope.placementId());
            }
            return AgentTelemetryResult.acked(mark, CREDITS);
        }
        validateLabels(batch, scope.placementId());
        counts.add(AgentTelemetryCounts.GAPS_NOT_FORWARDED, batch.gaps().size());

        if (scope.clockOffsetMs() != null && Math.abs(scope.clockOffsetMs()) > window.toMillis() && !batch.samples().isEmpty()) {
            return refuseSkewed(ref, batch, scope);
        }

        Instant receivedAt = clock.instant();
        List<IngestSample> samples = new ArrayList<>(batch.samples().size());
        int malformed = 0;
        for (AgentTelemetryBatch.Sample sample : batch.samples()) {
            if (sample.seriesKey() == null || sample.seriesKey().isBlank() || sample.timestamp() == null) {
                malformed++;
            } else {
                samples.add(new IngestSample(sample.seriesKey(), sample.timestamp(), sample.value(), receivedAt));
            }
        }
        if (samples.isEmpty()) {
            return acknowledged(ref, batch, scope, malformed);   // nothing to commit: every sample is dropped and counted
        }

        IngestResult result = ingest.ingest(new IngestBatch(scope.tenantId(), scope.placementId(), scope.placementId(),
                scope.placementId().toString(), SourceDiscriminator.AGENT, samples, List.of()));
        // IngestResult is sealed to four kinds and every one is handled; a fifth added later fails the last branch loudly
        if (result instanceof IngestResult.Committed) {
            return acknowledged(ref, batch, scope, malformed);
        }
        if (result instanceof IngestResult.RetryAfter retry) {
            return notAcked(scope, Duration.ofMillis(retry.millis()));
        }
        if (result instanceof IngestResult.Pending) {
            counts.add(AgentTelemetryCounts.PENDING_UNEXPECTED, 1);
            return notAcked(scope, null);
        }
        if (result instanceof IngestResult.Rejected rejected) {
            return rejected(ref, batch, scope, rejected, malformed);
        }
        throw new IllegalStateException("an ingest result this bridge does not know");
    }

    // ---- outcomes ---------------------------------------------------------------------------------------------------------

    /**
     * Acknowledges, then writes the bridge's own counts: only a FINAL outcome counts, so a batch that is held and resent
     * (RetryAfter, Pending, buffer.dropped) or whose acknowledge is refused is not counted twice. The ingest port's own drop counts
     * are at-least-once across the crash window between its commit and the acknowledge.
     */
    private AgentTelemetryResult acknowledged(AgentSessionRef ref, AgentTelemetryBatch batch,
            JdbcAgentTelemetryWatermarkRepository.Scope scope, int malformed) {
        long watermark = watermarks.acknowledge(ref, batch.streamId(), batch.batchSeq());
        if (malformed > 0) {
            counts.add(AgentTelemetryCounts.MALFORMED_SAMPLES, malformed);
            count(scope, CollectorReason.PARSE_FAILED, malformed);
        }
        return AgentTelemetryResult.acked(watermark, CREDITS);
    }

    /** Held or unwritable for now: the watermark is NOT moved, and no credit comes back until the agent sends it again. */
    private AgentTelemetryResult notAcked(JdbcAgentTelemetryWatermarkRepository.Scope scope, Duration retryAfter) {
        counts.add(AgentTelemetryCounts.NOT_ACKED, 1);
        return new AgentTelemetryResult(AgentTelemetryResult.Disposition.DO_NOT_ACK, scope.watermark().orElse(0), 0,
                retryAfter, null);
    }

    private AgentTelemetryResult rejected(AgentSessionRef ref, AgentTelemetryBatch batch,
            JdbcAgentTelemetryWatermarkRepository.Scope scope, IngestResult.Rejected rejected, int malformed) {
        if ("buffer.dropped".equals(rejected.code())) {
            return notAcked(scope, null);               // dropped from a bounded buffer to make room: it can be sent again
        }
        counts.add(AgentTelemetryCounts.REJECTED, 1);
        GatewayCode code = switch (rejected.code()) {
            case "placement.unknown" -> GatewayCode.BINDING_NOT_IN_LEASE;
            case "batch.too-large" -> GatewayCode.PAYLOAD_TOO_LARGE;
            default -> GatewayCode.UNKNOWN;
        };
        LOG.warn("agent telemetry batch refused for good by the ingest port: {}", rejected.code());
        long watermark = watermarks.acknowledge(ref, batch.streamId(), batch.batchSeq());
        if (malformed > 0) {
            counts.add(AgentTelemetryCounts.MALFORMED_SAMPLES, malformed);
            count(scope, CollectorReason.PARSE_FAILED, malformed);
        }
        return new AgentTelemetryResult(AgentTelemetryResult.Disposition.REJECTED_COUNTED, watermark, CREDITS, null, code);
    }

    private AgentTelemetryResult refuseSkewed(AgentSessionRef ref, AgentTelemetryBatch batch,
            JdbcAgentTelemetryWatermarkRepository.Scope scope) {
        int refused = batch.samples().size();
        long watermark = watermarks.acknowledge(ref, batch.streamId(), batch.batchSeq());
        counts.add(AgentTelemetryCounts.CLOCK_SKEW_SAMPLES, refused);
        count(scope, CollectorReason.CLOCK_SKEW, refused);
        return new AgentTelemetryResult(AgentTelemetryResult.Disposition.REJECTED_COUNTED, watermark, CREDITS, null,
                GatewayCode.CLOCK_SUSPECT);
    }

    /** A durable per-placement count (M-Q21); a failure to write it is logged by class only and never loses the batch's outcome. */
    private void count(JdbcAgentTelemetryWatermarkRepository.Scope scope, CollectorReason reason, long n) {
        try {
            counters.add(scope.tenantId(), scope.placementId(), reason, clock.instant().atZone(ZoneOffset.UTC).toLocalDate(), n);
        } catch (RuntimeException e) {
            LOG.warn("agent telemetry count {} could not be written ({})", reason.wire(), e.getClass().getSimpleName());
        }
    }

    // ---- labels -------------------------------------------------------------------------------------------------------------

    /**
     * The label contract (AGW-D25 item 3, design 6.1): every sample carries EXACTLY {@code binding_id}, {@code replica_id} and
     * {@code incarnation_id}, each a uuid; {@code binding_id} and {@code replica_id} are the stream's own placement id, which is what
     * the DP minted for the agent (the real agent sends all three, {@code runner.go}). Another key is {@code UNDECLARED_LABEL}, a
     * missing key or a non-uuid value {@code LABEL_INVALID}, a binding of another placement {@code BINDING_NOT_IN_LEASE}, another
     * replica {@code UNKNOWN_REPLICA}. A label is compared and then ignored: it never selects a tenant, an instance or a placement.
     */
    private static void validateLabels(AgentTelemetryBatch batch, UUID placement) {
        for (AgentTelemetryBatch.Sample sample : batch.samples()) {
            Map<String, String> labels = sample.labels();
            for (String key : labels.keySet()) {
                if (!LABELS.contains(key)) {
                    throw new GatewayPortException(GatewayCode.UNDECLARED_LABEL, "a sample carries a label outside the closed list");
                }
            }
            for (String key : LABELS) {
                String value = labels.get(key);
                if (value == null || !UUID_TEXT.matcher(value).matches()) {
                    throw new GatewayPortException(GatewayCode.LABEL_INVALID, "a sample label is missing or not an identifier");
                }
            }
            if (!placement.equals(UUID.fromString(labels.get("binding_id")))) {
                throw new GatewayPortException(GatewayCode.BINDING_NOT_IN_LEASE, "a sample names another binding than its stream");
            }
            if (!placement.equals(UUID.fromString(labels.get("replica_id")))) {
                throw new GatewayPortException(GatewayCode.UNKNOWN_REPLICA, "a sample names another replica than its stream");
            }
        }
    }
}
