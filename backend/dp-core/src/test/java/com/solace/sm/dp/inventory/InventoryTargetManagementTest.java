package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.ConnectionRetirement;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.EditPlan;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionChangedException;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.api.inventory.TargetWorkInFlightException;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.inventory.InventoryRegistrationTest.MemorySecrets;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
 * Plan 013 T1.5, the data plane's half of {@code editTargetConnection}, {@code rotateTargetCredential} and
 * {@code retireTarget}, offline: what each does, in what ORDER (the scan before any lock, the {@code apply_scope} locks
 * before the record's, every refusal before the first write), and what it never does. Real PostgreSQL proofs of the same
 * (the locks actually excluding admission, the triggers, the rollback) are {@code TargetManagementIT}.
 */
class InventoryTargetManagementTest {

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
    private static final String FOURTH = "SHA256:" + "S".repeat(43);

    private InMemoryTargetConnectionRows rows;
    private FakePlacements placements;
    private MemorySecrets secrets;
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private List<OfferedHostKey> offered;
    private RuntimeException scanFails;
    private final List<String> scans = new ArrayList<>();
    private InventoryService service;

    @BeforeEach
    void wire() {
        rows = new InMemoryTargetConnectionRows();
        placements = new FakePlacements(rows.log);
        secrets = new MemorySecrets();
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"), new OfferedHostKey(SECOND, "ssh-rsa"));
        scanFails = null;
        HostKeyScanService scanner = (host, port) -> {
            rows.log.add("scan");
            scans.add(host + ":" + port);
            if (scanFails != null) {
                throw scanFails;
            }
            return offered;
        };
        TransactionTemplate tx = new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                rows.log.add("begin");
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                commits.incrementAndGet();
                rows.log.add("commit");
            }

            @Override
            public void rollback(TransactionStatus status) {
                rollbacks.incrementAndGet();
                rows.log.add("rollback");
            }
        });
        service = new InventoryService(tx, (InstanceRows) java.lang.reflect.Proxy.newProxyInstance(
                InventoryTargetManagementTest.class.getClassLoader(), new Class<?>[] {InstanceRows.class},
                (p, m, a) -> null), placements, rows, secrets, secrets, Clock.fixed(NOW, ZoneOffset.UTC),
                MetricDeclarationRows.DISCARDING, LadderRecorder.none(), ApplyEventEmitter.silent(), scanner);
        // a registered target: vm1.example.com:22 as sm, pinned PIN, the host also offers SECOND
        service.registerConnection(ORG, TARGET,
                new com.solace.sm.dp.api.inventory.TargetConnectionSpec("vm1.example.com", 22, "sm", PIN, "/srv/sm",
                        "sm-", Set.of(SECOND)), KEY.chars(), ACTOR, ORIGIN);
        rows.events.clear();
        rows.log.clear();
        scans.clear();
        commits.set(0);
        rollbacks.set(0);
        secrets.puts.set(0);
    }

    /** A {@link PlacementRows} that answers only the three questions an edit and a retirement ask. */
    static final class FakePlacements implements PlacementRows {
        final List<String> log;
        boolean inFlight;
        boolean liveOnTarget;
        List<UUID> scopes = List.of(UUID.randomUUID(), UUID.randomUUID());

        FakePlacements(List<String> log) {
            this.log = log;
        }

        @Override
        public List<UUID> lockApplyScopesOfTarget(UUID tenantId, UUID targetId) {
            log.add("lockScopes");
            return scopes;
        }

        @Override
        public boolean inFlightOnTarget(UUID tenantId, UUID targetId) {
            log.add("inFlight");
            return inFlight;
        }

        @Override
        public boolean anyLiveOnTarget(UUID tenantId, UUID targetId) {
            log.add("anyLive");
            return liveOnTarget;
        }

        @Override
        public void insert(PlacementRecord row) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlacementRecord> findLive(UUID tenantId, UUID instanceId, UUID placementId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PlacementRecord> find(UUID tenantId, UUID instanceId, UUID placementId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PlacementRecord> live(UUID tenantId, UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int retireAll(UUID tenantId, UUID instanceId, Instant at) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int retire(UUID tenantId, UUID instanceId, UUID placementId, Instant at, UUID by) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean lockApplyScope(UUID tenantId, UUID instanceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean inFlightOn(UUID tenantId, UUID instanceId, UUID placementId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean instanceWideInFlight(UUID tenantId, UUID instanceId) {
            throw new UnsupportedOperationException();
        }
    }

    private TargetConnectionRecord row() {
        return rows.rows.get(TARGET);
    }

    private static TargetConnectionEdit edit(String host, Integer port, String user, String pin) {
        return new TargetConnectionEdit(host, port, user, pin);
    }

    /** The log up to the end of the edit's own transaction (the read of the record afterwards opens its own). */
    private List<String> editLog() {
        for (int i = 0; i < rows.log.size(); i++) {
            if (rows.log.get(i).equals("commit") || rows.log.get(i).equals("rollback")) {
                return List.copyOf(rows.log.subList(0, i + 1));
            }
        }
        return List.copyOf(rows.log);
    }

    private TargetConnectionView apply(TargetConnectionEdit edit) {
        return service.editConnection(ORG, TARGET, edit, ACTOR, ORIGIN);
    }

    // ---------------------------------------------------------------------------------------------- planEdit

    @Test
    void anEditNamingTheStoredValuesChangesNothingAndRescansNothing() {
        assertEquals(new EditPlan(false, false), service.planEdit(ORG, TARGET, edit("vm1.example.com", 22, "sm", PIN)));
        assertEquals(new EditPlan(false, false), service.planEdit(ORG, TARGET, edit(null, null, null, null)));
    }

    @Test
    void aChangeOfTheSshUserAloneNeedsNoScan() {
        assertEquals(new EditPlan(true, false), service.planEdit(ORG, TARGET, edit(null, null, "deploy", null)));
    }

    @Test
    void aChangeOfHostPortOrPinRescans() {
        assertEquals(new EditPlan(true, true), service.planEdit(ORG, TARGET, edit("vm2.example.com", null, null, null)));
        assertEquals(new EditPlan(true, true), service.planEdit(ORG, TARGET, edit(null, 2222, null, null)));
        assertEquals(new EditPlan(true, true), service.planEdit(ORG, TARGET, edit(null, null, null, SECOND)));
        assertEquals(new EditPlan(true, true), service.planEdit(ORG, TARGET, edit(null, null, "deploy", SECOND)));
    }

    @Test
    void planningDoesNoIoAndOpensNoTransaction() {
        service.planEdit(ORG, TARGET, edit("vm2.example.com", null, "deploy", SECOND));

        assertEquals(List.of(), scans);
        assertEquals(List.of(), rows.log, "no lock, no write, no scan");
        assertEquals(0, commits.get() + rollbacks.get());
    }

    @Test
    void aFieldOutsideItsGrammarIsRefusedNamingTheFieldNotTheValue() {
        for (TargetConnectionEdit bad : List.of(edit("bad host!", null, null, null), edit(null, 0, null, null),
                edit(null, 70000, null, null), edit(null, null, "q q", null),
                edit(null, null, null, "SHA256:short"))) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> service.planEdit(ORG, TARGET, bad));
            assertFalse(refused.getMessage().contains("bad host!") || refused.getMessage().contains("q q"),
                    refused.getMessage());
        }
        assertEquals("host", assertThrows(IllegalArgumentException.class,
                () -> service.planEdit(ORG, TARGET, edit("bad host!", null, null, null))).getMessage().split(" ")[0]);
        assertEquals("sshUser", assertThrows(IllegalArgumentException.class,
                () -> service.planEdit(ORG, TARGET, edit(null, null, "x y", null))).getMessage().split(" ")[0]);
    }

    @Test
    void anAbsentOrRetiredTargetIsNotFound() {
        assertThrows(NoSuchElementException.class,
                () -> service.planEdit(ORG, UUID.randomUUID(), edit(null, null, "deploy", null)));
        service.retireConnection(ORG, TARGET, ACTOR, ORIGIN);
        assertThrows(NoSuchElementException.class, () -> service.planEdit(ORG, TARGET, edit(null, null, "deploy", null)));
        assertThrows(NoSuchElementException.class, () -> apply(edit(null, null, "deploy", null)));
    }

    // ------------------------------------------------------------------------------------- editConnection: user only

    @Test
    void anSshUserOnlyEditIsNeitherScannedNorLockedAgainstWorkNorRefusedWhileWorkIsInFlight() {
        placements.inFlight = true;

        TargetConnectionView view = apply(edit(null, null, "deploy", null));

        assertEquals("deploy", view.sshUser());
        assertEquals("deploy", row().sshUser());
        assertEquals(List.of(), scans, "an SSH user change re-scans nothing");
        assertFalse(rows.log.contains("lockScopes") || rows.log.contains("inFlight"), rows.log.toString());
        assertEquals(1, rows.events.size());
        TargetConnectionEvent event = rows.events.get(0);
        assertEquals(TargetConnectionEvent.Kind.EDITED, event.kind());
        assertEquals(List.of("sshUser"), event.changedFields());
        assertEquals(ACTOR, event.actor());
        assertEquals(ORIGIN.correlationId(), event.correlationId());
        assertEquals("203.0.113.9", event.clientAddress());
        assertNull(event.publicKeyFingerprint(), "only a registration and a rotation carry a public fingerprint");
        assertEquals(NOW, event.recordedAt());
        assertEquals(Set.of(PIN, SECOND), rows.liveHostKeys(ORG, TARGET), "the host keys are untouched");
    }

    @Test
    void anEditThatChangesNothingWritesNothingAndTakesNoLock() {
        TargetConnectionView view = apply(edit("vm1.example.com", 22, "sm", PIN));

        assertEquals("vm1.example.com", view.host());
        assertEquals(List.of(), rows.events);
        assertEquals(0, rows.updatesOfConnection);
        assertEquals(List.of(), scans);
        assertFalse(rows.log.contains("lockRecord") || rows.log.contains("lockScopes"), "not even a lock: " + rows.log);
    }

    // ---------------------------------------------------------------------------------- editConnection: host / pin

    @Test
    void aHostChangeScansTheNewHostThenLocksScopesThenTheRecordThenChecksThenWrites() {
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"), new OfferedHostKey(THIRD, "ssh-rsa"));

        TargetConnectionView view = apply(edit("vm2.example.com", 2222, null, null));

        assertEquals(List.of("vm2.example.com:2222"), scans);
        assertEquals(List.of("scan", "begin", "lockScopes", "lockRecord", "inFlight", "updateConnection", "retireKeys",
                "reviveKeys", "commit"), editLog(), "no network I/O under a lock; locks in the established order");
        assertEquals("vm2.example.com", view.host());
        assertEquals(2222, view.port());
        assertEquals("sm", view.sshUser());
        assertEquals(PIN, view.hostKeyFingerprint());
        assertEquals(TargetConnectionEvent.Kind.EDITED, rows.events.get(0).kind());
        assertEquals(List.of("host", "port"), rows.events.get(0).changedFields());
    }

    // review W4C-3: the data plane tells the caller about the scan it makes, and about none it does not make

    private static com.solace.sm.dp.api.inventory.ScanAccounting recording(List<String> into) {
        return new com.solace.sm.dp.api.inventory.ScanAccounting() {
            @Override
            public void beforeScan() {
                into.add("beforeScan");
            }

            @Override
            public void afterScan() {
                into.add("afterScan");
            }
        };
    }

    @Test
    void aScanIsAnnouncedBeforeTheHostIsContactedAndAnsweredWhenItReturnsAndNeverUnderALock() {
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"), new OfferedHostKey(THIRD, "ssh-rsa"));
        rows.log.clear();

        service.editConnection(ORG, TARGET, edit("vm2.example.com", 2222, null, null), ACTOR, ORIGIN, recording(rows.log));

        assertEquals(List.of("beforeScan", "scan", "afterScan", "begin"), rows.log.subList(0, 4),
                "charged, then scanned, then answered, all before the transaction and its locks");
    }

    @Test
    void aSpentBudgetRefusesBeforeAnythingIsScannedLockedOrWritten() {
        com.solace.sm.dp.api.inventory.ScanAccounting spent = () -> {
            throw new IllegalStateException("budget spent");
        };

        assertThrows(IllegalStateException.class,
                () -> service.editConnection(ORG, TARGET, edit("vm2.example.com", 2222, null, null), ACTOR, ORIGIN, spent));

        assertEquals(List.of(), scans);
        assertEquals(List.of(), rows.log);
        assertEquals(0, rows.updatesOfConnection);
    }

    @Test
    void anEditThatNeedsNoScanTellsTheCallerNothingEvenIfTheCallerPlannedOne() {
        List<String> told = new java.util.ArrayList<>();

        service.editConnection(ORG, TARGET, edit(null, null, "deploy", null), ACTOR, ORIGIN, recording(told));
        service.editConnection(ORG, TARGET, edit("vm1.example.com", 22, "deploy", PIN), ACTOR, ORIGIN, recording(told));

        assertEquals(List.of(), told);
    }

    @Test
    void aRefusedPinIsAnsweredBeforeItIsRefusedSoTheScanThatCausedItIsAudited() {
        offered = List.of(new OfferedHostKey(THIRD, "ssh-rsa"));         // the pin is not among them
        List<String> told = new java.util.ArrayList<>();

        assertThrows(com.solace.sm.dp.api.inventory.HostKeyNotOfferedException.class, () -> service.editConnection(ORG,
                TARGET, edit("vm2.example.com", 2222, null, null), ACTOR, ORIGIN, recording(told)));

        assertEquals(List.of("beforeScan", "afterScan"), told);
    }

    @Test
    void aRepinRetiresTheKeysTheHostNoLongerOffersRevivesOnesItOffersAgainAndRecordsTheNewOnes() {
        rows.keys.get(TARGET).put(FOURTH, true);                 // a key once retired
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"), new OfferedHostKey(FOURTH, "ssh-ecdsa"),
                new OfferedHostKey(THIRD, "ssh-ed25519"));

        apply(edit(null, null, null, SECOND));

        Map<String, Boolean> keys = rows.keys.get(TARGET);
        assertEquals(Boolean.TRUE, keys.get(PIN), "the old pin is no longer offered: retired");
        assertEquals(Boolean.FALSE, keys.get(SECOND), "the new pin is live");
        assertEquals(Boolean.FALSE, keys.get(FOURTH), "re-offered: revived");
        assertEquals(Boolean.FALSE, keys.get(THIRD), "new: recorded");
        assertEquals(Set.of(SECOND, FOURTH, THIRD), rows.liveHostKeys(ORG, TARGET));
        assertEquals(SECOND, row().hostKeyFingerprint());
        assertEquals(TargetConnectionEvent.Kind.REPINNED, rows.events.get(0).kind());
        assertEquals(List.of("hostKeyFingerprint"), rows.events.get(0).changedFields());
    }

    // ---- OR-125 (2): the pinned key's type follows the pin ------------------------------------------------------

    @Test
    void aRepinRecordsTheTypeOfTheNewPinFromTheScanItJustMade() {
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"), new OfferedHostKey(THIRD, "ssh-ed25519"));

        apply(edit(null, null, null, SECOND));

        assertEquals(HostKeyType.RSA, row().hostKeyType());
    }

    @Test
    void aRepinToAKeyOfATypeTheDataPlaneDoesNotPinByClearsTheOldTypeInsteadOfKeepingAStaleOne() {
        rows.rows.put(TARGET, row().withHostKeyType(HostKeyType.ED25519));
        offered = List.of(new OfferedHostKey(THIRD, "unknown"));

        apply(edit(null, null, null, THIRD));

        assertNull(row().hostKeyType(), "a type that describes the OLD pin would make every connection fail closed");
    }

    @Test
    void aRepinToAKeyOfAnotherTypeReplacesTheRecordedTypeInTheSameWrite() {
        rows.rows.put(TARGET, row().withHostKeyType(HostKeyType.ED25519));
        offered = List.of(new OfferedHostKey(SECOND, "ecdsa-sha2-nistp384"));

        apply(edit(null, null, null, SECOND));

        assertEquals(HostKeyType.ECDSA_NISTP384, row().hostKeyType());
        assertEquals(1, rows.updatesOfConnection, "pin and type are one statement, never half-changed");
    }

    @Test
    void anEditOfTheHostThatKeepsThePinTakesTheTypeTheNewMachineOffersForIt() {
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"));

        apply(edit("vm2.example.com", null, null, null));

        assertEquals(HostKeyType.ED25519, row().hostKeyType());
    }

    @Test
    void anSshUserOnlyEditKeepsTheRecordedTypeAndScansNothing() {
        rows.rows.put(TARGET, row().withHostKeyType(HostKeyType.RSA));

        apply(edit(null, null, "other", null));

        assertEquals(HostKeyType.RSA, row().hostKeyType());
        assertEquals(List.of(), scans);
    }

    @Test
    void aRepinToTheKeyAlreadyPinnedUnderANewHostKeepsItsRecordedTypeWhenTheScanAgrees() {
        rows.rows.put(TARGET, row().withHostKeyType(HostKeyType.ED25519));
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"), new OfferedHostKey(SECOND, "ssh-rsa"));

        apply(edit(null, 2222, null, null));

        assertEquals(HostKeyType.ED25519, row().hostKeyType());
    }

    @Test
    void aRepinToAKeyThatWasRetiredBeforeRevivesItBecauseThePinnedKeyTriggerWouldLeaveItRetired() {
        rows.keys.get(TARGET).put(THIRD, true);
        offered = List.of(new OfferedHostKey(THIRD, "ssh-ed25519"));

        apply(edit(null, null, null, THIRD));

        assertEquals(Set.of(THIRD), rows.liveHostKeys(ORG, TARGET));
    }

    @Test
    void anEditThatChangesTheHostAndKeepsThePinAlsoRetiresWhatTheNewMachineDoesNotOffer() {
        offered = List.of(new OfferedHostKey(PIN, "ssh-ed25519"));          // SECOND is gone on the new machine

        apply(edit("vm2.example.com", null, null, null));

        assertEquals(Set.of(PIN), rows.liveHostKeys(ORG, TARGET));
        assertEquals(TargetConnectionEvent.Kind.EDITED, rows.events.get(0).kind(), "the pin did not change");
    }

    @Test
    void anEditThatChangesThePinAndOtherFieldsIsARepinNamingEachField() {
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"));

        apply(edit("vm2.example.com", null, "deploy", SECOND));

        TargetConnectionEvent event = rows.events.get(0);
        assertEquals(TargetConnectionEvent.Kind.REPINNED, event.kind());
        assertEquals(List.of("host", "sshUser", "hostKeyFingerprint"), event.changedFields());
    }

    @Test
    void aPinTheNewHostDoesNotOfferIsRefusedBeforeAnyLockOrWrite() {
        offered = List.of(new OfferedHostKey(THIRD, "ssh-rsa"));

        HostKeyNotOfferedException refused = assertThrows(HostKeyNotOfferedException.class,
                () -> apply(edit(null, null, null, SECOND)));

        assertFalse(refused.getMessage().contains(SECOND) || refused.getMessage().contains(THIRD), refused.getMessage());
        assertEquals(List.of("scan"), rows.log, "no transaction, no lock, no write");
        assertEquals(PIN, row().hostKeyFingerprint());
        assertEquals(List.of(), rows.events);
    }

    @Test
    void theKeptPinMustStillBeOfferedWhenOnlyTheHostChanges() {
        offered = List.of(new OfferedHostKey(THIRD, "ssh-rsa"));

        assertThrows(HostKeyNotOfferedException.class, () -> apply(edit("vm2.example.com", null, null, null)));

        assertEquals("vm1.example.com", row().host());
    }

    @Test
    void aKeyAnotherLiveTargetHoldsRefusesTheEditBeforeAnyLockOrWrite() {
        rows.keys.put(UUID.randomUUID(), new LinkedHashMap<>(Map.of(THIRD, false)));
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"), new OfferedHostKey(THIRD, "ssh-ed25519"));

        assertThrows(HostKeyHeldElsewhereException.class, () -> apply(edit(null, null, null, SECOND)));

        assertEquals(List.of("scan"), rows.log);
        assertEquals(PIN, row().hostKeyFingerprint());
    }

    @Test
    void thisTargetsOwnKeysAreNotAnotherTargetsAndThePinMayMoveToAnotherOfThem() {
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"), new OfferedHostKey(PIN, "ssh-ed25519"));

        apply(edit(null, null, null, SECOND));

        assertEquals(SECOND, row().hostKeyFingerprint());
        assertEquals(Set.of(PIN, SECOND), rows.liveHostKeys(ORG, TARGET), "the old pin is still offered by the host");
    }

    @Test
    void aNewPinAnotherTargetsConnectionAlreadyPinsIsRefusedByTheRecordWrite() {
        UUID other = UUID.randomUUID();
        rows.rows.put(other, new TargetConnectionRecord(ORG, other, "vm9.example.com", 22, "sm", SECOND,
                UUID.randomUUID(), "/srv/other", "o-", ACTOR, NOW, null));
        // the other target's keys are not in the key table here, so the pre-check's probe does not see them: the write is
        // the final arbiter (target_connection_host_once)
        offered = List.of(new OfferedHostKey(SECOND, "ssh-rsa"));

        assertThrows(HostKeyHeldElsewhereException.class, () -> apply(edit(null, null, null, SECOND)));

        assertEquals(PIN, row().hostKeyFingerprint());
        assertEquals(List.of(), rows.events);
    }

    @Test
    void anAddressTheDataPlaneMayNotConnectToIsRefusedByTheScanBeforeAnyLock() {
        scanFails = new TargetAddressRefusedException();

        assertThrows(TargetAddressRefusedException.class, () -> apply(edit("127.0.0.1", null, null, null)));

        assertEquals(List.of("scan"), rows.log);
    }

    @Test
    void anUnreachableNewHostIsUnavailableBeforeAnyLock() {
        scanFails = new HostKeyScanUnavailableException();

        assertThrows(HostKeyScanUnavailableException.class, () -> apply(edit("vm2.example.com", null, null, null)));

        assertEquals(List.of("scan"), rows.log);
        assertEquals("vm1.example.com", row().host());
    }

    // ------------------------------------------------------------------------------- editConnection: work in flight

    @Test
    void workInFlightOnAPlacementOfTheTargetRefusesAHostPortOrPinEditBeforeTheFirstWrite() {
        placements.inFlight = true;

        for (TargetConnectionEdit change : List.of(edit("vm2.example.com", null, null, null),
                edit(null, 2222, null, null), edit(null, null, null, SECOND))) {
            rows.log.clear();
            assertThrows(TargetWorkInFlightException.class, () -> apply(change));
            assertEquals(List.of("scan", "begin", "lockScopes", "lockRecord", "inFlight", "rollback"), editLog(),
                    "the refusal comes under the lock and before the first write");
        }
        assertEquals(0, rows.updatesOfConnection);
        assertEquals(List.of(), rows.events);
        assertEquals("vm1.example.com", row().host());
    }

    @Test
    void theSameEditAfterTheWorkHasEndedSucceeds() {
        placements.inFlight = true;
        assertThrows(TargetWorkInFlightException.class, () -> apply(edit(null, 2222, null, null)));

        placements.inFlight = false;
        TargetConnectionView view = apply(edit(null, 2222, null, null));

        assertEquals(2222, view.port());
    }

    @Test
    void theRefusalNamesNoRequestInstanceOrPlacement() {
        placements.inFlight = true;

        TargetWorkInFlightException refused = assertThrows(TargetWorkInFlightException.class,
                () -> apply(edit(null, 2222, null, null)));

        assertFalse(refused.getMessage().matches(".*[0-9a-f]{8}-[0-9a-f]{4}.*"), refused.getMessage());
    }

    @Test
    void aRecordThatMovedUnderTheEditAfterTheScanIsARetryNotAWriteOfAHostNobodyScanned() {
        // The caller named only a new port. The plan was made against vm1 (so vm1:2222 was scanned); another edit then moved
        // the host to vm3 before this one took the lock. Writing vm3:2222 would pin a machine nobody looked at.
        rows.beforeLockForChange = () -> {
            TargetConnectionRecord r = rows.rows.get(TARGET);
            rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm3.example.com", r.port(), r.sshUser(),
                    r.hostKeyFingerprint(), r.credentialRef(), r.allowedConfigRoot(), r.allowedContainerPrefix(),
                    r.createdBy(), r.createdAt(), null, r.credentialPublicFingerprint()));
            rows.beforeLockForChange = () -> { };
        };

        assertThrows(TargetConnectionChangedException.class, () -> apply(edit(null, 2222, null, null)));

        assertEquals(List.of("vm1.example.com:2222"), scans);
        assertEquals(0, rows.updatesOfConnection);
        assertEquals(List.of(), rows.events);
        assertEquals("vm3.example.com", row().host());
    }

    @Test
    void aFieldNamedAsUnchangedThatTheLockedRecordNowDiffersInNeedsAScanItNeverHadAndIsARetry() {
        rows.beforeLockForChange = () -> {
            TargetConnectionRecord r = rows.rows.get(TARGET);
            rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, "vm3.example.com", r.port(), r.sshUser(),
                    r.hostKeyFingerprint(), r.credentialRef(), r.allowedConfigRoot(), r.allowedContainerPrefix(),
                    r.createdBy(), r.createdAt(), null, r.credentialPublicFingerprint()));
            rows.beforeLockForChange = () -> { };
        };

        // names the host the record had when read ("no change") and an SSH user change that needs no scan; under the lock
        // the host named differs from the moved record, so the edit would now move the host without a scan ever being made
        assertThrows(TargetConnectionChangedException.class,
                () -> apply(edit("vm1.example.com", null, "deploy", null)));

        assertEquals(0, rows.updatesOfConnection);
        assertEquals(List.of(), rows.events);
    }

    // ------------------------------------------------------------------------------------------------- rotation

    @Test
    void aNewKeyIsStoredOnceRepointedAndRecordedWithItsPublicFingerprintUnderTheRowLock() {
        TargetConnectionView.CredentialSummary summary = service.rotateConnectionCredential(ORG, TARGET,
                OTHER_KEY.chars(), ACTOR, ORIGIN);

        assertEquals(OTHER_KEY.publicFingerprint(), summary.publicKeyFingerprint());
        assertTrue(summary.present());
        assertEquals(NOW, summary.setAt());
        assertEquals(1, secrets.puts.get());
        assertEquals(OTHER_KEY.publicFingerprint(), row().credentialPublicFingerprint());
        assertTrue(rows.log.contains("lockRecord"), "the record is locked for the compare-then-write (T1.1's residual)");
        assertEquals(1, rows.events.size());
        TargetConnectionEvent event = rows.events.get(0);
        assertEquals(TargetConnectionEvent.Kind.CREDENTIAL_ROTATED, event.kind());
        assertEquals(OTHER_KEY.publicFingerprint(), event.publicKeyFingerprint());
        assertEquals(List.of(), event.changedFields());
        assertEquals(ACTOR, event.actor());
        assertEquals(ORIGIN.correlationId(), event.correlationId());
    }

    @Test
    void theSameKeyAgainInAnyEncodingWritesNothingAndRecordsNoEvent() {
        for (SshTestKeys.Key same : List.of(KEY, KEY.crlf(), KEY.withoutTrailingNewline())) {
            TargetConnectionView.CredentialSummary summary = service.rotateConnectionCredential(ORG, TARGET,
                    same.chars(), ACTOR, ORIGIN);

            assertEquals(KEY.publicFingerprint(), summary.publicKeyFingerprint());
        }
        assertEquals(0, secrets.puts.get(), "no second sm_secret row (OR-85)");
        assertEquals(0, rows.updatesOfCredential);
        assertEquals(List.of(), rows.events);
    }

    @Test
    void aKeyThatDoesNotOpenIsRefusedBeforeAnythingIsReadOrWritten() {
        assertThrows(CredentialInvalidException.class, () -> service.rotateConnectionCredential(ORG, TARGET,
                "-----BEGIN NOPE-----".toCharArray(), ACTOR, ORIGIN));

        assertEquals(List.of(), rows.log);
        assertEquals(0, secrets.puts.get());
    }

    @Test
    void rotatingAnAbsentOrRetiredTargetIsNotFoundAndStoresNothing() {
        assertThrows(NoSuchElementException.class, () -> service.rotateConnectionCredential(ORG, UUID.randomUUID(),
                OTHER_KEY.chars(), ACTOR, ORIGIN));
        service.retireConnection(ORG, TARGET, ACTOR, ORIGIN);
        assertThrows(NoSuchElementException.class,
                () -> service.rotateConnectionCredential(ORG, TARGET, OTHER_KEY.chars(), ACTOR, ORIGIN));
        assertEquals(0, secrets.puts.get());
    }

    @Test
    void aRotationIsNotRefusedWhileWorkIsInFlightAndTakesNoApplyScopeLock() {
        placements.inFlight = true;

        service.rotateConnectionCredential(ORG, TARGET, OTHER_KEY.chars(), ACTOR, ORIGIN);

        assertEquals(OTHER_KEY.publicFingerprint(), row().credentialPublicFingerprint());
        assertFalse(rows.log.contains("lockScopes") || rows.log.contains("inFlight"), rows.log.toString());
    }

    @Test
    void aStoredValueThatIsNoKeyIsReplacedByTheRotationThatRepairsIt() {
        TargetConnectionRecord r = row();
        rows.rows.put(TARGET, new TargetConnectionRecord(ORG, TARGET, r.host(), r.port(), r.sshUser(),
                r.hostKeyFingerprint(), secrets.seed("not a key"), r.allowedConfigRoot(), r.allowedContainerPrefix(),
                r.createdBy(), r.createdAt(), null));       // a legacy row: no fingerprint, and the stored text is no key

        TargetConnectionView.CredentialSummary summary = service.rotateConnectionCredential(ORG, TARGET,
                OTHER_KEY.chars(), ACTOR, ORIGIN);

        assertEquals(OTHER_KEY.publicFingerprint(), summary.publicKeyFingerprint());
        assertEquals(1, rows.events.size());
    }

    @Test
    void theOperatorCommandsRotationAlsoRecordsTheEventWithTheCommandsOrigin() {
        service.rotateCredential(ORG, TARGET, OTHER_KEY.chars(), ACTOR);

        assertEquals(1, rows.events.size());
        assertEquals(TargetConnectionEvent.Kind.CREDENTIAL_ROTATED, rows.events.get(0).kind());
        assertEquals(TargetChangeOrigin.OPERATOR_COMMAND_ADDRESS, rows.events.get(0).clientAddress());
    }

    // ------------------------------------------------------------------------------------------------ retirement

    @Test
    void retiringALiveTargetWithNoPlacementRetiresItAndRecordsTheEventInTheSameTransaction() {
        ConnectionRetirement outcome = service.retireConnection(ORG, TARGET, ACTOR, ORIGIN);

        assertEquals(ConnectionRetirement.RETIRED, outcome);
        assertEquals(NOW, row().retiredAt());
        assertEquals(Set.of(), rows.liveHostKeys(ORG, TARGET), "retiring frees the host keys (V012)");
        assertEquals(1, rows.events.size());
        TargetConnectionEvent event = rows.events.get(0);
        assertEquals(TargetConnectionEvent.Kind.RETIRED, event.kind());
        assertEquals(ACTOR, event.actor());
        assertEquals(List.of(), event.changedFields());
        assertTrue(rows.log.indexOf("begin") < rows.log.indexOf("retire") && rows.log.indexOf("retire") < rows.log.indexOf("commit"));
        assertEquals(1, commits.get());
    }

    @Test
    void theRetirementLocksTheRecordForUpdateBeforeItLooksForPlacementsAndBeforeItWrites() {
        // W4C-2: a plain UPDATE of retired_at takes only FOR NO KEY UPDATE, so a placement writer that has read the target
        // as live would not be made to wait. The strong lock comes first, then the pre-check, then the write.
        service.retireConnection(ORG, TARGET, ACTOR, ORIGIN);

        assertTrue(rows.log.indexOf("begin") < rows.log.indexOf("lockForRetirement")
                && rows.log.indexOf("lockForRetirement") < rows.log.indexOf("retire"), rows.log.toString());
    }

    @Test
    void aLivePlacementRefusesTheRetirementBeforeAnyWrite() {
        placements.liveOnTarget = true;

        assertThrows(TargetHasLivePlacementsException.class, () -> service.retireConnection(ORG, TARGET, ACTOR, ORIGIN));

        assertNull(row().retiredAt());
        assertEquals(0, rows.retirements);
        assertEquals(List.of(), rows.events);
    }

    @Test
    void theGuardTriggerRefusingTheUpdateIsTheSameRefusalAndNoEventIsWritten() {
        rows.retireFailure = new TargetHasLivePlacementsException();      // a placement slipped in after the pre-check

        assertThrows(TargetHasLivePlacementsException.class, () -> service.retireConnection(ORG, TARGET, ACTOR, ORIGIN));

        assertEquals(List.of(), rows.events);
        assertEquals(1, rollbacks.get());
    }

    @Test
    void retiringAnAlreadyRetiredTargetIsAnAnswerAndWritesNothing() {
        service.retireConnection(ORG, TARGET, ACTOR, ORIGIN);
        rows.events.clear();
        rows.retirements = 0;

        assertEquals(ConnectionRetirement.ALREADY_RETIRED, service.retireConnection(ORG, TARGET, ACTOR, ORIGIN));

        assertEquals(0, rows.retirements);
        assertEquals(List.of(), rows.events);
    }

    @Test
    void aTargetTheDataPlaneNeverHeardOfIsNoConnection() {
        assertEquals(ConnectionRetirement.NO_CONNECTION,
                service.retireConnection(ORG, UUID.randomUUID(), ACTOR, ORIGIN));
        assertEquals(List.of(), rows.events);
    }
}
