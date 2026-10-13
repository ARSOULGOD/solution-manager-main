package com.solace.sm.dp.api.config;

import java.util.UUID;

/**
 * Whether a secret reference a staged draft names can be resolved at render time (plan 010 T5.3). It answers a
 * yes or a no and never reads, returns or decrypts a value (I6).
 */
public interface SecretReferences {

    /**
     * True only for a stored secret of THIS organization whose purpose is a config field. A secret of any other
     * purpose (an SSH private key, a hash key) is not referenceable from configuration, whatever its id: letting a
     * draft name one would render it into a file on a customer host.
     */
    boolean exists(UUID organizationId, UUID secretRef);
}
