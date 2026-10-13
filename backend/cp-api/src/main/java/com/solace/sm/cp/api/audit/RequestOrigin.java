package com.solace.sm.cp.api.audit;

import java.util.Objects;
import java.util.UUID;

/**
 * Where a state-changing request came from, carried explicitly into every CP port that changes state.
 *
 * <p>Two consumers, both ruled by the owner on 2026-09-23: the trigger-written audit trail (ruling
 * {@code OR-19}) records {@code correlationId} so an audit row joins to the HTTP response that caused it;
 * the login throttle (ruling {@code OR-20}) keys its second dimension on {@code clientAddress}.
 *
 * <p>⛔ <b>Both values are server-determined.</b> {@code correlationId} is the server-minted id, never a
 * caller-supplied header. {@code clientAddress} is the socket peer, or — only when that peer is a
 * configured trusted proxy — the nearest untrusted {@code X-Forwarded-For} entry. A client can never
 * choose either.
 *
 * @param clientAddress the resolved client IP literal (IPv4, or IPv6 without brackets)
 * @param correlationId the server-generated request correlation id
 */
public record RequestOrigin(String clientAddress, UUID correlationId) {

    /** Canonicalises {@code clientAddress} through {@link ClientAddresses}, so every consumer sees one spelling. */
    public RequestOrigin {
        Objects.requireNonNull(clientAddress, "client address");
        Objects.requireNonNull(correlationId, "correlation id");
        clientAddress = ClientAddresses.canonicalize(clientAddress)
                .orElseThrow(() -> new IllegalArgumentException("client address must be an IP literal"));
    }
}
