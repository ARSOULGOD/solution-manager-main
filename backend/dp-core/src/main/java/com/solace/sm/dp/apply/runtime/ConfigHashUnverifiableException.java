package com.solace.sm.dp.apply.runtime;

/**
 * A hash could not be checked because the key version it names is not loadable. This is NOT a mismatch:
 * mapping it to "does not verify" would report {@code CONFIG_DIVERGED} for an instance nobody changed
 * (D-KEY 9 note 5). The message is a classifier; it carries no key, no hash and no tenant, and no cause is
 * chained.
 */
public final class ConfigHashUnverifiableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CLASSIFIER = "config.hash.unverifiable";

    private final int keyVersion;

    public ConfigHashUnverifiableException(int keyVersion) {
        super(CLASSIFIER + ": key version " + keyVersion);
        this.keyVersion = keyVersion;
    }

    /** The key version that could not be loaded (a label, not a secret). */
    public int keyVersion() {
        return keyVersion;
    }
}
