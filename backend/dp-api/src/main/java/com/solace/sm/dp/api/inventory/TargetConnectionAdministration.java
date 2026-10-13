package com.solace.sm.dp.api.inventory;

import java.util.Optional;
import java.util.UUID;

/**
 * The data plane's half of managing a target's connection record over HTTP (plan 013 T1.4; D-REG sections 5.1 and 8).
 * Separate from {@link TargetConnectionService} on purpose: that port is what every other consumer (declare, health,
 * the metrics fetcher) already holds, and widening it would force every implementer and fake of it to change in a wave
 * in which other work is editing them. One bean answers both.
 *
 * <p>Every call is scoped to {@code organizationId} ({@code I5}: the session's, never the request's). The private key is
 * a {@code char[]} the caller owns and zeroises; nothing here keeps it, logs it or puts any part of it in a message
 * ({@code I6}).
 */
public interface TargetConnectionAdministration {

    /**
     * Step 2 of the registration (D-REG section 8): every refusal that needs no write, in this order, so a refused
     * registration leaves <b>nothing</b> in either plane (no row, no secret, no event):
     * the field grammars, the key (it must open as an UNENCRYPTED private key), the address policy, a fresh scan that
     * must offer the pin, and a probe that no other live target holds any offered host key.
     *
     * @param replayingTargetId the live target this registration would replay, if the control plane already has one by
     *                          this name; the probe does not count that target's own keys as another's
     * @throws IllegalArgumentException      a field is outside its grammar (the message names the field, never the value)
     * @throws CredentialInvalidException    the key does not open as an unencrypted private key
     * @throws TargetAddressRefusedException the host resolves to an address the data plane may not connect to
     * @throws HostKeyScanUnavailableException the host could not be reached or offered no key
     * @throws HostKeyNotOfferedException    the pin is not among the keys the host offered
     * @throws HostKeyHeldElsewhereException an offered key is held by another live target of the organization
     */
    RegistrationPrecheck precheckRegistration(UUID organizationId, UUID replayingTargetId, TargetConnectionSpec spec,
            char[] privateKey);

    /**
     * Step 4: writes the connection record, the sealed key, the host keys and the {@code registered} event in ONE
     * transaction, or recognises an identical registration and writes nothing.
     *
     * <p>A unique violation on the record (two identical requests racing) is not an error: the loser's transaction rolls
     * back whole (its secret with it) and this method re-runs, finds the winner's record and answers
     * {@link RegistrationOutcome#REPLAYED}.
     *
     * @throws TargetCredentialConflictException the record is the same but the key is not (rotate instead)
     * @throws IllegalStateException             a different record is registered for this target id
     * @throws HostKeyHeldElsewhereException     another live target holds a host key (the race the pre-check's probe
     *                                           cannot close)
     */
    RegistrationOutcome registerConnection(UUID organizationId, UUID targetId, TargetConnectionSpec spec,
            char[] privateKey, UUID actor, TargetChangeOrigin origin);

    /** The live record of this target, or empty when the data plane has none (absent, or retired). */
    Optional<TargetConnectionView> connection(UUID organizationId, UUID targetId);
}
