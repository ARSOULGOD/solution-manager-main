package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.TenantId;
import java.util.Optional;

/**
 * Where {@link HmacConfigHasher} gets a tenant's key. The seam exists so the store behind it can be a database
 * of envelope-encrypted secrets today and gain a bounded per-tenant cache later without touching a caller
 * (D-KEY 7.2: not cached in v1, one read and two GCM operations per hash).
 *
 * <p>Every method resolves a key for ONE use; the caller closes it.
 */
public interface HashKeySource {

    /**
     * The key new hashes are spelled with. Minted on first use by whichever caller needs it first (D-KEY 7.2).
     */
    HashKey current(TenantId tenant);

    /**
     * A named version, for verifying a hash written earlier. Never mints.
     *
     * @return empty when this tenant has no such version, or it cannot be loaded
     */
    Optional<HashKey> version(TenantId tenant, int keyVersion);
}
