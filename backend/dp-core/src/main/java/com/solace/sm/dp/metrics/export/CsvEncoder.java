package com.solace.sm.dp.metrics.export;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The export's CSV, written by hand (plan 011 P11-6.2, M-Q18: CSV is encoded in the application; no dependency, no
 * {@code COPY}). RFC 4180: comma separated, records end in CRLF, the header is the first record, a field with a comma, a quote
 * or a line break is quoted and its quotes doubled. The export's own fields are uuids, an ISO-8601 UTC timestamp and a finite
 * number, none of which needs quoting; {@link #field} is applied anyway so a column added later cannot break the format.
 *
 * <p>No locale, no time zone, no formatter pattern: {@link DateTimeFormatter#ISO_INSTANT} is always UTC with a trailing
 * {@code Z}, and {@link Double#toString} is always a point decimal.
 */
public final class CsvEncoder {

    public static final String HEADER = "instance_id,placement_id,timestamp,value";

    private static final String EOL = "\r\n";

    private CsvEncoder() {
    }

    public static void header(Appendable out) throws IOException {
        out.append(HEADER).append(EOL);
    }

    /** One record. A non-finite value is refused before any character of the record is written. */
    public static void row(Appendable out, UUID instanceId, UUID placementId, Instant ts, double value) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("a non-finite value cannot be exported");
        }
        out.append(field(instanceId.toString())).append(',')
                .append(field(placementId.toString())).append(',')
                .append(field(DateTimeFormatter.ISO_INSTANT.format(ts))).append(',')
                .append(field(Double.toString(value))).append(EOL);
    }

    /** RFC 4180 field quoting. */
    static String field(String text) {
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\r') < 0 && text.indexOf('\n') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
