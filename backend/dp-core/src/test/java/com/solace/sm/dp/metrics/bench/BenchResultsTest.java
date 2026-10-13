package com.solace.sm.dp.metrics.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 011 P11-8.1: each step may run in its own JVM (the integrator stops after Step 0b), so a later step reads what an
 * earlier one measured from a file, not from memory.
 */
class BenchResultsTest {

    @TempDir
    Path dir;

    @Test
    void aValueSurvivesIntoANewInstance() {
        BenchResults first = new BenchResults(dir);
        first.put("0b.bytesPerRow", "141.5");
        first.put("0b.rows", "2880000");
        BenchResults second = new BenchResults(dir);
        assertEquals("141.5", second.get("0b.bytesPerRow").orElseThrow());
        assertEquals(OptionalLong.of(2_880_000L), second.getLong("0b.rows"));
        assertTrue(second.get("nothing").isEmpty());
    }

    @Test
    void aLaterPutOverwritesAndKeepsTheOthers() {
        BenchResults r = new BenchResults(dir);
        r.put("a", "1");
        r.put("b", "2");
        r.put("a", "3");
        assertEquals("3", new BenchResults(dir).get("a").orElseThrow());
        assertEquals("2", new BenchResults(dir).get("b").orElseThrow());
    }

    @Test
    void theEvidenceLineIsPrintedAndStored() throws IOException {
        BenchResults r = new BenchResults(dir);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Evidence evidence = new Evidence(r, new java.io.PrintStream(out, true));
        evidence.measured("1", "rows", 86_400_000L);
        evidence.extrapolated("4", "sixMonthBytes", 7L, "arithmetic from the measured bytes/row");
        String printed = out.toString();
        assertTrue(printed.contains("MEASURED step=1 rows=86400000"), printed);
        assertTrue(printed.contains("EXTRAPOLATED step=4 sixMonthBytes=7"), printed);
        assertTrue(printed.contains("arithmetic from the measured bytes/row"), printed);
        assertEquals("86400000", r.get("1.rows").orElseThrow());
        assertTrue(Files.readString(dir.resolve("storage-gate-evidence.log")).contains("MEASURED step=1 rows=86400000"),
                "an append-only log of every evidence line");
    }

    @Test
    void anEvidenceLineNeverCarriesAPassword() {
        BenchResults r = new BenchResults(dir);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Evidence evidence = new Evidence(r, new java.io.PrintStream(out, true));
        evidence.redact("super-secret-value");
        evidence.note("0b", "connected with super-secret-value to the database");
        assertTrue(!out.toString().contains("super-secret-value"), out.toString());
        assertTrue(out.toString().contains("***"), out.toString());
    }
}
