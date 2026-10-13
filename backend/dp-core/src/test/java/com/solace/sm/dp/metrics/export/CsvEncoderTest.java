package com.solace.sm.dp.metrics.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-6.2: the hand-written CSV encoder (M-Q18: encoded in the application, no dependency). */
class CsvEncoderTest {

    private static final UUID INSTANCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLACEMENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void theCsvIsRfc4180AndUtc() throws IOException {
        StringBuilder out = new StringBuilder();
        CsvEncoder.header(out);
        CsvEncoder.row(out, INSTANCE, PLACEMENT, Instant.parse("2026-10-06T10:00:00Z"), 1.5);
        CsvEncoder.row(out, INSTANCE, PLACEMENT, Instant.parse("2026-10-06T10:00:05.250Z"), 42);
        CsvEncoder.row(out, INSTANCE, PLACEMENT, Instant.parse("2026-10-06T10:00:10Z"), -0.0000001);
        // RFC 4180: records end in CRLF, the header is the first record, fields are comma separated.
        assertEquals("instance_id,placement_id,timestamp,value\r\n"
                        + INSTANCE + "," + PLACEMENT + ",2026-10-06T10:00:00Z,1.5\r\n"
                        + INSTANCE + "," + PLACEMENT + ",2026-10-06T10:00:05.250Z,42.0\r\n"
                        + INSTANCE + "," + PLACEMENT + ",2026-10-06T10:00:10Z,-1.0E-7\r\n",
                out.toString());
    }

    @Test
    void aTimestampIsAlwaysWrittenInUtcWhateverTheJvmZoneIs() throws IOException {
        java.util.TimeZone before = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            StringBuilder out = new StringBuilder();
            CsvEncoder.row(out, INSTANCE, PLACEMENT, Instant.parse("2026-10-06T10:00:00Z"), 1);
            assertEquals(INSTANCE + "," + PLACEMENT + ",2026-10-06T10:00:00Z,1.0\r\n", out.toString());
        } finally {
            java.util.TimeZone.setDefault(before);
        }
    }

    @Test
    void aFieldWithACommaAQuoteOrALineBreakIsQuotedAndQuotesAreDoubled() {
        assertEquals("plain", CsvEncoder.field("plain"));
        assertEquals("\"a,b\"", CsvEncoder.field("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", CsvEncoder.field("say \"hi\""));
        assertEquals("\"line1\r\nline2\"", CsvEncoder.field("line1\r\nline2"));
        assertEquals("\"line1\nline2\"", CsvEncoder.field("line1\nline2"));
        assertEquals("", CsvEncoder.field(""));
    }

    @Test
    void aNonFiniteValueIsRefusedNeverWritten() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            StringBuilder out = new StringBuilder();
            assertThrows(IllegalArgumentException.class,
                    () -> CsvEncoder.row(out, INSTANCE, PLACEMENT, Instant.parse("2026-10-06T10:00:00Z"), bad));
            assertEquals("", out.toString(), "nothing of the refused row reached the output");
        }
    }
}
