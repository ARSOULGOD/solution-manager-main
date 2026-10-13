package com.solace.sm.dp.secret;

import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port of the secret store. The crypto in this package imports no JDBC type
 * ({@code jdbcIsConfinedToPersistencePackages}); {@code JdbcSecretRepository} implements this. Every method
 * is tenant-scoped ({@code I5}).
 */
public interface SecretRows {

    void insert(SecretRow row);

    Optional<SecretRow> find(UUID tenantId, UUID secretId);

    boolean exists(UUID tenantId, UUID secretId);

    // ---- the per-tenant config-hash key (OR-32; D-KEY 7.2) -----------------------------------------------------

    /** The tenant's {@code CONFIG_HASH_KEY} row of exactly this version, if there is one. */
    Optional<SecretRow> findHashKey(UUID tenantId, int keyVersion);

    /** The tenant's {@code CONFIG_HASH_KEY} row with the highest version, if any. */
    Optional<SecretRow> latestHashKey(UUID tenantId);

    /**
     * Inserts a {@code CONFIG_HASH_KEY} row unless the tenant already has that version
     * ({@code ON CONFLICT (tenant_id, key_version) DO NOTHING}): two callers racing to mint the first key both
     * insert and exactly one wins.
     *
     * @return {@code true} when this call inserted the row, {@code false} when another caller's row won
     */
    boolean insertHashKeyIfAbsent(SecretRow row);
}
