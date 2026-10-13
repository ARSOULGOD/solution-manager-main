package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * The {@code direct-http} half of a health read (owner rulings OR-71, OR-82): a bounded {@code GET} from the DP to the
 * <b>target's registered address</b> at the placement's health port and path.
 *
 * <p>The address is never the browser's: the caller passes {@code target_connection.host}, which the registering
 * operator command fixed (T2.7), so the DP cannot be steered at loopback, link-local metadata or its own services by
 * a declare body (plan 010 B2). Redirects are never followed - a redirect is a second address nobody registered.
 *
 * <p>It is a read, bounded in time and size, and it answers in {@link ProbeResult} so both acquisition modes share one
 * mapping: {@code exitCode 0} is an answer whatever its status; {@code 7} connection refused, {@code 6}
 * {@link #EXIT_UNREACHABLE} the host or network could not be reached, {@code 28} timeout after the connection was made,
 * {@code 1} any other transport failure - the values {@code curl} reports for the same causes (M19), with status
 * {@code 0}.
 *
 * <p>One probe serves the apply path's health gate and the health poller (OR-105 d). The poller must tell "nothing
 * listens there" from "cannot find the host", and the JDK client throws the same {@code ConnectException}, with no
 * message, for a refused port, a name that does not resolve and a missing route (measured, JDK 17, 2026-10-05); so the
 * name is resolved and a plain socket connect is made first, and only then is the request sent.
 */
public interface DirectHttpProbe {

    /**
     * The host or the network could not be reached: the name did not resolve, there is no route, or the connection could
     * not be established in time. {@code curl}'s own value for "could not resolve host"; the other causes share it
     * because both read the same to a caller ("not reachable", as opposed to a refusal, {@code 7}, which proves the host
     * is there).
     */
    int EXIT_UNREACHABLE = 6;

    /**
     * @throws RuntimeAdapterException {@code http.probe.refused} for a host, port, path or timeout outside the grammar,
     *         before any connection is attempted; {@code target.address.refused} for a host the address policy refuses,
     *         also before any connection. A non-2xx answer is <b>not</b> a failure: it is a {@link ProbeResult}.
     */
    ProbeResult get(String host, int port, String path, Duration timeout);

    /**
     * The production implementation over the JDK client. The {@code policy} runs on the stored host immediately before
     * the connection (plan 013 T1.2, OQ-3 (d)): a refusal is {@code RuntimeAdapterException target.address.refused},
     * never a connection. The JDK client re-resolves the NAME when it sends the request, so the residual is the window
     * between the check and that resolution (D-REG section 7.3), which only an attacker who controls the name's DNS can
     * use; the TCP connect that decides reachability goes to the checked address itself.
     */
    static DirectHttpProbe jdk(TargetAddressPolicy policy) {
        return new JdkDirectHttpProbe(policy);
    }

    /** The JDK-client implementation; its behaviour is {@link DirectHttpProbe}'s contract. */
    final class JdkDirectHttpProbe implements DirectHttpProbe {

        private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?");
        private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9/_.-]{0,255}$");

        private final TargetAddressPolicy policy;

        JdkDirectHttpProbe(TargetAddressPolicy policy) {
            this.policy = java.util.Objects.requireNonNull(policy, "policy");
        }

        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)).build();

        @Override
        public ProbeResult get(String host, int port, String path, Duration timeout) {
            if (host == null || !HOST.matcher(host).matches() || port < 1 || port > 65535 || path == null
                    || !PATH.matcher(path).matches() || timeout == null
                    || timeout.compareTo(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) > 0
                    || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new RuntimeAdapterException(new ErrorClass("http.probe.refused"));
            }
            java.net.InetAddress address;
            try {
                address = policy.connectable(host);
            } catch (TargetAddressRefusedException e) {
                throw new RuntimeAdapterException(new ErrorClass(SshjDockerTransport.TARGET_ADDRESS_REFUSED));
            } catch (java.net.UnknownHostException e) {
                return new ProbeResult(EXIT_UNREACHABLE, 0, new byte[0]);
            }
            ProbeResult notConnected = tcpConnect(address, port, timeout);
            if (notConnected != null) {
                return notConnected;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                    .timeout(timeout).GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = response.body()) {
                    byte[] body = in.readNBytes(HostProbe.MAX_BODY_BYTES + 1);
                    if (body.length > HostProbe.MAX_BODY_BYTES) {
                        throw new RuntimeAdapterException(new ErrorClass("http.probe.output.too.large"));
                    }
                    return new ProbeResult(0, response.statusCode(), body);
                }
            } catch (HttpTimeoutException e) {
                return new ProbeResult(28, 0, new byte[0]);
            } catch (java.net.ConnectException e) {
                return new ProbeResult(7, 0, new byte[0]);
            } catch (IOException e) {
                return new ProbeResult(1, 0, new byte[0]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ProbeResult(1, 0, new byte[0]);
            }
        }

        /** Null when the port accepted; otherwise the result that explains why not: refused (7) or unreachable (6). */
        private static ProbeResult tcpConnect(java.net.InetAddress address, int port, Duration timeout) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(address, port),
                        (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE));
                return null;
            } catch (java.net.ConnectException e) {
                String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
                return new ProbeResult(message.contains("refused") ? 7 : EXIT_UNREACHABLE, 0, new byte[0]);
            } catch (IOException e) {
                return new ProbeResult(EXIT_UNREACHABLE, 0, new byte[0]);
            }
        }
    }
}
