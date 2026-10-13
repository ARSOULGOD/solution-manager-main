package com.solace.sm.dp.metrics.export;

import com.solace.sm.dp.api.metrics.export.ExportBusyException;
import com.solace.sm.dp.api.metrics.export.ExportCommand;
import com.solace.sm.dp.api.metrics.export.ExportPlan;
import com.solace.sm.dp.api.metrics.export.ExportTooLargeException;
import com.solace.sm.dp.api.metrics.export.MetricsExportService;
import com.solace.sm.dp.metrics.MetricsBusyException;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Streaming metrics export (plan 011 P11-6.2, M-Q18 and M-Q22 {@code OR-79}). The bounds, in the order they are taken:
 * <ol>
 *   <li>the concurrency caps (1 per organization, 2 per install), non-blocking: a busy answer is immediate;</li>
 *   <li>a permit from the ONE {@link MetricsDbBudget} (injected, never rebuilt here), waiting its bound;</li>
 *   <li>the row limit: the projection before any byte, and the actual count while streaming.</li>
 * </ol>
 * All three are released on every path (normal end, refusal, failure, a vanished client).
 *
 * <p>The service holds no JDBC: the cursor, the transaction and the SQL are behind {@link ExportRows}.
 */
public final class MetricsExportServiceImpl implements MetricsExportService {

    /** The BufferedWriter chunk: how much CSV is held before it is handed to the response. Bounded, never the result. */
    private static final int BUFFER_CHARS = 64 * 1024;

    private final ExportRows rows;
    private final MetricsDbBudget budget;
    private final ExportProperties properties;

    private final Object slots = new Object();
    private final Map<UUID, Integer> perOrganization = new HashMap<>();
    private int running;

    public MetricsExportServiceImpl(ExportRows rows, MetricsDbBudget budget, ExportProperties properties) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public ExportPlan plan(UUID organizationId, ExportCommand command) {
        Objects.requireNonNull(organizationId, "organizationId");
        try (MetricsDbBudget.Permit permit = permit()) {
            return projected(organizationId, command);
        }
    }

