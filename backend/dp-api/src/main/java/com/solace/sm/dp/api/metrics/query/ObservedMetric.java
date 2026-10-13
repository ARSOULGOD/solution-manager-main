package com.solace.sm.dp.api.metrics.query;

import java.time.Instant;
import java.util.List;

/**
 * What the data plane has observed for one metric across a set of instances: the catalog's data-plane half. The control
 * plane contributes the declared metadata; this contributes only what collection produced.
 *
 * @param lastSeen the newest stored sample over the instances, or null when none is stored
 * @param reasons  the last 24 hours' drop counts of the placements serving this metric (OQ-17 (a)). Never per metric: the
 *                 counter has no metric column. {@code undeclared_dropped} is never listed here, only in capabilities
 */
public record ObservedMetric(String name, RangeResult.MetricType metricType, String unit, Instant lastSeen,
        List<CollectorHealthView.ReasonCount> reasons) {

    public ObservedMetric {
        reasons = List.copyOf(reasons);
    }
}
