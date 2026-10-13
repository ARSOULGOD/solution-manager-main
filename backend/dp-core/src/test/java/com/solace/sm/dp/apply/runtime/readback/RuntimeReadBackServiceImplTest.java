package com.solace.sm.dp.apply.runtime.readback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.readback.FieldPathTable;
import com.solace.sm.dp.api.readback.FieldPathTable.Entry;
import com.solace.sm.dp.api.readback.NotAcceptedReason;
import com.solace.sm.dp.api.readback.ReadBackCandidate;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.readback.ReadBackRefusedException.Reason;
import com.solace.sm.dp.api.secret.SecretCompare;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.ConfigReadBack;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.OverlayKeys;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The data-plane half of D-ACCEPT over an in-memory host: placement scoping (I5), the read's classification, the V-4
 * comparison, the buffers' lifetime (I6), and the bytes-gap report. Nothing here opens a socket or a database.
 */
class RuntimeReadBackServiceImplTest {

    private static final UUID SECRET_REF = UUID.randomUUID();
    private static final String SECRET = "host-password-sentinel-77ce";
    private static final ConfigHasher HASHER = TestHashers.keyed();

    private static final FieldPathTable TABLE = new FieldPathTable(List.of(
            new Entry("i1", "max-size", "cache-engine.max-size", "int", false, null),
            new Entry("i1", "ttl", "cache-engine.ttl", "duration", false, null),
            new Entry("i1", "solace.password", "cache-engine.solace.password", "secret", true, SECRET_REF)));

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final InstanceId instance = new InstanceId(UUID.randomUUID());
    private final PlacementId placement = new PlacementId(UUID.randomUUID());
    private final PlacementRef ref = new PlacementRef(tenant, instance, placement);
    private final InMemoryRuntimeAdapter host = new InMemoryRuntimeAdapter(ref, false, "replica-1");
    private final TargetReplica replica = host.replicas().get(0);
    private final List<byte[]> candidatesSeen = new ArrayList<>();
    private SecretCompare.Outcome comparison = SecretCompare.Outcome.SAME;
    private final List<String> compared = new ArrayList<>();

    private java.util.Set<String> configuredLevelPaths = java.util.Set.of();
    private final RuntimeReadBackServiceImpl.ConfiguredLevelPaths levelPaths = (o, i) -> configuredLevelPaths;

    private final SecretCompare secrets = (org, secretRef, candidate) -> {
        candidatesSeen.add(candidate);
        compared.add(new String(candidate, StandardCharsets.UTF_8));
        return comparison;
    };

    private RuntimeReadBackServiceImpl service() {
        return new RuntimeReadBackServiceImpl((t, i, p) -> t.equals(tenant.value()) && i.equals(instance.value())
                && p.equals(placement.value()), () -> host, () -> null, secrets, HASHER, OverlayKeys::ownsBaseline, levelPaths);
    }

    private void hostFile(String yaml) {
        host.writeFileAsHuman(replica, yaml.getBytes(StandardCharsets.UTF_8), HASHER);
    }

    private ReadBackCandidate read() {
        return service().readBack(tenant.value(), instance.value(), placement.value(), TABLE);
    }

    // ---- I5: the placement ----------------------------------------------------------------------------------------

    @Test
    void aRetiredOrForeignPlacementIs404() {
        hostFile("cache-engine:\n  max-size: 1\n");
        for (UUID[] ids : new UUID[][] {
                {tenant.value(), instance.value(), UUID.randomUUID()},            // another placement
                {tenant.value(), UUID.randomUUID(), placement.value()},           // another instance's
                {UUID.randomUUID(), instance.value(), placement.value()}}) {      // another tenant's
            ReadBackRefusedException e = assertThrows(ReadBackRefusedException.class,
                    () -> service().readBack(ids[0], ids[1], ids[2], TABLE));
            assertEquals(Reason.PLACEMENT_NOT_FOUND, e.reason());
        }
        assertEquals(0, host.readConfigCalls(), "an unauthorised placement never reaches the host");
    }

    // ---- the read ---------------------------------------------------------------------------------------------------

    @Test
    void theFileIsReadMappedAndHashedAndNothingIsWritten() {
        hostFile("cache-engine:\n  max-size: 250000\n  ttl: 30m\nserver:\n  port: 9090\n");
        int materializesBefore = host.materializeCalls();

        ReadBackCandidate candidate = read();

        assertEquals(placement.value(), candidate.placementId());
        assertEquals(2, candidate.values().size());
        assertEquals("250000", candidate.values().stream().filter(v -> v.fieldKey().equals("max-size")).findFirst()
                .orElseThrow().valueJson());
        assertEquals("\"30m\"", candidate.values().stream().filter(v -> v.fieldKey().equals("ttl")).findFirst()
                .orElseThrow().valueJson());
        assertEquals("server.port", candidate.notAccepted().get(0).path());
        assertTrue(candidate.readBackHash().startsWith("hmacsha256v1:"), "the DP's keyed hash");
        assertEquals(materializesBefore, host.materializeCalls());
        assertEquals(0, host.restarts());
    }

