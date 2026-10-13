package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Builds the two fixed commands plan 011's collector runs on a target host (P11-4.2, M-Q2, M-Q9), the
 * {@link HostProbe} sibling. SM-D11a: <b>no shell composition from data</b>. Nothing here executes anything.
 *
 * <ul>
 *   <li>{@link #curl}: {@code curl --silent --show-error --max-time <n> --output - --write-out '\n%{http_code}'
 *       'http://127.0.0.1:<port><path>[?k=v&...]'}. The inputs are an {@code int} port, a path matching
 *       {@code ^/[A-Za-z0-9/_.-]{0,255}$}, and at most eight {@code queryParams} whose keys and values match
 *       {@code ^[A-Za-z0-9_.-]{1,64}$}. This class re-checks all of it, independent of the schema; anything else is
 *       refused before a command exists. The DP inserts {@code ?}, {@code =} and {@code &}, and the URL is
 *       single-quoted as defence in depth (the grammar is the guarantee). <b>No {@code --fail}</b> (plan 010 M19 /
 *       MF12): a {@code 503} stays distinguishable from a refused connection.</li>
 *   <li>{@link #inspect}: {@code timeout --kill-after=2 <n> docker inspect --format '{{.Id}} {{.State.StartedAt}}' --
 *       <container>}, the container name in {@link SshDockerPlacement}'s own grammar. The SSH-mode restart token is the
 *       one output line (M-Q9 (d'), MF11 / plan 010 M18). <b>Bounded on the host (W2-03):</b> a wedged {@code dockerd}
 *       makes {@code docker inspect} block forever, and sshj's channel streams have no read timeout, so the command
 *       carries its own {@code timeout}; exit {@code 124} (or {@code 137}, killed after the grace) is the host saying
 *       "too slow".</li>
 * </ul>
 *
 * <p>A command is a {@link Curl} or an {@link Inspect}: types only this class can construct, so a pool method that
 * takes one cannot be handed a caller-supplied shell string.
 */
public final class HostMetricProbe {

    /** At most this many query parameters (the schema's {@code maxProperties}). */
    public static final int MAX_QUERY_PARAMS = 8;

    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9/_.-]{0,255}$");
    private static final Pattern QUERY_TOKEN = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern CONTAINER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    private HostMetricProbe() {
    }

    /**
     * @throws RuntimeAdapterException {@code ssh.probe.refused} for a port, path, parameter or timeout outside the
     *         grammar
     */
    public static Curl curl(int port, String path, Map<String, String> queryParams, Duration timeout) {
        if (port < 1 || port > 65535 || timeout == null
                || timeout.compareTo(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) > 0
                || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw refused("ssh.probe.refused");
        }
        String relative = relativeUrl(path, queryParams);
        long seconds = (timeout.toMillis() + 999) / 1000;
        return new Curl(timeout, "curl --silent --show-error --max-time " + seconds + " --output - --write-out '\\n%{http_code}' "
                + "'http://127.0.0.1:" + port + relative + "'");
    }

    /**
     * The path and query as they appear after the authority: {@code /p} or {@code /p?a=1&b=2}, parameters in key
     * order so the same URL is the same string. The direct fetcher builds its URI from this too, so there is ONE
     * grammar for both modes.
     *
     * @throws RuntimeAdapterException {@code ssh.probe.refused} for a path or parameter outside the grammar
     */
    public static String relativeUrl(String path, Map<String, String> queryParams) {
        if (path == null || !PATH.matcher(path).matches()) {
            throw refused("ssh.probe.refused");
        }
        if (queryParams == null || queryParams.isEmpty()) {
            return path;
        }
        if (queryParams.size() > MAX_QUERY_PARAMS) {
            throw refused("ssh.probe.refused");
        }
        StringBuilder url = new StringBuilder(path);
        char separator = '?';
        for (Map.Entry<String, String> parameter : new TreeMap<>(queryParams).entrySet()) {
            String key = parameter.getKey();
            String value = parameter.getValue();
            if (key == null || value == null || !QUERY_TOKEN.matcher(key).matches()
                    || !QUERY_TOKEN.matcher(value).matches()) {
                throw refused("ssh.probe.refused");
            }
            url.append(separator).append(key).append('=').append(value);
            separator = '&';
        }
        return url.toString();
    }

    /**
     * @param timeout the host-side bound, 1 s to {@value HostProbe#MAX_TIMEOUT_SECONDS} s, rounded up to whole seconds
     * @throws RuntimeAdapterException {@code ssh.inspect.refused} for a container name outside the placement's grammar
     *         or a timeout outside the bound
     */
    public static Inspect inspect(String container, Duration timeout) {
        if (container == null || !CONTAINER.matcher(container).matches() || timeout == null
                || timeout.compareTo(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) > 0
                || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw refused("ssh.inspect.refused");
        }
        long seconds = (timeout.toMillis() + 999) / 1000;
        return new Inspect(timeout, "timeout --kill-after=" + KILL_AFTER_SECONDS + " " + seconds
                + " docker inspect --format '{{.Id}} {{.State.StartedAt}}' -- " + container);
    }

    /** {@code <64 hex id> <RFC 3339 UTC start time with up to nine fractional digits>}, one line. */
    private static final Pattern INSPECT_LINE =
            Pattern.compile("^([0-9a-f]{64}) ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z)$");

    /** What {@link #inspect}'s one output line says: the container, and when the Docker host started it (the host's clock). */
    public record ContainerStart(String containerId, Instant startedAt) {
    }

    /**
     * Reads {@link #inspect}'s output ({@code <id> <StartedAt>}, one line, a trailing newline allowed). Anything else is
     * empty: the host's output is never trusted past its own grammar, and is never echoed.
     */
    public static Optional<ContainerStart> parseInspect(String stdout) {
        if (stdout == null) {
            return Optional.empty();
        }
        String line = stdout.endsWith("\n") ? stdout.substring(0, stdout.length() - 1) : stdout;
        java.util.regex.Matcher m = INSPECT_LINE.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ContainerStart(m.group(1), Instant.parse(m.group(2))));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();   // shaped like a time and not one (month 13): not an observation
        }
    }

    /** {@code timeout --kill-after}: how long after the TERM a stuck {@code docker inspect} is killed outright. */
    public static final int KILL_AFTER_SECONDS = 2;

    /** One fixed metrics {@code curl}; only {@link HostMetricProbe#curl} makes one. */
    public static final class Curl {
        private final Duration timeout;
        private final String command;

        private Curl(Duration timeout, String command) {
            this.timeout = timeout;
            this.command = command;
        }

        /** The bound the command carries ({@code --max-time}); the pool's deadline is derived from it. */
        public Duration timeout() {
            return timeout;
        }

        public String command() {
            return command;
        }

        @Override
        public String toString() {
            return "Curl";
        }
    }

    /** One fixed {@code docker inspect}; only {@link HostMetricProbe#inspect} makes one. */
    public static final class Inspect {
        private final Duration timeout;
        private final String command;

        private Inspect(Duration timeout, String command) {
            this.timeout = timeout;
            this.command = command;
        }

        /** The bound the command carries ({@code timeout}); the pool's deadline is derived from it. */
        public Duration timeout() {
            return timeout;
        }

        public String command() {
            return command;
        }

        @Override
        public String toString() {
            return "Inspect";
        }
    }

    private static RuntimeAdapterException refused(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }
}
