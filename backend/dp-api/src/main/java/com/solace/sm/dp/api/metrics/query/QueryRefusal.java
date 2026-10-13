package com.solace.sm.dp.api.metrics.query;

import java.util.Locale;

/**
 * Why a coherent-looking request was refused rather than answered with a number that would mislead (DM-D8, DM-D22,
 * DM-D7). The list is closed and mirrors the console contract's {@code 422} codes; a new refusal is a contract change.
 */
public enum QueryRefusal {
    SERIES_CAP_EXCEEDED, AGGREGATION_NOT_APPLICABLE, GROUP_BY_REQUIRES_AGGREGATION, STAT_REQUIRES_AGGREGATION,
    METRIC_UNTYPED;

    /** The wire spelling, which is the constant's name. */
    public String wire() {
        return name();
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
