package com.solace.sm.dp.metrics.collect.fetch;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.metrics.collect.JsonPointerExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The {@code direct-http} fetcher (plan 011 P11-4.2a, M-Q2, {@code OR-82}): a bounded {@code GET} from the data plane to
 * {@code http://<target's registered address>:<host port><path>?...}.
 *
 * <p><b>The address is the target's.</b> It is {@code target_connection.host}, read FRESH on every call through
 * {@link TargetConnectionRows#find} ({@code dp-api}'s {@code TargetConnectionService} deliberately exposes no host), so
 * a re-target polls the new target's address and nothing is carried forward. Nothing about it comes from a browser
 * (B2), so there is no host to validate against a deny list. A target with no live record is a registration problem
 * (T2.7): {@code Transport("target.unavailable")}, a gap, and never a fallback to SSH. The column's grammar admits a DNS
 * name or an IPv4 literal and nothing with {@code @ / : [}; the fetcher <b>re-checks it</b> before it builds the
 * {@link URI} (the row is runtime-writable, {@code GRANT UPDATE (host, ...)}), so the URI cannot gain a second authority.
 *
 * <p><b>The port is the placement's mapped host port</b> ({@code hostPort}, {@code OR-74}), never the package's
 * container port. The path and query pass {@link HostMetricProbe#relativeUrl}'s closed grammar, the one SSH mode uses.
 *
 * <p><b>Bounds (MF10).</b> {@code HttpClient.Builder.connectTimeout} bounds the connect (an
 * {@code HttpConnectTimeoutException}); {@code HttpRequest.Builder.timeout} bounds the wait for the RESPONSE
 * ({@code HttpTimeoutException}) and says nothing of the body, and the JDK's {@code ofInputStream} has no size limit. So
 * the body is read here, at most {@code maxBodyBytes + 1}, under a watchdog that closes the stream at the request
 * deadline: a server that sends headers and drips the body is bounded too. Redirects are never followed: a redirect
 * is a second address nobody registered, so a {@code 3xx} is its status.
 *
 * <p>The failure vocabulary is closed: {@code timeout}, {@code connect.failed}, {@code io.failed}, {@code body.too.large},
 * {@code fetch.refused}, {@code target.unavailable}, {@code target.host.refused}, {@code target.address.refused}
 * (the address policy refused the stored host, plan 013 T1.2). A body, a host or an exception message never
 * appears in one (I6).
 */
public final class DirectHttpFetcher implements MetricFetcher, AutoCloseable {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?");

    private final TargetConnectionRows targets;
    private final FetchProperties properties;
    private final Clock clock;
    private final HttpClient client;
    private final ScheduledExecutorService watchdog;
    private final TargetAddressPolicy addressPolicy;

    public DirectHttpFetcher(TargetConnectionRows targets, FetchProperties properties, Clock clock,
            TargetAddressPolicy addressPolicy) {
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(properties.connectTimeout()).build();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sm-metrics-fetch-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public FetchResult fetch(FetchRequest request) {
        Objects.requireNonNull(request, "request");
        return get(request.targetId(), request.placement().tenant().value(), request.hostPort(), request.path(),
                request.queryParams());
    }

    /**
     * Direct mode reads the component's declared {@code restartSignal} (OQ-1 (a)). With none declared the token is
     * absent and the DP derives the source's detection as inferred; with one declared that failed to read this cycle
     * the answer is {@link RestartRead.Failed}, which is never an inferred reset (P11-3.1 rule 3).
     */
    @Override
    public RestartRead restartToken(RestartRequest request) {
        Objects.requireNonNull(request, "request");
        RestartRequest.Signal signal = request.signal();
        if (signal == null) {
            return new RestartRead.NotDeclared();
        }
        FetchResult result = get(request.targetId(), request.placement().tenant().value(), signal.hostPort(),
                signal.path(), Map.of());
        if (result instanceof FetchResult.Transport transport) {
            return new RestartRead.Failed(transport.classifier());
        }
        if (result instanceof FetchResult.Http) {
            return new RestartRead.Failed("http.status");
        }
        String token = JsonPointerExtractor.token(((FetchResult.Ok) result).body(), signal.pointer());
        return token == null ? new RestartRead.Failed("parse_failed") : new RestartRead.Token(token);
    }

    private FetchResult get(java.util.UUID targetId, java.util.UUID tenantId, int hostPort, String path,
            Map<String, String> queryParams) {
        // 1. the request's own grammar, before the target is even read: a refused request touches nothing
        String relative;
        try {
            relative = HostMetricProbe.relativeUrl(path, queryParams);
        } catch (RuntimeAdapterException e) {
            return new FetchResult.Transport("fetch.refused");
        }
        if (hostPort < 1 || hostPort > 65535) {
            return new FetchResult.Transport("fetch.refused");
        }
        // 2. the target's registered address, read fresh
        Optional<TargetConnectionRecord> target = targets.find(tenantId, targetId).filter(t -> t.retiredAt() == null);
        if (target.isEmpty()) {
            return new FetchResult.Transport("target.unavailable");
        }
        String host = target.get().host();
        if (host == null || !HOST.matcher(host).matches()) {
            return new FetchResult.Transport("target.host.refused");
        }
        // 3. the address policy, on the stored host, immediately before the connection (plan 013 T1.2, OQ-3 (d)). The
        //    JDK client re-resolves the name when it sends, so what this closes is a name that NOW resolves somewhere the
        //    data plane may not go; the residual is the check-to-connect window (D-REG section 7.3).
        try {
            addressPolicy.check(host);
        } catch (TargetAddressRefusedException e) {
            return new FetchResult.Transport("target.address.refused");
        } catch (UnknownHostException e) {
            return new FetchResult.Transport("connect.failed");
        }
        int query = relative.indexOf('?');
        URI uri;
        try {
            uri = new URI("http", null, host, hostPort, query < 0 ? relative : relative.substring(0, query),
                    query < 0 ? null : relative.substring(query + 1), null);
        } catch (URISyntaxException e) {
            return new FetchResult.Transport("fetch.refused");
        }
        return send(uri);
    }

    private FetchResult send(URI uri) {
        Duration timeout = properties.requestTimeout();
        long startNanos = System.nanoTime();
        Instant sent = clock.instant();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> guard = null;
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                long remaining = timeout.toNanos() - (System.nanoTime() - startNanos);
                guard = watchdog.schedule(() -> {
                    expired.set(true);
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // closing aborts the exchange; the reader sees the failure
                    }
                }, Math.max(remaining, 1), TimeUnit.NANOSECONDS);
                byte[] body = in.readNBytes(properties.maxBodyBytes() + 1);
                if (expired.get()) {
                    return new FetchResult.Transport("timeout");
                }
                if (body.length > properties.maxBodyBytes()) {
                    return new FetchResult.Transport("body.too.large");
                }
                int status = response.statusCode();
                if (status < 200 || status > 299) {
                    return new FetchResult.Http(status);
                }
                Instant received = clock.instant();
                return new FetchResult.Ok(body, FetchRequest.midpoint(sent, received), received);
            }
        } catch (HttpTimeoutException e) {
            return new FetchResult.Transport("timeout");
        } catch (ConnectException e) {
            return new FetchResult.Transport("connect.failed");
        } catch (IOException e) {
            return new FetchResult.Transport(expired.get() ? "timeout" : "io.failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult.Transport("io.failed");
        } finally {
            if (guard != null) {
                guard.cancel(false);
            }
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "DirectHttpFetcher";
    }
}
