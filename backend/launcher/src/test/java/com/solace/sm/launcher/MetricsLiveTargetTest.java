package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 011 P11-4.4: the live collection gate's settings are the walkthrough's own {@code sm.walkthrough.*} target plus a
 * few {@code sm.walkthrough.metrics.*} knobs, and a malformed set is refused BEFORE a host is contacted. Pure: no Docker,
 * no network. Every refusal names the PROPERTY, never a value.
 */
class MetricsLiveTargetTest {

    @TempDir
    Path tmp;

    private Map<String, String> properties;

    @BeforeEach
    void theWalkthroughsOwnTarget() throws IOException {
        Path key = Files.writeString(tmp.resolve("id_e2e"), "not a key");
        properties = new HashMap<>();
        properties.put("sm.walkthrough.ssh.host", "e2e-host.example.test");
        properties.put("sm.walkthrough.ssh.user", "ec2-user");
        properties.put("sm.walkthrough.ssh.fingerprint", "SHA256:" + "A".repeat(43));
        properties.put("sm.walkthrough.ssh.key-file", key.toString());
        properties.put("sm.walkthrough.ssh.container", "cache-engine");
        properties.put("sm.walkthrough.config-dir", "/srv/cache-engine/config");
    }

    private MetricsLiveTarget parse() {
        return MetricsLiveTarget.parse(properties::get, WalkthroughTarget.parse(properties::get, n -> null));
    }

    @Test
    void theDefaultsAreOneReadOnlyCacheOnTheHealthPortForTenMinutes() {
        MetricsLiveTarget live = parse();

        assertEquals(Duration.ofMinutes(10), live.window());
        assertFalse(live.disruptive(), "restarting or stopping a container is opt-in, never the default");
        assertEquals(1, live.caches().size());
        MetricsLiveTarget.Cache first = live.caches().get(0);
        assertEquals("cache-engine", first.container());
        assertEquals("/srv/cache-engine/config", first.configDirectory());
        assertEquals(8081, first.hostPort(), "the host port of the Cache's 8081 defaults to the walkthrough's health port");
        assertEquals("via-ssh", first.mode());
        assertEquals(Map.of("8081", 8081), first.metricsPorts());
    }

    @Test
    void aSecondCacheOnTheSameHostIsAddedByAllThreeOrNone() {
        properties.put("sm.walkthrough.metrics.second.container", "cache-engine-2");
        properties.put("sm.walkthrough.metrics.second.config-dir", "/srv/cache-engine/config/second");
        properties.put("sm.walkthrough.metrics.second.host-port", "28081");
        properties.put("sm.walkthrough.metrics.host-port", "18081");

        MetricsLiveTarget live = parse();

        assertEquals(2, live.caches().size());
        assertEquals(18081, live.caches().get(0).hostPort());
        MetricsLiveTarget.Cache second = live.caches().get(1);
        assertEquals("cache-engine-2", second.container());
        assertEquals(28081, second.hostPort());
        assertEquals("via-ssh", second.mode(), "the same host, the walkthrough's mode unless the second asks for its own");
    }

    @Test
    void aSecondCacheMayBeReadDirectlyToTestTheOtherModeOnTheSameHost() {
        properties.put("sm.walkthrough.metrics.second.container", "cache-engine-2");
        properties.put("sm.walkthrough.metrics.second.config-dir", "/srv/cache-engine/config/second");
        properties.put("sm.walkthrough.metrics.second.host-port", "28081");
        properties.put("sm.walkthrough.metrics.second.mode", "direct-http");

        assertEquals("direct-http", parse().caches().get(1).mode());
    }

    @Test
    void aPartialSecondCacheIsRefusedNamingEveryMissingProperty() {
        properties.put("sm.walkthrough.metrics.second.container", "cache-engine-2");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(refused.getMessage().contains("sm.walkthrough.metrics.second.config-dir"), refused.getMessage());
        assertTrue(refused.getMessage().contains("sm.walkthrough.metrics.second.host-port"), refused.getMessage());
    }

