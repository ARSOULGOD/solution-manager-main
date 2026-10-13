package com.solace.sm.gateway.http.metrics;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Set;

/**
 * The body of {@code declareMetricType}: {@code {metricType, unit}} and nothing else. Read and checked only so that a
 * malformed request is the contract's {@code 400}; in v1 every well-formed one is the contracted {@code 501}
 * ({@code metric-declare-deferred}, M-Q6).
 */
public record DeclareMetricTypeRequest(String metricType, String unit) {

    public DeclareMetricTypeRequest {
        if (metricType == null || !Set.of("counter", "gauge").contains(metricType)) {
            throw new IllegalArgumentException("metricType is missing or invalid");
        }
        if (unit == null || unit.isEmpty() || unit.length() > 64) {
            throw new IllegalArgumentException("unit is missing or invalid");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
