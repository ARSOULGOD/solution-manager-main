package com.solace.sm.gateway.http;

/**
 * The result of {@code testTargetConnection} (console.yaml {@code TargetConnectionTest}): one flag per step, never an
 * error. An unreachable host, a host that presents another key than the pin, a key the host refuses and a stopped Docker
 * daemon are each a RESULT. The steps are cumulative ({@code authenticated} implies {@code hostKeyPinned}, Docker
 * implies {@code authenticated}). {@code checkedAt} is an ISO-8601 instant, spelt as a {@code String} like every other
 * timestamp on this wire.
 */
public record ConnectionTestResult(boolean hostKeyPinned, boolean authenticated, DockerCheck docker, String checkedAt) {

    /** @param serverVersion the daemon's version, only when it answered */
    public record DockerCheck(boolean available, @WireOptional String serverVersion) {
    }
}
