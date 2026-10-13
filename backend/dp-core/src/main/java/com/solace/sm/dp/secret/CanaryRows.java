package com.solace.sm.dp.secret;

import java.util.Optional;

/** The persistence port of the boot canary. Install-level: no method takes a tenant. */
public interface CanaryRows {

    Optional<CanaryRow> find(KeyId keyId);

    /** Whether any canary exists. An empty table, with no secrets either, is a fresh install. */
    boolean anyExists();

    /**
     * Whether any secret row exists, as a boolean: no tenant id, no value, no key id leaves the database. A fresh
     * install has none; a first canary is minted only when this is false, so a restored or hand-edited canary
     * table cannot make the wrong key look like a fresh install.
     */
    boolean anySecretExists();

    /** {@code INSERT ... ON CONFLICT (key_id) DO NOTHING}; true when this call inserted the row. */
    boolean insertIfAbsent(CanaryRow row);
}
