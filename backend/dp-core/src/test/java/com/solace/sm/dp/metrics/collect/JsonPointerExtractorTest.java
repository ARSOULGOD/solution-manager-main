package com.solace.sm.dp.metrics.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.solace.sm.dp.metrics.collect.JsonPointerExtractor.Extraction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-4.2a: what the collector reads out of a metric document (MF8, MF9) - and what it refuses. */
class JsonPointerExtractorTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static double value(Extraction e) {
        return assertInstanceOf(Extraction.Value.class, e).value();
    }

    @Test
    void aPointerReadsTheNumberItNames() {
        assertEquals(42.5, value(JsonPointerExtractor.number(b("{\"a\":{\"b\":42.5}}"), "/a/b")));
        assertEquals(7, value(JsonPointerExtractor.number(b("{\"hitCount\":7,\"x\":1}"), "/hitCount")));
    }

    @Test
    void bootsSingleMetricResponseIsReadAtMeasurementsZeroValue() {
        // MF8's shape, process.start.time measured 2026-10-06 on the E2E host
        String body = "{\"name\":\"process.start.time\",\"description\":\"Start time of the process\","
                + "\"baseUnit\":\"seconds\",\"measurements\":[{\"statistic\":\"VALUE\",\"value\":1.790665479471E9}],"
                + "\"availableTags\":[]}";

        assertEquals(1.790665479471E9, value(JsonPointerExtractor.number(b(body), "/measurements/0/value")));
    }

    @Test
    void anAbsentPointerMeansTheWholeBodyMustBeANumber() {
        // cache.size (MF9): the wire is a bare JSON number
        assertEquals(0, value(JsonPointerExtractor.number(b("0"), null)));
        assertEquals(1234, value(JsonPointerExtractor.number(b("1234\n"), null)));
        assertEquals(-3.5, value(JsonPointerExtractor.number(b("  -3.5 "), "")));
        assertInstanceOf(Extraction.Failed.class, JsonPointerExtractor.number(b("{\"a\":1}"), null));
        assertInstanceOf(Extraction.Failed.class, JsonPointerExtractor.number(b("\"7\""), null));
    }

    @Test
    void aStringAtThePointerIsParseFailedAndTheValueNeverAppearsInTheMessage() {
        Extraction e = JsonPointerExtractor.number(b("{\"a\":\"SECRET-TOKEN-123\"}"), "/a");

        assertInstanceOf(Extraction.Failed.class, e);
        assertFalse(e.toString().contains("SECRET-TOKEN-123"), e.toString());
        assertFalse(((Extraction.Failed) e).reason().contains("SECRET"), "the reason is a fixed word");
        assertEquals("parse_failed", ((Extraction.Failed) e).reason());
    }

    @Test
    void anythingThatIsNotANumberIsParseFailed() {
        for (String body : List.of("{\"a\":null}", "{\"a\":true}", "{\"a\":[1]}", "{\"a\":{}}", "{}", "[1]",
                "not json", "", "{\"a\":1", "{\"a\":1} trailing", "1 2", "{\"b\":1}")) {
            assertInstanceOf(Extraction.Failed.class, JsonPointerExtractor.number(b(body), "/a"), body);
        }
        assertInstanceOf(Extraction.Failed.class, JsonPointerExtractor.number(null, "/a"));
    }

    @Test
    void aNonFiniteNumberIsParseFailedNotRunwayDegraded() {
        // 1e400 is a valid JSON number that Jackson reads as Infinity as a double
        for (String body : List.of("{\"a\":1e400}", "{\"a\":-1e400}", "1e400", "-1e400",
                "{\"a\":1" + "0".repeat(400) + "}")) {
            String pointer = body.startsWith("{") ? "/a" : null;
            Extraction e = JsonPointerExtractor.number(b(body), pointer);
            assertInstanceOf(Extraction.Failed.class, e, body);
            assertFalse(e.toString().contains("400"), "no value in the message: " + e);
        }
    }

    @Test
    void aPointerOutsideTheGrammarIsRefusedNotInterpreted() {
        for (String pointer : List.of("a", "/a/", "//a", "/a/../b", "/./a", "/a~0b", "/a b", "/a%20", "/..",
                "/1/2/3/4/5/6/7/8/9", "/" + "a".repeat(65))) {
            assertInstanceOf(Extraction.Failed.class, JsonPointerExtractor.number(b("{\"a\":{\"b\":1}}"), pointer),
                    pointer);
        }
    }

    @Test
    void theTokenIsAnumberOrAStringRenderedStablyAndNothingElse() {
        assertEquals("1.790665479471E9", JsonPointerExtractor.token(
                b("{\"measurements\":[{\"value\":1.790665479471E9}]}"), "/measurements/0/value"));
        assertEquals("2026-09-29T07:04:39Z", JsonPointerExtractor.token(b("{\"t\":\"2026-09-29T07:04:39Z\"}"), "/t"));
        assertNull(JsonPointerExtractor.token(b("{\"t\":null}"), "/t"));
        assertNull(JsonPointerExtractor.token(b("{\"t\":{}}"), "/t"));
        assertNull(JsonPointerExtractor.token(b("{\"t\":\"" + "x".repeat(300) + "\"}"), "/t"));
        assertNull(JsonPointerExtractor.token(b("{\"t\":1e400}"), "/t"));
        assertNull(JsonPointerExtractor.token(b("garbage"), "/t"));
        assertNull(JsonPointerExtractor.token(b("{\"t\":\"a b\"}"), "/t"));
    }
}
