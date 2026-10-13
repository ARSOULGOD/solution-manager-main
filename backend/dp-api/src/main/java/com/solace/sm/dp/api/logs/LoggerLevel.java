package com.solace.sm.dp.api.logs;

import java.util.Locale;
import java.util.Optional;

/**
 * The log levels the contract offers (console.yaml {@code LogLevel.liveLevel}). {@code OFF} and {@code FATAL} are not
 * members, on purpose: they silence the log an operator needs to diagnose with (log-levels.md section 7).
 */
public enum LoggerLevel {
    TRACE, DEBUG, INFO, WARN, ERROR;

    /** Case-insensitive; empty for a name outside the contract's set (including Boot's own {@code OFF} and {@code FATAL}). */
    public static Optional<LoggerLevel> fromBoot(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
