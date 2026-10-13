package com.solace.sm.dp.api.inventory.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link PackageDeclarationSource} must meet, in-process or remote (plan 015 T4.1, W4 lane A review MINOR 11;
 * {@code AGW-D2}: every in-process port has contract tests a future remote adapter must pass). The data plane's discovery intake is the
 * caller; the answer is keyed by the organization the agent was ENROLLED into, so an answer for another organization's package is a
 * tenant leak ({@code I5}), not a convenience.
 */
public abstract class AbstractPackageDeclarationSourceContractTest {

    /** What a binding provides: the source under test, and a way to load a package version for one organization. */
    public interface World {

        PackageDeclarationSource source();

        /**
         * Loads, for {@code organizationId} only, a package version whose component declares an HTTP health probe and at least one
         * metric.
         */
        void loadReference(UUID organizationId, UUID catalogSolutionId, String packageVersion);
    }

    /** A fresh world for each case. */
    protected abstract World newWorld();

    private World world;

    @BeforeEach
    void freshWorld() {
        world = newWorld();
    }

    @Test
    void aVersionNobodyLoadedAnswersEmpty() {
        assertTrue(world.source().resolve(UUID.randomUUID(), UUID.randomUUID(), "1").isEmpty());
    }

    @Test
    void aLoadedVersionAnswersItsProbeAndMetricsWithEachMetricsPortMappedToItself() {
        UUID org = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        world.loadReference(org, entry, "1");

        PackageDeclarationSource.PackageDeclaration declared = world.source().resolve(org, entry, "1").orElseThrow();

        assertTrue(declared.probe().isPresent(), "the reference package declares an HTTP probe");
        assertFalse(declared.metrics().isNone(), "never MetricsDeclaration.none()");
        assertFalse(declared.metrics().definitions().isEmpty());
        declared.metrics().metricsPorts().forEach((source, host) ->
                assertEquals(source, host, "host ports are the data plane's to map, from the agent's facts"));
    }

    @Test
    void anotherVersionOrAnotherCatalogEntryAnswersEmpty() {
        UUID org = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        world.loadReference(org, entry, "1");

        assertTrue(world.source().resolve(org, entry, "2").isEmpty(), "another version");
        assertTrue(world.source().resolve(org, UUID.randomUUID(), "1").isEmpty(), "another catalog entry");
    }

    @Test
    void anotherOrganizationsPackageIsNeverAnswered() {
        UUID owner = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        world.loadReference(owner, entry, "1");

        UUID other = UUID.randomUUID();
        assertTrue(world.source().resolve(other, entry, "1").isEmpty(),
                "the same entry and version, loaded by another organization: never this one's answer (I5)");
        assertTrue(world.source().resolve(owner, entry, "1").isPresent(), "and the owner's still is");
        assertTrue(world.source().resolve(other, entry, "1").isEmpty(), "nor after the owner's was answered");
    }
}
