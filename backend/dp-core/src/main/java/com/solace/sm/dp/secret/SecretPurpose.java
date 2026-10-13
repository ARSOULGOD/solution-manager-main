package com.solace.sm.dp.secret;

/**
 * Why a secret exists. The purpose is part of the authenticated data (D-KEY 3.3), so a row relabelled
 * from one purpose to another fails authentication rather than being read as the new purpose.
 *
 * <p>{@link #CANARY} is the install-level key check of D-KEY 4.3. It never appears in
 * {@code sm_secret.secret}, and {@link SecretStore#put} refuses it. {@link #CONFIG_HASH_KEY} rows
 * carry a {@code key_version} and are minted by the keyed-hash seam (plan 010 T5.1), not by
 * {@link SecretStore#put}.
 */
public enum SecretPurpose {
    SSH_PRIVATE_KEY,
    CONFIG_FIELD,
    CONFIG_HASH_KEY,
    /**
     * The credential of one broker connection (plan 011 P11-5.1; OR-107 OQ-3 (a); {@code BE-D43}, which amends
     * {@code BE-D28}'s closed set). A broker credential is not a config field: labelling it {@code CONFIG_FIELD} would let it
     * open as one, because the purpose is part of the authenticated data.
     */
    BROKER_CREDENTIAL,
    /**
     * A secret-typed attribute of a Connection config object (plan 013 T3; {@code BE-D66}, which gives Connections
     * the cp/dp split Targets already has) — {@code password}, {@code privateKey} or {@code passphrase}, keyed by
     * {@code connectionId}. A Connection is not a config field: labelling it {@code CONFIG_FIELD} would let it open
     * as one, because the purpose is part of the authenticated data. It is also not {@link #BROKER_CREDENTIAL} or
     * {@link #SSH_PRIVATE_KEY}, which belong to the backend's own mesh identity and to Target registration
     * respectively — a Connection is governed by {@code connection.*} verbs and never a deployment-trust anchor.
     */
    CONNECTION_CREDENTIAL,
    CANARY
}
