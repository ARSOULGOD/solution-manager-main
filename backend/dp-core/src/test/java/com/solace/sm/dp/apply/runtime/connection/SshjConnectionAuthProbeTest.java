package com.solace.sm.dp.apply.runtime.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.AuthMethod;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.Binding;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester.Steps;
import com.solace.sm.dp.inventory.PermissiveTargetAddressPolicy;
import com.solace.sm.dp.inventory.StubTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretResolver;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T3, {@code BE-D66}: the real sshj classifiers, without a live {@code sshd} — an unreachable host is
 * enough to prove a connection failure is a {@link Steps} and never an exception. Authentication against a real
 * host (both auth methods, and the {@code CONNECTION_CREDENTIAL} purpose at the secret store) needs sshj to
 * complete a full transport handshake with a real SSH server first and so needs a live {@code sshd}; that is out
 * of this task's scope (Task 3 brief) and belongs to an integration test, exactly as {@code SshjTargetSshProbe}'s
 * own wire behaviour does ({@code TargetConnectionTestIT}).
 *
 * <p>Fix round 1 (owner ruling): the submitted host must clear {@link TargetAddressPolicy} before anything
 * connects — before even the secret store is asked for a credential.
 */
class SshjConnectionAuthProbeTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final UUID PASSWORD_REF = UUID.randomUUID();

    /** Everything resolves and clears the policy — these tests are about the sshj transport, not the address rule. */
    private static final TargetAddressPolicy PERMISSIVE = new PermissiveTargetAddressPolicy();

    private static int closedLocalPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void anUnreachableHostIsAFailedResultNotAnException() throws IOException {
        int port = closedLocalPort();
        SshjConnectionAuthProbe probe = new SshjConnectionAuthProbe(fixedSecret("secret"), PERMISSIVE);
        Binding binding = new Binding(TENANT, "127.0.0.1", port, "sm", AuthMethod.BASIC, PASSWORD_REF, null, null);

        Steps steps = probe.run(binding);

        assertFalse(steps.connected());
        assertFalse(steps.authenticated());
        assertNotNull(steps.rejectionReason());
    }

    @Test
    void basicAuthNeverCallsTheSecretStoreWhenTheHostIsNeverReached() throws IOException {
        int port = closedLocalPort();
        SshjConnectionAuthProbe probe = new SshjConnectionAuthProbe((tenantId, secretId, expected) -> {
            throw new AssertionError("the host was never reached; resolve must not be called");
        }, PERMISSIVE);
        Binding binding = new Binding(TENANT, "127.0.0.1", port, "sm", AuthMethod.BASIC, null, null, null);

        Steps steps = probe.run(binding);

        assertFalse(steps.connected());
    }

    @Test
    void aHostThePolicyRefusesIsAFailedResultBeforeAnySessionOrSecretResolution() {
        TargetAddressPolicy refusing = new StubTargetAddressPolicy(host -> {
            throw new TargetAddressRefusedException();
        });
        SshjConnectionAuthProbe probe = new SshjConnectionAuthProbe((tenantId, secretId, expected) -> {
            throw new AssertionError("the host was refused before any secret should be resolved");
        }, refusing);
        Binding binding = new Binding(TENANT, "169.254.169.254", 22, "sm", AuthMethod.BASIC, PASSWORD_REF, null,
                null);

        Steps steps = probe.run(binding);

        assertFalse(steps.connected());
        assertFalse(steps.authenticated());
        assertNotNull(steps.rejectionReason());
    }

    @Test
    void thePolicyIsCheckedWithTheSubmittedHostNameUnchanged() {
        boolean[] checked = {false};
        TargetAddressPolicy recording = new StubTargetAddressPolicy(host -> {
            checked[0] = true;
            assertEquals("vm1.example.com", host);
            throw new TargetAddressRefusedException();
        });
        SshjConnectionAuthProbe probe = new SshjConnectionAuthProbe(fixedSecret("secret"), recording);
        Binding binding = new Binding(TENANT, "vm1.example.com", 22, "sm", AuthMethod.BASIC, null, null, null);

        probe.run(binding);

        assertTrue(checked[0]);
    }

    private static SecretResolver fixedSecret(String value) {
        return (tenantId, secretId, expected) -> SecretMaterial.adopt(value.getBytes(StandardCharsets.UTF_8));
    }
}
