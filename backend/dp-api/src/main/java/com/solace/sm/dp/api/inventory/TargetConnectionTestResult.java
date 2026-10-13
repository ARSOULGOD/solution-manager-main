package com.solace.sm.dp.api.inventory;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The result of {@code testTargetConnection} (plan 013 T1.5, OQ-9 (a)): one flag per step, never an error. An unreachable
 * host, a host that presents another key than the pin, a refused key and a missing Docker daemon are each a RESULT.
 *
 * <p>The steps are cumulative: {@code authenticated} implies {@code hostKeyPinned}, and {@code dockerAvailable} implies
 * {@code authenticated}. {@code dockerServerVersion} is the one fixed command's first output line, kept only if it is a
 * plain version string; it is the host's answer and never anything of the request's.
 */
public record TargetConnectionTestResult(boolean hostKeyPinned, boolean authenticated, boolean dockerAvailable,
        String dockerServerVersion, Instant checkedAt) {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+~-]{0,63}$");

    public TargetConnectionTestResult {
        if (authenticated && !hostKeyPinned) {
            throw new IllegalArgumentException("a connection cannot authenticate without its pinned host key");
        }
        if (dockerAvailable && !authenticated) {
            throw new IllegalArgumentException("Docker cannot be reached without authenticating");
        }
        if (dockerServerVersion != null && (!dockerAvailable || !VERSION.matcher(dockerServerVersion).matches())) {
            throw new IllegalArgumentException("a Docker version is reported only for an available daemon, as a plain version");
        }
        Objects.requireNonNull(checkedAt, "checkedAt");
    }

    /** Whether {@code line} is a version string this result may carry. */
    public static boolean isVersion(String line) {
        return line != null && VERSION.matcher(line).matches();
    }
}
