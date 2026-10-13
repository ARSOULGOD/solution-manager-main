package com.solace.sm.dp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.ObservedInstance;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ObservedStateServiceImpl} and {@link ConfigLadderReader} (plan 010 T5.3), over a fake config of record. The
 * persistence behind {@code latestDraft} is {@code ConfigRecordServiceIT}'s; what is pinned here is the ladder's
 * honesty about what the apply path has not yet reported.
 */
class ObservedStateServiceImplTest {

    private static final String DIGEST = "hmacsha256v1:" + "a".repeat(64);

    private final UUID organization = UUID.randomUUID();
    private final UUID instance = UUID.randomUUID();
    private Optional<ConfigDraft> latest = Optional.empty();
    private UUID askedOrganization;
    private UUID askedInstance;
    private TenantId askedTenant;

    private final ConfigRecordService config = new ConfigRecordService() {
        @Override
        public StageResult stage(UUID organizationId, UUID instanceId, StageCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StageResult copyForward(UUID organizationId, UUID instanceId, long fromGeneration,
                com.solace.sm.dp.api.config.GenerationOrigin origin, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StageResult> copyLatestForward(UUID organizationId, UUID instanceId,
                com.solace.sm.dp.api.config.GenerationOrigin origin, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StageResult> copyLatestWithLogLevel(UUID organizationId, UUID instanceId, UUID placementId, String loggerPrefix,
                com.solace.sm.dp.api.logs.LoggerLevel level, UUID actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Map<String, com.solace.sm.dp.api.logs.LoggerLevel> configuredLogLevels(UUID organizationId,
                UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ConfigDraft> latestDraft(UUID organizationId, UUID instanceId) {
            askedOrganization = organizationId;
            askedInstance = instanceId;
            return latest;
        }

        @Override
        public List<GenerationView> history(UUID organizationId, UUID instanceId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GenerationView> generation(UUID organizationId, UUID instanceId, long generation) {
            throw new UnsupportedOperationException();
        }
    };

    private List<com.solace.sm.dp.api.health.PlacementHealthView> polled = List.of();

    private final com.solace.sm.dp.api.health.HealthService health = (organizationId, instanceId) -> {
        askedOrganization = organizationId;
        askedInstance = instanceId;
        return new com.solace.sm.dp.api.health.HealthView(polled, 0);
    };

    private final LadderRows ladderRows = new LadderRows() {
        @Override
        public LadderEvidence evidence(TenantId tenant, InstanceId instance) {
            askedTenant = tenant;
            return LadderEvidence.none();
        }

        @Override
        public java.util.OptionalLong storeReachedRung(TenantId tenant, InstanceId instance, LadderRung expected,
                LadderRung next) {
            throw new UnsupportedOperationException("a read never writes");
        }
    };

    private final ObservedStateServiceImpl service = new ObservedStateServiceImpl(
            new ConfigLadderReader(config, ladderRows), health);

    private static com.solace.sm.dp.api.health.PlacementHealthView polled(UUID placement,
            com.solace.sm.dp.api.health.HealthState state) {
        return new com.solace.sm.dp.api.health.PlacementHealthView(placement, state, java.time.Instant.EPOCH, 7L);
    }

    @Test
    void replicasAreThePersistedPlacementHealthInTheProjectionsFourValueVocabulary() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        polled = List.of(polled(a, com.solace.sm.dp.api.health.HealthState.GREEN),
                polled(b, com.solace.sm.dp.api.health.HealthState.RESTART_EXPECTED));

        var replicas = service.observed(organization, instance).replicas();

        assertEquals(List.of(new com.solace.sm.dp.api.config.ObservedInstance.Replica(a.toString(), "GREEN"),
                new com.solace.sm.dp.api.config.ObservedInstance.Replica(b.toString(), "RESTART_EXPECTED")), replicas);
    }

    @Test
    void everyNoAnswerStateIsRedForTheProjectionNeverGreen() {
        // ReplicaHealth has four values; the projection treats anything that is not RED / RESTART_EXPECTED /
        // INACTIVE_REPLICA as GREEN, so an unreachable placement MUST be mapped here or it would read as healthy.
        // getHealth keeps the full vocabulary; this is the designer's rollup, where "no response" is red (UI-D9).
        for (var state : List.of(com.solace.sm.dp.api.health.HealthState.UNREACHABLE,
                com.solace.sm.dp.api.health.HealthState.NOT_EXPOSED, com.solace.sm.dp.api.health.HealthState.MISSING,
                com.solace.sm.dp.api.health.HealthState.BOOTSTRAP, com.solace.sm.dp.api.health.HealthState.RED)) {
            polled = List.of(polled(UUID.randomUUID(), state));
            assertEquals("RED", service.observed(organization, instance).replicas().get(0).health(), state.name());
        }
    }

    @Test
    void aPlacementWithNoDeclaredProbeIsNotAReplicaObservationAndSoNeverRed() {
        // OR-101: NOT_APPLICABLE is a package property. The designer's rollup must not count it as RED, and must not
        // invent a GREEN for it either: it is simply not an observation of anything.
        UUID probed = UUID.randomUUID();
        polled = List.of(polled(UUID.randomUUID(), com.solace.sm.dp.api.health.HealthState.NOT_APPLICABLE),
                polled(probed, com.solace.sm.dp.api.health.HealthState.GREEN));

        var replicas = service.observed(organization, instance).replicas();

        assertEquals(List.of(new com.solace.sm.dp.api.config.ObservedInstance.Replica(probed.toString(), "GREEN")),
                replicas);
    }

    @Test
    void anInstanceNothingHasPolledYetHasNoReplicas() {
        polled = List.of();
        assertTrue(service.observed(organization, instance).replicas().isEmpty());
    }
    private void staged(long generation) {
        latest = Optional.of(new ConfigDraft(generation, "cache", "1", "{\"instances\":[]}", "{}", DIGEST));
    }

    @Test
    void anInstanceWithNoGenerationHasReachedNothingBeyondDesiredAndSaysSo() {
        ConfigLadderView ladder = service.ladder(organization, instance);

        assertEquals(LadderRung.DESIRED, ladder.reached());
        assertEquals(List.of(LadderRung.values()), ladder.rungs().stream().map(ConfigLadderView.Rung::rung).toList());
        assertTrue(ladder.rungs().stream().noneMatch(ConfigLadderView.Rung::present));
        assertTrue(ladder.rungs().stream().allMatch(r -> r.generation() == null && r.configHash() == null));
    }

    @Test
    void aStagedGenerationIsDesiredAndRenderedWithItsKeyedDigest() {
        staged(3);

        ConfigLadderView ladder = service.ladder(organization, instance);

        assertEquals(LadderRung.RENDERED, ladder.reached());
        for (LadderRung rung : List.of(LadderRung.DESIRED, LadderRung.RENDERED)) {
            ConfigLadderView.Rung entry = ladder.rungs().get(rung.ordinal());
            assertTrue(entry.present(), rung.wire());
            assertEquals(3L, entry.generation());
            assertEquals(DIGEST, entry.configHash());
        }
    }

    @Test
    void withNoJournalEvidencePersistedRestartObservedAndEffectiveVerifiedAreAbsentAndTheTopRungIsDeclared() {
        staged(1);

        ConfigLadderView ladder = service.ladder(organization, instance);

        for (LadderRung rung : List.of(LadderRung.PERSISTED, LadderRung.RESTART_OBSERVED,
                LadderRung.EFFECTIVE_VERIFIED)) {
            ConfigLadderView.Rung entry = ladder.rungs().get(rung.ordinal());
            assertFalse(entry.present(), rung.wire());
            assertNull(entry.generation());
            assertNull(entry.configHash());
        }
        assertEquals(ConfigLadder.EFFECTIVE_UNVERIFIED, ladder.degradedReason(),
                "a ladder that cannot report a rung says so, and says the real reason");
    }

    @Test
    void theReadsAreScopedToTheOrganizationAndInstanceTheyWereAskedFor() {
        staged(1);
        service.ladder(organization, instance);
        assertEquals(organization, askedOrganization);
        assertEquals(instance, askedInstance);
        assertEquals(new TenantId(organization), askedTenant, "the journal is read for the same organization");
    }

    @Test
    void aDpRemoteInstanceIsVersionUnverifiedWithNoReplicasUntilHealthIsPolled() {
        ObservedInstance observed = service.observed(organization, instance);

        assertEquals("VERSION_UNVERIFIED", observed.versionState());
        assertNull(observed.declaredVersion());
        assertNull(observed.observedVersion());
        assertTrue(observed.replicas().isEmpty());
    }

    @Test
    void theObservedConfigStateCarriesOnlyTheReachedRungsAsKeyedDigests() {
        staged(2);

        ObservedInstance.Rungs rungs = service.observed(organization, instance).config();

        assertEquals(DIGEST, rungs.desired());
        assertEquals(DIGEST, rungs.rendered());
        assertNull(rungs.persisted());
        assertNull(rungs.restartObserved());
        assertNull(rungs.effectiveVerified());
    }

    @Test
    void withNoGenerationNoRungIsObserved() {
        ObservedInstance.Rungs rungs = service.observed(organization, instance).config();

        assertNull(rungs.desired());
        assertNull(rungs.rendered());
    }
}
