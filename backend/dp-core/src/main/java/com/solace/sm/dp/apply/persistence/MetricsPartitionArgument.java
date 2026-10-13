package com.solace.sm.dp.apply.persistence;

import java.util.regex.Pattern;

/**
 * The month an operator names to {@link MetricsPartitionMain}: the SECOND sanctioned {@code I5} case of plan 011
 * (Global Constraints), where a partition identifier is not a bind parameter because DDL cannot bind one.
 *
 * <p>Checked against {@code ^[0-9]{4}_(0[1-9]|1[0-2])$} <b>before any connection is opened</b>. Even a month that
 * passes is never spliced into SQL: the partition it names must be found by a bound catalog lookup, and the statement
 * is built from the catalog's own {@code relname}, quoted as an identifier. The pattern is applied with
 * {@link java.util.regex.Matcher#matches()} (the whole input, so a trailing newline cannot slip past {@code $}) and
 * the digits are ASCII-only, so a full-width digit is refused too.
 *
 * <p>The refusal message does not echo the argument.
 */
record MetricsPartitionArgument(String yyyyMm) {

    private static final Pattern MONTH = Pattern.compile("[0-9]{4}_(0[1-9]|1[0-2])");

    /** The only children {@code metrics_raw} has (OQ-21 (2a)). */
    static final String PARTITION_PREFIX = "metrics_raw_";

    static MetricsPartitionArgument parse(String raw) {
        if (raw == null || !MONTH.matcher(raw).matches()) {
            throw new IllegalArgumentException("the month must be given as yyyy_mm, for example 2026_01 (four digits, "
                    + "an underscore, a month 01 to 12)");
        }
        return new MetricsPartitionArgument(raw);
    }

    /** {@code metrics_raw_yyyy_mm}: the name the catalog lookup is bound to. */
    String partitionName() {
        return PARTITION_PREFIX + yyyyMm;
    }
}
