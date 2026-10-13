package com.solace.sm.dp.apply.runtime.readback;

/**
 * Which keys of a read-back file are SM's own (D-ACCEPT §4.2; {@code BE-D37}, D-LOG). A
 * read-back strips them <b>before</b> matching declared paths, so SM's own overlay keys are never counted as an
 * operator's unmapped keys, and an operator's edit of one is reported {@code sm-owned-overlay} rather than adopted.
 *
 * <p>There is exactly one implementation, {@link com.solace.sm.dp.config.OverlayKeys#owns}: the same predicate the stage
 * and the renderer use, so accept can neither treat SM's keys as an operator's edits nor the reverse. This interface
 * stays only so the mapper can be driven by another owner in a test; production wires {@code OverlayKeys::owns} and
 * nothing else.
 */
@FunctionalInterface
public interface SmOverlayKeys {

    /** Whether the dotted path is a key SM writes. */
    boolean owns(String dottedPath);
}
