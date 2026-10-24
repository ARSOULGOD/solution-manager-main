package com.solace.sm.dp.api.agw;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One answer of {@link AgentConfigViewPort#read} (plan 017).
 *
 * <ul>
 *   <li>{@link Status#OK}: the agent read the file; {@code entries} are the allow-listed scalar settings, {@code hiddenCount} the
 *       settings that matched nothing in the allow list, {@code truncated} whether the agent stopped at {@code maxBytes}. No reason.</li>
 *   <li>{@link Status#REFUSED}: the agent (or the gateway, for a placement outside the lease) said no; {@code reason} is always set.</li>
 *   <li>{@link Status#BUSY}: a view for this agent is already in flight; nothing was sent. No reason.</li>
 *   <li>{@link Status#UNAVAILABLE}: no live session, the session ended, the deadline passed (an agent that does not know the
 *       request never answers), or the agent's reply was malformed ({@code reason} {@code UNKNOWN} in that case only).</li>
 * </ul>
 *
 * <p>{@code reason} is {@code null} or the name of one {@link Reason}; any other text becomes {@code UNKNOWN}. Neither
 * {@code toString} prints a key name or a value: a value may be sensitive even after the allow list, and keys name structure.
 */
public record AgentConfigView(Status status, List<Entry> entries, int hiddenCount, boolean truncated, String reason) {

    public enum Status { OK, REFUSED, BUSY, UNAVAILABLE }

    /** The closed vocabulary of refusal reasons. Anything the agent sends outside it is {@link #UNKNOWN}. */
    public enum Reason {
        PLACEMENT_NOT_IN_LEASE,
        FILE_MISSING,
        FILE_UNREADABLE,
        FILE_TOO_LARGE,
        INVALID_YAML,
        NOT_PERMITTED,
        TOO_MANY_KEYS,
        UNKNOWN;

        /** The reason named by {@code text}; {@code UNKNOWN} for any other non-null text. */
        public static Reason of(String text) {
            Objects.requireNonNull(text, "text");
            for (Reason reason : values()) {
                if (reason.name().equals(text)) return reason;
            }
            return UNKNOWN;
        }
    }

    /** One allow-listed scalar setting: its dotted path and its value as text. */
    public record Entry(String path, String value) {
        public Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        }

        @Override public String toString() { return "Entry[redacted]"; }
    }

    public AgentConfigView {
        Objects.requireNonNull(status, "status");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (hiddenCount < 0) throw new IllegalArgumentException("hiddenCount must not be negative");
        if (reason != null) reason = Reason.of(reason).name();
        switch (status) {
            case OK -> {
                if (reason != null) throw new IllegalArgumentException("an OK view has no reason");
            }
            case REFUSED -> {
                if (reason == null) throw new IllegalArgumentException("a REFUSED view has a reason");
                requireEmpty(entries, hiddenCount, truncated);
            }
            case BUSY -> {
                if (reason != null) throw new IllegalArgumentException("a BUSY view has no reason");
                requireEmpty(entries, hiddenCount, truncated);
            }
            case UNAVAILABLE -> {
                if (reason != null && !Reason.UNKNOWN.name().equals(reason)) {
                    throw new IllegalArgumentException("an UNAVAILABLE view has no reason but UNKNOWN");
                }
                requireEmpty(entries, hiddenCount, truncated);
            }
        }
    }

    private static void requireEmpty(List<Entry> entries, int hiddenCount, boolean truncated) {
        if (!entries.isEmpty() || hiddenCount != 0 || truncated) {
            throw new IllegalArgumentException("only an OK view carries entries");
        }
    }

    public static AgentConfigView ok(List<Entry> entries, int hiddenCount, boolean truncated) {
        return new AgentConfigView(Status.OK, entries, hiddenCount, truncated, null);
    }

    public static AgentConfigView refused(Reason reason) {
        return new AgentConfigView(Status.REFUSED, List.of(), 0, false, Objects.requireNonNull(reason, "reason").name());
    }

    public static AgentConfigView busy() { return new AgentConfigView(Status.BUSY, List.of(), 0, false, null); }

    public static AgentConfigView unavailable() { return new AgentConfigView(Status.UNAVAILABLE, List.of(), 0, false, null); }

    /** The agent answered, but not in a shape the contract allows; nothing of the reply is kept. */
    public static AgentConfigView malformed() {
        return new AgentConfigView(Status.UNAVAILABLE, List.of(), 0, false, Reason.UNKNOWN.name());
    }

    /** {@link #reason} as its enum value. */
    public Optional<Reason> reasonCode() { return Optional.ofNullable(reason).map(Reason::of); }

    @Override
    public String toString() {
        return "AgentConfigView[status=" + status + ", entries=" + entries.size() + ", hiddenCount=" + hiddenCount
                + ", truncated=" + truncated + ", reason=" + reason + "]";
    }
}
