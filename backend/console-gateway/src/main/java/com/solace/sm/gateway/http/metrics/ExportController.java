package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.audit.ExportAudit;
import com.solace.sm.cp.api.audit.ExportAudit.ExportResult;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.ExportTooLargeException;
import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.CorrelationIdFilter;
import com.solace.sm.gateway.http.ErrorCode;
import com.solace.sm.gateway.http.GatewayRejectionException;
import com.solace.sm.gateway.http.RequestOriginFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * {@code exportRange} (row 60): one metric's raw samples over a range, streamed as CSV (M-Q18, M-Q22 {@code OR-79}).
 *
 * <p>⚠️ <b>Wired and tested; not counted served</b> until P11-8.1 records the 105 M-row stream (M-Q18).
 *
 * <h2>Before any byte</h2>
 * Everything that can be refused is refused while the request thread still owns the response: the rate gate, the reach
 * filter, the data plane's {@code plan} (the {@code 422 EXPORT_TOO_LARGE} with {@code {projectedRows, limit}}, and the
 * shared database budget's {@code 503}). The concurrency caps (one per organization, two overall) are taken by
 * {@code stream} itself, on the streaming thread, before it writes; an {@link ExportBusyException} thrown there reaches
 * {@link MetricsErrorAdvice} on the async dispatch while the response is still uncommitted, so it is a clean {@code 429}
 * (measured by {@code ExportEndpointIT}).
 *
 * <h2>The stream, the audit and the clock</h2>
 * The body runs as Spring MVC async processing. Its bound is the MVC async timeout, set EXPLICITLY from
 * {@code sm.gateway.metrics.export-async-timeout} (default 30 minutes, M-Q22 (2)) and never left to the container's (MF18);
 * the stream ALSO carries its own deadline, so a stream the container cannot interrupt (a database that stopped
 * returning rows) still ends at the bound. Each export is audited ONCE, with how it ended: {@code completed},
 * {@code failed} (the data plane failed part-way) or {@code cut} (the deadline, or the client went away). An export
 * refused ({@code 403}, {@code 422}, {@code 429}) is not an export and is not audited HERE: it is one {@code DENY} row of the
 * same verb, written by {@link ExportRefusalAuditInterceptor} from the status the client is about to see (OR-112 (6),
 * {@code W4-04}), so that a refusal this controller never sees (the {@code 403} that precedes its handler) is audited the
 * same way as the ones it throws.
 *
 * <p>The authorization decision, the rate count and the audit row are each taken once, on the initial dispatch: the
 * async re-dispatch runs neither the handler nor, see {@code AuthenticationInterceptor}, the interceptor's checks.
 */
@RestController
@RequestMapping("/api/console/v1")
public class ExportController {

    private static final Logger LOG = LoggerFactory.getLogger(ExportController.class);

    private final MonitoringInstances instances;
    private final MetricsExportService export;
    private final ExportAudit audit;
    private final MetricsRateGate gate;
    private final RequestOriginFactory originFactory;
    private final MetricsGatewayProperties properties;
    private final LongSupplier nanos;

    @Autowired
    public ExportController(MonitoringInstances instances, MetricsExportService export, ExportAudit audit,
            MetricsRateGate gate, RequestOriginFactory originFactory, MetricsGatewayProperties properties) {
        this(instances, export, audit, gate, originFactory, properties, System::nanoTime);
    }

