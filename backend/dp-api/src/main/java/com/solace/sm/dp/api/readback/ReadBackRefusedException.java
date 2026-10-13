package com.solace.sm.dp.api.readback;

import java.util.Objects;

/**
 * The read-back of a placement's file was refused. The reason is a member of a closed set; the exception carries
 * <b>no message text from the file, no path and no cause</b> ({@code I6}): a YAML parser's message quotes the text it
 * choked on, and a logger prints cause chains.
 *
 * <p>The gateway renders it: {@link #PLACEMENT_NOT_FOUND} as {@code 404}, {@link #HOST_UNREADABLE} as
 * {@code 503 SERVICE_UNAVAILABLE} (a dependency did not answer; retry), everything else as {@code 422}.
 */
public final class ReadBackRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why. {@link #wire()} is the spelling the {@code 422} detail uses. */
    public enum Reason {
        PLACEMENT_NOT_FOUND("placement-not-found"),
        HOST_UNREADABLE("host-unreadable"),
        FILE_ABSENT("file-absent"),
        FILE_NOT_READABLE("file-not-readable"),
        NOT_YAML("not-yaml"),
        EMPTY("empty"),
        NOT_A_MAPPING("not-a-mapping"),
        MULTI_DOCUMENT("multi-document"),
        ANCHOR("anchor"),
        ALIAS("alias"),
        TAG("tag"),
        DUPLICATE_KEY("duplicate-key"),
        TOO_DEEP("too-deep"),
        TOO_LARGE("too-large"),
        AMBIGUOUS_KEY("ambiguous-key");

        private final String wire;

        Reason(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final transient Reason reason;

    public ReadBackRefusedException(Reason reason) {
        super("config.read-back.refused." + Objects.requireNonNull(reason, "reason").wire(), null, true, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
