package com.solace.sm.dp.meshbridge.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester.Binding;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester.Steps;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.StubTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T3/T4, {@code BE-D66}: the real JCSMP classifiers, without a live broker — an unreachable host is
 * enough to prove a session failure is a {@link Steps} and never an exception, and that the credential's purpose
 * is {@code CONNECTION_CREDENTIAL}. Fix round 1 (owner ruling): the submitted host must clear
 * {@link com.solace.sm.dp.inventory.TargetAddressPolicy} before anything connects or any secret is resolved.
 * Fix round 1, item 0: moved here from {@code com.solace.sm.dp.apply.runtime.connection} with the class under
 * test, to stay confined to {@code com.solace.sm.dp.meshbridge..} ({@code PlaneBoundaryTest}).
 */
class JcsmpBrokerProbeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID PASSWORD_REF = UUID.randomUUID();

    /** Everything resolves and clears the policy — these tests are about the JCSMP transport, not the address rule. */
    private static final TargetAddressPolicy PERMISSIVE = new PermissiveTargetAddressPolicy();

    /** A port nothing listens on: bound then released immediately, so the loopback refuses the connection fast. */
    private static int closedLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void anUnreachableBrokerIsAFailedResultNotAnException() throws IOException {
        int port = closedLocalPort();
        JcsmpBrokerProbe probe = new JcsmpBrokerProbe(fixedPassword("secret"), PERMISSIVE);
        Binding binding = new Binding(TENANT, "tcp://127.0.0.1", port, "default", "sm-client", PASSWORD_REF);

        Steps steps = probe.run(binding);

        assertFalse(steps.connected());
        assertNotNull(steps.rejectionReason());
    }

    @Test
    void noPasswordAttributeConnectsWithAnEmptyPassword() throws IOException {
        int port = closedLocalPort();
        JcsmpBrokerProbe probe = new JcsmpBrokerProbe((tenantId, secretId, expected) -> {
            throw new AssertionError("no secret reference was given; resolve must not be called");
        }, PERMISSIVE);
        Binding binding = new Binding(TENANT, "tcp://127.0.0.1", port, "default", "sm-client", null);

        Steps steps = probe.run(binding);

        assertFalse(steps.connected());
    }

    @Test
    void theSecretIsResolvedUnderTheConnectionCredentialPurposeForTheCallersTenant() throws IOException {
        int port = closedLocalPort();
        boolean[] called = {false};
        SecretResolver resolver = (tenantId, secretId, expected) -> {
            called[0] = true;
            assertEquals(TENANT.value(), tenantId);
            assertEquals(PASSWORD_REF, secretId);
            assertEquals(SecretPurpose.CONNECTION_CREDENTIAL, expected);
            return SecretMaterial.adopt("secret".getBytes(StandardCharsets.UTF_8));
        };
        JcsmpBrokerProbe probe = new JcsmpBrokerProbe(resolver, PERMISSIVE);
        Binding binding = new Binding(TENANT, "tcp://127.0.0.1", port, "default", "sm-client", PASSWORD_REF);

        probe.run(binding);

        assertTrue(called[0]);
    }

    @Test
    void aHostThePolicyRefusesIsAFailedResultBeforeAnySessionOrSecretResolution() {
        TargetAddressPolicy refusing = new StubTargetAddressPolicy(host -> {
            throw new TargetAddressRefusedException();
        });
        JcsmpBrokerProbe probe = new JcsmpBrokerProbe((tenantId, secretId, expected) -> {
            throw new AssertionError("the host was refused before any secret should be resolved");
        }, refusing);
        Binding binding = new Binding(TENANT, "169.254.169.254", 55555, "default", "sm-client", PASSWORD_REF);

        Steps steps = probe.run(binding);

        assertFalse(steps.connected());
        assertNotNull(steps.rejectionReason());
    }

    @Test
    void theSchemePrefixIsStrippedBeforeTheBareHostIsCheckedByThePolicy() {
        boolean[] checked = {false};
        TargetAddressPolicy recording = new StubTargetAddressPolicy(host -> {
            checked[0] = true;
            assertEquals("broker.example.com", host);
            throw new TargetAddressRefusedException();
        });
        JcsmpBrokerProbe probe = new JcsmpBrokerProbe(fixedPassword("secret"), recording);
        Binding binding = new Binding(TENANT, "tcps://broker.example.com", 55443, "default", "sm-client", null);

        probe.run(binding);

        assertTrue(checked[0]);
    }

    private static SecretResolver fixedPassword(String value) {
        return (tenantId, secretId, expected) -> SecretMaterial.adopt(value.getBytes(StandardCharsets.UTF_8));
    }
}