    @Test
    void theSecondCacheMustFitTheRegisteredEnvelopeAndCollideWithNothing() {
        properties.put("sm.walkthrough.metrics.second.container", "other-name");              // outside the prefix
        properties.put("sm.walkthrough.metrics.second.config-dir", "/srv/elsewhere");         // outside the root
        properties.put("sm.walkthrough.metrics.second.host-port", "8081");                    // the first's port

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(refused.getMessage().contains("sm.walkthrough.metrics.second.container"), refused.getMessage());
        assertTrue(refused.getMessage().contains("sm.walkthrough.metrics.second.config-dir"), refused.getMessage());
        assertTrue(refused.getMessage().contains("sm.walkthrough.metrics.second.host-port"), refused.getMessage());
        assertFalse(refused.getMessage().contains("elsewhere"), "a refusal never echoes a value");
    }

    @Test
    void aClimbingOrSiblingDirectoryIsRefused() {
        properties.put("sm.walkthrough.metrics.second.container", "cache-engine-2");
        properties.put("sm.walkthrough.metrics.second.config-dir", "/srv/cache-engine/config/../x");
        properties.put("sm.walkthrough.metrics.second.host-port", "28081");
        assertThrows(IllegalArgumentException.class, this::parse);

        properties.put("sm.walkthrough.metrics.second.config-dir", "/srv/cache-engine/config-sibling");
        assertThrows(IllegalArgumentException.class, this::parse);
    }

    @Test
    void theWindowIsBoundedAndTheDisruptiveSwitchIsExactlyYes() {
        properties.put("sm.walkthrough.metrics.minutes", "3");
        properties.put("sm.walkthrough.metrics.disruptive", "yes");
        MetricsLiveTarget live = parse();
        assertEquals(Duration.ofMinutes(3), live.window());
        assertTrue(live.disruptive());

        properties.put("sm.walkthrough.metrics.disruptive", "true");
        assertFalse(parse().disruptive(), "only the word yes: a stray true must not stop a container");

        properties.put("sm.walkthrough.metrics.minutes", "0");
        assertThrows(IllegalArgumentException.class, this::parse);
        properties.put("sm.walkthrough.metrics.minutes", "9999");
        assertThrows(IllegalArgumentException.class, this::parse);
        properties.put("sm.walkthrough.metrics.minutes", "ten");
        assertThrows(IllegalArgumentException.class, this::parse);
    }

    @Test
    void theCachesNamedHere() {
        assertEquals(List.of("cache-engine"), parse().caches().stream().map(MetricsLiveTarget.Cache::container).toList());
    }

    @Test
    void anSshCacheBootsWithTheHealthSwitchOnBecauseTheCollectorsSshFetcherSharesItsPool() {
        // the live run of 2026-10-06: the test database switches the poller (and so the SSH pool) OFF; the collector then
        // had no SSH fetcher and counted every document scrape_failed
        MetricsLiveTarget ssh = parse();
        assertEquals("via-ssh", ssh.caches().get(0).mode());
        Map<String, String> boot = AbstractMetricsCollectionWalkthrough.bootProperties(ssh, Map.of("a", "b"));
        assertEquals("true", boot.get("sm.dp.health.enabled"));
        assertEquals("b", boot.get("a"), "the run's own overrides are kept");

        properties.put("sm.walkthrough.acquisition", "direct-http");
        MetricsLiveTarget direct = parse();
        assertFalse(AbstractMetricsCollectionWalkthrough.bootProperties(direct, Map.of())
                .containsKey("sm.dp.health.enabled"), "direct-http never needs the pool");
    }

    @Test
    void missTrafficIsOptInByExactlyYesWithThirtyRequestsByDefault() {
        MetricsLiveTarget off = parse();
        assertFalse(off.traffic(), "generating requests on the host is never the default");
        assertEquals(30, off.trafficCount());

        properties.put("sm.walkthrough.metrics.traffic", "yes");
        properties.put("sm.walkthrough.metrics.traffic.count", "12");
        MetricsLiveTarget on = parse();
        assertTrue(on.traffic());
        assertEquals(12, on.trafficCount());

        properties.put("sm.walkthrough.metrics.traffic", "true");
        assertFalse(parse().traffic(), "only the word yes");

        properties.put("sm.walkthrough.metrics.traffic.count", "0");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);
        assertTrue(refused.getMessage().contains("sm.walkthrough.metrics.traffic.count"), refused.getMessage());
    }
}
