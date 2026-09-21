package com.solace.sm.cp.api.authz;

/**
 * How consequential an operation is, per the ratified {@code D10} amendment §1.
 *
 * <p><b>Three-valued on purpose.</b> A two-valued enum would force every verb to be either
 * {@code STANDARD} or {@code HIGH}, so a verb nobody has ruled on would arrive as
 * {@code STANDARD} — the safe-looking default that silently ships an unreviewed
 * high-consequence operation. {@link #UNDECIDED} makes that impossible: an operation
 * registered against an {@code UNDECIDED} verb refuses to register at all
 * ({@link OperationRegistry}).
 *
 * <p>⚠️ The {@code UNDECIDED} set is <b>empty today</b> — ruling {@code OR-13} settled the last
 * open member ({@code user.deactivate} is {@code HIGH}). The value stays so the <i>next</i>
 * unruled verb cannot arrive as {@code STANDARD}.
 */
public enum ConsequenceClass {

    /** Ordinary. Authorized by holding the verb at the resource's scope. */
    STANDARD,

    /**
     * High-consequence: audited, and surfaced to the operator as consequential before it runs.
     * {@code architecture.md} §16 and the amendment's §1.1/§1.7 name each one.
     */
    HIGH,

    /**
     * ⛔ <b>Not a state any shipped verb may be in.</b> It exists so that an unruled verb is
     * loud rather than quiet. An operation mapped to a verb in this class cannot be registered
     * and therefore cannot be served.
     */
    UNDECIDED
}
