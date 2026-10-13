package com.solace.sm.dp.health;

import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.DirectHttpProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import java.time.Duration;

/**
 * Runs the apply path's one {@link DirectHttpProbe} for the health poller and gives its answer the poller's meaning
 * (OR-105 d; plan 010 T7.2, OR-82). The same causes read as the same {@link Observation}s the SSH path produces:
 * answered, refused, silent (reached, no usable answer), unreachable.
 *
 * <ul>
 *   <li>exit {@code 0} - {@link Observation.Answered}, whatever the status;</li>
 *   <li>{@code 7} - {@link Observation#REFUSED}: the host is there, nothing listens on the port;</li>
 *   <li>{@link DirectHttpProbe#EXIT_UNREACHABLE} - {@link Observation#UNREACHABLE};</li>
 *   <li>{@code 28} and any other exit - {@link Observation#SILENT}: the connection was made, no usable answer;</li>
 *   <li>an oversized answer ({@code http.probe.output.too.large}) - {@code SILENT}, it is not a health document; any
 *       other adapter failure - {@code UNREACHABLE};</li>
 *   <li>a thread interrupt during the probe - {@link Observation#DEFERRED}: a shutdown is not a red service.</li>
 * </ul>
 *
 * <p>A host, port, path or timeout outside the probe's grammar ({@code http.probe.refused}) is not mapped: it propagates,
 * and the poller logs the class and skips the placement exactly as it always did for a declaration it cannot probe.
 */
final class DirectHttpObservations {

    private DirectHttpObservations() {
    }

    static Observation observe(DirectHttpProbe probe, String host, int port, String path, Duration timeout) {
        ProbeResult result;
        try {
            result = probe.get(host, port, path, timeout);
        } catch (RuntimeAdapterException e) {
            return switch (e.errorClass().token()) {
                case "http.probe.refused" -> throw e;
                case "http.probe.output.too.large" -> Observation.SILENT;
                default -> Observation.UNREACHABLE;
            };
        }
        if (Thread.currentThread().isInterrupted()) {
            return Observation.DEFERRED;
        }
        return switch (result.exitCode()) {
            case 0 -> new Observation.Answered(result.httpStatus(), result.body());
            case 7 -> Observation.REFUSED;
            case DirectHttpProbe.EXIT_UNREACHABLE -> Observation.UNREACHABLE;
            default -> Observation.SILENT;
        };
    }
}
