package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Set;

/**
 * The {@code schedule} object of {@code submitApply} and {@code rollbackApply} (plan 012 T7; {@code OPS-Q9} Sch-2): the
 * window {@code [notBefore, notAfter)} an operator allows the change to be admitted in. Both instants are RFC 3339
 * {@code date-time} strings WITH an offset ({@code 2030-01-02T02:00:00Z}, {@code ...+05:30}); a date alone, a number, a
 * local time and any other key are {@code 400}, as is a year outside 0000-9999 (RFC 3339 has four digits; {@code java.time} accepts more). Whether
 * {@code notBefore} is in the future and neither end is further ahead than {@code sm.dp.apply.schedule.max-lead} (at most 7
 * days) depends on the clock and is the data plane's ({@code 422}).
 *
 * <p>Parsed from a {@code JsonNode} on purpose, as {@code generation} is: a typed {@code Instant} component would let
 * Jackson coerce a number to an instant and ignore a stray key, and an explicit JSON {@code null} would read as "no
 * schedule" - a request that asked to be scheduled and was quietly applied at once.
 */
record ScheduleWindow(Instant notBefore, Instant notAfter) {

    private static final Set<String> KEYS = Set.of("notBefore", "notAfter");

    ScheduleWindow {
        if (notBefore == null || notAfter == null || !notAfter.isAfter(notBefore)) {
            throw new IllegalArgumentException("schedule.notAfter must be after schedule.notBefore");
        }
    }

    /** The validated window; {@code IllegalArgumentException} (a {@code 400}) for anything else, including JSON null. */
    static ScheduleWindow parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("schedule must be an object with notBefore and notAfter");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
            if (!KEYS.contains(names.next())) {
                throw new IllegalArgumentException("schedule carries notBefore and notAfter and nothing else");
            }
        }
        return new ScheduleWindow(instant(node.get("notBefore")), instant(node.get("notAfter")));
    }

    private static Instant instant(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("schedule.notBefore and schedule.notAfter are RFC 3339 date-time strings");
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(node.textValue());
            // RFC 3339 has a four-digit year; java.time also accepts "+300000-01-01T00:00:00Z", which is past PostgreSQL's
            // timestamptz range (W3 review W3B-4) - refuse it here as a 400 rather than let it reach an insert
            if (parsed.getYear() < 0 || parsed.getYear() > 9999) {
                throw new IllegalArgumentException("schedule.notBefore and schedule.notAfter are RFC 3339 date-time strings");
            }
            return parsed.toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("schedule.notBefore and schedule.notAfter are RFC 3339 date-time strings");
        }
    }
}
