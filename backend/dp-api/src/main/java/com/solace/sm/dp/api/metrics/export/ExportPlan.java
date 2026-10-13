package com.solace.sm.dp.api.metrics.export;

/**
 * What an export WOULD write, known before any byte is (plan 011 P11-6.2, M-Q22 (4)).
 *
 * @param projectedRows the sum over the matching series of (range divided by the interval in force); an upper bound that
 *                      never under-counts a series whose interval is known
 * @param limit         the row limit in force ({@code sm.dp.metrics.export.row-limit}, default 20 M)
 */
public record ExportPlan(long projectedRows, long limit) {

    public ExportPlan {
        if (projectedRows < 0 || limit < 1) {
            throw new IllegalArgumentException("projectedRows must be >= 0 and limit >= 1");
        }
    }
}
