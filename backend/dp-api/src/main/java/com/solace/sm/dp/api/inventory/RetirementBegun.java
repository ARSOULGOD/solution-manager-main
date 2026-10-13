package com.solace.sm.dp.api.inventory;

import java.util.Objects;

/**
 * What {@link InstanceRetirementService#beginRetire} answers: the open record, and whether this call started it or
 * found it already open ({@code resumed}: the caller is an authorized retry of an undeclare that stopped between its steps).
 */
public record RetirementBegun(RetirementView retirement, boolean resumed) {
    public RetirementBegun {
        Objects.requireNonNull(retirement, "retirement");
    }
}
