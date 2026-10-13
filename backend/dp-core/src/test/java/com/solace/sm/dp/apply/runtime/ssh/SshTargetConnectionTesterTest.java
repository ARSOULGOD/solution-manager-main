package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.5, {@code testTargetConnection} (D-REG section 5.1 T6, OQ-9 (a)): the data plane's mapping from what an SSH
 * probe saw to the result, offline. The sshj code itself is {@code SshjTargetSshProbeTest} (its classifiers) and
 * {@code TargetConnectionTestIT} (a real {@code sshd}); the Docker daemon and the group are live gate L4.
 *
 * <p>What is proven here: every failure is a RESULT, never an exception (no {@code 5xx}); the steps are cumulative; the
 * tenant of the lookup and of the credential is the caller's; the version is kept only if it is a plain version string;
 * and the target must exist and be live.
 */
class SshTargetConnectionTesterTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID CREDENTIAL = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final String PIN = "SHA256:" + "P".repeat(43);

    private final List<SshTargetConnectionTester.Binding> seen = new ArrayList<>();
    private SshTargetConnectionTester.Probe probe;
    private TargetConnectionRecord record;
    private SshTargetConnectionTester tester;

    @BeforeEach
    void wire() {
        record = new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 2222, "sm", PIN, CREDENTIAL, "/srv/sm",
                "sm-", UUID.randomUUID(), NOW, null);
        probe = binding -> {
            seen.add(binding);
            return new SshTargetConnectionTester.Steps(true, true, 0, "28.5.1\n");
        };
        tester = new SshTargetConnectionTester(rows(), (b) -> probe.run(b), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TargetConnectionRows rows() {
        return (TargetConnectionRows) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {TargetConnectionRows.class}, (p, method, args) -> {
                    if (method.getName().equals("find")) {
                        return args[0].equals(ORG) && args[1].equals(TARGET) ? Optional.ofNullable(record)
                                : Optional.empty();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Plan 015 T3.5 (V028): an agent target has no address; the tester refuses it by name and the probe is never reached. */
    @Test
    void anAgentTargetIsRefusedByNameAndNoProbeIsRun() {
        record = new TargetConnectionRecord(ORG, TARGET, null, 0, null, null, null, "/srv/sm", "sm-", UUID.randomUUID(), NOW, null,
                null, null, UUID.randomUUID());

        assertThrows(com.solace.sm.dp.api.inventory.TargetTransportUnsupportedException.class, () -> tester.test(ORG, TARGET));
        assertTrue(seen.isEmpty(), "no connector was constructed");
    }

    @Test
    void aReachableAuthenticatedHostWithADockerDaemonPassesEveryStepAndReportsTheVersion() {
        TargetConnectionTestResult result = tester.test(ORG, TARGET);

        assertTrue(result.hostKeyPinned());
        assertTrue(result.authenticated());
        assertTrue(result.dockerAvailable());
        assertEquals("28.5.1", result.dockerServerVersion());
        assertEquals(NOW, result.checkedAt());
    }

    @Test
    void theProbeIsHandedTheStoredRecordForTheCallersTenantAndNothingElse() {
        tester.test(ORG, TARGET);

        SshTargetConnectionTester.Binding binding = seen.get(0);
        assertEquals(new TenantId(ORG), binding.tenant());
        assertEquals("vm1.example.com", binding.host());
        assertEquals(2222, binding.port());
        assertEquals("sm", binding.sshUser());
        assertEquals(CREDENTIAL, binding.credentialRef());
        assertEquals(PIN, binding.pinnedHostKeyFingerprint());
    }

    @Test
    void theProbeIsHandedTheRecordedTypeOfThePinnedKeyOrNoneWhenItIsNotKnown() {
        tester.test(ORG, TARGET);
        assertNull(seen.get(0).pinnedHostKeyType());

        record = record.withHostKeyType(com.solace.sm.dp.api.inventory.HostKeyType.ECDSA_NISTP256);
        tester.test(ORG, TARGET);
        assertEquals(com.solace.sm.dp.api.inventory.HostKeyType.ECDSA_NISTP256, seen.get(1).pinnedHostKeyType());
    }

    @Test
    void aHostThatPresentsAnotherKeyThanThePinPassesNothing() {
        probe = binding -> new SshTargetConnectionTester.Steps(false, false, null, null);

        TargetConnectionTestResult result = tester.test(ORG, TARGET);

        assertFalse(result.hostKeyPinned());
        assertFalse(result.authenticated());
        assertFalse(result.dockerAvailable());
        assertNull(result.dockerServerVersion());
    }

    @Test
    void anInconsistentProbeIsReadCumulativelyNotTrustedStepByStep() {
        // a probe that claims to have authenticated to a host whose key it did not verify (a defect, or a double)
        probe = binding -> new SshTargetConnectionTester.Steps(false, true, 0, "28.5.1");

        TargetConnectionTestResult result = tester.test(ORG, TARGET);

        assertFalse(result.hostKeyPinned());
        assertFalse(result.authenticated());
        assertFalse(result.dockerAvailable());
    }

    @Test
    void aKeyTheHostRefusesPassesThePinAndNothingAfterIt() {
        probe = binding -> new SshTargetConnectionTester.Steps(true, false, null, null);

        TargetConnectionTestResult result = tester.test(ORG, TARGET);

        assertTrue(result.hostKeyPinned());
        assertFalse(result.authenticated());
        assertFalse(result.dockerAvailable());
    }

    @Test
    void aStoppedDaemonOrAUserWithoutTheDockerGroupIsDockerUnavailableNotAnError() {
        // docker version exits 1 in both cases (MF-R2: "Cannot connect to the Docker daemon" / "permission denied")
        probe = binding -> new SshTargetConnectionTester.Steps(true, true, 1, "");

        TargetConnectionTestResult result = tester.test(ORG, TARGET);

        assertTrue(result.authenticated());
        assertFalse(result.dockerAvailable());
        assertNull(result.dockerServerVersion());
    }

    @Test
    void aCommandThatDidNotFinishIsDockerUnavailable() {
        probe = binding -> new SshTargetConnectionTester.Steps(true, true, null, null);

        assertFalse(tester.test(ORG, TARGET).dockerAvailable());
    }

    @Test
    void anAnswerThatIsNoVersionIsNotTakenForOne() {
        for (String output : new String[] {"", "\n", "Error response from daemon: x y", "28.5.1 extra", "../../etc",
                "a".repeat(200)}) {
            probe = binding -> new SshTargetConnectionTester.Steps(true, true, 0, output);

            TargetConnectionTestResult result = tester.test(ORG, TARGET);

            assertFalse(result.dockerAvailable(), "'" + output + "'");
            assertNull(result.dockerServerVersion());
        }
    }

    @Test
    void onlyTheFirstLineOfTheAnswerIsTheVersion() {
        probe = binding -> new SshTargetConnectionTester.Steps(true, true, 0, "20.10.24+dfsg1\nsecond line\n");

        assertEquals("20.10.24+dfsg1", tester.test(ORG, TARGET).dockerServerVersion());
    }

    @Test
    void aProbeThatThrowsAnythingIsAResultNeverAnException() {
        for (RuntimeException failure : List.of(new RuntimeAdapterException(new ErrorClass("target.address.refused")),
                new RuntimeAdapterException(new ErrorClass("ssh.connection.failed")),
                new IllegalStateException("boom"), new NullPointerException())) {
            probe = binding -> {
                throw failure;
            };

            TargetConnectionTestResult result = tester.test(ORG, TARGET);

            assertFalse(result.hostKeyPinned());
            assertFalse(result.authenticated());
            assertFalse(result.dockerAvailable());
        }
    }

    @Test
    void anAbsentOrRetiredTargetIsNotFoundAndNothingIsConnectedTo() {
        assertThrows(NoSuchElementException.class, () -> tester.test(ORG, UUID.randomUUID()));
        assertThrows(NoSuchElementException.class, () -> tester.test(UUID.randomUUID(), TARGET));
        record = new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN, CREDENTIAL, "/srv/sm", "sm-",
                UUID.randomUUID(), NOW, NOW);
        assertThrows(NoSuchElementException.class, () -> tester.test(ORG, TARGET));
        assertEquals(List.of(), seen);
    }

    @Test
    void theResultAndItsStringCarryNoCredentialReference() {
        String everything = tester.test(ORG, TARGET).toString();

        assertFalse(everything.contains(CREDENTIAL.toString()), everything);
        assertFalse(everything.contains("BEGIN"), everything);
    }
}
