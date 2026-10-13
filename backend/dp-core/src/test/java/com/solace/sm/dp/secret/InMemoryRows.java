package com.solace.sm.dp.secret;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory stand-ins for the two persistence ports, for the unit tests. */
final class InMemoryRows implements SecretRows, CanaryRows {

    final Map<String, SecretRow> secrets = new HashMap<>();
    final Map<KeyId, CanaryRow> canaries = new LinkedHashMap<>();
    /** Set to simulate another boot minting a canary between our read and our insert. */
    CanaryRow racingCanary;
    /** Set to simulate another caller minting the tenant's first hash key between our read and our insert. */
    SecretRow racingHashKey;

    private static String k(UUID tenant, UUID secret) {
        return tenant + "/" + secret;
    }

    @Override
    public void insert(SecretRow row) {
        secrets.put(k(row.tenantId(), row.secretId()), row);
    }

    @Override
    public Optional<SecretRow> find(UUID tenantId, UUID secretId) {
        return Optional.ofNullable(secrets.get(k(tenantId, secretId)));
    }

    @Override
    public boolean exists(UUID tenantId, UUID secretId) {
        return secrets.containsKey(k(tenantId, secretId));
    }

    @Override
    public Optional<SecretRow> findHashKey(UUID tenantId, int keyVersion) {
        return secrets.values().stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.purpose() == SecretPurpose.CONFIG_HASH_KEY
                        && r.keyVersion() == keyVersion)
                .findFirst();
    }

    @Override
    public Optional<SecretRow> latestHashKey(UUID tenantId) {
        return secrets.values().stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.purpose() == SecretPurpose.CONFIG_HASH_KEY)
                .max(java.util.Comparator.comparingInt(SecretRow::keyVersion));
    }

    @Override
    public boolean insertHashKeyIfAbsent(SecretRow row) {
        if (racingHashKey != null) {
            SecretRow winner = racingHashKey;
            racingHashKey = null;
            secrets.put(k(winner.tenantId(), winner.secretId()), winner);
        }
        if (findHashKey(row.tenantId(), row.keyVersion()).isPresent()) {
            return false;
        }
        secrets.put(k(row.tenantId(), row.secretId()), row);
        return true;
    }

    @Override
    public Optional<CanaryRow> find(KeyId keyId) {
        return Optional.ofNullable(canaries.get(keyId));
    }

    @Override
    public boolean anyExists() {
        return !canaries.isEmpty();
    }

    @Override
    public boolean anySecretExists() {
        return !secrets.isEmpty();
    }

    @Override
    public boolean insertIfAbsent(CanaryRow row) {
        if (racingCanary != null) {
            canaries.putIfAbsent(racingCanary.sealed().keyId(), racingCanary);
            racingCanary = null;
        }
        return canaries.putIfAbsent(row.sealed().keyId(), row) == null;
    }
}
