package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.TenantId;

/**
 * OR-32: the write-verification, crash-recovery and ladder hash. One owner ({@code BE-D21}), now keyed, and
 * keyed per tenant (D-KEY 7.2; owner ruling OR-85, OQ-5).
 *
 * <p>An implementation hashes in the Data Plane, from bytes the DP already holds. The key never leaves the DP,
 * so no hash is ever computed on a customer host.
 *
 * <p>{@code I6} is still named by OR-32: the keyed hash depends on the <b>resolved</b> bytes, secrets included.
 * It is keyed precisely so that a holder of a hash alone cannot test guessed secrets against it.
 */
public interface ConfigHasher {

    /** {@code hmacsha256v<keyVersion>:<64 lowercase hex>} for {@link HmacConfigHasher}. */
    String hash(TenantId tenant, byte[] bytes);

    /**
     * Whether {@code hash} describes {@code bytes} for this tenant, under whichever key version the hash names,
     * provided that version is still loadable.
     *
     * @return {@code false} when the hash is a well-formed claim about different bytes, or is not spelled in
     *         this hasher's grammar
     * @throws ConfigHashUnverifiableException when the hash names a key version that cannot be loaded.
     *         "Could not check" is a different fact from "does not match", and the ladder must be able to
     *         tell them apart (D-KEY 9 note 5)
     */
    boolean verifies(TenantId tenant, String hash, byte[] bytes);
}
