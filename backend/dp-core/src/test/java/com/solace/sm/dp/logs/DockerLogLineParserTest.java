package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.logs.LogLineView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T9.1: the {@code docker logs --timestamps} line (measured M11: an RFC3339Nano UTC stamp, one space, the
 * container's own line) becomes {@code (at, level?, message)}. The level is read only when the line is shaped like a
 * Spring Boot or logback line; a message that merely mentions a level word has none.
 */
class DockerLogLineParserTest {

    private static String line(String second, String message) {
        return "2026-10-06T06:25:" + second + "Z " + message;
    }

    @Test
    void aLineIsItsTimestampAtNanosecondPrecisionAndTheRestVerbatim() {
        LogLineView view = DockerLogLineParser.parseLine("2026-10-06T06:25:17.036499796Z hello  world ").orElseThrow();

        assertEquals(Instant.parse("2026-10-06T06:25:17.036499796Z"), view.at());
        assertEquals("hello  world ", view.message(), "everything after the first space, untouched");
        assertEquals(Optional.empty(), view.level());
    }

    @Test
    void aSpringBootLineCarriesItsLevel() {
        LogLineView view = DockerLogLineParser.parseLine(line("17.1",
                "2026-10-06T06:25:17.100+00:00  INFO 1 --- [           main] c.s.cache.CacheApplication : Started")).orElseThrow();

        assertEquals(Optional.of("INFO"), view.level());
    }

    @Test
    void aLogbackLineCarriesItsLevel() {
        assertEquals(Optional.of("WARN"), DockerLogLineParser.parseLine(line("17.1",
                "06:25:17.100 [main] WARN  c.s.Foo - low on memory")).orElseThrow().level());
        assertEquals(Optional.of("ERROR"), DockerLogLineParser.parseLine(line("17.1",
                "2026-10-06 06:25:17.100 ERROR 1 --- [main] c.s.Foo : boom")).orElseThrow().level());
    }

    @Test
    void aMessageThatOnlyMentionsALevelWordHasNone() {
        assertEquals(Optional.empty(), DockerLogLineParser.parseLine(line("17.1", "the ERROR happened")).orElseThrow().level());
        assertEquals(Optional.empty(), DockerLogLineParser.parseLine(line("17.1", "INFO is not a prefix here")).orElseThrow().level());
        assertEquals(Optional.empty(), DockerLogLineParser.parseLine(line("17.1", "\tat com.solace.Foo.bar(Foo.java:12)")).orElseThrow().level());
    }

    @Test
    void aLineWithoutAParseableTimestampIsNotALogLine() {
        assertTrue(DockerLogLineParser.parseLine("Error response from daemon: No such container: x").isEmpty());
        assertTrue(DockerLogLineParser.parseLine("").isEmpty());
        assertTrue(DockerLogLineParser.parseLine("   ").isEmpty());
        assertTrue(DockerLogLineParser.parseLine("2026-13-45T99:99:99Z nope").isEmpty());
        assertTrue(DockerLogLineParser.parseLine("2026-10-06T06:25:17Z").isEmpty(), "a stamp with no separator and no message");
        assertTrue(DockerLogLineParser.parseLine(null).isEmpty());
    }

    @Test
    void aCarriageReturnIsStripped() {
        assertEquals("ready", DockerLogLineParser.parseLine(line("17.1", "ready\r")).orElseThrow().message());
    }

    @Test
    void theNewestLimitLinesComeBackOldestFirst() {
        String output = String.join("\n", line("01.0", "a"), line("02.0", "b"), line("03.0", "c"), line("04.0", "d"),
                line("05.0", "e"));

        List<LogLineView> page = DockerLogLineParser.page(output, null, null, 3);

        assertEquals(List.of("c", "d", "e"), page.stream().map(LogLineView::message).toList());
    }

    @Test
    void theWindowIsExclusiveAtBothCursors() {
        String output = String.join("\n", line("01.0", "a"), line("02.0", "b"), line("03.0", "c"), line("04.0", "d"));
        Instant two = Instant.parse("2026-10-06T06:25:02Z");
        Instant four = Instant.parse("2026-10-06T06:25:04Z");

        assertEquals(List.of("c", "d"), messages(DockerLogLineParser.page(output, two, null, 10)), "after is exclusive");
        assertEquals(List.of("a", "b"), messages(DockerLogLineParser.page(output, null, Instant.parse("2026-10-06T06:25:03Z"), 10)));
        assertEquals(List.of("a", "b", "c"), messages(DockerLogLineParser.page(output, null, four, 10)), "before is exclusive");
        assertEquals(List.of("c"), messages(DockerLogLineParser.page(output, two, four, 10)));
    }

    @Test
    void outputOutOfOrderIsSortedAndEqualStampsKeepTheirEmissionOrder() {
        String output = String.join("\n", line("03.0", "late"), line("01.0", "first"), line("01.0", "second"));

        assertEquals(List.of("first", "second", "late"), messages(DockerLogLineParser.page(output, null, null, 10)));
    }

    @Test
    void noiseBetweenLinesIsSkippedAndAnEmptyOutputIsAnEmptyPage() {
        String output = "garbage\n" + line("01.0", "a") + "\n\n" + line("02.0", "b") + "\r\n";

        assertEquals(List.of("a", "b"), messages(DockerLogLineParser.page(output, null, null, 10)));
        assertFalse(DockerLogLineParser.page("", null, null, 10).iterator().hasNext());
    }

    private static List<String> messages(List<LogLineView> page) {
        return page.stream().map(LogLineView::message).toList();
    }
}