    @Override
    public long stream(UUID organizationId, ExportCommand command, OutputStream out) throws IOException {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(out, "out");
        takeSlot(organizationId);
        try (MetricsDbBudget.Permit permit = permit()) {
            projected(organizationId, command);
            Writer csv = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), BUFFER_CHARS);
            CsvEncoder.header(csv);
            long[] written = {0};
            long n = rows.stream(organizationId, command.metric(), command.instanceIds(), command.from(), command.to(),
                    properties.statementTimeout(), properties.fetchSize(), (instance, placement, ts, value) -> {
                        if (++written[0] > properties.rowLimit()) {
                            // The projection is an upper bound for a declared interval, but it is not a promise about the
                            // rows: the limit is enforced on what is written, so no export ever exceeds it.
                            throw new ExportTooLargeException(written[0], properties.rowLimit());
                        }
                        CsvEncoder.row(csv, instance, placement, ts, value);
                    });
            csv.flush();
            return n;
        } finally {
            releaseSlot(organizationId);
        }
    }

    // ---- the budget ---------------------------------------------------------------------------------------------

    private MetricsDbBudget.Permit permit() {
        try {
            return budget.acquire(properties.budgetWait());
        } catch (MetricsBusyException e) {
            throw new ExportBusyException(ExportBusyException.Reason.DATABASE_BUDGET);
        }
    }

    // ---- the caps -----------------------------------------------------------------------------------------------

    private void takeSlot(UUID organization) {
        synchronized (slots) {
            if (perOrganization.getOrDefault(organization, 0) >= properties.perOrganization()) {
                throw new ExportBusyException(ExportBusyException.Reason.ORGANIZATION);
            }
            if (running >= properties.global()) {
                throw new ExportBusyException(ExportBusyException.Reason.INSTALL);
            }
            perOrganization.merge(organization, 1, Integer::sum);
            running++;
        }
    }

    private void releaseSlot(UUID organization) {
        synchronized (slots) {
            perOrganization.computeIfPresent(organization, (k, n) -> n <= 1 ? null : n - 1);
            running--;
        }
    }

    // ---- the projection -----------------------------------------------------------------------------------------

    /**
     * Reads the series' intervals and refuses past the limit. Runs under a permit the caller already holds.
     *
     * <p>A series with a declared interval is projected from it. A series with NONE for part of the range (the push path:
     * File Mesh heartbeats arrive every 5 s, but nothing declares that) cannot be, and a fixed fallback interval misjudges it
     * by a large factor in either direction (W3-12), so its rows are COUNTED instead, in the same permit and under the same
     * statement timeout, stopping once the limit is certain to be passed. The in-stream row limit stays as the backstop.
     */
    private ExportPlan projected(UUID organizationId, ExportCommand command) {
        List<ExportRows.SeriesSpan> spans = rows.spans(organizationId, command.metric(), command.instanceIds());
        List<ExportRows.SeriesSpan> declared = new ArrayList<>();
        List<Long> undeclared = new ArrayList<>();
        for (ExportRows.SeriesSpan span : spans) {
            if (hasUndeclaredInterval(span, command.from(), command.to())) {
                undeclared.add(span.seriesId());
            } else {
                declared.add(span);
            }
        }
        long projected = project(declared, command.from(), command.to(), properties.unknownIntervalSeconds());
        if (!undeclared.isEmpty() && projected <= properties.rowLimit()) {
            long room = properties.rowLimit() - projected;
            projected = add(projected, rows.countRaw(organizationId, undeclared, command.from(), command.to(),
                    room == Long.MAX_VALUE ? room : room + 1, properties.statementTimeout()));
        }
        if (projected > properties.rowLimit()) {
            throw new ExportTooLargeException(projected, properties.rowLimit());
        }
        return new ExportPlan(projected, properties.rowLimit());
    }

    /** True when some part of {@code [from, to)} has no interval declared for the series (no segment, or one with none). */
    private static boolean hasUndeclaredInterval(ExportRows.SeriesSpan span, Instant from, Instant to) {
        List<ExportRows.Segment> segments = span.segments();
        if (segments.isEmpty()) {
            return true;
        }
        for (int i = 0; i < segments.size(); i++) {
            ExportRows.Segment segment = segments.get(i);
            Instant end = i + 1 < segments.size() ? segments.get(i + 1).effectiveFrom() : to;
            Instant lo = segment.effectiveFrom().isAfter(from) ? segment.effectiveFrom() : from;
            Instant hi = end.isBefore(to) ? end : to;
            if (hi.isAfter(lo) && segment.intervalSeconds() == null) {
                return true;
            }
        }
        // before its first segment the series declares nothing, but it also has no rows there (a segment starts with it)
        return false;
    }

    /**
     * The sum over series and segments of ceil(overlap of {@code [from, to)} with the segment, in seconds, divided by the
     * segment's interval). A segment runs from its {@code effectiveFrom} to the next one's; a series with no segment is
     * projected whole at the fallback interval. Saturates at {@link Long#MAX_VALUE} rather than wrapping.
     */
    static long project(List<ExportRows.SeriesSpan> spans, Instant from, Instant to, int fallbackIntervalSeconds) {
        long total = 0;
        for (ExportRows.SeriesSpan span : spans) {
            List<ExportRows.Segment> segments = span.segments();
            if (segments.isEmpty()) {
                total = add(total, rowsIn(from, to, fallbackIntervalSeconds));
                continue;
            }
            for (int i = 0; i < segments.size(); i++) {
                ExportRows.Segment segment = segments.get(i);
                Instant lo = segment.effectiveFrom().isAfter(from) ? segment.effectiveFrom() : from;
                Instant end = i + 1 < segments.size() ? segments.get(i + 1).effectiveFrom() : to;
                Instant hi = end.isBefore(to) ? end : to;
                if (hi.isAfter(lo)) {
                    int interval = segment.intervalSeconds() == null ? fallbackIntervalSeconds : segment.intervalSeconds();
                    total = add(total, rowsIn(lo, hi, interval));
                }
            }
        }
        return total;
    }

    /** ceil(millis / (interval * 1000)), so a partial interval at the edge still counts as a row. */
    private static long rowsIn(Instant lo, Instant hi, int intervalSeconds) {
        long millis = hi.toEpochMilli() - lo.toEpochMilli();
        long intervalMillis = intervalSeconds * 1000L;
        return Math.floorDiv(millis + intervalMillis - 1, intervalMillis);
    }

    private static long add(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
