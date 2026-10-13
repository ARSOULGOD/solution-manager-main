package com.solace.sm.gateway.http.metrics;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** The body of {@code setWidgetHidden}: {@code {hidden: boolean}} and nothing else. */
public record HiddenRequest(Boolean hidden) {

    public HiddenRequest {
        if (hidden == null) {
            throw new IllegalArgumentException("hidden is required");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
