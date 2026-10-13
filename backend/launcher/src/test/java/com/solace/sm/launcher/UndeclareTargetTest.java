package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 012 T5 (live gate L5): the SECOND Cache a live run declares and undeclares comes from system properties and nothing
 * else, and an incomplete or malformed set is refused BEFORE a container is looked at or a host is contacted. Pure: no
 * Docker, no network, no host. Every refusal names the offending PROPERTY and never a value ({@code I6}), as
 * {@link MultiPlacementTargetTest} requires of the L2 target.
 */
class UndeclareTargetTest {

    private static final String SENTINEL = "SENTINEL-value-that-must-never-be-echoed";

    private WalkthroughTarget base;
    private Map<String, String> properties;

    @BeforeEach
    void aBaseTargetAndACompleteSecondCache() {
        base = WalkthroughTarget.rehearsal(Path.of("not-opened"));   // container "sm-e2e-cache", root "/srv/sm-e2e-cache", port 8081
        properties = new HashMap<>();
        properties.put("sm.walkthrough.undeclare.second.container", "sm-e2e-cache-2");
        properties.put("sm.walkthrough.undeclare.second.config-dir", "/srv/sm-e2e-cache/second");
        properties.put("sm.walkthrough.undeclare.second.host-port", "18081");
    }

    private UndeclareTarget parse() {
        return UndeclareTarget.parse(properties::get, base);
    }

    private String refusal() {
        return assertThrows(IllegalArgumentException.class, this::parse).getMessage();
    }

    @Test
    void aCompleteSetParsesAndTheHostPortIsBothTheHealthPortAndTheMetricsHostPort() {
        UndeclareTarget.Second second = parse().second();

        assertEquals("sm-e2e-cache-2", second.container());
        assertEquals("/srv/sm-e2e-cache/second", second.configDirectory());
        assertEquals(18081, second.hostPort());
        assertEquals(18081, second.healthPort());
        assertEquals(Map.of("8081", 18081), second.metricsPorts(), "container port 8081 published on the host port");
    }

    @Test
    void eachOfTheThreeIsRequiredAndAllMissingOnesAreNamedAtOnce() {
        properties.clear();

        String message = refusal();

        for (String name : new String[] {"second.container", "second.config-dir", "second.host-port"}) {
            assertTrue(message.contains("sm.walkthrough.undeclare." + name), name + " named: " + message);
        }
    }

    @Test
    void theContainerMustStartWithTheRegisteredPrefixAndDifferFromTheFirstCache() {
        properties.put("sm.walkthrough.undeclare.second.container", "other-cache");
        assertTrue(refusal().contains("second.container"));

        properties.put("sm.walkthrough.undeclare.second.container", base.container());
        assertTrue(refusal().contains("second.container"), "the first Cache is never the gate's");

        properties.put("sm.walkthrough.undeclare.second.container", "sm-e2e-cache-2; rm -rf /");
        assertTrue(refusal().contains("second.container"));
    }

    @Test
    void theDirectoryMustLieStrictlyUnderTheRegisteredRootWithNoDotDot() {
        for (String directory : new String[] {"/srv/sm-e2e-cache", "/srv/other", "/srv/sm-e2e-cache/../etc",
                "/srv/sm-e2e-cache/second dir", "relative/dir", "/srv/sm-e2e-cache/$(id)"}) {
            properties.put("sm.walkthrough.undeclare.second.config-dir", directory);
            assertTrue(refusal().contains("second.config-dir"), directory);
        }
    }

    @Test
    void theHostPortMustBeAPortAndDifferFromTheFirstCaches() {
        for (String port : new String[] {"0", "65536", "abc", "-1", String.valueOf(base.healthPort())}) {
            properties.put("sm.walkthrough.undeclare.second.host-port", port);
            assertTrue(refusal().contains("second.host-port"), port);
        }
    }

    @Test
    void aRefusalNamesThePropertyAndNeverTheValue() {
        properties.put("sm.walkthrough.undeclare.second.container", SENTINEL);
        properties.put("sm.walkthrough.undeclare.second.config-dir", "/srv/" + SENTINEL);
        properties.put("sm.walkthrough.undeclare.second.host-port", SENTINEL);

        String message = refusal();

        assertFalse(message.contains(SENTINEL), message);
    }

    @Test
    void theRehearsalTargetIsValidAgainstTheRehearsalBaseAndReadsNoProperty() {
        UndeclareTarget rehearsal = UndeclareTarget.rehearsal(base);

        assertTrue(rehearsal.second().container().startsWith(base.container()));
        assertTrue(rehearsal.second().configDirectory().startsWith(base.configDirectory() + "/"));
        assertTrue(rehearsal.second().hostPort() != base.healthPort());
    }
}
