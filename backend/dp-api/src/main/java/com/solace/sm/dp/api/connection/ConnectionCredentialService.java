package com.solace.sm.dp.api.connection;

import java.util.UUID;

/**
 * The data plane's half of the Connection config object (plan 013 T4, {@code BE-D66}, {@code OR-130}): seals a
 * secret-typed attribute's value and, when {@code testBeforeSave} asks, probes connectivity with it. The
 * {@code console-gateway} composition (authorize at cp, seal + optionally test at dp, write the cp row — in that
 * order) depends on this port only, never on the dp-core testers directly ({@code I7}).
 *
 * <p>⛔ {@code I6}: a value handed to {@link #sealCredential} is the caller's {@code char[]} and is never copied,
 * logged, or kept beyond the call; the caller zeroises it afterward. A secret-typed attribute is stored as a
 * reference ({@link #sealCredential}'s return) — the Connection's {@code attributes} column never carries a value.
 *
 * <p>Every test is a RESULT, never an exception — see {@link ConnectionTestOutcome}. Neither test method scans or
 * pins a host key: a Connection is a config value object, never a deployment-trust anchor (unlike a Target).
 */
public interface ConnectionCredentialService {

    /**
     * Encrypts and stores {@code value} under {@code SecretPurpose.CONNECTION_CREDENTIAL}, keyed by tenant; returns
     * the reference. The caller zeroises {@code value} after this returns.
     */
    UUID sealCredential(UUID tenantId, char[] value, UUID actor);

    /**
     * {@code solace-broker}: opens a JCSMP session with the given coordinates, confirms it connected, and closes it.
     * No message is sent or subscribed to. {@code passwordSecretRef} is {@code null} when no password attribute is
     * set.
     */
    ConnectionTestOutcome testSolaceBroker(UUID tenantId, String host, int port, String vpn, String username,
            UUID passwordSecretRef);

    /**
     * {@code remote-server}: connects over SSH and authenticates — {@code basic} (password) or {@code private-key}
     * (optionally passphrase-protected) — then disconnects. No command is run and no host key is pinned or scanned.
     *
     * @param authMethod {@code remote-server.json}'s {@code authMethod} discriminator value, exactly as submitted
     *                   ({@code "basic"} or {@code "private-key"})
     */
    ConnectionTestOutcome testRemoteServer(UUID tenantId, String host, int port, String username, String authMethod,
            UUID passwordSecretRef, UUID privateKeySecretRef, UUID passphraseSecretRef);
}
