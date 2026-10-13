package com.solace.sm.dp.api.metrics.export;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Streaming metrics export (plan 011 P11-6.2, M-Q18 {@code OR-79}): a bound {@code SELECT} read through a cursor, CSV
 * encoded in the application, never buffered, bounded in rows, concurrency and time. JDK types only.
 */
public interface MetricsExportService {

    /**
     * Before any byte is written: the projected rows (the sum over the matching series of range divided by interval).
     *
     * @throws ExportTooLargeException the projection is over the row limit
     * @throws ExportBusyException     the database budget stayed spent ({@code DATABASE_BUDGET})
     */
    ExportPlan plan(UUID organizationId, ExportCommand command);

    /**
     * Streams the CSV (a header, then one row per sample) into {@code out} and returns the number of data rows written.
     * Never buffers the result and never closes {@code out}; the caller owns it.
     *
     * <p>Refusals happen BEFORE the first byte: {@link ExportBusyException} past the concurrency caps or the budget wait,
     * {@link ExportTooLargeException} over the row limit. Once the first byte is out the status is already sent, so a
     * failure after it (a database error, a vanished client) ends the CSV early by throwing: the caller records the outcome
     * ({@code completed}, {@code failed} or {@code cut}) in the export audit. The connection, the permit and the
     * concurrency slot are released on every path.
     *
     * @return data rows written (the header is not counted)
     */
    long stream(UUID organizationId, ExportCommand command, OutputStream out) throws IOException;
}
