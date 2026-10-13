package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory {@link TargetConnectionRows} for the registration unit tests (plan 013 T1.4). It models the two unique
 * rules the real store gets from PostgreSQL: a target id is unique, and a live host-key fingerprint belongs to one
 * target of a tenant.
 *
 * <p>{@link #beforeInsertOnce} is the race hook: a test sets it to commit "the other request's" row at the instant this
 * one tries to insert, which makes {@code insertOnce} answer {@code false} exactly as the primary key would.
 */
final class InMemoryTargetConnectionRows implements TargetConnectionRows {

    final Map<UUID, TargetConnectionRecord> rows = new LinkedHashMap<>();
    /** target id to (fingerprint to retired?). */
    final Map<UUID, Map<String, Boolean>> keys = new LinkedHashMap<>();
    final List<TargetConnectionEvent> events = new ArrayList<>();
    final List<String> backfills = new ArrayList<>();
    int updatesOfCredential;
    String lastCredentialFingerprint;
    Runnable beforeInsertOnce = () -> { };

    private void holdKey(UUID tenant, UUID target, String fingerprint, boolean viaOnce) {
        for (Map.Entry<UUID, Map<String, Boolean>> other : keys.entrySet()) {
            if (!other.getKey().equals(target) && Boolean.FALSE.equals(other.getValue().get(fingerprint))) {
                if (viaOnce) {
                    throw new HostKeyHeldElsewhereException();
                }
                throw new IllegalStateException("target_host_key_once");
            }
        }
        keys.computeIfAbsent(target, t -> new LinkedHashMap<>()).putIfAbsent(fingerprint, false);
    }

    @Override
    public void insert(TargetConnectionRecord row) {
        if (rows.containsKey(row.targetId())) {
            throw new IllegalStateException("target_connection_pkey");
        }
        rows.put(row.targetId(), row);
        holdKey(row.tenantId(), row.targetId(), row.hostKeyFingerprint(), false);
    }

    @Override
    public Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId) {
        return Optional.ofNullable(rows.get(targetId));
    }

    @Override
    public void addHostKeys(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
        for (String fingerprint : fingerprints) {
            holdKey(tenantId, targetId, fingerprint, false);
        }
    }

    @Override
    public Set<String> hostKeys(UUID tenantId, UUID targetId) {
        return new LinkedHashSet<>(keys.getOrDefault(targetId, Map.of()).keySet());
    }

    @Override
    public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef) {
        return updateCredential(tenantId, targetId, credentialRef, null);
    }

    @Override
    public boolean insertOnce(TargetConnectionRecord row) {
        beforeInsertOnce.run();
        if (rows.containsKey(row.targetId())) {
            return false;
        }
        rows.put(row.targetId(), row);
        holdKey(row.tenantId(), row.targetId(), row.hostKeyFingerprint(), true);
        return true;
    }

    @Override
    public void addHostKeysOnce(UUID tenantId, UUID targetId, Collection<String> fingerprints) {
        for (String fingerprint : fingerprints) {
            holdKey(tenantId, targetId, fingerprint, true);
        }
    }

    @Override
    public Optional<UUID> liveHolderOfAny(UUID tenantId, Collection<String> fingerprints, UUID exceptTargetId) {
        for (Map.Entry<UUID, Map<String, Boolean>> target : keys.entrySet()) {
            if (target.getKey().equals(exceptTargetId)) {
                continue;
            }
            for (String fingerprint : fingerprints) {
                if (Boolean.FALSE.equals(target.getValue().get(fingerprint))) {
                    return Optional.of(target.getKey());
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Set<String> liveHostKeys(UUID tenantId, UUID targetId) {
        Set<String> live = new LinkedHashSet<>();
        keys.getOrDefault(targetId, Map.of()).forEach((fingerprint, retired) -> {
            if (!retired) {
                live.add(fingerprint);
            }
        });
        return live;
    }

    @Override
    public void backfillCredentialFingerprint(UUID tenantId, UUID targetId, String fingerprint) {
        backfills.add(fingerprint);
        TargetConnectionRecord row = rows.get(targetId);
        if (row != null && row.credentialPublicFingerprint() == null) {
            rows.put(targetId, row.withCredentialPublicFingerprint(fingerprint));
        }
    }

    @Override
    public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef, String publicFingerprint) {
        TargetConnectionRecord row = rows.get(targetId);
        if (row == null || row.retiredAt() != null) {
            return 0;
        }
        updatesOfCredential++;
        lastCredentialFingerprint = publicFingerprint;
        rows.put(targetId, new TargetConnectionRecord(row.tenantId(), row.targetId(), row.host(), row.port(),
                row.sshUser(), row.hostKeyFingerprint(), credentialRef, row.allowedConfigRoot(),
                row.allowedContainerPrefix(), row.createdBy(), row.createdAt(), row.retiredAt(), publicFingerprint,
                row.hostKeyType()));
        return 1;
    }

    /** Every {@code recordHostKeyType} call, whether or not it changed a row: {@code fingerprint:type}. */
    final List<String> typeRecords = new ArrayList<>();

    @Override
    public int recordHostKeyType(UUID tenantId, String fingerprint, HostKeyType type) {
        typeRecords.add(fingerprint + ":" + type);
        java.util.Objects.requireNonNull(type, "type");           // the real statement binds type.wire()
        int changed = 0;
        for (Map.Entry<UUID, TargetConnectionRecord> entry : rows.entrySet()) {
            TargetConnectionRecord row = entry.getValue();
            // the real statement: this tenant's LIVE row pinned to this fingerprint, and only while no type is recorded
            if (row.tenantId().equals(tenantId) && row.retiredAt() == null && row.hostKeyFingerprint().equals(fingerprint)
                    && row.hostKeyType() == null) {
                entry.setValue(row.withHostKeyType(type));
                changed++;
            }
        }
        return changed;
    }

    @Override
    public void recordEvent(TargetConnectionEvent event) {
        events.add(event);
    }

    @Override
    public Optional<Instant> credentialSetAt(UUID tenantId, UUID targetId) {
        return events.stream().filter(e -> e.targetId().equals(targetId))
                .filter(e -> e.kind() == TargetConnectionEvent.Kind.REGISTERED
                        || e.kind() == TargetConnectionEvent.Kind.CREDENTIAL_ROTATED)
                .map(TargetConnectionEvent::recordedAt).max(Instant::compareTo)
                .or(() -> Optional.ofNullable(rows.get(targetId)).map(TargetConnectionRecord::createdAt));
    }

    // ---- plan 013 T1.5 ------------------------------------------------------------------------------------------

    /** The ordered log of what was asked of the store, so a test can assert the ORDER of a lock and a write. */
    final List<String> log = new ArrayList<>();
    int updatesOfConnection;
    int retirements;
    /** Runs when the record is locked for change, before it is read: a test commits "the other request's" change here. */
    Runnable beforeLockForChange = () -> { };
    RuntimeException retireFailure;

    @Override
    public Optional<TargetConnectionRecord> findLiveForChange(UUID tenantId, UUID targetId) {
        beforeLockForChange.run();
        log.add("lockRecord");
        return Optional.ofNullable(rows.get(targetId)).filter(r -> r.retiredAt() == null);
    }

    /** Runs when a placement writer holds the record live, before it is read: a test commits "the other request's" retirement here. */
    Runnable beforeHoldLive = () -> { };

    @Override
    public Optional<TargetConnectionRecord> findForRetirement(UUID tenantId, UUID targetId) {
        log.add("lockForRetirement");
        return Optional.ofNullable(rows.get(targetId));
    }

    @Override
    public boolean holdLive(UUID tenantId, UUID targetId) {
        beforeHoldLive.run();
        log.add("holdLive");
        TargetConnectionRecord row = rows.get(targetId);
        return row != null && row.retiredAt() == null;
    }

    @Override
    public int updateConnection(UUID tenantId, UUID targetId, String host, int port, String sshUser,
            String hostKeyFingerprint, HostKeyType hostKeyType) {
        log.add("updateConnection");
        TargetConnectionRecord row = rows.get(targetId);
        if (row == null || row.retiredAt() != null) {
            return 0;
        }
        // target_connection_host_once: a live pin belongs to one target
        for (TargetConnectionRecord other : rows.values()) {
            if (!other.targetId().equals(targetId) && other.retiredAt() == null
                    && other.hostKeyFingerprint().equals(hostKeyFingerprint)) {
                throw new HostKeyHeldElsewhereException();
            }
        }
        updatesOfConnection++;
        rows.put(targetId, new TargetConnectionRecord(row.tenantId(), row.targetId(), host, port, sshUser,
                hostKeyFingerprint, row.credentialRef(), row.allowedConfigRoot(), row.allowedContainerPrefix(),
                row.createdBy(), row.createdAt(), row.retiredAt(), row.credentialPublicFingerprint(), hostKeyType));
        // V012's trigger: the pinned key is always among the target's keys (and the table's own unique index applies)
        holdKey(tenantId, targetId, hostKeyFingerprint, true);
        return 1;
    }

    @Override
    public int retireHostKeysNotIn(UUID tenantId, UUID targetId, Collection<String> keep, Instant at) {
        log.add("retireKeys");
        int retired = 0;
        for (Map.Entry<String, Boolean> key : keys.getOrDefault(targetId, new LinkedHashMap<>()).entrySet()) {
            if (!key.getValue() && !keep.contains(key.getKey())) {
                key.setValue(true);
                retired++;
            }
        }
        return retired;
    }

    @Override
    public int reviveHostKeys(UUID tenantId, UUID targetId, Collection<String> offered) {
        log.add("reviveKeys");
        int revived = 0;
        for (Map.Entry<String, Boolean> key : keys.getOrDefault(targetId, new LinkedHashMap<>()).entrySet()) {
            if (key.getValue() && offered.contains(key.getKey())) {
                for (Map.Entry<UUID, Map<String, Boolean>> other : keys.entrySet()) {
                    if (!other.getKey().equals(targetId) && Boolean.FALSE.equals(other.getValue().get(key.getKey()))) {
                        throw new HostKeyHeldElsewhereException();
                    }
                }
                key.setValue(false);
                revived++;
            }
        }
        return revived;
    }

    @Override
    public int retire(UUID tenantId, UUID targetId, Instant at) {
        log.add("retire");
        if (retireFailure != null) {
            throw retireFailure;
        }
        TargetConnectionRecord row = rows.get(targetId);
        if (row == null || row.retiredAt() != null) {
            return 0;
        }
        retirements++;
        rows.put(targetId, new TargetConnectionRecord(row.tenantId(), row.targetId(), row.host(), row.port(),
                row.sshUser(), row.hostKeyFingerprint(), row.credentialRef(), row.allowedConfigRoot(),
                row.allowedContainerPrefix(), row.createdBy(), row.createdAt(), at, row.credentialPublicFingerprint()));
        // V012's trigger: retiring a target retires its keys, which frees them
        keys.getOrDefault(targetId, new LinkedHashMap<>()).replaceAll((fingerprint, retired) -> true);
        return 1;
    }
}
