package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.logs.LogLevelView;
import com.solace.sm.dp.api.logs.LogLevelView.State;
import com.solace.sm.dp.api.logs.LoggerLevel;
import com.solace.sm.dp.api.logs.LoggerTarget;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T9.3 under OR-103 (option Rd): the live READ over SSH from the placement's loopback. The host is a fake
 * {@link HostProbe} that records what it was asked, so the tests pin both the answer and the command's inputs: what is
 * asked is built from a validated prefix and a validated path and an {@code int} port, and nothing else.
 */
class LogLevelServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final String PREFIX = "com.solace.solutions.cache";
    private static final LoggerTarget TARGET = new LoggerTarget("cache-engine", PREFIX, "/actuator/loggers");

    private final List<PlacementRef> lookups = new ArrayList<>();
    private final List<String> probed = new ArrayList<>();
    private final List<Integer> ports = new ArrayList<>();
    private HostProbe.ProbeResult answer = new HostProbe.ProbeResult(0, 200,
            "{\"effectiveLevel\":\"DEBUG\",\"configuredLevel\":\"DEBUG\"}".getBytes(StandardCharsets.UTF_8));
    private RuntimeAdapterException failure;
    private AcquisitionMode mode = AcquisitionMode.VIA_SSH;
    private HealthProbeSpec declared = new HealthProbeSpec("actuator-health", "/actuator/health", 58081,
            ProbeStrength.READINESS);
    private RuntimeAdapterException unresolvable;

    private final SshDockerPlacementCatalog catalog = ref -> {
        lookups.add(ref);
        if (unresolvable != null) {
            throw unresolvable;
        }
        return new SshDockerPlacement(ref.tenant(), "vm-1.example", 22, "sm", UUID.randomUUID(),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "sm-cache", "/srv/sm", "/srv/sm/cache", "/app/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, mode, declared);
    };

    private final HostProbe probe = (placement, port, path, timeout) -> {
        probed.add(path);
        ports.add(port);
        if (failure != null) {
            throw failure;
        }
        return answer;
    };

    private final List<String> configuredAsked = new ArrayList<>();
    private java.util.Optional<LoggerLevel> configured = java.util.Optional.empty();

    private final LogLevelServiceImpl.ConfiguredLevels configuredLevels = (org, instance, prefix) -> {
        configuredAsked.add(org + "/" + instance + "/" + prefix);
        return configured;
    };

    private LogLevelServiceImpl service() {
        return new LogLevelServiceImpl(catalog, probe, Duration.ofSeconds(5), configuredLevels);
    }

    private LogLevelView read(LoggerTarget target) {
        return service().read(ORG, INSTANCE, PLACEMENT, target);
    }

    private static HostProbe.ProbeResult answered(int status, String body) {
        return new HostProbe.ProbeResult(0, status, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aLiveReadReturnsTheLevelTheProcessReportsAndNoOverrideBecauseSmHasNoLiveWrite() {
        LogLevelView view = read(TARGET);

        assertEquals(State.LIVE, view.availability().state());
        assertEquals(java.util.Optional.of(LoggerLevel.DEBUG), view.liveLevel());
        assertFalse(view.runtimeOverride(), "SM never set a level live, so it can never have caused an override");
        assertTrue(view.configuredLevel().isEmpty(), "nothing is configured, and the live answer is not a configured one");
        assertEquals(PLACEMENT, view.placementId());
        assertEquals("cache-engine", view.component());
    }

    @Test
    void theReadIsAGetOnTheDeclaredHostPortOfTheValidatedPathAndPrefixOnly() {
        read(TARGET);

        assertEquals(List.of("/actuator/loggers/" + PREFIX), probed);
        assertEquals(List.of(58081), ports, "the placement's host-side port: the actuator does not move (OR-103)");
    }

    @Test
    void thePlacementIsLookedUpByTenantInstanceAndPlacementTogether() {
        read(TARGET);

        assertEquals(1, lookups.size());
        PlacementRef ref = lookups.get(0);
        assertEquals(new TenantId(ORG), ref.tenant());
        assertEquals(new InstanceId(INSTANCE), ref.instance());
        assertEquals(new PlacementId(PLACEMENT), ref.placement());
    }

    @Test
    void aLoggerNameOutsideTheGrammarIsRefusedBeforeAnySession() {
        List<String> bad = new ArrayList<>(List.of("", "ROOT", "root", "a..b", ".a", "a.", "a b", "a;rm -rf /", "a$b",
                "a/b", "1abc", "a-b", "a\nb", "com.solace.'x'"));
        bad.add("a".repeat(256));
        for (String prefix : bad) {
            probed.clear();
            lookups.clear();
            LogLevelView view = read(new LoggerTarget("c", prefix, "/actuator/loggers"));

            assertEquals(State.UNAVAILABLE, view.availability().state(), prefix);
            assertTrue(probed.isEmpty(), "no session for: " + prefix);
            assertTrue(lookups.isEmpty(), "no placement lookup for: " + prefix);
            assertFalse(view.availability().reason().contains(prefix.isEmpty() ? "\u0000" : prefix),
                    "the reason never quotes the declared name");
        }
    }

    @Test
    void aPathOutsideTheGrammarIsRefusedBeforeAnySession() {
        for (String path : List.of("", "actuator/loggers", "/actuator/loggers;x", "/a/../b", "/x/", "/a b", "/$HOME",
                "/" + "a".repeat(300))) {
            probed.clear();
            LogLevelView view = read(new LoggerTarget("c", PREFIX, path));

            assertEquals(State.UNAVAILABLE, view.availability().state(), path);
            assertTrue(probed.isEmpty(), "no session for: " + path);
        }
    }

    @Test
    void aDirectHttpPlacementIsUnavailableOpensNoSessionAndPointsToConfiguration() {
        mode = AcquisitionMode.DIRECT_HTTP;

        LogLevelView view = read(TARGET);

        assertEquals(State.UNAVAILABLE, view.availability().state());
        assertTrue(view.availability().reason().contains("configuration"), view.availability().reason());
        assertTrue(probed.isEmpty());
    }

    @Test
    void aPlacementThatDeclaresNoHostPortIsUnavailableAndOpensNoSession() {
        declared = null;

        LogLevelView view = read(TARGET);

        assertEquals(State.UNAVAILABLE, view.availability().state());
        assertTrue(probed.isEmpty());
    }

    @Test
    void a404IsDegradedNotAnErrorAndSaysTheEndpointMayNotBeExposedYet() {
        answer = answered(404, "");

        LogLevelView view = read(TARGET);

        assertEquals(State.DEGRADED, view.availability().state());
        assertTrue(view.liveLevel().isEmpty());
        assertTrue(view.availability().reason().contains("apply"), view.availability().reason());
    }

    @Test
    void aRefusedConnectionIsUnavailable() {
        answer = new HostProbe.ProbeResult(7, 0, new byte[0]);

        assertEquals(State.UNAVAILABLE, read(TARGET).availability().state());
    }

    @Test
    void aTimeoutIsUnavailable() {
        answer = new HostProbe.ProbeResult(28, 0, new byte[0]);

        assertEquals(State.UNAVAILABLE, read(TARGET).availability().state());
    }

    @Test
    void anAnswerThatIsNotALevelIsDegradedAndTheBodyIsNeverQuoted() {
        answer = answered(200, "SENTINEL-BODY <html>not json</html>");

        LogLevelView view = read(TARGET);

        assertEquals(State.DEGRADED, view.availability().state());
        assertFalse(view.availability().reason().contains("SENTINEL"));
    }

    @Test
    void aLevelOutsideTheContractsSetIsDegradedWithNoLiveLevelAndNeverGuessed() {
        answer = answered(200, "{\"effectiveLevel\":\"OFF\"}");

        LogLevelView view = read(TARGET);

        assertEquals(State.DEGRADED, view.availability().state());
        assertTrue(view.liveLevel().isEmpty());
    }

    @Test
    void aLowerCaseLevelIsAccepted() {
        answer = answered(200, "{\"effectiveLevel\":\"warn\"}");

        assertEquals(java.util.Optional.of(LoggerLevel.WARN), read(TARGET).liveLevel());
    }

    @Test
    void anOtherHttpStatusIsDegradedAndNamesOnlyTheNumber() {
        answer = answered(500, "SENTINEL-500-BODY");

        LogLevelView view = read(TARGET);

        assertEquals(State.DEGRADED, view.availability().state());
        assertTrue(view.availability().reason().contains("500"));
        assertFalse(view.availability().reason().contains("SENTINEL"));
    }

    @Test
    void aBusyPoolIsDegradedAndAFailedConnectionIsUnavailable() {
        failure = new RuntimeAdapterException(new ErrorClass("ssh.pool.busy"));
        assertEquals(State.DEGRADED, read(TARGET).availability().state());

        failure = new RuntimeAdapterException(new ErrorClass("ssh.connection.failed"));
        assertEquals(State.UNAVAILABLE, read(TARGET).availability().state());

        failure = new RuntimeAdapterException(new ErrorClass("ssh.credential.unavailable"));
        assertEquals(State.UNAVAILABLE, read(TARGET).availability().state());
    }

    @Test
    void aPlacementThatIsNotThisInstancesLivePlacementIsNoSuchElement() {
        unresolvable = new RuntimeAdapterException(new ErrorClass("placement.unresolvable"));

        assertThrows(NoSuchElementException.class, () -> read(TARGET));
        assertTrue(probed.isEmpty());
    }

    // ---- T9.4: configuredLevel is the latest generation's, reported beside the live answer ----------------------

    @Test
    void theConfiguredLevelIsReportedBesideTheLiveOneAndTheyMayDiffer() {
        configured = java.util.Optional.of(LoggerLevel.WARN);

        LogLevelView view = read(TARGET);

        assertEquals(java.util.Optional.of(LoggerLevel.DEBUG), view.liveLevel());
        assertEquals(java.util.Optional.of(LoggerLevel.WARN), view.configuredLevel(),
                "a level staged and not yet restarted differs from the running one");
        assertEquals(List.of(ORG + "/" + INSTANCE + "/" + PREFIX), configuredAsked, "asked for this tenant, instance and logger");
    }

    @Test
    void theConfiguredLevelIsReportedWhenTheHostCannotAnswer() {
        configured = java.util.Optional.of(LoggerLevel.ERROR);
        failure = new RuntimeAdapterException(new ErrorClass("ssh.connection.failed"));

        LogLevelView view = read(TARGET);

        assertEquals(State.UNAVAILABLE, view.availability().state());
        assertTrue(view.liveLevel().isEmpty());
        assertEquals(java.util.Optional.of(LoggerLevel.ERROR), view.configuredLevel(),
                "the record is the platform's own: it is known even when the host is not");
    }

    @Test
    void theConfiguredLevelIsReportedForADirectHttpPlacementWhichHasNoLiveRead() {
        configured = java.util.Optional.of(LoggerLevel.INFO);
        mode = AcquisitionMode.DIRECT_HTTP;

        LogLevelView view = read(TARGET);

        assertEquals(State.UNAVAILABLE, view.availability().state());
        assertEquals(java.util.Optional.of(LoggerLevel.INFO), view.configuredLevel());
    }

    @Test
    void aPrefixOutsideTheGrammarAsksForNoConfiguredLevelAndReportsNone() {
        configured = java.util.Optional.of(LoggerLevel.INFO);

        LogLevelView view = read(new LoggerTarget("cache-engine", "a$b", "/actuator/loggers"));

        assertTrue(view.configuredLevel().isEmpty());
        assertTrue(configuredAsked.isEmpty(), "nothing the package declared outside the grammar reaches a lookup");
    }

    @Test
    void anUnknownPlacementIsStillRefusedBeforeAnyConfiguredLookup() {
        unresolvable = new RuntimeAdapterException(new ErrorClass("placement.unresolvable"));

        assertThrows(NoSuchElementException.class, () -> read(TARGET));
        assertTrue(configuredAsked.isEmpty());
    }
}
