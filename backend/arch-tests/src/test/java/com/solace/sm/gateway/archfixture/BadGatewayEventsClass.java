package com.solace.sm.gateway.archfixture;

import com.solace.sm.events.EventEnvelope;

/** Planted violation: the gateway is a byte-level proxy (BE-D7) and must never parse events. */
public final class BadGatewayEventsClass {
    EventEnvelope event;
}
