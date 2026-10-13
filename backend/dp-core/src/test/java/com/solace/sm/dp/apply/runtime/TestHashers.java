package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Test-only hashing helpers. {@link #SHA256} is the unkeyed hasher the engine and adapter tests use where the
 * hash's key is not what is under test: after OR-32 no unkeyed hasher exists in dp-core MAIN code, so a
 * production wiring cannot pick one up, and the one place that needs it outside tests is the demo's own
 * {@code DemoUnkeyedConfigHasher}. It ignores the tenant.
 *
 * <p>The keyed hasher's own behaviour is pinned by {@code HmacConfigHasherTest} and the secret-store ITs, not here.
 */
public final class TestHashers {

    /** A tenant for tests that need one and do not care which. */
    public static final TenantId TENANT = new TenantId(UUID.fromString("00000000-0000-0000-0000-00000000f001"));

    /** Unkeyed SHA-256 spelled {@code sha256:<hex>}; tenant-blind. Test scope only. */
    public static final ConfigHasher SHA256 = new ConfigHasher() {
        @Override
        public String hash(TenantId tenant, byte[] bytes) {
            try {
                return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available on this JVM");
            }
        }

        @Override
        public boolean verifies(TenantId tenant, String hash, byte[] bytes) {
            return hash(tenant, bytes).equals(hash);
        }
    };

    private TestHashers() {
    }

    /** The production hasher over in-memory keys: keyed, per tenant, spelled {@code hmacsha256v1:<hex>}. */
    public static ConfigHasher keyed() {
        return new HmacConfigHasher(new InMemoryHashKeys());
    }

    public static RenderedConfig rendered(byte[] bytes) {
        return RenderedConfig.of(TENANT, bytes, SHA256);
    }

    public static RenderedConfig rendered(String utf8) {
        return rendered(utf8.getBytes(StandardCharsets.UTF_8));
    }
}
