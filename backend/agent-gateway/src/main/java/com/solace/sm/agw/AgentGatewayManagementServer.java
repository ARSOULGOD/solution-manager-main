package com.solace.sm.agw;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.springframework.context.SmartLifecycle;

/**
 * The authenticated observability surface the launcher's HTTP chain deliberately does not provide.
 *
 * <p>The launcher has no actuator HTTP endpoint: the actuator starter registers web {@code HandlerMapping}s outside the
 * console's authenticated chain, and the console's {@code OperationCoverageValidator} refuses to start next to one. That
 * left operators with a TCP readiness socket and nothing else — no way to scrape the gateway's meters or read why it is
 * not ready. This is the replacement, and it is not a route of the console: a separate listener on its own port, so the
 * validator (which inspects the console's mappings) is not involved and no console route is added.
 *
 * <p>What keeps it from becoming the unauthenticated surface the actuator would have been: <b>it is off unless
 * {@code management-port} is set; it binds to loopback unless told otherwise; every request needs the configured bearer
 * token, compared in constant time; and it is GET-only.</b> There is no TLS here — it is meant for a scraper on the same
 * host or an overlay network the operator already trusts; put it behind the operator's own TLS terminator otherwise. It
 * serves {@code /management/health} (JSON) and {@code /management/metrics} (Prometheus text exposition, including the
 * JVM's heap, threads and CPU so a capacity review needs nothing else).
 */
public final class AgentGatewayManagementServer implements SmartLifecycle {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentGatewayManagementServer.class);
    private final AgentGatewayProperties properties;
    private final AgentGatewayServer gateway;
    private final AgentGatewayService service;
    private final AgentGatewayMetrics metrics;
    private volatile HttpServer server;

    public AgentGatewayManagementServer(AgentGatewayProperties properties, AgentGatewayServer gateway,
            AgentGatewayService service, AgentGatewayMetrics metrics) {
        this.properties = properties;
        this.gateway = gateway;
        this.service = service;
        this.metrics = metrics;
    }

    @Override public synchronized void start() {
        if (!properties.isManagementEnabled() || server != null) return;
        String token = properties.getManagementToken();
        if (token == null || token.length() < 16) {
            throw new IllegalStateException("sm.agent.gateway.management-token (at least 16 characters) is required when "
                    + "the management port is enabled; the endpoint is never served unauthenticated");
        }
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(properties.getManagementBindAddress(),
                    properties.getManagementPort() < 0 ? 0 : properties.getManagementPort()), 16);
            created.createContext("/management/health", exchange -> handle(exchange, token, this::health, "application/json"));
            created.createContext("/management/metrics", exchange -> handle(exchange, token, this::prometheus,
                    "text/plain; version=0.0.4; charset=utf-8"));
            created.setExecutor(Executors.newFixedThreadPool(2, task -> {
                Thread thread = new Thread(task, "agent-gateway-management");
                thread.setDaemon(true);
                return thread;
            }));
            created.start();
            server = created;
        } catch (IOException e) {
            throw new IllegalStateException("agent-gateway management listener could not start", e);
        }
    }

    @Override public synchronized void stop() {
        HttpServer stopping = server;
        server = null;
        if (stopping != null) stopping.stop(0);
    }

    @Override public boolean isRunning() { return server != null; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 90; }

    /** The bound port, for tests that ask for an ephemeral one with a negative setting. */
    public int actualPort() { return server == null ? 0 : server.getAddress().getPort(); }

    private interface Body { String render(); }

    private static void handle(HttpExchange exchange, String token, Body body, String contentType) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "method not allowed\n", "Allow", "GET");
                return;
            }
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            boolean authorized = header != null && header.startsWith("Bearer ")
                    && MessageDigest.isEqual(header.substring(7).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
            if (!authorized) {
                respond(exchange, 401, "text/plain", "unauthorized\n", "WWW-Authenticate", "Bearer");
                return;
            }
            respond(exchange, 200, contentType, body.render());
        } catch (RuntimeException e) {
            LOG.warn("agent-gateway management request failed: {}", e.getClass().getName());
            respond(exchange, 500, "text/plain", "error\n");
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body, String... header) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        for (int i = 0; i + 1 < header.length; i += 2) exchange.getResponseHeaders().set(header[i], header[i + 1]);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String health() {
        var health = gateway.health();
        return "{\"status\":\"" + health.getStatus().getCode() + "\",\"gateway\":{\"enabled\":" + properties.isEnabled()
                + ",\"listening\":" + gateway.isRunning() + ",\"draining\":" + service.isDraining()
                + ",\"sessions\":" + service.liveSessions() + ",\"maxSessions\":" + properties.getMaxSessions()
                + (health.getDetails().get("reason") == null ? "" : ",\"reason\":\"" + health.getDetails().get("reason") + "\"")
                + "}}\n";
    }

    private String prometheus() {
        StringBuilder out = new StringBuilder();
        List<Meter> meters = metrics.registry().getMeters().stream()
                .sorted(Comparator.comparing((Meter m) -> m.getId().getName()).thenComparing(m -> m.getId().getTags().toString()))
                .collect(Collectors.toList());
        String last = "";
        for (Meter meter : meters) {
            String name = meter.getId().getName();
            double value;
            String type;
            if (meter instanceof Counter counter) { value = counter.count(); type = "counter"; }
            else if (meter instanceof Gauge gauge) { value = gauge.value(); type = "gauge"; }
            else continue;
            if (!name.equals(last)) { out.append("# TYPE ").append(name).append(' ').append(type).append('\n'); last = name; }
            out.append(name).append(labels(meter.getId().getTags())).append(' ').append(format(value)).append('\n');
        }
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        gauge(out, "jvm_memory_heap_used_bytes", memory.getUsed());
        gauge(out, "jvm_memory_heap_max_bytes", memory.getMax());
        gauge(out, "jvm_threads_live", ManagementFactory.getThreadMXBean().getThreadCount());
        var os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            gauge(out, "process_cpu_usage", Math.max(0, sun.getProcessCpuLoad()));
            gauge(out, "process_cpu_seconds_total", sun.getProcessCpuTime() / 1e9);
        }
        gauge(out, "available_processors", Runtime.getRuntime().availableProcessors());
        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, double value) {
        out.append("# TYPE ").append(name).append(" gauge\n").append(name).append(' ').append(format(value)).append('\n');
    }

    private static String labels(List<Tag> tags) {
        if (tags.isEmpty()) return "";
        return tags.stream().map(t -> t.getKey() + "=\"" + t.getValue().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String format(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
