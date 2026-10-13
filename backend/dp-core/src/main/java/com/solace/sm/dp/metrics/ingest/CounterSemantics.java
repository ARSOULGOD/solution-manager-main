package com.solace.sm.dp.metrics.ingest;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.RestartDetection;
import java.time.Duration;
import java.time.Instant;

/**
 * M-Q9 (d') as one pure function (plan 011 P11-3.1 step 4): given a counter series' own previous sample and the token
 * seen with it, and a new sample, decide the attributable {@code increment}, whether the series reset, and whether the
 * sample counts against a collector reason. No I/O, no clock, no state: {@link IngestService} owns where the previous
 * sample comes from.
 *
 * <p>The rules apply in this order and the first that matches wins:
 * <ol>
 *   <li><b>Rule 1.</b> Detection {@code DECLARED} or {@code DOCKER}, the sample carries a token and the series'
 *       previous token differs: a RESET, {@code increment = value}, at any spacing, even across a gap (S-D).</li>
 *   <li><b>Rule 2.</b> The same detection, the token equals the previous one and {@code value < previous}:
 *       {@code increment = NULL} and {@code DECREASE_WITHOUT_RESTART}. Never a reset.</li>
 *   <li><b>Rule 3.</b> The same detection and NO token (the signal failed to fetch this cycle):
 *       {@code increment = NULL}, the previous token is unchanged and {@code SCRAPE_FAILED}. Never an inferred reset.</li>
 *   <li><b>Rule 4.</b> Detection {@code INFERRED} and {@code value < previous}: a reset with signal {@code inferred}.</li>
 *   <li><b>Rule 5.</b> {@code value >= previous} and spacing at most twice the interval: {@code increment = value - previous}.</li>
 *   <li><b>Rule 6.</b> {@code value >= previous} and spacing over twice the interval: {@code increment = NULL} (a gap).</li>
 * </ol>
 * (Rule 3 is tested first in the code because "no token" must be decided before a token is compared; the outcomes are
 * the plan's.) A series' first sample has {@code increment = NULL}. Spacing is always the ACTUAL difference between the
 * two timestamps (M-Q24 (8)), never the nominal interval. A source with no interval (push) has no spacing bound.
 */
final class CounterSemantics {

    private CounterSemantics() {
    }

    /** What a series remembers about its last stored sample. {@code token} is null when none was ever seen. */
    record Previous(Instant ts, double value, String token) {
    }

    /**
     * @param increment null for none; never zero-filled
     * @param resetSignal the {@code series_reset.signal} to record, or null
     * @param counted     the reason this sample counts against, or null
     * @param tokenAfter  the token the series remembers after this sample
     */
    record Decision(Double increment, String resetSignal, CollectorReason counted, String tokenAfter) {
    }

    static Decision counter(RestartDetection detection, Previous prev, Instant ts, double value, String token,
            Integer intervalSeconds) {
        String prevToken = prev == null ? null : prev.token();
        if (detection.hasSignal() && token == null) {
            return new Decision(null, null, CollectorReason.SCRAPE_FAILED, prevToken);
        }
        String tokenAfter = token != null ? token : prevToken;
        if (prev == null) {
            return new Decision(null, null, null, tokenAfter);
        }
        if (detection.hasSignal()) {
            if (prevToken != null && !prevToken.equals(token)) {
                return new Decision(value, signal(detection), null, tokenAfter);
            }
            if (value < prev.value()) {
                return new Decision(null, null, CollectorReason.DECREASE_WITHOUT_RESTART, tokenAfter);
            }
        } else if (value < prev.value()) {
            return new Decision(value, signal(detection), null, tokenAfter);
        }
        if (intervalSeconds != null && spacing(prev.ts(), ts).compareTo(Duration.ofSeconds(2L * intervalSeconds)) > 0) {
            return new Decision(null, null, null, tokenAfter);
        }
        return new Decision(value - prev.value(), null, null, tokenAfter);
    }

    /** A gauge never has an increment; its token still advances. */
    static Decision gauge(Previous prev, String token) {
        return new Decision(null, null, null, token != null ? token : (prev == null ? null : prev.token()));
    }

    static Duration spacing(Instant from, Instant to) {
        return Duration.between(from, to);
    }

    static String signal(RestartDetection detection) {
        return switch (detection) {
            case DECLARED -> "declared";
            case DOCKER -> "docker";
            case INFERRED -> "inferred";
        };
    }
}
