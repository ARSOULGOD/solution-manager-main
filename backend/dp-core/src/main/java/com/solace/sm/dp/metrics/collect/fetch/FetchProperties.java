package com.solace.sm.dp.metrics.collect.fetch;

import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The fetchers' bounds - {@code sm.dp.metrics.fetch.*} (plan 011 P11-4.2).
 *
 * @param connectTimeout the direct fetcher's TCP connect bound (MF10: {@code HttpClient.Builder.connectTimeout}); 2 s
 * @param requestTimeout one fetch's bound, headers AND body (MF10: {@code HttpRequest.Builder.timeout} covers the
 *                       response only, so the body read is guarded separately); 5 s, and at most
 *                       {@value HostProbe#MAX_TIMEOUT_SECONDS} s so an SSH {@code curl --max-time} may carry the same value
 * @param maxBodyBytes   a larger answer is a transport failure, never a truncated body; default and ceiling are the
 *                       transport's cap ({@value HostProbe#MAX_BODY_BYTES} bytes)
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.fetch")
public record FetchProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("65536") int maxBodyBytes) {

    public FetchProperties {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.fetch.connect-timeout must be a positive duration");
        }
        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || requestTimeout.compareTo(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) > 0) {
            throw new IllegalArgumentException("sm.dp.metrics.fetch.request-timeout must be between 1 s and "
                    + HostProbe.MAX_TIMEOUT_SECONDS + " s, was " + requestTimeout);
        }
        if (maxBodyBytes < 1 || maxBodyBytes > HostProbe.MAX_BODY_BYTES) {
            throw new IllegalArgumentException("sm.dp.metrics.fetch.max-body-bytes must be between 1 and "
                    + HostProbe.MAX_BODY_BYTES + ", was " + maxBodyBytes);
        }
    }

    /** The defaults, for code that builds a fetcher outside Spring. */
    public static FetchProperties defaults() {
        return new FetchProperties(Duration.ofSeconds(2), Duration.ofSeconds(5), HostProbe.MAX_BODY_BYTES);
    }
}
