package com.solace.sm.gateway.http;

import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code ApplyAccepted} ({@code Versioned} + {@code applyId}): the request is admitted and
 * durable. It carries no outcome - that is observed, not awaited.
 */
public record ApplyAccepted(long stateVersion, @WireOptional Availability availability, UUID applyId)
        implements AcceptedRequest {
}
