package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.TenantId;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * OR-32's hash: {@code HmacSHA256} under the tenant's key (D-KEY 7.2), spelled
 * {@code hmacsha256v<keyVersion>:<64 lowercase hex>}.
 *
 * <p>The spelling fits the model grammar {@code ^[a-z0-9]+:[0-9a-f]{16,128}$} and V002's
 * {@code resolution_is_evidence} element predicate unchanged, so no migration widens a grammar. A spelling with
 * a {@code -} or a second {@code :} fails both, which is why it is not used.
 *
 * <p>The name carries the key <b>version</b>, never the key. Verification accepts any version the source can
 * still load; v1 mints only version 1 and builds no hash-key rotation (OR-85, OQ-5).
 */
public final class HmacConfigHasher implements ConfigHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern SPELLING = Pattern.compile("^hmacsha256v([1-9][0-9]{0,8}):([0-9a-f]{64})$");

    private final HashKeySource keys;

    public HmacConfigHasher(HashKeySource keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public String hash(TenantId tenant, byte[] bytes) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(bytes, "bytes");
        try (HashKey key = keys.current(tenant)) {
            return spell(key.version(), hmac(key, bytes));
        }
    }

    @Override
    public boolean verifies(TenantId tenant, String hash, byte[] bytes) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(bytes, "bytes");
        Matcher m = hash == null ? null : SPELLING.matcher(hash);
        if (m == null || !m.matches()) {
            return false;
        }
        int version = Integer.parseInt(m.group(1));
        Optional<HashKey> loaded = keys.version(tenant, version);
        if (loaded.isEmpty()) {
            throw new ConfigHashUnverifiableException(version);
        }
        try (HashKey key = loaded.get()) {
            byte[] expected = HexFormat.of().parseHex(m.group(2));
            return MessageDigest.isEqual(expected, hmac(key, bytes));
        }
    }

    private static byte[] hmac(HashKey key, byte[] bytes) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key.material(), ALGORITHM));
            return mac.doFinal(bytes);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandated by the JDK; this is not a caller-recoverable failure, and the cause is
            // not chained (a provider's message is not ours to forward).
            throw new IllegalStateException("HmacSHA256 is not usable on this JVM");
        }
    }

    private static String spell(int keyVersion, byte[] digest) {
        return "hmacsha256v" + keyVersion + ":" + HexFormat.of().formatHex(digest);
    }

    /** Names no key and no hash. */
    @Override
    public String toString() {
        return "HmacConfigHasher";
    }
}
