package com.solace.sm.dp.apply.persistence.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the world advances by hand: lease expiry and renotification are time, and a test must not sleep for them. */
final class MutableClock extends Clock {

    private volatile Instant now = Instant.now();

    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
