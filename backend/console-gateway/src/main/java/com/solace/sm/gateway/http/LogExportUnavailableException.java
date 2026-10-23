package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * An agent log export that cannot start now (plan 016 task 10): not connected, no log capability, not collecting, busy, silent.
 * {@code 503 SERVICE_UNAVAILABLE}; {@code detail} is the data plane's FIXED reason text (I6), never request content.
 */
final class LogExportUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String detail;

    LogExportUnavailableException(String detail) {
        super(ErrorCode.SERVICE_UNAVAILABLE.name());
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    String detail() {
        return detail;
    }
}
