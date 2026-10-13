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
 * Plan 008 S5 Step 2 (live gate L2): the SECOND placement a live run adds comes from system properties and nothing else,
 * and an incomplete or malformed set is refused BEFORE a container is started or a host is contacted. Pure: no Docker, no
 * network, no host.
 *
 * <p>Every refusal names the offending PROPERTY and never a value (I6), as {@link WalkthroughTargetTest} requires of the
 * first Cache's target. The rules mirror the registered envelope the first Cache fixes: the second container's name starts
 * with the first's, its directory lies strictly under the config root, and its host port differs.
 */
class MultiPlacementTargetTest {

    private static final String SENTINEL = "SENTINEL-value-that-must-never-be-echoed";

    private WalkthroughTarget base;
    private Map<String, String> properties;

    @BeforeEach
    void aBaseTargetAndACompleteSecondPlacement() {
        // The rehearsal target's values are synthetic: container "sm-e2e-cache", root "/srv/sm-e2e-cache", health port 8081.
        base = WalkthroughTarget.rehearsal(Path.of("not-opened"));
        properties = new HashMap<>();
        properties.put("sm.walkthrough.multi.second.container", "sm-e2e-cache-2");
        properties.put("sm.walkthrough.multi.second.config-dir", "/srv/sm-e2e-cache/second");
        properties.put("sm.walkthrough.multi.second.host-port", "18081");
    }

    private MultiPlacementTarget parse() {
        return MultiPlacementTarget.parse(properties::get, base);
    }

    private String refusal() {
        return assertThrows(IllegalArgumentException.class, this::parse).getMessage();
    }

    @Test
    void aCompleteSetParsesAndTheRunIsReadOnlyOfContainersUnlessAskedOtherwise() {
        MultiPlacementTarget target = parse();

        assertEquals("sm-e2e-cache-2", target.second().container());
        assertEquals("/srv/sm-e2e-cache/second", target.second().configDirectory());
        assertEquals(18081, target.second().hostPort());
        assertFalse(target.disruptive(), "stopping and restarting a container is opt-in");
    }

    @Test
    void theSecondCachesHostPortIsBothItsHealthPortAndItsMetricsHostPort() {
        MultiPlacementTarget.Second second = parse().second();

        assertEquals(second.hostPort(), second.healthPort());
        assertEquals(Map.of("8081", 18081), second.metricsPorts(), "the package's container port 8081 to the host port");
    }

    @Test
    void disruptiveIsExactlyYesAndNothingElseEnablesIt() {
        properties.put("sm.walkthrough.multi.disruptive", "yes");
        assertTrue(parse().disruptive());

        for (String notYes : new String[] {"true", "1", "YES", "y"}) {
            properties.put("sm.walkthrough.multi.disruptive", notYes);
            String message = refusal();
            assertTrue(message.contains("sm.walkthrough.multi.disruptive"), message);
            assertFalse(message.contains("=" + notYes), "no value is echoed: " + message);
        }
        properties.remove("sm.walkthrough.multi.disruptive");
        assertFalse(parse().disruptive());
    }

    @Test
    void everyMissingPropertyIsNamedAtOnceNotOneRefusalAtATime() {
        properties.clear();

        String message = refusal();

        for (String name : new String[] {"second.container", "second.config-dir", "second.host-port"}) {
            assertTrue(message.contains("sm.walkthrough.multi." + name + " (required)"), name + " in: " + message);
        }
    }

    @Test
    void aPartialSecondPlacementNamesWhatIsMissing() {
        properties.remove("sm.walkthrough.multi.second.host-port");

        String message = refusal();

        assertTrue(message.contains("sm.walkthrough.multi.second.host-port (required)"), message);
        assertFalse(message.contains("second.container"), "what was given is not reported as a problem: " + message);
    }

    @Test
    void aContainerOutsideTheRegisteredPrefixOrEqualToTheFirstIsRefusedNamingTheProperty() {
        properties.put("sm.walkthrough.multi.second.container", "other-" + SENTINEL);
        String outside = refusal();
        assertTrue(outside.contains("sm.walkthrough.multi.second.container"), outside);
        assertFalse(outside.contains(SENTINEL), "the value is never echoed: " + outside);

        properties.put("sm.walkthrough.multi.second.container", "sm-e2e-cache");
        assertTrue(refusal().contains("sm.walkthrough.multi.second.container"), "the same container is not a second one");
    }

    @Test
    void aDirectoryNotStrictlyUnderTheRegisteredRootIsRefused() {
        for (String directory : new String[] {"/srv/sm-e2e-cache", "/srv/sm-e2e-cache-sibling", "/srv/elsewhere",
                "/srv/sm-e2e-cache/../escape", "relative/" + SENTINEL}) {
            properties.put("sm.walkthrough.multi.second.config-dir", directory);

            String message = refusal();

            assertTrue(message.contains("sm.walkthrough.multi.second.config-dir"), directory + " in: " + message);
            assertFalse(message.contains(SENTINEL), "the value is never echoed: " + message);
        }
    }

    @Test
    void aHostPortThatIsTheFirstCachesOrNotAPortIsRefused() {
        for (String port : new String[] {"8081", "0", "65536", "-1", "eighty"}) {
            properties.put("sm.walkthrough.multi.second.host-port", port);

            String message = refusal();

            assertTrue(message.contains("sm.walkthrough.multi.second.host-port"), port + " in: " + message);
        }
    }

    @Test
    void theRehearsalsTargetIsValidAgainstTheRehearsalsBase() {
        MultiPlacementTarget target = MultiPlacementTarget.rehearsal(base);

        assertTrue(target.second().container().startsWith(base.container()));
        assertTrue(target.second().configDirectory().startsWith(base.configDirectory() + "/"));
        assertTrue(target.disruptive(), "the rehearsal's host is in memory, so it exercises every step");
        assertEquals(Map.of("8081", target.second().hostPort()), target.second().metricsPorts());
    }

    @Test
    void theGateIsTheWalkthroughsOwnHostPropertySoNoSecondHostIsEverLookedFor() {
        assertEquals(WalkthroughTarget.GATE_PROPERTY, MultiPlacementTarget.GATE_PROPERTY);
    }
}
