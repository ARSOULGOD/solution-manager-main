package com.solace.sm.dp.apply.runtime.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T3, {@code BE-D66}: the mapping from what the JCSMP probe saw to the result, offline. The JCSMP wire
 * behaviour (what {@code JCSMPException} actually carries) is {@code JcsmpBrokerProbeTest}.
 */
class SolaceBrokerConnectionTesterTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID PASSWORD_REF = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

    private final List<SolaceBrokerConnectionTester.Binding> seen = new ArrayList<>();
    private SolaceBrokerConnectionTester.Probe probe;
    private SolaceBrokerConnectionTester tester;

    @BeforeEach
    void wire() {
        probe = binding -> new SolaceBrokerConnectionTester.Steps(true, null);
        tester = new SolaceBrokerConnectionTester(b -> probe.run(b), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SolaceBrokerConnectionTester.Binding binding() {
        return new SolaceBrokerConnectionTester.Binding(TENANT, "tcps://broker.example.com", 55443, "default",
                "sm-client", PASSWORD_REF);
    }

    @Test
    void aConnectedSessionIsASuccessfulResultWithNoReason() {
        seen.add(binding());

        ConnectionTestResult result = tester.test(binding());

        assertTrue(result.success());
        assertNull(result.rejectionReason());
        assertEquals(NOW, result.checkedAt());
    }

    @Test
    void theProbeIsHandedExactlyWhatWasSubmitted() {
        probe = b -> {
            seen.add(b);
            return new SolaceBrokerConnectionTester.Steps(true, null);
        };
        tester = new SolaceBrokerConnectionTester(b -> probe.run(b), Clock.fixed(NOW, ZoneOffset.UTC));

        tester.test(binding());

        SolaceBrokerConnectionTester.Binding b = seen.get(0);
        assertEquals(TENANT, b.tenant());
        assertEquals("tcps://broker.example.com", b.host());
        assertEquals(55443, b.port());
        assertEquals("default", b.vpn());
        assertEquals("sm-client", b.username());
        assertEquals(PASSWORD_REF, b.passwordSecretRef());
    }

    @Test
    void aRejectedCredentialSurfacesTheBrokersOwnReason() {
        probe = b -> new SolaceBrokerConnectionTester.Steps(false, "Client username or password is incorrect");

        ConnectionTestResult result = tester.test(binding());

        assertFalse(result.success());
        assertEquals("Client username or password is incorrect", result.rejectionReason());
    }

    @Test
    void aProbeThatThrowsAnythingIsAResultNeverAnException() {
        for (RuntimeException failure : List.of(new IllegalStateException("boom"), new NullPointerException())) {
            probe = b -> {
                throw failure;
            };

            ConnectionTestResult result = tester.test(binding());

            assertFalse(result.success());
        }
    }

    @Test
    void noPasswordAttributeIsAValidBinding() {
        SolaceBrokerConnectionTester.Binding b = new SolaceBrokerConnectionTester.Binding(TENANT, "broker", 55555,
                "default", "sm-client", null);
        probe = binding -> new SolaceBrokerConnectionTester.Steps(true, null);

        assertTrue(tester.test(b).success());
    }

    @Test
    void theResultAndItsStringCarryNoCredentialReference() {
        String everything = tester.test(binding()).toString();

        assertFalse(everything.contains(PASSWORD_REF.toString()), everything);
    }
}