    /** @param nanos the clock the stream's deadline is read from (a test's, never wall-clock time) */
    public ExportController(MonitoringInstances instances, MetricsExportService export, ExportAudit audit,
            MetricsRateGate gate, RequestOriginFactory originFactory, MetricsGatewayProperties properties,
            LongSupplier nanos) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.export = Objects.requireNonNull(export, "export");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.originFactory = Objects.requireNonNull(originFactory, "originFactory");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    @GetMapping("/export/range")
    @ConsoleOperation("exportRange")
    public ResponseEntity<StreamingResponseBody> exportRange(@RequestParam("metric") String metric,
            @RequestParam("from") String from, @RequestParam("to") String to,
            @RequestParam(value = "instanceId", required = false) List<UUID> instanceIds, Session session,
            HttpServletRequest request, HttpServletResponse response) {
        gate.check(session.id());
        if (!MetricQueryRequest.METRIC_NAME.matcher(metric).matches()) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        UUID organization = session.organizationId();
        List<UUID> ids = reachableIds(session, instanceIds);
        UUID correlationId = correlationId(request);
        ExportCommand command;
        try {
            command = new ExportCommand(metric, ids, instant(from), instant(to), correlationId);
        } catch (IllegalArgumentException malformed) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        // Before any byte: 422 EXPORT_TOO_LARGE, or 503 when the shared budget stays spent.
        export.plan(organization, command);

        // The origin reads the current request, which the streaming thread does not have: take it here.
        RequestOrigin origin = originFactory.create();
        PrincipalRef actor = session.principal();
        TenantId tenant = new TenantId(organization);
        long deadline = nanos.getAsLong() + properties.exportAsyncTimeout().toNanos();
        StreamingResponseBody body = out -> {
            CountingOutput counted = new CountingOutput(out, response, deadline, nanos);
            ExportResult result;
            long rows;
            try {
                rows = export.stream(organization, command, counted);
                result = ExportResult.COMPLETED;
            } catch (ExportBusyException | ExportTooLargeException refused) {
                if (counted.started()) {
                    record(actor, origin, tenant, correlationId, counted.rows(), ExportResult.FAILED);
                }
                throw refused;   // before the first byte: a status, never an audited export
            } catch (IOException cut) {
                // The deadline, the container's async timeout, or the client going away: how it ended is "cut".
                record(actor, origin, tenant, correlationId, counted.rows(), ExportResult.CUT);
                throw cut;
            } catch (RuntimeException failed) {
                record(actor, origin, tenant, correlationId, counted.rows(), ExportResult.FAILED);
                throw failed;
            }
            record(actor, origin, tenant, correlationId, rows, result);
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
    }

    /**
     * {@code instanceId} absent selects every instance the caller can reach for export; present, the requested ids are
     * FILTERED by {@code metrics.export} at each instance's own scope, silently (M-Q17). Nothing reachable is a
     * header-only export, exactly what ids that do not exist produce.
     */
    private List<UUID> reachableIds(Session session, List<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return instances.reachableActive(session, Permission.METRICS_EXPORT).stream()
                    .map(InstanceView::instanceId).toList();
        }
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(requested));
        Set<UUID> allowed = instances.reachable(session, Permission.METRICS_EXPORT, distinct);
        List<UUID> out = new ArrayList<>();
        for (UUID id : distinct) {
            if (allowed.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private void record(PrincipalRef actor, RequestOrigin origin, TenantId tenant, UUID correlationId, long rows,
            ExportResult result) {
        try {
            audit.record(actor, origin, tenant, correlationId, rows, result);
        } catch (RuntimeException failed) {
            // The audit failing must not replace how the export ended; the class alone is logged (a driver message can
            // carry a host, a role or a path).
            LOG.error("the export audit row could not be written ({}), correlationId={}", failed.getClass().getSimpleName(),
                    correlationId);
        }
    }

    private static java.time.Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not an ISO-8601 date-time");
        }
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }

    /**
     * What the data plane writes into, counted and bounded. Counts newline bytes (the header is the first line, and a
     * value carries no newline), so {@link #rows()} is the data rows handed to the response whatever the end. The
     * {@code Content-Disposition} is set at the FIRST write, not on the response entity: a refusal before the first byte
     * is then an ordinary problem response with no download headers on it.
     */
    private static final class CountingOutput extends OutputStream {
        private final OutputStream delegate;
        private final HttpServletResponse response;
        private final long deadline;
        private final LongSupplier nanos;
        private long lines;
        private boolean started;

        CountingOutput(OutputStream delegate, HttpServletResponse response, long deadline, LongSupplier nanos) {
            this.delegate = delegate;
            this.response = response;
            this.deadline = deadline;
            this.nanos = nanos;
        }

        boolean started() {
            return started;
        }

        long rows() {
            return Math.max(0, lines - 1);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            if (nanos.getAsLong() - deadline > 0) {
                throw new IOException("the export reached its time limit");
            }
            if (!started) {
                started = true;
                if (!response.isCommitted()) {
                    response.setHeader("Content-Disposition", "attachment; filename=\"metrics-export.csv\"");
                }
            }
            delegate.write(bytes, off, len);
            for (int i = off; i < off + len; i++) {
                if (bytes[i] == '\n') {
                    lines++;
                }
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() {
            // The caller owns the stream; the data plane never closes it, and neither does this wrapper.
        }
    }
}
