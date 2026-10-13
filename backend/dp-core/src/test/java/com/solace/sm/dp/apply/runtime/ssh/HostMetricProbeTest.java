package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.2a: the two fixed metric commands (M-Q2, M-Q9). Golden strings; the grammar is re-checked HERE,
 * independent of the schema, so nothing reaches a shell that is not in it (SM-D11a). M19 / MF12 (curl without
 * {@code --fail}) is plan 010's measured fact, pinned in {@code SshjDockerTransportProbeTest}.
 */
class HostMetricProbeTest {

    private static final Duration FIVE = Duration.ofSeconds(5);

    @Test
    void theCurlCommandIsExactlyTheFixedShapeForAGrammarPathAndParams() {
        assertEquals("curl --silent --show-error --max-time 5 --output - --write-out '\\n%{http_code}' "
                        + "'http://127.0.0.1:8081/api/cache/size?filterByNamespace=false'",
                HostMetricProbe.curl(8081, "/api/cache/size", Map.of("filterByNamespace", "false"), FIVE).command());
        assertEquals("curl --silent --show-error --max-time 5 --output - --write-out '\\n%{http_code}' "
                        + "'http://127.0.0.1:8081/api/cache/stats'",
                HostMetricProbe.curl(8081, "/api/cache/stats", Map.of(), FIVE).command());
        assertEquals("curl --silent --show-error --max-time 5 --output - --write-out '\\n%{http_code}' "
                        + "'http://127.0.0.1:8081/actuator/metrics/process.start.time'",
                HostMetricProbe.curl(8081, "/actuator/metrics/process.start.time", null, FIVE).command());
    }

    @Test
    void parametersAreSortedSoTheSameUrlIsTheSameStringWhateverTheMapOrder() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("b", "2");
        forward.put("a", "1");
        Map<String, String> backward = new LinkedHashMap<>();
        backward.put("a", "1");
        backward.put("b", "2");

        assertEquals(HostMetricProbe.curl(80, "/x", forward, FIVE).command(),
                HostMetricProbe.curl(80, "/x", backward, FIVE).command());
        assertTrue(HostMetricProbe.curl(80, "/x", forward, FIVE).command().contains("'http://127.0.0.1:80/x?a=1&b=2'"));
        assertEquals("/x?a=1&b=2", HostMetricProbe.relativeUrl("/x", forward));
    }

    @Test
    void theCommandCarriesNoFailFlagSoA503StaysDistinguishableFromARefusedConnection() {
        String command = HostMetricProbe.curl(8081, "/actuator/health", Map.of(), FIVE).command();

        assertFalse(command.contains("--fail"), command);
    }

    @Test
    void aSubSecondRemainderRoundsTheTimeoutUpNeverToZero() {
        assertTrue(HostMetricProbe.curl(80, "/", Map.of(), Duration.ofMillis(1500)).command()
                .contains("--max-time 2 "));
    }

    @Test
    void everyShellMetacharacterInAQueryParamIsRefusedBeforeACommandExists() {
        // Review Focus 3: $(id) & ' ; ` space \n % | > < " \ * ? # = , /  - each refused, as value and as key
        List<String> hostile = List.of("$(id)", "a&b", "a'b", "a;b", "a`id`", "a b", "a\nb", "a%20b", "a|b", "a>b",
                "a<b", "a\"b", "a\\b", "a*b", "a?b", "a#b", "a=b", "a,b", "a/b", "", "é", "a".repeat(65), "${HOME}",
                "a\u0000b");
        for (String bad : hostile) {
            RuntimeAdapterException asValue = assertThrows(RuntimeAdapterException.class,
                    () -> HostMetricProbe.curl(8081, "/x", Map.of("k", bad), FIVE), "value " + bad);
            assertEquals("ssh.probe.refused", asValue.errorClass().token());
            assertThrows(RuntimeAdapterException.class,
                    () -> HostMetricProbe.curl(8081, "/x", Map.of(bad, "v"), FIVE), "key " + bad);
            assertThrows(RuntimeAdapterException.class, () -> HostMetricProbe.relativeUrl("/x", Map.of("k", bad)),
                    "relative url, value " + bad);
        }
    }

    @Test
    void moreThanEightParametersAreRefused() {
        Map<String, String> nine = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            nine.put("k" + i, "v");
        }
        assertThrows(RuntimeAdapterException.class, () -> HostMetricProbe.curl(80, "/x", nine, FIVE));
    }

    @Test
    void aPathOutsideTheGrammarIsRefusedBeforeAnySession() {
        List<String> bad = List.of("", "relative", "/a;b", "/a b", "/a?x=1", "/$(id)", "/a`id`", "/a\nb", "/a'b",
                "/" + "a".repeat(256), "/a|b", "/a&b", "/é", "/a%2e");
        for (String path : bad) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> HostMetricProbe.curl(8081, path, Map.of(), FIVE), path);
            assertEquals("ssh.probe.refused", e.errorClass().token(), path);
        }
        assertThrows(RuntimeAdapterException.class, () -> HostMetricProbe.curl(8081, null, Map.of(), FIVE));
        for (int port : new int[] {0, -1, 65536}) {
            assertThrows(RuntimeAdapterException.class, () -> HostMetricProbe.curl(port, "/x", Map.of(), FIVE));
        }
        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofMillis(500),
                Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS + 1)}) {
            assertThrows(RuntimeAdapterException.class, () -> HostMetricProbe.curl(8081, "/x", Map.of(), timeout));
        }
    }

    @Test
    void theInspectCommandIsTheFixedShape() {
        assertEquals("timeout --kill-after=2 5 docker inspect --format '{{.Id}} {{.State.StartedAt}}' -- cache-1",
                HostMetricProbe.inspect("cache-1", FIVE).command(),
                "W2-03: a wedged dockerd must not hold a probe slot forever, so the host bounds the command itself");
        assertEquals(FIVE, HostMetricProbe.inspect("cache-1", FIVE).timeout());
        assertEquals("timeout --kill-after=2 2 docker inspect --format '{{.Id}} {{.State.StartedAt}}' -- cache-1",
                HostMetricProbe.inspect("cache-1", Duration.ofMillis(1500)).command(), "rounded UP to whole seconds");
    }

    @Test
    void anInspectTimeoutOutsideTheBoundIsRefused() {
        for (Duration bad : new Duration[] {null, Duration.ZERO, Duration.ofMillis(999),
                Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS + 1)}) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> HostMetricProbe.inspect("cache-1", bad), String.valueOf(bad));
            assertEquals("ssh.inspect.refused", e.errorClass().token());
        }
    }

    @Test
    void aContainerNameOutsideThePlacementsGrammarIsRefused() {
        for (String bad : new String[] {null, "", "-rf", "a b", "a;b", "$(id)", "a`id`", "a/b", "a\nb", "a'b", "é",
                "a".repeat(129)}) {
            RuntimeAdapterException e = assertThrows(RuntimeAdapterException.class,
                    () -> HostMetricProbe.inspect(bad, FIVE), String.valueOf(bad));
            assertEquals("ssh.inspect.refused", e.errorClass().token());
        }
    }

    @Test
    void theCommandsCarryNoShellSeparatorOutsideTheirQuotedUrl() {
        String command = HostMetricProbe.curl(8081, "/a/b", Map.of("x", "y", "z", "w"), FIVE).command();
        String unquotedPart = command.substring(0, command.indexOf("'http://"));

        assertFalse(unquotedPart.contains(";") || unquotedPart.contains("|") || unquotedPart.contains("&")
                || unquotedPart.contains("$") || unquotedPart.contains("`"), unquotedPart);
    }
}
