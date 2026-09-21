package com.solace.sm.cp.identity;

import java.time.Duration;
import java.util.Objects;

/** Locally configurable login-throttle policy; defaults are deployment values, not an architecture decision. */
public record LoginRateLimitPolicy(int failureThreshold, Duration window, Duration lockout) {

    public static final LoginRateLimitPolicy LOCAL_DEFAULT =
            new LoginRateLimitPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));

    public LoginRateLimitPolicy {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failure threshold must be positive");
        }
        window = requirePositive(window, "window");
        lockout = requirePositive(lockout, "lockout");
    }

    private static Duration requirePositive(Duration value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
