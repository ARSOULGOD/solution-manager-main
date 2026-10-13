package com.solace.sm.dp.health;

import java.util.Arrays;

/**
 * What one probe saw, before it is given a meaning ({@link ProbeResultMapper}). The same vocabulary for both ways of
 * acquiring health (via SSH on the host loopback, or direct HTTP to the target's registered address), so the mapping
 * is written once.
 *
 * <ul>
 *   <li>{@link Answered} - the service answered, with this status and (bounded) body;</li>
 *   <li>{@link #REFUSED} - the host was reached and nothing listens on the declared port;</li>
 *   <li>{@link #SILENT} - the host was reached and the port accepted, but no usable answer came in time;</li>
 *   <li>{@link #UNREACHABLE} - the host or the network could not be reached (a transport-level failure);</li>
 *   <li>{@link #DEFERRED} - no observation this round (the session pool was busy): nothing is persisted.</li>
 * </ul>
 */
public sealed interface Observation permits Observation.Answered, Observation.Marker {

    Observation REFUSED = Marker.REFUSED;
    Observation SILENT = Marker.SILENT;
    Observation UNREACHABLE = Marker.UNREACHABLE;
    Observation DEFERRED = Marker.DEFERRED;

    /** The four answers that carry no data. Public only so the sealed hierarchy is closed. */
    enum Marker implements Observation {
        REFUSED, SILENT, UNREACHABLE, DEFERRED
    }

    /** @param body at most {@code HostProbe.MAX_BODY_BYTES}; a copy. Its length only is ever logged. */
    record Answered(int httpStatus, byte[] body) implements Observation {
        public Answered {
            body = body == null ? new byte[0] : body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Answered a && a.httpStatus == httpStatus && Arrays.equals(a.body, body);
        }

        @Override
        public int hashCode() {
            return 31 * httpStatus + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "Answered[httpStatus=" + httpStatus + ", body=" + body.length + " bytes]";
        }
    }
}
