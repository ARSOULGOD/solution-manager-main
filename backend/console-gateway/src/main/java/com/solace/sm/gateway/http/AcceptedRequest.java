package com.solace.sm.gateway.http;

/**
 * What {@code submitApply} and {@code rollbackApply} answer {@code 202} with: an {@link ApplyAccepted} when the change was
 * admitted at once, a {@link ScheduleAccepted} when it was recorded to be admitted later (plan 012 T7). Sealed so the two
 * handlers' wire type is a closed pair and the mirror test can pin it.
 */
public sealed interface AcceptedRequest permits ApplyAccepted, ScheduleAccepted {
}
