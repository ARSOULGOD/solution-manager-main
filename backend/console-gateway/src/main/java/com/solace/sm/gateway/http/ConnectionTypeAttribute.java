package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code ConnectionTypeAttribute} — a structural mirror of
 * {@code contracts/connection-types/descriptor.schema.json}'s {@code attribute} {@code $def}, field for field
 * (plan 013 T4, {@code UI-D27}). {@code default} is a Java keyword, so the component is named
 * {@code defaultValue} and mapped back to the wire name {@code default} explicitly.
 */
public record ConnectionTypeAttribute(String key, String type, @WireOptional List<Object> options,
                                      @WireOptional @JsonProperty("default") Object defaultValue,
                                      @WireOptional Boolean required, @WireOptional Boolean secret,
                                      @WireOptional Boolean discriminator, @WireOptional Map<String, Object> when,
                                      @WireOptional Double min, @WireOptional Double max,
                                      @WireOptional String pattern, @WireOptional Ui ui) {

    public ConnectionTypeAttribute {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
    }

    public boolean isSecret() {
        return Boolean.TRUE.equals(secret);
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    public boolean isDiscriminator() {
        return Boolean.TRUE.equals(discriminator);
    }

    /** Nested exactly as the real descriptor schema nests it — never flattened onto the attribute. */
    public record Ui(@WireOptional String label, @WireOptional String helperText, @WireOptional Integer order,
                     @WireOptional Boolean listColumn) {
    }
}
