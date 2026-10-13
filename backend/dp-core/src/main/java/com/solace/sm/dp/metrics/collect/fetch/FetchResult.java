package com.solace.sm.dp.metrics.collect.fetch;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * What one fetch saw, before the collector gives it a meaning. A transport failure and a non-2xx answer are
 * different things on purpose (M19 / MF12): a {@code 503} is an answer, a refused connection is not.
 *
 * <p>Nothing here renders a body into a string: {@link Ok#toString()} prints a length only (I6).
 */
public sealed interface FetchResult permits FetchResult.Ok, FetchResult.Http, FetchResult.Transport, FetchResult.Deferred {

    /**
     * A 2xx answer.
     *
     * @param body       at most the fetcher's byte cap; a copy
     * @param ts         the UTC midpoint of request-sent and response-received (M-Q24 (8))
     * @param receivedAt the response time
     */
    record Ok(byte[] body, Instant ts, Instant receivedAt) implements FetchResult {
        public Ok {
            body = body == null ? new byte[0] : body.clone();
            Objects.requireNonNull(ts, "ts");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Ok o && Arrays.equals(o.body, body) && o.ts.equals(ts)
                    && o.receivedAt.equals(receivedAt);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Arrays.hashCode(body) + ts.hashCode()) + receivedAt.hashCode();
        }

        @Override
        public String toString() {
            return "Ok[body=" + body.length + " bytes, ts=" + ts + ", receivedAt=" + receivedAt + "]";
        }
    }

    /** The host answered with a non-2xx status: a {@code 503} stays a 503 and is never a transport failure. */
    record Http(int status) implements FetchResult {
    }

    /** No usable answer. {@code classifier} is a fixed token from a closed vocabulary: never a message, a host or a cause. */
    record Transport(String classifier) implements FetchResult {
        public Transport {
            Objects.requireNonNull(classifier, "classifier");
        }
    }

    /**
     * W3-02: the fetch was NOT attempted, because the SSH pool (or the collector's own share of it) had no free slot, or the
     * pool was closing. That is a load signal about the DP, never an observation about the host: the collector counts it
     * {@code DEADLINE_MISSED}, not {@code SCRAPE_FAILED}, and never backs the healthy target off for it.
     */
    record Deferred(String classifier) implements FetchResult {
        public Deferred {
            Objects.requireNonNull(classifier, "classifier");
        }
    }
}
