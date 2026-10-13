package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.LoggerSupport;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** OR-103 / BE-D37: where the overlay enters a generation, and what may not be claimed beside it. */
class CanonicalContentOverlayTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"type\":\"cache\",\"instanceKey\":\"c1\"}]}";
    private static final List<RenderedEntryValue> FIELDS = List.of(RenderedEntryValue.plain("c1", "cache-engine.ttl", "\"5m\""));
    private static final LoggerSupport SUPPORT = new LoggerSupport(List.of("health", "info", "metrics"));

    private static StageCommand cmd(List<RenderedEntryValue> rendered, LoggerSupport support) {
        return new StageCommand(1, "cache", "2.4", SELECTION, "{}", rendered, GenerationOrigin.EDIT, null, ACTOR, null,
                support);
    }

    @Test
    void loggerSupportAddsTheOverlayUnderTheReservedInstanceKeyBesideThePackagesFields() {
        String stored = CanonicalContent.of(cmd(FIELDS, SUPPORT)).renderedEntries();

        assertTrue(stored.contains("\"instanceKey\":\"" + OverlayKeys.INSTANCE_KEY + "\""), stored);
        assertTrue(stored.contains("\"renderedPath\":\"management.endpoints.web.exposure.include\""), stored);
        assertTrue(stored.contains("health,info,loggers,metrics"), stored);
        assertTrue(stored.contains("\"renderedPath\":\"management.endpoint.loggers.access\""), stored);
        assertTrue(stored.contains("cache-engine.ttl"), stored);
    }

    @Test
    void aPackageThatDeclaresNothingGeneratesTheBytesItAlwaysDid() {
        byte[] without = CanonicalContent.of(cmd(FIELDS, null)).digestInput();
        byte[] explicit = CanonicalContent.of(new StageCommand(1, "cache", "2.4", SELECTION, "{}", FIELDS,
                GenerationOrigin.EDIT, null, ACTOR)).digestInput();

        assertArrayEquals(without, explicit);
        assertFalse(CanonicalContent.of(cmd(FIELDS, null)).renderedEntries().contains("management"));
    }

    @Test
    void theOverlayIsPartOfTheDigestSoAChangedDeclarationIsANewGeneration() {
        byte[] with = CanonicalContent.of(cmd(FIELDS, SUPPORT)).digestInput();
        byte[] without = CanonicalContent.of(cmd(FIELDS, null)).digestInput();
        byte[] otherBaseline = CanonicalContent.of(cmd(FIELDS, new LoggerSupport(List.of("health")))).digestInput();

        assertFalse(java.util.Arrays.equals(with, without));
        assertFalse(java.util.Arrays.equals(with, otherBaseline));
    }

    @Test
    void aCopyForwardKeepsTheOverlayEntriesOfTheGenerationItCopies() {
        List<RenderedEntryValue> copied = new ArrayList<>(FIELDS);
        copied.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, "management.endpoint.loggers.access",
                "\"read-only\""));

        String stored = CanonicalContent.of(cmd(copied, null)).renderedEntries();

        assertTrue(stored.contains("management.endpoint.loggers.access"), stored);
    }

    @Test
    void aRenderedSetThatAlreadyCarriesOverlayEntriesCannotAlsoDeclareLoggerSupport() {
        List<RenderedEntryValue> copied = new ArrayList<>(FIELDS);
        copied.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, "management.endpoint.loggers.access",
                "\"read-only\""));

        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(copied, SUPPORT)));
    }

    @Test
    void anOverlayKeyedEntryAtAPathSmDoesNotOwnIsRefused() {
        List<RenderedEntryValue> forged = new ArrayList<>(FIELDS);
        forged.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, "management.server.port", "9"));

        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(forged, null)));
    }

    @Test
    void aPackageFieldCannotClaimAPathSmOwns() {
        List<RenderedEntryValue> claimed = new ArrayList<>(FIELDS);
        claimed.add(RenderedEntryValue.plain("c1", "management.endpoint.loggers.access", "\"read-write\""));

        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(claimed, SUPPORT)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(claimed, null)));
    }

    @Test
    void theRefusalNamesTheRuleNeverAValue() {
        List<RenderedEntryValue> claimed = new ArrayList<>(FIELDS);
        claimed.add(RenderedEntryValue.plain("c1", "management.endpoint.loggers.access", "\"SENTINEL-VALUE\""));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CanonicalContent.of(cmd(claimed, SUPPORT)));

        assertFalse(String.valueOf(refused.getMessage()).contains("SENTINEL-VALUE"));
    }

    // ---- T9.4: carrying a configured level through an edit ---------------------------------------------------

    private static final OverlayKeys.Entry LEVEL = OverlayKeys.forLogLevel("com.solace.solutions.cache",
            com.solace.sm.dp.api.logs.LoggerLevel.DEBUG);

    @Test
    void aStageThatDeclaresSupportCarriesTheLevelItIsGivenBesideTheBaselineOverlay() {
        String stored = CanonicalContent.of(cmd(FIELDS, SUPPORT), List.of(LEVEL)).renderedEntries();

        assertTrue(stored.contains("\"instanceKey\":\"" + OverlayKeys.INSTANCE_KEY + "\""), stored);
        assertTrue(stored.contains("logging.level.com.solace.solutions.cache"), stored);
        assertTrue(stored.contains("\"value\":\"DEBUG\""), stored);
        assertTrue(stored.contains("management.endpoint.loggers.access"), stored);
    }

    @Test
    void aCarriedLevelIsPartOfTheDigestSoAChangedLevelIsANewGeneration() {
        byte[] debug = CanonicalContent.of(cmd(FIELDS, SUPPORT), List.of(LEVEL)).digestInput();
        byte[] info = CanonicalContent.of(cmd(FIELDS, SUPPORT), List.of(OverlayKeys.forLogLevel(
                "com.solace.solutions.cache", com.solace.sm.dp.api.logs.LoggerLevel.INFO))).digestInput();
        byte[] none = CanonicalContent.of(cmd(FIELDS, SUPPORT)).digestInput();

        assertFalse(java.util.Arrays.equals(debug, info));
        assertFalse(java.util.Arrays.equals(debug, none));
    }

    @Test
    void aStageThatDeclaresNoSupportDropsTheLevelBecauseThePackageNoLongerOffersLoggers() {
        String stored = CanonicalContent.of(cmd(FIELDS, null), List.of(LEVEL)).renderedEntries();

        assertFalse(stored.contains("logging.level"), stored);
    }

    @Test
    void aCarriedEntryThatIsNotALevelIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(FIELDS, SUPPORT),
                List.of(new OverlayKeys.Entry("management.endpoint.loggers.access", "\"read-write\""))));
    }

    @Test
    void aPackageFieldCannotClaimALevelPathAndAnOverlayKeyedLevelAtAnotherPathIsRefused() {
        List<RenderedEntryValue> claimed = new ArrayList<>(FIELDS);
        claimed.add(RenderedEntryValue.plain("c1", LEVEL.path(), "\"TRACE\""));
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(claimed, null)));

        List<RenderedEntryValue> forged = new ArrayList<>(FIELDS);
        forged.add(RenderedEntryValue.plain(OverlayKeys.INSTANCE_KEY, "logging.level.root", "\"TRACE\""));
        assertThrows(IllegalArgumentException.class, () -> CanonicalContent.of(cmd(forged, null)));
    }

    @Test
    void theLevelEntriesOfAStoredRenderedSetAreRecoveredAndNothingElse() {
        String stored = CanonicalContent.of(cmd(FIELDS, SUPPORT), List.of(LEVEL)).renderedEntries();

        assertEquals(List.of(LEVEL), CanonicalContent.overlayLevelsOf(stored));
        assertEquals(List.of(), CanonicalContent.overlayLevelsOf(CanonicalContent.of(cmd(FIELDS, SUPPORT)).renderedEntries()));
    }
}
