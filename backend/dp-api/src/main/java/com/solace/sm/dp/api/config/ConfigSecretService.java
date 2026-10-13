package com.solace.sm.dp.api.config;

import java.util.UUID;

/**
 * Stores one config-field secret and stages the generation that references it, in ONE transaction (plan 010 T10.1;
 * OR-30, OR-45, OR-60). Every method is tenant-scoped by {@code organizationId}, which is
 * {@code Session.organizationId()} and never a request field (I5).
 *
 * <p><b>Why a seam of its own.</b> The secret row and the generation that names it must commit together or not at all:
 * a secret stored by one call and referenced by another is either unreferenced (a stale row nobody owns) or
 * referenced before it exists. {@link ConfigRecordService#stage} runs in a transaction of its own, so it cannot be
 * composed with a secret write by a caller; this port does both inside the one transaction.
 *
 * <p><b>I6.</b> The value is passed as a {@code char[]} the caller zeroises after the call returns. It is encrypted
 * under the secret store's envelope and appears in no return value, no exception, no log line and no config table;
 * the generation carries the new reference only.
 */
public interface ConfigSecretService {

    /**
     * Under the instance row lock: refuse a stale {@code baseStateVersion}, encrypt and store {@code value} as a
     * {@code CONFIG_FIELD} secret of this organization, ask {@code stage} for the command that references it, and
     * write that command as the instance's next generation. Any failure, including one thrown by {@code stage},
     * leaves no secret row and no generation.
     *
     * @param stage builds the command from the reference the store just minted. It runs INSIDE the transaction and
     *              under the instance lock, so it must do no I/O; the reference is not yet visible to any other
     *              connection. Its {@code baseStateVersion} must equal this call's and its origin must be an edit.
     * @throws StaleStateVersionException  {@code baseStateVersion} is not the instance's (409); nothing was stored
     * @throws java.util.NoSuchElementException the instance is not declared in this organization (404)
     * @throws SecretValueRejectedException {@code value} is empty or larger than the store accepts (422)
     */
    StageResult storeAndStage(UUID organizationId, UUID instanceId, long baseStateVersion, char[] value, UUID actor,
            StageBuilder stage);

    /** Builds the stage command around the reference of the secret just stored. */
    @FunctionalInterface
    interface StageBuilder {

        StageCommand build(UUID secretRef);
    }
}
