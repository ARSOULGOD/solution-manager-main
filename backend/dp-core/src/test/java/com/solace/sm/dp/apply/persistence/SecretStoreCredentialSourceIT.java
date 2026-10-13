package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.production.SecretStoreCredentialSource;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Plan 010 T6.2: the SSH key comes out of the secret store, for its own tenant and only as an SSH key. */
@Testcontainers(disabledWithoutDocker = true)
class SecretStoreCredentialSourceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static EnvelopeSecretStore secrets;
    private static SecretStoreCredentialSource source;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 41), null));
        source = new SecretStoreCredentialSource(secrets);
    }

    @Test
    void theKeyIsResolvedForItsOwnTenantAndClosedByTheCaller() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID ref = secrets.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "KEY-MATERIAL".toCharArray(), ACTOR);

        SecretMaterial material = source.privateKey(new TenantId(tenant), ref);

        assertArrayEquals("KEY-MATERIAL".getBytes(StandardCharsets.UTF_8), material.bytes());
        material.close();
        assertTrue(material.isClosed());
    }

    @Test
    void anotherTenantsKeyIsNotFound() {
        UUID owner = SmDataPostgres.freshTenant();
        UUID ref = secrets.put(owner, SecretPurpose.SSH_PRIVATE_KEY, "KEY-MATERIAL".toCharArray(), ACTOR);

        SecretException e = assertThrows(SecretException.class,
                () -> source.privateKey(new TenantId(SmDataPostgres.freshTenant()), ref));

        assertEquals(SecretException.Code.NOT_FOUND, e.code());
    }

    @Test
    void aSecretOfAnotherPurposeIsNeverHandedOutAsAnSshKey() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID configField = secrets.put(tenant, SecretPurpose.CONFIG_FIELD, "broker-password".toCharArray(), ACTOR);

        assertThrows(SecretException.class, () -> source.privateKey(new TenantId(tenant), configField));
    }
}
