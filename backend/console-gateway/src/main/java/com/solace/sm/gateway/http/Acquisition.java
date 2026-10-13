package com.solace.sm.gateway.http;

import java.util.Objects;

/** Wire shape of console.yaml's {@code Acquisition}: the MODE only, never a host or an address ({@code OR-82}). */
public record Acquisition(String mode) {

    public Acquisition {
        Objects.requireNonNull(mode, "mode");
    }
}
