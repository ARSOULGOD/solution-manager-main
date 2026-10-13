package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link HashKeySource} for tests that are not about the store: every tenant gets a different, deterministic
 * 32-byte key per version, so a hasher built on it is genuinely keyed and genuinely per tenant. Versions can be
 * added (the newest becomes current) and forgotten (to simulate a key that can no longer be loaded).
 */
public final class InMemoryHashKeys implements HashKeySource {

    private int current = 1;
    private final Set<Integer> forgotten = new HashSet<>();

    /** Makes {@code version} the current one, as a later release that rotates hash keys would. */
    public InMemoryHashKeys currentIs(int version) {
        this.current = version;
        return this;
    }

    /** From now on {@code version} cannot be loaded. */
    public InMemoryHashKeys forget(int version) {
        forgotten.add(version);
        return this;
    }

    @Override
    public HashKey current(TenantId tenant) {
        return new HashKey(current, keyFor(tenant, current));
    }

    @Override
    public Optional<HashKey> version(TenantId tenant, int keyVersion) {
        if (forgotten.contains(keyVersion) || keyVersion < 1 || keyVersion > current) {
            return Optional.empty();
        }
        return Optional.of(new HashKey(keyVersion, keyFor(tenant, keyVersion)));
    }

    private static byte[] keyFor(TenantId tenant, int version) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("test-hash-key|" + tenant.value() + "|" + version).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
