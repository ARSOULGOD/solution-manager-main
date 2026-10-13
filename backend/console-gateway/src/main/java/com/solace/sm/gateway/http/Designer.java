package com.solace.sm.gateway.http;

import java.util.Map;
import java.util.Objects;

/**
 * Wire shape of console.yaml's {@code Designer} ({@code Versioned} + {@code generation} + {@code viewModel}).
 *
 * <p>{@code viewModel} is the ORDERED tree {@code DesignerCodec#viewModelTree} produced, written by Jackson as it
 * iterates. It is a {@code Map} on purpose: the byte contract on the view model is its key order (OR-34), and the
 * {@code cp-api} records would be written in declaration order, with no {@code FieldValue} discriminator. Nothing in
 * this module serializes a {@code ViewModel} record.
 *
 * <p>{@code generation} is an explicit {@code null} before any generation exists (the schema's {@code integer | null}).
 *
 * <p>{@code layout} (OR-96) is the canvas layout the operator last saved: instance key to position, read from the DP's
 * per-instance presentation record and NOT from any generation. It is always present, an empty object when nothing was
 * saved. It sits beside the view model, never inside it: the view model is the byte-locked projection, and layout never
 * enters {@code rendered} or the hash.
 */
public record Designer(long stateVersion, @WireOptional Availability availability, Long generation,
        Map<String, Object> viewModel, Map<String, Position> layout, @WireOptional String role) {

    public Designer(long stateVersion, Availability availability, Long generation,
            Map<String, Object> viewModel, Map<String, Position> layout) {
        this(stateVersion, availability, generation, viewModel, layout, null);
    }

    public Designer {
        Objects.requireNonNull(viewModel, "viewModel");
        Objects.requireNonNull(layout, "layout");
    }

    /** A canvas position as the client sent it: the contract types x and y as {@code number}, so possibly fractional. */
    public record Position(Number x, Number y) {
    }
}