    @Test
    void anAbsentFileAnUnreadableFileAndAnUnreachableHostAreDistinguished() {
        assertEquals(Reason.FILE_ABSENT, assertThrows(ReadBackRefusedException.class, this::read).reason());

        host.failStatusWith("ssh.unreachable");
        assertEquals(Reason.HOST_UNREADABLE, assertThrows(ReadBackRefusedException.class, this::read).reason(),
                "the host did not answer: retry (503), not a refusal of the file");
        host.failStatusWith("ssh.config.too.large");
        assertEquals(Reason.FILE_NOT_READABLE, assertThrows(ReadBackRefusedException.class, this::read).reason());
        host.failStatusWith("ssh.config.not.regular");
        assertEquals(Reason.FILE_NOT_READABLE, assertThrows(ReadBackRefusedException.class, this::read).reason());
        assertEquals(Reason.PLACEMENT_NOT_FOUND, RuntimeReadBackServiceImpl.classify("placement.unresolvable"));
        assertEquals(Reason.HOST_UNREADABLE, RuntimeReadBackServiceImpl.classify("target.unavailable"));
        // S811-13: a missing or unusable SSH credential is already the host not answering (the transport maps SM's
        // SecretException to these tokens), so it is 503, not a 500
        assertEquals(Reason.HOST_UNREADABLE, RuntimeReadBackServiceImpl.classify("ssh.credential.unavailable"));
        assertEquals(Reason.HOST_UNREADABLE, RuntimeReadBackServiceImpl.classify("ssh.credential.invalid"));
    }

