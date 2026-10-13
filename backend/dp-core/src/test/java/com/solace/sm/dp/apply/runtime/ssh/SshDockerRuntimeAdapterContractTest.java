package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.AbstractRuntimeAdapterContractTest;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The runtime-neutral contract kit, over a deterministic SSH/SFTP transport double. */
class SshDockerRuntimeAdapterContractTest extends AbstractRuntimeAdapterContractTest {

    @Test
    void statusUsesTheCombinedRemoteSnapshotContract() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config", "/srv/cache/config", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
        FakeTransport transport = new FakeTransport(binding);
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport,
                TestHashers.keyed());

        adapter.status(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution")));

        assertEquals(1, transport.statusSnapshotReads);
    }

    /**
     * A dead/unreachable Docker daemon must classify distinctly from an absent container, so {@code prepare()} must
     * reach {@code ensureDockerAvailable} BEFORE {@code inspect} and surface its own refusal rather than masking it.
     */
    @Test
    void prepareChecksDockerAvailabilityBeforeInspectingTheContainer() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant());
        FakeTransport transport = new FakeTransport(binding);
        transport.dockerUnavailable = true;
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport,
                TestHashers.keyed());

        var result = adapter.prepare(placement, 1);

        var refused = org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.solace.sm.dp.apply.runtime.PreflightResult.Refused.class, result);
        assertEquals("ssh.docker.unavailable", refused.reason().token());
        assertEquals(0, transport.inspectCalls, "inspect must not run once availability is already refused");
    }

    @Test
    void aStoppedContainerIsStartedWithoutUsingRestart() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant());
        FakeTransport transport = new FakeTransport(binding);
        transport.running = false;
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport,
                TestHashers.keyed());

        adapter.startOrUpdate(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution")), 1);

        assertTrue(transport.running);
        assertEquals(0, transport.restartCalls);
        assertEquals(1, transport.startCalls);
    }

    /**
     * OR-32: the transport hands back BYTES and the adapter hashes them in the DP, per tenant. A transport that
     * reported a digest computed on the host could not even be expressed any more ({@code StatusSnapshot} has no
     * hash), and the hash the ladder sees is the keyed one.
     */
    @Test
    void statusHashesTheBytesTheTransportReturnedWithTheKeyedHasherForThePlacementsTenant() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config", "/srv/cache/config", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
        FakeTransport transport = new FakeTransport(binding);
        byte[] onTheHost = "password: hunter2\n".getBytes(StandardCharsets.UTF_8);
        transport.writeFileAtomically(binding, "/srv/cache/config/application.yml", onTheHost);
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);

        String observed = adapter.status(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"))).observedHash();

        assertEquals(hasher.hash(placement.tenant(), onTheHost), observed);
        assertTrue(observed.startsWith("hmacsha256v1:"), observed);
        assertNotEquals(hasher.hash(new TenantId(UUID.randomUUID()), onTheHost), observed,
                "another tenant's key would have produced a different hash for the same bytes");
    }

    @Test
    void aStatusSnapshotOfAnAbsentFileCarriesNoBytesAndNoHash() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config", "/srv/cache/config", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding,
                new FakeTransport(binding), TestHashers.keyed());

        assertNull(adapter.status(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"))).observedHash());
    }

    /**
     * T6.9 reverses T6.1's M9 pin: {@code status()} health no longer comes from {@code docker inspect}'s health field
     * at all. A placement with no executable probe reports {@code NOT_DECLARED} whatever the image says (Q-G1 (a));
     * with a probe it reports what the probe answered. {@code ProbeBackedHealthTest} carries the full matrix.
     */
    @Test
    void statusNoLongerReadsDockerInspectHealth() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant());
        FakeTransport transport = new FakeTransport(binding);
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport,
                TestHashers.keyed());
        TargetReplica replica = new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"));

        for (String raw : new String[] {"healthy", "starting", "unhealthy", "not_declared"}) {
            transport.health = raw;
            assertEquals(com.solace.sm.dp.apply.runtime.HealthSignal.NOT_DECLARED, adapter.status(replica).health(),
                    "docker inspect said " + raw);
        }
    }

    @Test
    void theContractKitStillPassesOverTheNewConstructor() {
        // the production constructor takes the credential source; the kit below runs over the package seam
        assertNotNull(new SshDockerRuntimeAdapter(ignored -> null, (tenant, ref) -> null, TestHashers.keyed(),
                new PermissiveTargetAddressPolicy()));
    }

    // ---- OR-102: the retained pre-image carries a recorded keyed fingerprint -----------------------------

    private static final String CONFIG = "/srv/cache/config/application.yml";
    private static final String PRE_IMAGE = "/srv/cache/config/.application.yml.sm-preimage-8";
    private static final String RECORD = "/srv/cache/config/.application.yml.sm-fp-8";
    private static final String PRIOR = "cache-engine:\n  ttl: 5m\n  solace:\n    password: hunter2\n";

    /** A placement whose file already holds {@link #PRIOR}, then generation 8 materialized over it. */
    private record Retained(SshDockerRuntimeAdapter adapter, FakeTransport transport, PlacementRef placement,
            RenderedConfig applied, ConfigHasher hasher) {
    }

    private static Retained retained() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant());
        FakeTransport transport = new FakeTransport(binding);
        transport.writeFileAtomically(binding, CONFIG, PRIOR.getBytes(StandardCharsets.UTF_8));
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);
        RenderedConfig applied = RenderedConfig.of(placement.tenant(),
                "cache-engine:\n  ttl: 9m\n".getBytes(StandardCharsets.UTF_8), hasher,
                java.util.Set.of("cache-engine.ttl"));
        adapter.materializeConfig(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution")), 8, applied);
        return new Retained(adapter, transport, placement, applied, hasher);
    }

    private static com.solace.sm.dp.apply.runtime.RollbackAssessment.Unknowable unknowable(
            com.solace.sm.dp.apply.runtime.RollbackAssessment assessment) {
        return org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.solace.sm.dp.apply.runtime.RollbackAssessment.Unknowable.class, assessment);
    }

    @Test
    void materializeRecordsAKeyedFingerprintBesideTheRetainedPreImageAndItCarriesNoValue() {
        Retained r = retained();

        String record = new String(r.transport().files.get(RECORD), StandardCharsets.UTF_8);
        assertTrue(record.contains("hmacsha256v1:") && record.contains("cache-engine.ttl"), record);
        assertTrue(!record.contains("hunter2") && !record.contains("password") && !record.contains("5m"), record);
        assertTrue(r.transport().files.containsKey(PRE_IMAGE));
    }

    @Test
    void anUnchangedPreImageAssessesRestorableAndRestores() {
        Retained r = retained();

        var assessment = org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.solace.sm.dp.apply.runtime.RollbackAssessment.Restorable.class,
                r.adapter().assessRollback(r.placement(), 8, r.applied().hash()));
        r.adapter().restore(r.placement(), 8, assessment.targetHash());

        assertEquals(PRIOR, new String(r.transport().files.get(CONFIG), StandardCharsets.UTF_8));
    }

    @Test
    void aPreImageAlteredBeforeTheAssessmentIsReportedByPathAndNotUsed() {
        Retained r = retained();
        r.transport().files.put(PRE_IMAGE, PRIOR.replace("5m", "1d").getBytes(StandardCharsets.UTF_8));

        var unknowable = unknowable(r.adapter().assessRollback(r.placement(), 8, r.applied().hash()));

        assertEquals("rollback.preimage.altered", unknowable.why().token());
        assertEquals(java.util.List.of("cache-engine.ttl"), unknowable.change().declaredPathsChanged());
        assertEquals(false, unknowable.change().otherContentChanged());
    }

    @Test
    void aPreImageAlteredInASecretPathIsOtherContentChangedAndNeverNamesIt() {
        Retained r = retained();
        r.transport().files.put(PRE_IMAGE, PRIOR.replace("hunter2", "hunter3").getBytes(StandardCharsets.UTF_8));

        var unknowable = unknowable(r.adapter().assessRollback(r.placement(), 8, r.applied().hash()));

        assertEquals(java.util.List.of(), unknowable.change().declaredPathsChanged());
        assertTrue(unknowable.change().otherContentChanged());
    }

    /** Altered before the assessment read it, so the hash the intent would carry matches the altered copy. */
    @Test
    void aRestoreOfAnAlteredPreImageIsRefusedEvenWithTheAlteredCopysOwnHash() {
        Retained r = retained();
        byte[] altered = PRIOR.replace("5m", "1d").getBytes(StandardCharsets.UTF_8);
        r.transport().files.put(PRE_IMAGE, altered);
        String alteredHash = r.hasher().hash(r.placement().tenant(), altered);
        String live = r.adapter().status(new TargetReplica(r.placement(),
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"))).observedHash();

        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                com.solace.sm.dp.apply.runtime.RuntimeAdapterException.class,
                () -> r.adapter().restore(r.placement(), 8, alteredHash));

        assertEquals("rollback.preimage.altered", thrown.errorClass().token());
        assertEquals(java.util.List.of("cache-engine.ttl"), thrown.preImageChange().orElseThrow().declaredPathsChanged());
        assertEquals("cache-engine:\n  ttl: 9m\n", new String(r.transport().files.get(CONFIG), StandardCharsets.UTF_8),
                "nothing was written");
        assertEquals(live, r.adapter().status(new TargetReplica(r.placement(),
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"))).observedHash());
    }

    @Test
    void aPreImageWithNoRecordIsNotUsedAndSaysSo() {
        Retained r = retained();
        r.transport().files.remove(RECORD);

        assertEquals("rollback.preimage.unrecorded",
                unknowable(r.adapter().assessRollback(r.placement(), 8, r.applied().hash())).why().token());
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                com.solace.sm.dp.apply.runtime.RuntimeAdapterException.class,
                () -> r.adapter().restore(r.placement(), 8, "hmacsha256v1:" + "0".repeat(64)));
        assertEquals("rollback.preimage.unrecorded", thrown.errorClass().token());
    }

    // ---- Fable APPLY-02: placement.retained_generations is honoured on the customer's host ---------------------

    private static SshDockerPlacement binding(TenantId tenant, int retainedGenerations) {
        return new SshDockerPlacement(tenant, "cache-demo.example", 22, "ec2-user", UUID.randomUUID(),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, retainedGenerations);
    }

    private static java.util.SortedSet<Long> retainedGenerationsOn(FakeTransport transport) {
        java.util.SortedSet<Long> found = new java.util.TreeSet<>();
        java.util.regex.Pattern ours = java.util.regex.Pattern.compile(
                "^/srv/cache/config/\\.application\\.yml\\.sm-(?:preimage|fp)-(\\d{1,18})(?:\\.absent)?$");
        for (String path : transport.files.keySet()) {
            java.util.regex.Matcher m = ours.matcher(path);
            if (m.matches()) {
                found.add(Long.parseLong(m.group(1)));
            }
        }
        return found;
    }

    private static void materialize(SshDockerRuntimeAdapter adapter, PlacementRef placement, ConfigHasher hasher,
            long generation) {
        RenderedConfig config = RenderedConfig.of(placement.tenant(),
                ("cache-engine:\n  ttl: " + generation + "m\n").getBytes(StandardCharsets.UTF_8), hasher,
                java.util.Set.of("cache-engine.ttl"));
        adapter.materializeConfig(new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution")), generation, config);
    }

    @Test
    void theHostKeepsOnlyTheNewestRetainedGenerationsOfPreImagesAndFingerprints() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant(), 3);
        FakeTransport transport = new FakeTransport(binding);
        transport.writeFileAtomically(binding, CONFIG, PRIOR.getBytes(StandardCharsets.UTF_8));
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);

        for (long generation = 1; generation <= 6; generation++) {
            materialize(adapter, placement, hasher, generation);
        }

        assertEquals(java.util.List.of(4L, 5L, 6L), java.util.List.copyOf(retainedGenerationsOn(transport)),
                "N+3 applies leave N=3 generations, each as a pre-image and a fingerprint");
        assertTrue(transport.files.containsKey("/srv/cache/config/.application.yml.sm-preimage-6"));
        assertTrue(transport.files.containsKey("/srv/cache/config/.application.yml.sm-fp-6"));
        assertTrue(!transport.files.containsKey("/srv/cache/config/.application.yml.sm-preimage-3"));
        assertTrue(!transport.files.containsKey("/srv/cache/config/.application.yml.sm-fp-3"));
    }

    @Test
    void anAbsenceMarkerIsPrunedAsAGenerationToo() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant(), 1);
        FakeTransport transport = new FakeTransport(binding);
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);

        materialize(adapter, placement, hasher, 1); // no file yet: an absence marker
        assertTrue(transport.files.containsKey("/srv/cache/config/.application.yml.sm-preimage-1.absent"));
        materialize(adapter, placement, hasher, 2);

        assertEquals(java.util.List.of(2L), java.util.List.copyOf(retainedGenerationsOn(transport)));
        assertTrue(!transport.files.containsKey("/srv/cache/config/.application.yml.sm-preimage-1.absent"));
    }

    @Test
    void theCurrentGenerationIsNeverPrunedAndNothingOfTheCustomersIs() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant(), 2);
        FakeTransport transport = new FakeTransport(binding);
        transport.writeFileAtomically(binding, CONFIG, PRIOR.getBytes(StandardCharsets.UTF_8));
        byte[] theirs = "keep".getBytes(StandardCharsets.UTF_8);
        for (String name : new String[] {".other.yml.sm-preimage-1", ".application.yml.sm-preimage-abc",
                ".application.yml.sm-preimage-", ".application.yml.sm-preimage-1.bak", ".application.yml.swp",
                ".application.yml.sm-preimage-99999999999999999999", "application.yml.sm-preimage-1"}) {
            transport.files.put("/srv/cache/config/" + name, theirs);
        }
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);

        materialize(adapter, placement, hasher, 10);
        materialize(adapter, placement, hasher, 11);
        materialize(adapter, placement, hasher, 12);
        // an older generation materialized after newer ones: it is the current one and must survive its own prune
        materialize(adapter, placement, hasher, 5);

        assertTrue(retainedGenerationsOn(transport).contains(5L), "the current generation's pre-image is never removed");
        assertEquals(3, retainedGenerationsOn(transport).size(), "the two newest plus the current");
        assertTrue(transport.files.containsKey(CONFIG));
        for (String name : new String[] {".other.yml.sm-preimage-1", ".application.yml.sm-preimage-abc",
                ".application.yml.sm-preimage-", ".application.yml.sm-preimage-1.bak", ".application.yml.swp",
                ".application.yml.sm-preimage-99999999999999999999", "application.yml.sm-preimage-1"}) {
            assertTrue(transport.files.containsKey("/srv/cache/config/" + name), name + " is not SM's and stays");
        }
    }

    @Test
    void aFailedListingNeverFailsAnApplyThatAlreadyWroteAndVerifiedTheFile() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        SshDockerPlacement binding = binding(placement.tenant(), 1);
        FakeTransport transport = new FakeTransport(binding);
        transport.listingFails = true;
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);

        materialize(adapter, placement, hasher, 1);
        materialize(adapter, placement, hasher, 2);

        assertEquals(java.util.List.of(1L, 2L), java.util.List.copyOf(retainedGenerationsOn(transport)),
                "housekeeping failed, so nothing was pruned, and the applies were not refused for it");
    }

    private static SshDockerPlacement binding(TenantId tenant) {
        return new SshDockerPlacement(tenant, "cache-demo.example", 22, "ec2-user", UUID.randomUUID(),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
    }

    @Override
    protected Fixture newFixture() {
        PlacementRef placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        // The kit declares a health signal, so the placement declares the probe that supplies it (T6.9).
        SshDockerPlacement binding = new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config", "/srv/cache/config", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10,
                com.solace.sm.dp.api.inventory.AcquisitionMode.VIA_SSH,
                new com.solace.sm.dp.api.inventory.HealthProbeSpec("actuator-health", "/actuator/health", 8081,
                        com.solace.sm.dp.api.inventory.ProbeStrength.LIVENESS));
        FakeTransport transport = new FakeTransport(binding);
        // The whole kit runs over the KEYED hasher: the adapter must hash read-back bytes itself, per tenant (OR-32).
        ConfigHasher hasher = TestHashers.keyed();
        SshDockerRuntimeAdapter adapter = new SshDockerRuntimeAdapter(ignored -> binding, transport, hasher);
        TargetReplica replica = new TargetReplica(placement,
                new com.solace.sm.dp.apply.model.ReplicaRef("cache-solution"));
        return new Fixture(adapter, placement, java.util.List.of(replica), binding.location(), true, hasher);
    }

    private static final class FakeTransport implements SshDockerTransport {
        private final Map<String, byte[]> files = new LinkedHashMap<>();
        private final SshDockerPlacement placement;
        private boolean running = true;
        private String health = "healthy";
        private Instant started = Instant.parse("2026-09-14T00:00:00Z");
        private int statusSnapshotReads;
        private int restartCalls;
        private int startCalls;
        private int inspectCalls;
        boolean dockerUnavailable;

        FakeTransport(SshDockerPlacement placement) {
            this.placement = placement;
        }

        @Override
        public void ensureDockerAvailable(SshDockerPlacement ignored) {
            if (dockerUnavailable) {
                throw new com.solace.sm.dp.apply.runtime.RuntimeAdapterException(
                        new com.solace.sm.dp.apply.model.ErrorClass("ssh.docker.unavailable"));
            }
        }

        @Override
        public ContainerSnapshot inspect(SshDockerPlacement ignored) {
            inspectCalls++;
            return new ContainerSnapshot("abcdef012345", "sha256:abcdef0123456789", running,
                    started.toString(), health);
        }

        @Override
        public StatusSnapshot statusSnapshot(SshDockerPlacement ignored) {
            statusSnapshotReads++;
            return SshDockerTransport.super.statusSnapshot(ignored);
        }

        @Override
        public void verifyConfigDirectoryWritable(SshDockerPlacement ignored) {
            // The in-memory fixture is intrinsically writable; this keeps the contract focused on
            // runtime semantics while SshjDockerTransport covers the real remote preflight path.
        }

        @Override
        public Optional<byte[]> readFile(SshDockerPlacement ignored, String path) {
            byte[] bytes = files.get(path);
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        @Override
        public void writeFileAtomically(SshDockerPlacement ignored, String path, byte[] content) {
            files.put(path, content.clone());
        }

        @Override
        public void deleteFile(SshDockerPlacement ignored, String path) {
            files.remove(path);
        }

        boolean listingFails;

        @Override
        public java.util.List<String> listHiddenFileNames(SshDockerPlacement ignored) {
            if (listingFails) {
                throw new com.solace.sm.dp.apply.runtime.RuntimeAdapterException(
                        new com.solace.sm.dp.apply.model.ErrorClass("ssh.config.list.failed"));
            }
            String directory = placement.remoteConfigDirectory() + "/";
            return files.keySet().stream().filter(path -> path.startsWith(directory))
                    .map(path -> path.substring(directory.length())).filter(name -> name.startsWith("."))
                    .sorted().toList();
        }

        @Override
        public boolean restart(SshDockerPlacement ignored) {
            restartCalls++;
            running = true;
            started = started.plusSeconds(1);
            return true;
        }

        @Override
        public boolean start(SshDockerPlacement ignored) {
            startCalls++;
            running = true;
            started = started.plusSeconds(1);
            return true;
        }

        @Override
        public boolean stop(SshDockerPlacement ignored) {
            boolean wasRunning = running;
            running = false;
            return wasRunning;
        }

        /** The declared probe's answer: a running container serves {@code UP}; a stopped one refuses the connection. */
        @Override
        public ProbeResult probe(SshDockerPlacement ignored, int port, String path, java.time.Duration timeout) {
            return running ? new ProbeResult(0, 200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8))
                    : new ProbeResult(7, 0, new byte[0]);
        }
    }
}
