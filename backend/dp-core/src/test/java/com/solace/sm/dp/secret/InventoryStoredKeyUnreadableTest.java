package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * W1 review C-6, offline. This test lives in the {@code secret} package because {@link SecretException} can only be
 * constructed there: it needs the one thing a database-free run lacks, a resolver that fails the way the real one does.
 */
class InventoryStoredKeyUnreadableTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String FP = "SHA256:" + "A".repeat(43);

    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger credentialUpdates = new AtomicInteger();

    private final SecretStore store = new SecretStore() {
        @Override
        public UUID put(UUID tenantId, SecretPurpose purpose, char[] value, UUID actor) {
            puts.incrementAndGet();
            return UUID.randomUUID();
        }

        @Override
        public boolean exists(UUID tenantId, UUID secretId) {
            return true;
        }
    };

    /** The failure a store reports for a row it cannot find or a key its ring no longer holds. */
    private final SecretResolver unreadable = (tenant, id, purpose) -> {
        throw new SecretException(SecretException.Code.NOT_FOUND);
    };

    private final TargetConnectionRecord live = new TargetConnectionRecord(ORG, TARGET, "vm1", 22, "sm", FP,
            UUID.randomUUID(), "/srv/sm", "sm-", ACTOR, Instant.EPOCH, null);

    private final TargetConnectionRows connections = (TargetConnectionRows) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {TargetConnectionRows.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "find":
                    case "findLiveForChange":
                        return Optional.of(live);
                    case "updateCredential":
                        credentialUpdates.incrementAndGet();
                        return 1;
                    default:
                        return null;
                }
            });

    private final TransactionTemplate tx = new TransactionTemplate(new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    });

    private InventoryService service(SecretResolver resolver) {
        return new InventoryService(tx, (InstanceRows) stub(InstanceRows.class), (PlacementRows) stub(PlacementRows.class),
                connections, store, resolver, Clock.systemUTC(), MetricDeclarationRows.DISCARDING);
    }

    private static Object stub(Class<?> type) {
        return Proxy.newProxyInstance(InventoryStoredKeyUnreadableTest.class.getClassLoader(), new Class<?>[] {type},
                (p, m, a) -> null);
    }

    @Test
    void theCanonicalConstructorRequiresAResolver() {
        assertThrows(NullPointerException.class, () -> service(null));
    }

    @Test
    void aReplayAgainstAStoredKeyNobodyCanOpenIsNotAKeyConflict() {
        SshTestKeys.Key key = SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair());
        TargetConnectionSpec spec = new TargetConnectionSpec("vm1", 22, "sm", FP, "/srv/sm", "sm-", Set.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service(unreadable).register(ORG, TARGET, spec, key.chars(), ACTOR));

        assertFalse(e instanceof TargetCredentialConflictException, "unreadable is not 'a different key'");
        assertTrue(e instanceof com.solace.sm.dp.api.inventory.TargetCredentialUnreadableException,
                "its own type, so the gateway can answer it as what it is (review W3C-4)");
        assertEquals("the stored credential could not be opened", e.getMessage());
        assertNull(e.getCause(), "the secret store's message is not forwarded");
        assertEquals(0, puts.get(), "nothing was written");
    }

    @Test
    void aRotationReplacesAStoredKeyNobodyCanOpen() {
        SshTestKeys.Key key = SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair());

        service(unreadable).rotateCredential(ORG, TARGET, key.chars(), ACTOR);

        assertEquals(1, puts.get());
        assertTrue(credentialUpdates.get() == 1, "rotation is the repair for an unreadable stored key");
    }
}
