package com.solace.sm.dp.secret;

/**
 * Where the key-encryption keys come from. v1 has one implementation, {@link EnvironmentMasterKeyProvider};
 * an external key service (KMS, HSM, Vault) is out of v1 (owner ruling {@code OR-85}, D-KEY OQ-3) and would
 * arrive behind this seam.
 */
public interface MasterKeyProvider {

    /**
     * @throws IllegalStateException when the key material is absent or malformed. The message names the
     *         variable and the rule, and never a character of the value nor the length found.
     */
    MasterKeyRing load();
}
