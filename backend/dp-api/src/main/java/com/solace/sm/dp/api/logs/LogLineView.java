package com.solace.sm.dp.api.logs;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One container log line. {@code level} is present only when the line is shaped like a Spring Boot or logback line;
 * a line that merely mentions a level word has none.
 */
public record LogLineView(Instant at, Optional<String> level, String message) {

    public LogLineView {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
    }
}
