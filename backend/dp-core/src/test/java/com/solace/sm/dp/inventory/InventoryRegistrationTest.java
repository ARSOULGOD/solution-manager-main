package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.RegistrationPrecheck;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.dp.secret.SecretStore;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 013 T1.4, the data plane's half of {@code registerTarget} (D-REG section 8) and {@code getTargetConnection},
 * offline: the pre-check's order and what it writes (nothing), the registration's three outcomes, the
 * unique-violation race, the {@code registered} event, the fingerprint column, and the legacy back-fill. Real
 * PostgreSQL proofs of the same are {@code TargetRegistrationIT}.
 */
class InventoryRegistrationTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final TargetChangeOrigin ORIGIN = new TargetChangeOrigin(UUID.randomUUID(), "203.0.113.9");

    private static final KeyPair PAIR = SshTestKeys.ed25519Pair();
    private static final SshTestKeys.Key KEY = SshTestKeys.ed25519OpenSsh(PAIR);
    private static final SshTestKeys.Key OTHER_KEY = SshTestKeys.rsaPkcs8(SshTestKeys.rsaPair());

    private static final String PIN = "SHA256:" + "P".repeat(43);
    private static final String SECOND = "SHA256:" + "Q".repeat(43);
    private static final String THIRD = "SHA256:" + "R".repeat(43);

    private InMemoryTargetConnectionRows rows;
    private MemorySecrets secrets;
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final List<String> calls = new ArrayList<>();
    private List<OfferedHostKey> offered;
    private RuntimeException scanFails;
    private InventoryService service;

    @BeforeEach
    void wire() {
        rows = new InMemoryTargetConnectionRows();
        secrets = new MemorySecrets();
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"), new OfferedHostKey(SECOND, "ssh-rsa"));
        scanFails = null;
        HostKeyScanService scanner = (host, port) -> {
            calls.add("scan");
            if (scanFails != null) {
                throw scanFails;
            }
            return offered;
        };
        TransactionTemplate tx = new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                commits.incrementAndGet();
            }

            @Override
            public void rollback(TransactionStatus status) {
                rollbacks.incrementAndGet();
            }
        });
        service = new InventoryService(tx, (InstanceRows) stub(InstanceRows.class),
                (PlacementRows) stub(PlacementRows.class), rows, secrets, secrets,
                Clock.fixed(NOW, ZoneOffset.UTC), MetricDeclarationRows.DISCARDING, LadderRecorder.none(),
                ApplyEventEmitter.silent(), scanner);
    }

    private static Object stub(Class<?> type) {
        return Proxy.newProxyInstance(InventoryRegistrationTest.class.getClassLoader(), new Class<?>[] {type},
                (p, m, a) -> null);
    }

    private static TargetConnectionSpec spec() {
        return new TargetConnectionSpec("vm1.example.com", 22, "sm", PIN, "/srv/sm", "sm-");
    }

    // ---- precheck ----------------------------------------------------------------------------------------------

    @Test
    void precheckReturnsTheOfferedKeysAndThePublicFingerprintAndWritesNothing() {
        RegistrationPrecheck result = service.precheckRegistration(ORG, null, spec(), KEY.chars());

        assertEquals(List.of(PIN, SECOND), result.offeredFingerprints());
        assertEquals(KEY.publicFingerprint(), result.publicKeyFingerprint());
        assertEquals(0, secrets.puts.get(), "no secret stored");
        assertTrue(rows.rows.isEmpty(), "no connection row");
        assertTrue(rows.events.isEmpty(), "no event");
        assertEquals(0, commits.get() + rollbacks.get(), "no transaction was even opened");
    }

    @Test
    void aFieldOutsideTheGrammarIsRefusedBeforeTheKeyIsReadOrTheHostIsScanned() {
        TargetConnectionSpec bad = new TargetConnectionSpec("vm1.example.com", 22, "not a login", PIN, "/srv/sm", "sm-");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service.precheckRegistration(ORG, null, bad, "not a key".toCharArray()));

        assertFalse(refused instanceof CredentialInvalidException, "the grammar comes before the key");
        assertEquals(List.of(), calls, "nothing was scanned");
    }

    @Test
    void aKeyThatDoesNotOpenIsRefusedBeforeTheHostIsScanned() {
        assertThrows(CredentialInvalidException.class,
                () -> service.precheckRegistration(ORG, null, spec(), "-----BEGIN NOPE-----".toCharArray()));

        assertEquals(List.of(), calls, "the key is parsed before any connection is made to the host");
    }

    @Test
    void anAddressTheDataPlaneMayNotConnectToIsRefusedByTheScan() {
        scanFails = new TargetAddressRefusedException();

        assertThrows(TargetAddressRefusedException.class,
                () -> service.precheckRegistration(ORG, null, spec(), KEY.chars()));
    }

    @Test
    void anUnreachableHostIsUnavailable() {
        scanFails = new HostKeyScanUnavailableException();

        assertThrows(HostKeyScanUnavailableException.class,
                () -> service.precheckRegistration(ORG, null, spec(), KEY.chars()));
    }

    @Test
    void aPinTheHostDidNotOfferIsRefusedAndNamesNoFingerprint() {
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"));

        HostKeyNotOfferedException refused = assertThrows(HostKeyNotOfferedException.class,
                () -> service.precheckRegistration(ORG, null, spec(), KEY.chars()));

        assertFalse(refused.getMessage().contains(PIN) || refused.getMessage().contains(SECOND), refused.getMessage());
    }

    @Test
    void aHostKeyAnotherLiveTargetHoldsRefusesTheRegistrationEvenWhenTheHeldKeyIsNotThePin() {
        UUID other = UUID.randomUUID();
        rows.keys.put(other, new java.util.LinkedHashMap<>(Map.of(SECOND, false)));

        assertThrows(HostKeyHeldElsewhereException.class,
                () -> service.precheckRegistration(ORG, null, spec(), KEY.chars()));
    }

    @Test
    void aRetiredTargetsKeysAreFreeAndTheTargetBeingReplayedIsNotAnotherTarget() {
        UUID retired = UUID.randomUUID();
        rows.keys.put(retired, new java.util.LinkedHashMap<>(Map.of(SECOND, true)));
        rows.keys.put(TARGET, new java.util.LinkedHashMap<>(Map.of(PIN, false)));

        RegistrationPrecheck result = service.precheckRegistration(ORG, TARGET, spec(), KEY.chars());

        assertEquals(KEY.publicFingerprint(), result.publicKeyFingerprint());
    }

    @Test
    void theProbeComesAfterTheScanSoAnUnreachableHostIsNeverReportedAsAConflict() {
        rows.keys.put(UUID.randomUUID(), new java.util.LinkedHashMap<>(Map.of(PIN, false)));
        scanFails = new HostKeyScanUnavailableException();

        assertThrows(HostKeyScanUnavailableException.class,
                () -> service.precheckRegistration(ORG, null, spec(), KEY.chars()));
    }

    // ---- register ----------------------------------------------------------------------------------------------

    private RegistrationOutcome register(char[] key) {
        return service.registerConnection(ORG, TARGET,
                new TargetConnectionSpec("vm1.example.com", 22, "sm", PIN, "/srv/sm", "sm-", Set.of(SECOND)), key, ACTOR,
                ORIGIN);
    }

    @Test
    void aNewRegistrationWritesOneRowOneSecretTheHostKeysAndOneRegisteredEvent() {
        assertEquals(RegistrationOutcome.CREATED, register(KEY.chars()));

        TargetConnectionRecord row = rows.rows.get(TARGET);
        assertEquals(KEY.publicFingerprint(), row.credentialPublicFingerprint());
        assertEquals(1, secrets.puts.get());
        assertEquals(Set.of(PIN, SECOND), rows.liveHostKeys(ORG, TARGET));
        assertEquals(1, rows.events.size());
        TargetConnectionEvent event = rows.events.get(0);
        assertEquals(TargetConnectionEvent.Kind.REGISTERED, event.kind());
        assertEquals(ACTOR, event.actor());
        assertEquals(ORIGIN.correlationId(), event.correlationId());
        assertEquals("203.0.113.9", event.clientAddress());
        assertEquals(KEY.publicFingerprint(), event.publicKeyFingerprint());
        assertEquals(NOW, event.recordedAt());
        assertEquals(List.of(), event.changedFields(), "a registration names no changed field");
        assertEquals(0, rollbacks.get());
    }

    @Test
    void theEventHoldsNeitherTheKeyNorTheCredentialReference() {
        register(KEY.chars());

        String everything = rows.events.get(0).toString();
        assertFalse(everything.contains("BEGIN"), everything);
        assertFalse(everything.contains(row().credentialRef().toString()), everything);
    }

    private TargetConnectionRecord row() {
        return rows.rows.get(TARGET);
    }

    @Test
    void theSameRegistrationAgainIsAReplayAndWritesNothing() {
        register(KEY.chars());
        int eventsBefore = rows.events.size();

        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.chars()));

        assertEquals(1, secrets.puts.get(), "no second sm_secret row (OR-85)");
        assertEquals(eventsBefore, rows.events.size(), "a replay is not a change");
    }

    @Test
    void aReExportOfTheSameKeyIsStillAReplay() {
        register(KEY.chars());

        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.crlf().chars()));
        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.withoutTrailingNewline().chars()));
        assertEquals(1, secrets.puts.get());
    }

    @Test
    void aDifferentKeyUnderTheSameRecordIsAConflictAndWritesNothing() {
        register(KEY.chars());

        assertThrows(TargetCredentialConflictException.class, () -> register(OTHER_KEY.chars()));

        assertEquals(1, secrets.puts.get());
        assertEquals(1, rows.events.size());
    }

    @Test
    void aDifferentRecordForTheSameTargetIsRefused() {
        register(KEY.chars());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> service.registerConnection(ORG, TARGET,
                        new TargetConnectionSpec("vm2.example.com", 22, "sm", PIN, "/srv/sm", "sm-"), KEY.chars(),
                        ACTOR, ORIGIN));

        assertFalse(refused instanceof TargetCredentialConflictException);
    }

    @Test
    void theLoserOfARaceRollsBackItsOwnSecretAndAnswersAReplay() {
        // The winner's row commits between this request's check and its insert.
        rows.beforeInsertOnce = () -> {
            rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN,
                    UUID.randomUUID(), "/srv/sm", "sm-", ACTOR, NOW, null, KEY.publicFingerprint()));
            rows.keys.put(TARGET, new java.util.LinkedHashMap<>(Map.of(PIN, false, SECOND, false)));
            rows.beforeInsertOnce = () -> { };
        };

        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.chars()));

        assertEquals(1, rollbacks.get(), "the loser's transaction (and the secret it stored) is rolled back whole");
        assertEquals(1, rows.rows.size());
        assertTrue(rows.events.isEmpty(), "the loser wrote no event: the winner's request writes its own");
    }

    @Test
    void theLoserOfARaceWithADifferentKeyIsAConflictNotASilentOverwrite() {
        rows.beforeInsertOnce = () -> {
            rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN,
                    UUID.randomUUID(), "/srv/sm", "sm-", ACTOR, NOW, null, OTHER_KEY.publicFingerprint()));
            rows.keys.put(TARGET, new java.util.LinkedHashMap<>(Map.of(PIN, false)));
            rows.beforeInsertOnce = () -> { };
        };

        assertThrows(TargetCredentialConflictException.class, () -> register(KEY.chars()));
    }

    @Test
    void aHostKeyTakenBetweenTheProbeAndTheInsertIsRefusedAndRollsEverythingBack() {
        rows.keys.put(UUID.randomUUID(), new java.util.LinkedHashMap<>(Map.of(SECOND, false)));

        assertThrows(HostKeyHeldElsewhereException.class, () -> register(KEY.chars()));

        assertEquals(2, rollbacks.get(), "run once, run again to see whether the holder is this very target, refuse");
        assertTrue(rows.events.isEmpty());
    }

    @Test
    void aDeadlockLoserIsRetriedOnceLikeALostRaceAndCreatesTheRecord() {
        // W3C-7: PostgreSQL aborts one of two registrations that cross-wait on each other's host-key entries (40P01)
        rows.beforeInsertOnce = () -> {
            rows.beforeInsertOnce = () -> { };
            throw new org.springframework.dao.DeadlockLoserDataAccessException("deadlock detected", null);
        };

        assertEquals(RegistrationOutcome.CREATED, register(KEY.chars()));

        assertEquals(1, rollbacks.get(), "the aborted run was rolled back whole, then run again");
        assertEquals(1, rows.rows.size());
        assertEquals(1, rows.events.size());
    }

    @Test
    void aSecondDeadlockIsTheCallersRetryNotAnInternalError() {
        rows.beforeInsertOnce = () -> {
            throw new org.springframework.dao.DeadlockLoserDataAccessException("deadlock detected", null);
        };

        assertThrows(com.solace.sm.dp.api.inventory.TargetConnectionChangedException.class,
                () -> register(KEY.chars()));

        assertEquals(2, rollbacks.get());
        assertTrue(rows.rows.isEmpty());
    }

    @Test
    void aHostKeyViolationFromTheInsertOfTheTwinRequestIsAReplayWhenTheWinnerIsThisTarget() {
        // The twin's row commits while this request is inserting; this insert meets the host-key index first and the
        // database says "held by another target", which is the twin, which is this target.
        rows.beforeInsertOnce = () -> {
            rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN,
                    UUID.randomUUID(), "/srv/sm", "sm-", ACTOR, NOW, null, KEY.publicFingerprint()));
            rows.keys.put(TARGET, new java.util.LinkedHashMap<>(Map.of(PIN, false, SECOND, false)));
            rows.beforeInsertOnce = () -> { };
            throw new HostKeyHeldElsewhereException();
        };

        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.chars()));

        assertEquals(1, rollbacks.get());
        assertEquals(1, rows.rows.size());
    }

    @Test
    void aLegacyRowWithNoFingerprintIsBackfilledOnItsFirstReplay() {
        rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN,
                secrets.seed(KEY.text()), "/srv/sm", "sm-", ACTOR, NOW, null));
        rows.keys.put(TARGET, new java.util.LinkedHashMap<>(Map.of(PIN, false)));

        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.crlf().chars()));

        assertEquals(List.of(KEY.publicFingerprint()), rows.backfills);
        assertEquals(KEY.publicFingerprint(), row().credentialPublicFingerprint());
        assertEquals(0, secrets.puts.get());
    }

    @Test
    void aKnownFingerprintIsComparedWithoutOpeningTheStoredSecret() {
        register(KEY.chars());
        secrets.resolves.set(0);

        assertEquals(RegistrationOutcome.REPLAYED, register(KEY.chars()));

        assertEquals(0, secrets.resolves.get(), "the normal path opens no secret");
    }

    // ---- rotateCredential and the fingerprint column ----------------------------------------------------------

    @Test
    void rotatingToTheSameKeyWritesNothing() {
        register(KEY.chars());

        service.rotateCredential(ORG, TARGET, KEY.crlf().chars(), ACTOR);

        assertEquals(1, secrets.puts.get());
        assertEquals(0, rows.updatesOfCredential);
    }

    @Test
    void rotatingToANewKeyStoresItAndRecordsItsPublicFingerprint() {
        register(KEY.chars());

        service.rotateCredential(ORG, TARGET, OTHER_KEY.chars(), ACTOR);

        assertEquals(2, secrets.puts.get());
        assertEquals(1, rows.updatesOfCredential);
        assertEquals(OTHER_KEY.publicFingerprint(), rows.lastCredentialFingerprint);
        assertEquals(OTHER_KEY.publicFingerprint(), row().credentialPublicFingerprint());
    }

    // ---- OR-125 (2): the pinned key's type is stored beside the pin -------------------------------------------

    private RegistrationOutcome registerTyped(HostKeyType type) {
        return service.registerConnection(ORG, TARGET, new TargetConnectionSpec("vm1.example.com", 22, "sm", PIN,
                "/srv/sm", "sm-", Set.of(SECOND), type), KEY.chars(), ACTOR, ORIGIN);
    }

    @Test
    void aNewRegistrationStoresTheTypeOfThePinnedKeyTheScanReported() {
        assertEquals(RegistrationOutcome.CREATED, registerTyped(HostKeyType.ED25519));

        assertEquals(HostKeyType.ED25519, row().hostKeyType());
    }

    @Test
    void aRegistrationThatDoesNotKnowTheTypeStoresNoneAndSoDoesTheOperatorCommand() {
        register(KEY.chars());
        assertNull(row().hostKeyType(), "the HTTP path with no scanned type");

        rows.rows.clear();
        rows.keys.clear();
        service.register(ORG, TARGET, new TargetConnectionSpec("vm1.example.com", 22, "sm", PIN, "/srv/sm", "sm-",
                Set.of(SECOND)), KEY.chars(), ACTOR);
        assertNull(row().hostKeyType(), "the operator command scans fingerprints only");
    }

    @Test
    void theOperatorCommandStoresATypeItIsGiven() {
        service.register(ORG, TARGET, new TargetConnectionSpec("vm1.example.com", 22, "sm", PIN, "/srv/sm", "sm-",
                Set.of(SECOND), HostKeyType.RSA), KEY.chars(), ACTOR);

        assertEquals(HostKeyType.RSA, row().hostKeyType());
    }

    @Test
    void aReplayBackFillsAPinnedKeyTypeTheStoredRowLacksAndOnlyThen() {
        registerTyped(null);                                       // a row registered before V027
        assertNull(row().hostKeyType());

        assertEquals(RegistrationOutcome.REPLAYED, registerTyped(HostKeyType.ED25519));

        assertEquals(HostKeyType.ED25519, row().hostKeyType(), "back-filled from the replay's own fresh scan");
        assertEquals(List.of(PIN + ":" + HostKeyType.ED25519), rows.typeRecords);
    }

    @Test
    void aReplayNeverOverwritesARecordedTypeAndAReplayThatKnowsNoTypeLeavesIt() {
        registerTyped(HostKeyType.ED25519);

        registerTyped(HostKeyType.RSA);
        assertEquals(HostKeyType.ED25519, row().hostKeyType(), "a recorded type is never replaced by a later claim");

        registerTyped(null);
        assertEquals(HostKeyType.ED25519, row().hostKeyType());
    }

    @Test
    void aReplayThatKnowsNoTypeWritesNothingToARowThatHasNone() {
        registerTyped(null);

        assertEquals(RegistrationOutcome.REPLAYED, registerTyped(null));

        assertEquals(List.of(), rows.typeRecords, "there is nothing to record, so no statement is made");
        assertNull(row().hostKeyType());
    }

    @Test
    void aReplayWithADifferentTypeOnlyIsStillAReplayNotAConflict() {
        registerTyped(HostKeyType.ED25519);

        assertEquals(RegistrationOutcome.REPLAYED, registerTyped(HostKeyType.ECDSA_NISTP256),
                "the type is not part of the record's identity: the pin is");
    }

    @Test
    void thePrecheckAnswersTheTypeOfEachOfferedKeyAndNoneForOneItDidNotOffer() {
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"), new OfferedHostKey(SECOND, "ssh-rsa"),
                new OfferedHostKey(THIRD, "unknown"));
        RegistrationPrecheck result = service.precheckRegistration(ORG, null, spec(), KEY.chars());

        assertEquals(Optional.of(HostKeyType.ED25519), result.typeOf(PIN));
        assertEquals(Optional.of(HostKeyType.RSA), result.typeOf(SECOND));
        assertEquals(Optional.empty(), result.typeOf(THIRD), "a type the data plane does not pin by is unknown, not an error");
        assertEquals(Optional.empty(), result.typeOf("SHA256:" + "Z".repeat(43)));
    }

    // ---- the legacy command path -------------------------------------------------------------------------------

    @Test
    void theOperatorCommandsRegistrationAlsoRecordsTheFingerprintAndAnEventWithTheCommandsOrigin() {
        service.register(ORG, TARGET, new TargetConnectionSpec("vm1.example.com", 22, "sm", PIN, "/srv/sm", "sm-",
                Set.of(SECOND)), KEY.chars(), ACTOR);

        assertEquals(KEY.publicFingerprint(), row().credentialPublicFingerprint());
        assertEquals(1, rows.events.size());
        assertEquals(TargetChangeOrigin.OPERATOR_COMMAND_ADDRESS, rows.events.get(0).clientAddress());
        assertEquals(TargetConnectionEvent.Kind.REGISTERED, rows.events.get(0).kind());
    }

    // ---- connection() ------------------------------------------------------------------------------------------

    @Test
    void theManagementReadCarriesNoSecretAndTheLiveHostKeys() {
        register(KEY.chars());
        rows.keys.get(TARGET).put(THIRD, true);   // a retired key is not offered by the machine any more

        TargetConnectionView view = service.connection(ORG, TARGET).orElseThrow();

        assertEquals("vm1.example.com", view.host());
        assertEquals(22, view.port());
        assertEquals("sm", view.sshUser());
        assertEquals(PIN, view.hostKeyFingerprint());
        assertEquals(Set.of(PIN, SECOND), Set.copyOf(view.offeredHostKeys()));
        assertEquals("/srv/sm", view.allowedConfigRoot());
        assertEquals("sm-", view.allowedContainerPrefix());
        assertTrue(view.credential().present());
        assertEquals(KEY.publicFingerprint(), view.credential().publicKeyFingerprint());
        assertEquals(NOW, view.credential().setAt());
        String everything = view.toString();
        assertFalse(everything.contains(row().credentialRef().toString()), everything);
    }

    @Test
    void aLegacyRowReadsWithNoPublicFingerprint() {
        rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN,
                secrets.seed(KEY.text()), "/srv/sm", "sm-", ACTOR, NOW, null));
        rows.keys.put(TARGET, new java.util.LinkedHashMap<>(Map.of(PIN, false)));

        TargetConnectionView view = service.connection(ORG, TARGET).orElseThrow();

        assertNull(view.credential().publicKeyFingerprint());
        assertTrue(view.credential().present());
        assertEquals(NOW, view.credential().setAt(), "no event: the record's own creation");
    }

    @Test
    void aRetiredOrAbsentTargetIsEmpty() {
        assertEquals(Optional.empty(), service.connection(ORG, TARGET));
        rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm1.example.com", 22, "sm", PIN,
                UUID.randomUUID(), "/srv/sm", "sm-", ACTOR, NOW, NOW));

        assertEquals(Optional.empty(), service.connection(ORG, TARGET));
    }

    @Test
    void withoutAScannerThePrecheckSaysSoInsteadOfPassingEverything() {
        InventoryService noScanner = new InventoryService(new TransactionTemplate(), (InstanceRows) stub(InstanceRows.class),
                (PlacementRows) stub(PlacementRows.class), rows, secrets, secrets, Clock.systemUTC(),
                MetricDeclarationRows.DISCARDING, LadderRecorder.none(), ApplyEventEmitter.silent());

        assertThrows(IllegalStateException.class,
                () -> noScanner.precheckRegistration(ORG, null, spec(), KEY.chars()));
    }

    /** A store that is also its own resolver, in memory. */
    static final class MemorySecrets implements SecretStore, SecretResolver {
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger resolves = new AtomicInteger();
        private final Map<UUID, String> stored = new HashMap<>();

        UUID seed(String text) {
            UUID id = UUID.randomUUID();
            stored.put(id, text);
            return id;
        }

        @Override
        public UUID put(UUID tenantId, SecretPurpose purpose, char[] value, UUID actor) {
            puts.incrementAndGet();
            return seed(new String(value));
        }

        @Override
        public boolean exists(UUID tenantId, UUID secretId) {
            return stored.containsKey(secretId);
        }

        @Override
        public SecretMaterial resolve(UUID tenantId, UUID secretId, SecretPurpose expected) {
            resolves.incrementAndGet();
            return SecretMaterial.adopt(stored.get(secretId).getBytes(StandardCharsets.UTF_8));
        }
    }
}
