package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.LoggerSupport;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * OR-103 / BE-D37: the SM-owned overlay is ONE function. It is the only source of the keys SM adds beside a package's
 * fields, and {@link OverlayKeys#owns} is the only predicate that says which keys are SM's: the renderer's refusal and
 * accept-runtime's read-back strip (T10.3) call it, so there are not two lists to drift.
 */
class OverlayKeysTest {

    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final String ACCESS = "management.endpoint.loggers.access";

    private static Map<String, String> byPath(List<OverlayKeys.Entry> entries) {
        return entries.stream().collect(Collectors.toMap(OverlayKeys.Entry::path, OverlayKeys.Entry::valueJson));
    }

    @Test
    void aPackageThatDeclaresLoggersGetsLoggersInTheExposureListAndReadOnlyAccess() {
        Map<String, String> overlay = byPath(OverlayKeys.forLoggerSupport(
                new LoggerSupport(List.of("health", "info", "metrics"))));

        assertEquals("\"health,info,loggers,metrics\"", overlay.get(EXPOSURE));
        assertEquals("\"read-only\"", overlay.get(ACCESS));
        assertEquals(2, overlay.size(), "no management port, no address: the actuator does not move (OR-103)");
    }

    @Test
    void theExposureListIsTheSortedUnionOfTheBaselineAndSmsRequiredNames() {
        Map<String, String> overlay = byPath(OverlayKeys.forLoggerSupport(
                new LoggerSupport(List.of("metrics", "health", "loggers", "health", "prometheus"))));

        assertEquals("\"health,loggers,metrics,prometheus\"", overlay.get(EXPOSURE));
    }

    @Test
    void theBaselineIsNeverDroppedBecauseTheSmWrittenListReplacesTheMisOwn() {
        Map<String, String> overlay = byPath(OverlayKeys.forLoggerSupport(new LoggerSupport(List.of("health"))));

        assertTrue(overlay.get(EXPOSURE).contains("health"), overlay.get(EXPOSURE));
    }

    @Test
    void theSameDeclarationAlwaysYieldsTheSameEntriesInTheSameOrder() {
        List<OverlayKeys.Entry> a = OverlayKeys.forLoggerSupport(new LoggerSupport(List.of("info", "health")));
        List<OverlayKeys.Entry> b = OverlayKeys.forLoggerSupport(new LoggerSupport(List.of("health", "info")));

        assertEquals(a, b);
    }

    @Test
    void ownsIsExactlyThePathsTheWriterEmitsAndNoOther() {
        for (OverlayKeys.Entry entry : OverlayKeys.forLoggerSupport(new LoggerSupport(List.of("health")))) {
            assertTrue(OverlayKeys.owns(entry.path()), entry.path());
        }
        assertFalse(OverlayKeys.owns("management.server.port"), "a key SM does not write is not SM's");
        assertFalse(OverlayKeys.owns("management.endpoints.web.exposure.exclude"));
        assertFalse(OverlayKeys.owns("cache-engine.ttl"));
        assertFalse(OverlayKeys.owns(EXPOSURE + ".x"), "a path beneath an owned key is not the owned key");
    }

    @Test
    void anEndpointIdOutsideTheGrammarIsRefusedBeforeItCanReachAProperty() {
        for (String bad : List.of("a,b", "x y", "", "1abc", "a;b", "a\nb")) {
            assertThrows(IllegalArgumentException.class, () -> new LoggerSupport(List.of(bad)), bad);
        }
    }

    // ---- T9.4: the level as an overlay entry (OR-103 P2) ----------------------------------------------------

    private static final String PREFIX = "com.solace.solutions.cache";

    @Test
    void aConfiguredLevelIsOneEntryAtLoggingLevelDotThePrefixWithTheLevelAsAString() {
        OverlayKeys.Entry entry = OverlayKeys.forLogLevel(PREFIX, com.solace.sm.dp.api.logs.LoggerLevel.DEBUG);

        assertEquals("logging.level." + PREFIX, entry.path());
        assertEquals("\"DEBUG\"", entry.valueJson());
    }

    @Test
    void everyContractLevelIsWrittenAsItsOwnName() {
        for (com.solace.sm.dp.api.logs.LoggerLevel level : com.solace.sm.dp.api.logs.LoggerLevel.values()) {
            assertEquals("\"" + level.name() + "\"", OverlayKeys.forLogLevel(PREFIX, level).valueJson());
        }
    }

    @Test
    void aLevelPathIsOwnedSoTheReadBackStripsItAndAPackageFieldCannotClaimIt() {
        assertTrue(OverlayKeys.owns("logging.level." + PREFIX));
        assertTrue(OverlayKeys.isLevelPath("logging.level." + PREFIX));
        assertFalse(OverlayKeys.isLevelPath(EXPOSURE), "an exposure path is owned but is not a level");
        assertFalse(OverlayKeys.owns("logging.level."), "no logger, no key");
        assertFalse(OverlayKeys.owns("logging.level.root"), "the root logger is not SM's to set");
        assertFalse(OverlayKeys.owns("logging.level.ROOT"));
        assertFalse(OverlayKeys.owns("logging.level.a$b"), "outside the logger-name grammar");
        assertFalse(OverlayKeys.owns("logging.file.name"), "another logging property is not SM's");
        assertFalse(OverlayKeys.owns("cache-engine.logging.level." + PREFIX), "only the top-level property");
    }

    @Test
    void aLoggerNameOutsideTheGrammarOrTheRootLoggerNeverBecomesAProperty() {
        for (String bad : List.of("", "ROOT", "root", "a$b", "a b", "a=b", "a\nb", "1abc", "a..b", ".a", "a.", "a:b",
                "x".repeat(256))) {
            assertThrows(IllegalArgumentException.class,
                    () -> OverlayKeys.forLogLevel(bad, com.solace.sm.dp.api.logs.LoggerLevel.INFO), bad);
        }
    }

    @Test
    void aLoggerAndALevelAreRecoverableFromAStoredEntry() {
        assertEquals(PREFIX, OverlayKeys.loggerOf("logging.level." + PREFIX));
        assertEquals(java.util.Optional.of(com.solace.sm.dp.api.logs.LoggerLevel.WARN),
                OverlayKeys.levelOf("\"WARN\""));
        assertEquals(java.util.Optional.empty(), OverlayKeys.levelOf("\"FATAL\""));
        assertEquals(java.util.Optional.empty(), OverlayKeys.levelOf("7"));
        assertEquals(java.util.Optional.empty(), OverlayKeys.levelOf("not json"));
    }

    @Test
    void aGenerationOwnsItsBaselineAndOnlyTheLevelPathsItCarries() {
        java.util.Set<String> carried = java.util.Set.of("logging.level." + PREFIX);

        assertTrue(OverlayKeys.ownsIn(EXPOSURE, carried));
        assertTrue(OverlayKeys.ownsIn(ACCESS, java.util.Set.of()));
        assertTrue(OverlayKeys.ownsIn("logging.level." + PREFIX, carried));
        assertFalse(OverlayKeys.ownsIn("logging.level.com.other.logger", carried),
                "SM may write it, but this generation did not: it is the operator's");
        assertFalse(OverlayKeys.ownsIn("logging.level." + PREFIX, java.util.Set.of()));
        assertFalse(OverlayKeys.ownsIn(null, carried));
        assertTrue(OverlayKeys.ownsBaseline(EXPOSURE));
        assertFalse(OverlayKeys.ownsBaseline("logging.level." + PREFIX), "a level is never baseline");
    }
}
