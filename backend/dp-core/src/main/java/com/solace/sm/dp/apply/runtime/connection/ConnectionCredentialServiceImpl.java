package com.solace.sm.dp.apply.runtime.connection;

import com.solace.sm.dp.api.connection.ConnectionCredentialService;
import com.solace.sm.dp.api.connection.ConnectionTestOutcome;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretStore;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code dp-api} answer for Connection credentials (plan 013 T4, {@code BE-D66}): seals a secret-typed
 * attribute under {@link SecretPurpose#CONNECTION_CREDENTIAL} and delegates a {@code testBeforeSave} probe to
 * {@link SolaceBrokerConnectionTester} or {@link RemoteServerConnectionTester} (Task 3).
 */
public final class ConnectionCredentialServiceImpl implements ConnectionCredentialService {

    private final SecretStore secretStore;
    private final SolaceBrokerConnectionTester brokerTester;
    private final RemoteServerConnectionTester remoteServerTester;

    public ConnectionCredentialServiceImpl(SecretStore secretStore, SolaceBrokerConnectionTester brokerTester,
            RemoteServerConnectionTester remoteServerTester) {
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.brokerTester = Objects.requireNonNull(brokerTester, "brokerTester");
        this.remoteServerTester = Objects.requireNonNull(remoteServerTester, "remoteServerTester");
    }

    @Override
    public UUID sealCredential(UUID tenantId, char[] value, UUID actor) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(actor, "actor");
        return secretStore.put(tenantId, SecretPurpose.CONNECTION_CREDENTIAL, value, actor);
    }

    @Override
    public ConnectionTestOutcome testSolaceBroker(UUID tenantId, String host, int port, String vpn, String username,
            UUID passwordSecretRef) {
        SolaceBrokerConnectionTester.Binding binding = new SolaceBrokerConnectionTester.Binding(
                new TenantId(Objects.requireNonNull(tenantId, "tenantId")), host, port, vpn, username,
                passwordSecretRef);
        return outcome(brokerTester.test(binding));
    }

    @Override
    public ConnectionTestOutcome testRemoteServer(UUID tenantId, String host, int port, String username,
            String authMethod, UUID passwordSecretRef, UUID privateKeySecretRef, UUID passphraseSecretRef) {
        RemoteServerConnectionTester.AuthMethod method = switch (Objects.requireNonNull(authMethod, "authMethod")) {
            case "basic" -> RemoteServerConnectionTester.AuthMethod.BASIC;
            case "private-key" -> RemoteServerConnectionTester.AuthMethod.PRIVATE_KEY;
            default -> throw new IllegalArgumentException("authMethod");
        };
        RemoteServerConnectionTester.Binding binding = new RemoteServerConnectionTester.Binding(
                new TenantId(Objects.requireNonNull(tenantId, "tenantId")), host, port, username, method,
                passwordSecretRef, privateKeySecretRef, passphraseSecretRef);
        return outcome(remoteServerTester.test(binding));
    }

    private static ConnectionTestOutcome outcome(ConnectionTestResult result) {
        return new ConnectionTestOutcome(result.success(), result.rejectionReason(), result.reachable(),
                result.checkedAt());
    }
}
