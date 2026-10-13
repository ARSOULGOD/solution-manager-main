package com.solace.sm.dp.api.inventory;

import java.util.UUID;

/**
 * The data plane's half of changing a registered target over HTTP (plan 013 T1.5; D-REG sections 5.1 T4, T5, T7 and 6.4):
 * edit the connection, rotate the key, retire the record. Beside {@link TargetConnectionAdministration} (registration and
 * the management read) and separate from it for the reason that port gives: widening it would force every fake of it to
 * change. One bean answers both.
 *
 * <p>Every call is scoped to {@code organizationId} ({@code I5}: the session's, never the request's). The private key is
 * a {@code char[]} the caller owns and zeroises; nothing here keeps it, logs it or puts any part of it in a message
 * ({@code I6}). Every write also appends one {@code target_connection_event} row in the same transaction, carrying the
 * actor and the request's {@link TargetChangeOrigin}, field names only (D-REG section 9).
 *
 * <p>An absent or retired target is a {@link java.util.NoSuchElementException} on every call but
 * {@link #retireConnection}, for which it is an answer.
 */
public interface TargetConnectionManagement {

    /**
     * Read-only, no I/O: whether {@code edit} changes the stored record and whether it must scan the host. Refuses a field
     * outside its grammar here, before anything is scanned or locked.
     *
     * @throws IllegalArgumentException a named field is outside its grammar (names the field, never its value)
     * @throws java.util.NoSuchElementException the target has no live connection record
     */
    EditPlan planEdit(UUID organizationId, UUID targetId, TargetConnectionEdit edit);

    /**
     * Applies the edit. A change of host, port or pin scans the host afresh (the address policy first) and requires the
     * pin to be among the keys it offers; then, in one transaction and under the {@code apply_scope} lock of every instance
     * with a live placement on the target, refuses while work is in flight on any of those placements, writes the record,
     * re-pins the host keys (retiring those the scan no longer offers, reviving those it offers again) and appends the
     * event ({@code edited}, or {@code repinned} when the pin changed). A change of the SSH user alone needs none of that:
     * it is neither scanned nor refused while work is in flight. An edit that changes nothing writes nothing.
     *
     * @return the record as it now stands
     * @throws IllegalArgumentException        a field is outside its grammar
     * @throws java.util.NoSuchElementException the target has no live connection record
     * @throws TargetAddressRefusedException   the new host resolves to an address the data plane may not connect to
     * @throws HostKeyScanUnavailableException the new host could not be reached or presented no key
     * @throws HostKeyNotOfferedException      the pin is not among the keys the host offers
     * @throws HostKeyHeldElsewhereException   an offered key (or the new pin) is held by another live target
     * @throws TargetWorkInFlightException     work is in flight on a placement of the target
     * @throws TargetConnectionChangedException the record changed under the edit; retry
     */
    default TargetConnectionView editConnection(UUID organizationId, UUID targetId, TargetConnectionEdit edit, UUID actor,
            TargetChangeOrigin origin) {
        return editConnection(organizationId, targetId, edit, actor, origin, ScanAccounting.NONE);
    }

    /**
     * As above, telling {@code accounting} when it scans (review {@code W4C-3}): {@link ScanAccounting#beforeScan} before
     * the host is contacted, {@link ScanAccounting#afterScan} when it answered. An edit that needs no scan (an SSH user
     * alone, or nothing) calls neither, whatever {@link #planEdit} said when it was asked. A refusal thrown by
     * {@code beforeScan} propagates unchanged and the edit is not made.
     */
    TargetConnectionView editConnection(UUID organizationId, UUID targetId, TargetConnectionEdit edit, UUID actor,
            TargetChangeOrigin origin, ScanAccounting accounting);

    /**
     * Replaces the stored key (D-REG section 6.4). The SAME key again, compared by the PUBLIC key's fingerprint, writes
     * nothing. A different key is stored as a new secret (the old row stays: the runtime cannot delete one, {@code OR-85}),
     * the record is repointed, and the event {@code credential_rotated} carries the new public fingerprint. Not refused
     * while work is in flight: a leaked key must be replaceable at once.
     *
     * @return the stored key's summary after the call
     * @throws CredentialInvalidException       the key does not open as an unencrypted private key
     * @throws java.util.NoSuchElementException the target has no live connection record
     */
    TargetConnectionView.CredentialSummary rotateConnectionCredential(UUID organizationId, UUID targetId,
            char[] privateKey, UUID actor, TargetChangeOrigin origin);

    /**
     * Retires the connection record in one transaction with its {@code retired} event. Idempotent. A target is never
     * "absent" here: no record is {@link ConnectionRetirement#NO_CONNECTION}.
     *
     * @throws TargetHasLivePlacementsException a live placement names the target
     */
    ConnectionRetirement retireConnection(UUID organizationId, UUID targetId, UUID actor, TargetChangeOrigin origin);
}