    @Test
    void noAdapterIsTheHostNotAnswering() {
        RuntimeReadBackServiceImpl none = new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> null, () -> null,
                secrets, HASHER, OverlayKeys::ownsBaseline, levelPaths);
        assertEquals(Reason.HOST_UNREADABLE, assertThrows(ReadBackRefusedException.class,
                () -> none.readBack(tenant.value(), instance.value(), placement.value(), TABLE)).reason());
    }

    @Test
    void aParserRefusalPropagatesWithItsReason() {
        hostFile("a: &x 1\nb: *x\n");
        assertEquals(Reason.ANCHOR, assertThrows(ReadBackRefusedException.class, this::read).reason());
    }

    // ---- V-4: compare, never store ----------------------------------------------------------------------------------

    @Test
    void aSecretFieldIsComparedInMemoryAndReportedSameOrDiffersWithNoValue() {
        hostFile("cache-engine:\n  max-size: 1\n  solace:\n    password: " + SECRET + "\n");

        comparison = SecretCompare.Outcome.SAME;
        ReadBackCandidate same = read();
        comparison = SecretCompare.Outcome.DIFFERS;
        ReadBackCandidate differs = read();

        ReadBackCandidate.NotAccepted a = same.notAccepted().get(0);
        assertEquals(NotAcceptedReason.SECRET_NOT_READ, a.reason());
        assertEquals(SecretCompare.Outcome.SAME, a.comparison());
        assertEquals(SecretCompare.Outcome.DIFFERS, differs.notAccepted().get(0).comparison());
        assertEquals(List.of(SECRET, SECRET), compared, "the comparer received exactly the file's text, for the instance's ref");
        for (ReadBackCandidate c : List.of(same, differs)) {
            assertFalse(c.toString().contains(SECRET));
            assertFalse(c.notAccepted().toString().contains(SECRET));
            assertFalse(c.values().toString().contains(SECRET));
            assertTrue(c.values().stream().noneMatch(v -> v.valueJson().contains(SECRET)), "never a mapped value");
        }
    }

    @Test
    void aSecretValueIsZeroedAfterTheCompare() {
        hostFile("cache-engine:\n  solace:\n    password: " + SECRET + "\n");
        read();
        assertEquals(1, candidatesSeen.size());
        assertArrayEquals(new byte[SECRET.length()], candidatesSeen.get(0),
                "the candidate bytes handed to the compare port are zeroed once it answers");
    }

    @Test
    void aSecretNodeThatIsNotAScalarOrWithNoStoredReferenceIsDifferWithoutAsking() {
        hostFile("cache-engine:\n  solace:\n    password: {nested: " + SECRET + "}\n");
        assertEquals(SecretCompare.Outcome.DIFFERS, read().notAccepted().get(0).comparison());
        assertTrue(compared.isEmpty(), "a map cannot equal a stored string, and is not offered to the comparer");

        FieldPathTable noRef = new FieldPathTable(List.of(
                new Entry("i1", "solace.password", "cache-engine.solace.password", "secret", true, null)));
        hostFile("cache-engine:\n  solace:\n    password: " + SECRET + "\n");
        ReadBackCandidate c = service().readBack(tenant.value(), instance.value(), placement.value(), noRef);
        assertEquals(SecretCompare.Outcome.DIFFERS, c.notAccepted().get(0).comparison(),
                "SM holds no secret here and the file carries one");
        assertTrue(compared.isEmpty());
    }

    @Test
    void anUnavailableComparisonIsNoClaim() {
        hostFile("cache-engine:\n  solace:\n    password: x\n");
        comparison = SecretCompare.Outcome.UNAVAILABLE;
        assertNull(read().notAccepted().get(0).comparison());
    }

    // ---- I6: the buffers' lifetime ---------------------------------------------------------------------------------

    @Test
    void theConfigReadBackBuffersAreZeroedAfterUse() {
        hostFile("cache-engine:\n  solace:\n    password: " + SECRET + "\n");
        List<ConfigReadBack> opened = new ArrayList<>();
        List<byte[]> owned = new ArrayList<>();
        // wrap through a delegating adapter that keeps the very array the ConfigReadBack owns
        com.solace.sm.dp.apply.runtime.RuntimeAdapter capturing = new DelegatingAdapter(host) {
            @Override
            public ConfigReadBack readConfig(TargetReplica r) {
                byte[] mine = host.readConfig(r).bytes();
                owned.add(mine);
                ConfigReadBack back = new ConfigReadBack(mine, HASHER.hash(tenant, mine));
                opened.add(back);
                return back;
            }
        };
        RuntimeReadBackServiceImpl s = new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> capturing, () -> null,
                secrets, HASHER, OverlayKeys::ownsBaseline, levelPaths);

        s.readBack(tenant.value(), instance.value(), placement.value(), TABLE);

        assertTrue(opened.get(0).isClosed());
        assertArrayEquals(new byte[owned.get(0).length], owned.get(0), "the array the read-back owned is zero afterwards");
    }

    @Test
    void theBuffersAreZeroedEvenWhenTheFileIsRefused() {
        hostFile("a: &x 1\n");
        List<byte[]> owned = new ArrayList<>();
        com.solace.sm.dp.apply.runtime.RuntimeAdapter capturing = new DelegatingAdapter(host) {
            @Override
            public ConfigReadBack readConfig(TargetReplica r) {
                byte[] mine = host.readConfig(r).bytes();
                owned.add(mine);
                return new ConfigReadBack(mine, "h");
            }
        };
        RuntimeReadBackServiceImpl s = new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> capturing, () -> null,
                secrets, HASHER, OverlayKeys::ownsBaseline, levelPaths);
        assertThrows(ReadBackRefusedException.class,
                () -> s.readBack(tenant.value(), instance.value(), placement.value(), TABLE));
        assertArrayEquals(new byte[owned.get(0).length], owned.get(0));
    }

    /** m3: the read runs in the caller's thread with no database transaction open. */
    @Test
    void theReadHoldsNoDatabaseTransaction() {
        hostFile("cache-engine:\n  max-size: 1\n");
        AtomicBoolean transactionDuringRead = new AtomicBoolean(true);
        com.solace.sm.dp.apply.runtime.RuntimeAdapter observing = new DelegatingAdapter(host) {
            @Override
            public ConfigReadBack readConfig(TargetReplica r) {
                transactionDuringRead.set(TransactionSynchronizationManager.isActualTransactionActive());
                return host.readConfig(r);
            }
        };
        new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> observing, () -> null, secrets, HASHER,
                OverlayKeys::ownsBaseline, levelPaths).readBack(tenant.value(), instance.value(), placement.value(), TABLE);
        assertFalse(transactionDuringRead.get());
    }

    // ---- the bytes gap (X-1) -----------------------------------------------------------------------------------------

    @Test
    void acceptedBytesDifferingFromRenderedIsReported() {
        byte[] rendered = "cache-engine:\n  max-size: 250000\n".getBytes(StandardCharsets.UTF_8);
        RenderedConfig config = RenderedConfig.of(tenant, rendered, HASHER);

        hostFile("cache-engine:\n  max-size: 250000\n");
        ReadBackCandidate identical = read();
        hostFile("cache-engine:\n   max-size: 250000   # a human's comment\n");
        ReadBackCandidate edited = read();

        RuntimeReadBackServiceImpl s = new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> host,
                () -> (placementRef, generation) -> config, secrets, HASHER, OverlayKeys::ownsBaseline, levelPaths);
        assertFalse(s.fileDiffersFromRendering(tenant.value(), instance.value(), placement.value(), 3, identical),
                "byte-identical to SM's own rendering");
        assertTrue(s.fileDiffersFromRendering(tenant.value(), instance.value(), placement.value(), 3, edited),
                "a cosmetic edit: the same values, different bytes, still drifted by hash");
    }

    @Test
    void whenTheRenderingCannotBeProducedOrComparedTheAnswerIsMayDifferNeverTheSame() {
        hostFile("cache-engine:\n  max-size: 1\n");
        ReadBackCandidate candidate = read();
        assertTrue(new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> host, () -> null, secrets, HASHER,
                OverlayKeys::ownsBaseline, levelPaths).fileDiffersFromRendering(tenant.value(), instance.value(), placement.value(), 1,
                candidate));
        assertTrue(new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> host,
                () -> (p, g) -> { throw new IllegalStateException("render failed"); }, secrets, HASHER,
                OverlayKeys::ownsBaseline, levelPaths).fileDiffersFromRendering(tenant.value(), instance.value(), placement.value(), 1,
                candidate));
        ConfigHasher unverifiable = new ConfigHasher() {
            @Override
            public String hash(TenantId t, byte[] bytes) {
                return HASHER.hash(t, bytes);
            }

            @Override
            public boolean verifies(TenantId t, String hash, byte[] bytes) {
                throw new com.solace.sm.dp.apply.runtime.ConfigHashUnverifiableException(1);
            }
        };
        assertTrue(new RuntimeReadBackServiceImpl((t, i, p) -> true, () -> host,
                () -> (p, g) -> RenderedConfig.of(tenant, "x\n".getBytes(StandardCharsets.UTF_8), HASHER), secrets,
                unverifiable, OverlayKeys::ownsBaseline, levelPaths).fileDiffersFromRendering(tenant.value(), instance.value(),
                placement.value(), 1, candidate));
    }

    /** Delegates every operation, so a test overrides exactly the one it is observing. */
    private abstract static class DelegatingAdapter implements com.solace.sm.dp.apply.runtime.RuntimeAdapter {
        final InMemoryRuntimeAdapter host;

        DelegatingAdapter(InMemoryRuntimeAdapter host) {
            this.host = host;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            return host.replicas(p);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.PreflightResult prepare(PlacementRef p, long g) {
            return host.prepare(p, g);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
            return host.materializeConfig(r, g, c);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult rematerializeIfCurrent(TargetReplica r, long g,
                RenderedConfig c, String h) {
            return host.rematerializeIfCurrent(r, g, c, h);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult startOrUpdate(TargetReplica r, long g) {
            return host.startOrUpdate(r, g);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult stop(TargetReplica r) {
            return host.stop(r);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RuntimeStatus status(TargetReplica r) {
            return host.status(r);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RollbackAssessment assessRollback(PlacementRef p, long g, String h) {
            return host.assessRollback(p, g, h);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RestoreResult restore(PlacementRef p, long g, String h) {
            return host.restore(p, g, h);
        }
    }

    // ---- T9.4: a level SM configured is SM's in the file; another logger's level is the operator's -------------------

    @Test
    void aLevelThisGenerationConfiguresIsSmsAndAnotherLoggersLevelIsTheOperators() {
        configuredLevelPaths = java.util.Set.of("logging.level.com.solace.solutions.cache");
        hostFile("cache-engine:\n  max-size: 1\nlogging:\n  level:\n    com.solace.solutions.cache: DEBUG\n"
                + "    com.other.logger: TRACE\n");

        ReadBackCandidate candidate = read();

        java.util.Map<String, String> reasons = new java.util.TreeMap<>();
        candidate.notAccepted().forEach(n -> reasons.put(n.path(), n.reason().name()));
        assertEquals("SM_OWNED_OVERLAY", reasons.get("logging.level.com.solace.solutions.cache"));
        assertEquals("UNDECLARED_KEY", reasons.get("logging.level.com.other.logger"));
        assertEquals(1, candidate.values().size());
    }

    @Test
    void withNoLevelConfiguredEveryLoggingLevelKeyInTheFileIsTheOperators() {
        hostFile("logging:\n  level:\n    com.solace.solutions.cache: DEBUG\n");

        ReadBackCandidate candidate = read();

        assertEquals("UNDECLARED_KEY", candidate.notAccepted().get(0).reason().name());
    }
}
