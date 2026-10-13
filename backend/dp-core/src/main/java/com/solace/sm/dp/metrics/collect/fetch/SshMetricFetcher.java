package com.solace.sm.dp.metrics.collect.fetch;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricExec;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The {@code via-ssh} fetcher (plan 011 P11-4.2b, M-Q2, M-Q9): the DP asks the target host to read its own loopback.
 *
 * <p>Two fixed commands, built by {@link HostMetricProbe} and run on the target's pooled session through
 * {@link HostMetricExec}: a {@code curl} to {@code http://127.0.0.1:<host port><path>?...} (no {@code --fail}, M19: a
 * {@code 503} is an answer and a refused connection is not), and {@code timeout --kill-after=2 <n> docker inspect --format '{{.Id}}
 * {{.State.StartedAt}}'} for the restart token (M-Q9 (d'); {@code StartedAt} changes on restart and on stop-then-start,
 * and a recreated container has a new id: plan 010 M18). The request's path and parameters are checked against the closed
 * grammar BEFORE the placement is looked up, so a refused request touches neither the catalog nor a session.
 *
 * <p>The placement is looked up fresh for every call, so a rotated credential or a corrected pin is used by the very next
 * session. A placement whose instance is {@code direct-http} is never scraped here ({@code mode.mismatch}): there is no
 * automatic fallback, in either direction (M-Q2).
 *
 * <p>A failure is a result, never an exception, and its classifier comes from a fixed vocabulary (the pool's own tokens
 * and {@code timeout}, {@code connect.failed}, {@code io.failed}, {@code body.too.large}, {@code fetch.refused},
 * {@code mode.mismatch}, {@code docker.inspect.failed}, {@code docker.inspect.invalid}). A body, a host or a container's
 * output never appears in one (I6).
 *
 * <p><b>The pool is shared with health (W3-02).</b> The pool's own load tokens ({@code ssh.pool.busy},
 * {@code ssh.pool.closed}) are not a host failure: they come back as {@link FetchResult.Deferred}, which the collector
 * counts as a missed deadline and never as a failed scrape. And the collector's in-flight SSH runs are bounded
 * GLOBALLY by {@code maxConcurrentRuns} (the pool's {@code maxConcurrentProbes} less the health reserve), so a burst of
 * due targets cannot hold every probe slot and leave health waiting out its acquire timeout.
 */
public final class SshMetricFetcher implements MetricFetcher {

    /** {@code <64 hex id> <RFC 3339 UTC start time with up to nine fractional digits>}, one line. */
    private static final Pattern INSPECT_LINE =
            Pattern.compile("^[0-9a-f]{64} [0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z$");

    /** {@code timeout(1)}: the command ran out of time (124), or was killed after the grace period (128 + 9). */
    private static final int TIMEOUT_EXIT = 124;
    private static final int TIMEOUT_KILLED_EXIT = 137;

    private final SshDockerPlacementCatalog catalog;
    private final HostMetricExec exec;
    private final FetchProperties properties;
    private final Clock clock;
    /** W3-02: the collector's global share of the pool's probe slots; {@code null} is unbounded (tests, no health). */
    private final Semaphore runs;
    private final Duration runWait;
    private final Runnable idleEvictor;

    /** No global bound on the collector's runs: for code that builds a fetcher outside the shared pool's wiring. */
    public SshMetricFetcher(SshDockerPlacementCatalog catalog, HostMetricExec exec, FetchProperties properties,
            Clock clock) {
        this(catalog, exec, properties, clock, 0, Duration.ZERO, () -> { });
    }

    /** Bounded runs, no idle eviction of its own (the health poller closes idle sessions). */
    public SshMetricFetcher(SshDockerPlacementCatalog catalog, HostMetricExec exec, FetchProperties properties,
            Clock clock, int maxConcurrentRuns, Duration runWait) {
        this(catalog, exec, properties, clock, maxConcurrentRuns, runWait, () -> { });
    }

    /**
     * @param maxConcurrentRuns most SSH commands the collector has in flight at once, across every target; {@code 0} is
     *                          unbounded. Production passes {@code max(1, maxConcurrentProbes - healthReserve)}.
     * @param runWait           how long a run waits for one of those places before it is deferred; the pool's acquire timeout
     * @param idleEvictor       closes the pool's idle sessions; the health poller does this after each round, and with health
     *                          off (W3-10) the collector's loop must, or sessions would never be released
     */
    public SshMetricFetcher(SshDockerPlacementCatalog catalog, HostMetricExec exec, FetchProperties properties,
            Clock clock, int maxConcurrentRuns, Duration runWait, Runnable idleEvictor) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.exec = Objects.requireNonNull(exec, "exec");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxConcurrentRuns < 0) {
            throw new IllegalArgumentException("maxConcurrentRuns must be >= 0, was " + maxConcurrentRuns);
        }
        this.runs = maxConcurrentRuns == 0 ? null : new Semaphore(maxConcurrentRuns, true);
        this.runWait = Objects.requireNonNull(runWait, "runWait");
        this.idleEvictor = Objects.requireNonNull(idleEvictor, "idleEvictor");
    }

    @Override
    public void housekeeping() {
        idleEvictor.run();
    }

    /** Takes one of the collector's global places; {@code false} when none came free in time (or on interrupt). */
    private boolean enter() {
        if (runs == null) {
            return true;
        }
        try {
            return runs.tryAcquire(runWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void leave() {
        if (runs != null) {
            runs.release();
        }
    }

    /** The pool's load tokens say nothing about the host. */
    private static boolean isLoad(String token) {
        return "ssh.pool.busy".equals(token) || "ssh.pool.closed".equals(token);
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        Objects.requireNonNull(request, "request");
        HostMetricProbe.Curl curl;
        try {
            curl = HostMetricProbe.curl(request.hostPort(), request.path(), request.queryParams(),
                    properties.requestTimeout());
        } catch (RuntimeAdapterException e) {
            return new FetchResult.Transport("fetch.refused");
        }
        SshDockerPlacement placement;
        try {
            placement = catalog.lookup(request.placement());
        } catch (RuntimeAdapterException e) {
            return new FetchResult.Transport(e.errorClass().token());
        }
        if (placement.acquisition() != AcquisitionMode.VIA_SSH) {
            return new FetchResult.Transport("mode.mismatch");
        }
        if (!enter()) {
            return new FetchResult.Deferred("ssh.pool.busy");
        }
        Instant sent = clock.instant();
        ProbeResult answer;
        try {
            answer = exec.curl(placement, curl);
        } catch (RuntimeAdapterException e) {
            String token = classify(e);
            return isLoad(token) ? new FetchResult.Deferred(token) : new FetchResult.Transport(token);
        } finally {
            leave();
        }
        switch (answer.exitCode()) {
            case 0 -> {
                // an answer, whatever its status (M19)
            }
            case 7 -> {
                return new FetchResult.Transport("connect.failed");
            }
            case 28 -> {
                return new FetchResult.Transport("timeout");
            }
            default -> {
                return new FetchResult.Transport("io.failed");
            }
        }
        byte[] body = answer.body();
        if (body.length > properties.maxBodyBytes()) {
            return new FetchResult.Transport("body.too.large");
        }
        int status = answer.httpStatus();
        if (status < 200 || status > 299) {
            return new FetchResult.Http(status);
        }
        Instant received = clock.instant();
        return new FetchResult.Ok(body, FetchRequest.midpoint(sent, received), received);
    }

    /**
     * SSH mode's signal is the container's own (OQ-1 (a), M-Q9 (d')): {@code "<id> <startedAt>"}. A declared
     * {@code restartSignal} in the request is for direct mode and is ignored here. A failed read is {@code Failed},
     * which the DP never treats as an inferred reset.
     */
    @Override
    public RestartRead restartToken(RestartRequest request) {
        Objects.requireNonNull(request, "request");
        SshDockerPlacement placement;
        try {
            placement = catalog.lookup(request.placement());
        } catch (RuntimeAdapterException e) {
            return new RestartRead.Failed(e.errorClass().token());
        }
        if (placement.acquisition() != AcquisitionMode.VIA_SSH) {
            return new RestartRead.Failed("mode.mismatch");
        }
        if (!enter()) {
            return new RestartRead.Failed("ssh.pool.busy");
        }
        HostMetricExec.InspectResult result;
        try {
            result = exec.inspect(placement,
                    HostMetricProbe.inspect(placement.containerName(), properties.requestTimeout()));
        } catch (RuntimeAdapterException e) {
            // never an inferred reset, and a load token is not a host failure (the collector does not read this one as
            // one: a failed token read only fails a source that declared a signal)
            return new RestartRead.Failed(classify(e));
        } finally {
            leave();
        }
        if (result.exitCode() == TIMEOUT_EXIT || result.exitCode() == TIMEOUT_KILLED_EXIT) {
            // W2-03: the host's own "timeout" ran out (124), or had to kill the stuck docker inspect (137)
            return new RestartRead.Failed("timeout");
        }
        if (result.exitCode() != 0) {
            return new RestartRead.Failed("docker.inspect.failed");
        }
        String line = result.stdout().endsWith("\n")
                ? result.stdout().substring(0, result.stdout().length() - 1) : result.stdout();
        if (!INSPECT_LINE.matcher(line).matches()) {
            return new RestartRead.Failed("docker.inspect.invalid");
        }
        return new RestartRead.Token(line);
    }

    /** The pool's and the connection's classifiers are fixed tokens; one over-large output is this fetcher's own cap. */
    private static String classify(RuntimeAdapterException e) {
        String token = e.errorClass().token();
        return "ssh.probe.output.too.large".equals(token) ? "body.too.large" : token;
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "SshMetricFetcher";
    }
}
