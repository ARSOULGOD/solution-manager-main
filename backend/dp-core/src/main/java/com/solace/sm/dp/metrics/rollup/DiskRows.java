package com.solace.sm.dp.metrics.rollup;

/**
 * The two catalog reads of the disk alarm (plan 011 P11-3.2, OQ-16 (a)) and nothing else: the database's size and the
 * total size of {@code sm_metrics}' tables with their indexes and TOAST. Implemented in {@code dp.apply.persistence}; it
 * never shells out and never reads the filesystem.
 */
public interface DiskRows {

    /** Install-wide totals across tenants: they must never leave the data plane. */
    record Sizes(long databaseBytes, long metricsBytes) {
    }

    Sizes read();
}
