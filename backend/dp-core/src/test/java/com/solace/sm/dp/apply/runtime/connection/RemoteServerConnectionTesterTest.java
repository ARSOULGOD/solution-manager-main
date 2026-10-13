package com.solace.sm.dp.apply.runtime.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.AuthMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T3, {@code BE-D66}: the mapping from what the sshj probe saw to the result, offline. The sshj wire
 * behaviour is {@code SshjConnectionAuthProbeTest}.
 */
class RemoteServerConnectionTesterTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID PASSWORD_REF = UUID.randomUUID();
    private static final UUID KEY_REF = UUID.randomUUID();
    private static final UUID PASSPHRASE_REF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final List<RemoteServerConnectionTester.Binding> seen = new ArrayList<>();
    private RemoteServerConnectionTester.Probe probe;
    private RemoteServerConnectionTester tester;

    @BeforeEach
    void wire() {
        probe = binding -> new RemoteServerConnectionTester.Steps(true, true, null);
        tester = new RemoteServerConnectionTester(b -> probe.run(b), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private RemoteServerConnectionTester.Binding basicBinding() {
        return new RemoteServerConnectionTester.Binding(TENANT, "vm1.example.com", 22, "sm", AuthMethod.BASIC,
                PASSWORD_REF, null, null);
    }

    private RemoteServerConnectionTester.Binding privateKeyBinding() {
        return new RemoteServerConnectionTester.Binding(TENANT, "vm1.example.com", 22, "sm", AuthMethod.PRIVATE_KEY,
                null, KEY_REF, PASSPHRASE_REF);
    }

    @Test
    void aConnectedAndAuthenticatedBasicAuthHostIsASuccessfulResult() {
        ConnectionTestResult result = tester.test(basicBinding());

        assertTrue(result.success());
        assertNull(result.rejectionReason());
        assertEquals(NOW, result.checkedAt());
    }

    @Test
    void aConnectedAndAuthenticatedPrivateKeyHostIsASuccessfulResult() {
        assertTrue(tester.test(privateKeyBinding()).success());
    }

    @Test
    void theProbeIsHandedExactlyWhatWasSubmitted() {
        probe = b -> {
            seen.add(b);
            return new RemoteServerConnectionTester.Steps(true, true, null);
        };
        tester = new RemoteServerConnectionTester(b -> probe.run(b), Clock.fixed(NOW, ZoneOffset.UTC));

        tester.test(privateKeyBinding());

        RemoteServerConnectionTester.Binding b = seen.get(0);
        assertEquals(TENANT, b.tenant());
        assertEquals("vm1.example.com", b.host());
        assertEquals(22, b.port());
        assertEquals("sm", b.username());
        assertEquals(AuthMethod.PRIVATE_KEY, b.authMethod());
        assertEquals(KEY_REF, b.privateKeySecretRef());
        assertEquals(PASSPHRASE_REF, b.passphraseSecretRef());
    }

    @Test
    void anUnreachableHostPassesNoStepAndSurfacesTheHostsOwnReason() {
        probe = b -> new RemoteServerConnectionTester.Steps(false, false, "Connection refused");

        ConnectionTestResult result = tester.test(basicBinding());

        assertFalse(result.success());
        assertEquals("Connection refused", result.rejectionReason());
    }

    @Test
    void aHostThatRefusesTheCredentialPassesConnectButNotAuthenticate() {
        probe = b -> new RemoteServerConnectionTester.Steps(true, false, "Exhausted available authentication methods");

        ConnectionTestResult result = tester.test(basicBinding());

        assertFalse(result.success());
        assertEquals("Exhausted available authentication methods", result.rejectionReason());
    }

    @Test
    void anInconsistentProbeIsReadCumulativelyNotTrustedStepByStep() {
        // a probe that claims authenticated without having connected (a defect, or a double)
        probe = b -> new RemoteServerConnectionTester.Steps(false, true, null);

        assertFalse(tester.test(basicBinding()).success());
    }

    @Test
    void aProbeThatThrowsAnythingIsAResultNeverAnException() {
        for (RuntimeException failure : List.of(new IllegalStateException("boom"), new NullPointerException())) {
            probe = b -> {
                throw failure;
            };

            assertFalse(tester.test(basicBinding()).success());
        }
    }

    @Test
    void privateKeyAuthWithoutAKeyReferenceIsRefusedAtTheBinding() {
        assertThrows(NullPointerException.class, () -> new RemoteServerConnectionTester.Binding(TENANT, "h", 22,
                "u", AuthMethod.PRIVATE_KEY, null, null, null));
    }

    @Test
    void theResultAndItsStringCarryNoCredentialReference() {
        String everything = tester.test(privateKeyBinding()).toString();

        assertFalse(everything.contains(KEY_REF.toString()), everything);
        assertFalse(everything.contains(PASSPHRASE_REF.toString()), everything);
    }
}
