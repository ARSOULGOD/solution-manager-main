package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * {@code setLogLevel} was understood and refused (plan 010 T9.3/T9.4, {@code OR-103}). Under the ruling a level CHANGE is
 * configuration and there is no live write; what is refused is a live change, or a change for a package that declares no
 * logger support. The reason is a closed set; the response
 * detail is fixed text per reason and quotes nothing the request carried.
 */
public final class LogLevelChangeRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** {@code mode: live} on a direct-HTTP instance: the contract's {@code 422} ({@code OR-43}: configuration only). */
        LIVE_ON_DIRECT_HTTP,
        /** {@code mode: live}: no live write exists in v1 ({@code OR-103}); the later step is documented, not built. */
        LIVE_NOT_BUILT,
        /** {@code mode: config} on a package that declares no {@code logs.loggers} (or no logger prefix): {@code 422}. */
        NO_LOGGER_SUPPORT
    }

    private final transient Reason reason;

    public LogLevelChangeRefusedException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
