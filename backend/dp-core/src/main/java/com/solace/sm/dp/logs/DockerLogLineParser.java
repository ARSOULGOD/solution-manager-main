package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.logs.LogLineView;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns {@code docker logs --timestamps} output into log lines (plan 010 T9.1). Each line is an RFC3339Nano UTC stamp,
 * one space, then the container's own line (measured, M11).
 *
 * <p>The level is read ONLY from a message shaped like a Spring Boot or logback line - one or two leading
 * date/time-looking tokens, an optional single-word {@code [thread]}, then a level word. A message that merely
 * mentions a level ({@code the ERROR happened}) has none: a wrong level is worse than no level.
 *
 * <p>Nothing here redacts: a line is the managed integration's own output.
 */
final class DockerLogLineParser {

    private static final Pattern LEVEL = Pattern.compile(
            "^(?:\\d{1,4}[-:/]\\d\\S*\\s+){1,2}(?:\\[[^\\]\\s]*\\]\\s+)?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)(?:\\s|$)");

    private DockerLogLineParser() {
    }

    /** One raw line, or empty when it is not a stamped container line (daemon noise, a blank line). */
    static Optional<LogLineView> parseLine(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
        int separator = line.indexOf(' ');
        if (separator <= 0) {
            return Optional.empty();
        }
        Instant at;
        try {
            at = Instant.parse(line.substring(0, separator));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        String message = line.substring(separator + 1);
        Matcher level = LEVEL.matcher(message);
        return Optional.of(new LogLineView(at, level.find() ? Optional.of(level.group(1)) : Optional.empty(), message));
    }

    /**
     * The NEWEST {@code limit} lines strictly inside {@code (after, before)}, oldest first. Equal stamps keep the order
     * the host emitted them in. Whatever the host sent beyond the window or the limit is dropped here, so the bound
     * does not depend on the host honouring it.
     */
    static List<LogLineView> page(String output, Instant after, Instant before, int limit) {
        List<LogLineView> inside = new ArrayList<>();
        for (String raw : output.split("\n", -1)) {
            parseLine(raw).ifPresent(line -> {
                boolean afterOk = after == null || line.at().isAfter(after);
                boolean beforeOk = before == null || line.at().isBefore(before);
                if (afterOk && beforeOk) {
                    inside.add(line);
                }
            });
        }
        inside.sort(Comparator.comparing(LogLineView::at));
        return List.copyOf(inside.subList(Math.max(0, inside.size() - limit), inside.size()));
    }
}
