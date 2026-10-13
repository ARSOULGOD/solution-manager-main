package com.solace.sm.dp.apply.runtime.docker;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Where the Docker daemon is, and how long to wait for it.
 *
 * <p>The host is a <b>location</b> and nothing else — the {@code JdbcUrls} posture from plan 004,
 * applied to the second location string this backend holds. Refusal messages name the rule and never
 * the value.
 */
public record DockerProperties(URI host, Duration connectTimeout, Duration responseTimeout,
        int stopTimeoutSeconds) {

    private static final Set<String> SCHEMES = Set.of("unix", "tcp", "npipe");

    public DockerProperties {
        requireLocationOnly(host);
        Objects.requireNonNull(connectTimeout, "connect timeout");
        Objects.requireNonNull(responseTimeout, "response timeout");
        if (stopTimeoutSeconds < 0) {
            throw new IllegalArgumentException("the stop timeout is a number of seconds, not " + stopTimeoutSeconds);
        }
    }

    /**
     * A Docker host is a location: a scheme this transport speaks, and a path or authority. It
     * carries no credential, no query and no fragment.
     *
     * <p>Docker logs the host it connected to, so a credential smuggled into a userinfo segment
     * would reach a log line ({@code I6}). The offending value is deliberately never repeated.
     */
    public static URI requireLocationOnly(URI host) {
        Objects.requireNonNull(host, "docker host");
        if (host.getScheme() == null || !SCHEMES.contains(host.getScheme())) {
            throw new IllegalArgumentException("a docker host scheme must be one of " + SCHEMES
                    + " — the offending value is deliberately not repeated here (I6)");
        }
        if (host.getUserInfo() != null || host.getRawQuery() != null || host.getRawFragment() != null) {
            throw new IllegalArgumentException("a docker host is a LOCATION: no userinfo, no query, "
                    + "no fragment. The daemon host is logged, so a credential here would reach a "
                    + "log line — the offending value is deliberately not repeated (I6)");
        }
        return host;
    }
}
