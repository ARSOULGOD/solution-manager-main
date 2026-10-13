package com.solace.sm.dp.api.inventory;

/**
 * What a registration did (plan 013 T1.4): the answer is {@code 201} for {@link #CREATED} and {@code 200} for
 * {@link #REPLAYED}. A registration that finds the connection record already there with the same fields and the same
 * key writes nothing and is a replay, whichever request created the record.
 */
public enum RegistrationOutcome {
    /** This call wrote the connection record, its sealed key, its host keys and the {@code registered} event. */
    CREATED,
    /** An identical record with the same key (by public fingerprint) was already registered; nothing was written. */
    REPLAYED
}
