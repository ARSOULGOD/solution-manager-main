package com.solace.sm.agw;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import solace.sm.agentgateway.v1draft.AgentGatewayGrpc;

/** Lifecycle wrapper. TLS material is mandatory when the listener is enabled. */
public final class AgentGatewayServer implements SmartLifecycle, HealthIndicator {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentGatewayServer.class);
    private final AgentGatewayProperties properties;
    private final AgentGatewayService service;
    private final ScheduledExecutorService scheduler;
    private volatile Server server;
    private volatile java.util.concurrent.ThreadPoolExecutor grpcExecutor;
    private volatile ServerSocket readinessSocket;
    private volatile Thread readinessThread;
    private volatile boolean running;
    private volatile String lastFailure;

    public AgentGatewayServer(AgentGatewayProperties properties, AgentGatewayService service,
            ScheduledExecutorService scheduler) {
        this.properties = properties;
        this.service = service;
        this.scheduler = scheduler;
    }

    @Override public synchronized void start() {
        if (!properties.isEnabled() || running) return;
        try {
            properties.validate();
            var ssl = GrpcSslContexts.configure(SslContextBuilder.forServer(
                    new File(properties.getCertificateFile()), new File(properties.getPrivateKeyFile())))
                    .trustManager(new File(properties.getTrustBundleFile())).clientAuth(ClientAuth.OPTIONAL).build();
            server = NettyServerBuilder.forAddress(new InetSocketAddress(properties.getBindAddress(), properties.getPort()))
                    .sslContext(ssl).maxInboundMessageSize(properties.getMaxMessageBytes())
                    // One connection carries Enroll, Connect and Telemetry; more concurrent calls than that is a client holding streams open.
                    .maxConcurrentCallsPerConnection(8)
                    .executor(grpcExecutor = boundedExecutor(properties.getThreads()))
                    .addService(service).intercept(new AgentGatewayInterceptor()).build().start();
            running = true;
            service.startMaintenance();
            openReadinessSocket();
            lastFailure = null;
        } catch (Exception e) {
            running = false;
            lastFailure = e instanceof IllegalArgumentException
                    ? "configuration_invalid" : "tls_listener_start_failed";
            closeReadinessSocket();
            if (server != null) server.shutdownNow();
            throw new IllegalStateException("agent-gateway TLS listener could not start", e);
        }
    }

    /**
     * Planned stop (AGW-D18): close the readiness socket first so a balancer stops sending new connections, drain
     * every live session (each agent finishes its in-flight work and answers), then shut the transport down. The
     * enclosing Spring context must allow the drain window plus the shutdown grace
     * ({@code spring.lifecycle.timeout-per-shutdown-phase}) or it will close the datasource under the drain.
     */
    /**
     * gRPC's default server executor is an unbounded cached pool; every call here blocks on the database, so under overload it
     * grew to well over a thousand threads and made things worse (measured: 1276 live threads, p95 telemetry ack of several seconds
     * for minutes after a restart). A fixed pool bounds the concurrency; the queue is bounded in practice by the credit windows and by
     * each client's own send rate.
     */
    private static java.util.concurrent.ThreadPoolExecutor boundedExecutor(int threads) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        var pool = new java.util.concurrent.ThreadPoolExecutor(threads, threads, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "agent-gateway-grpc-" + n.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override public synchronized void stop() {
        if (!running) return;
        running = false;
        closeReadinessSocket();
        try {
            AgentGatewayService.DrainSummary summary = service.drain();
            LOG.info("agent-gateway drained: sessions={} answered={} forced={}", summary.sessions(),
                    summary.answered(), summary.forced());
        } catch (RuntimeException e) {
            LOG.warn("agent-gateway drain failed: {}", e.getClass().getName());
        } finally {
            service.stopMaintenance();
            Server transport = server;
            java.util.concurrent.ThreadPoolExecutor executor = grpcExecutor;
            if (transport != null) {
                transport.shutdown();
                try {
                    if (!transport.awaitTermination(properties.getShutdownGrace().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        transport.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    transport.shutdownNow();
                }
            }
            if (executor != null) executor.shutdownNow();
        }
    }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }

    @Override public Health health() {
        if (!properties.isEnabled()) return Health.up().withDetail("enabled", false).build();
        if (running) return Health.up().withDetail("enabled", true).build();
        return Health.down().withDetail("enabled", true)
                .withDetail("reason", lastFailure == null ? "not_started" : lastFailure).build();
    }

    private void openReadinessSocket() throws IOException {
        if (properties.getReadinessPort() == 0) return;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(properties.getBindAddress(), properties.getReadinessPort()));
        readinessSocket = socket;
        readinessThread = new Thread(() -> acceptReadiness(socket), "agent-gateway-readiness");
        readinessThread.setDaemon(true);
        readinessThread.start();
    }

    private void acceptReadiness(ServerSocket socket) {
        while (running && !socket.isClosed()) {
            try (Socket ignored = socket.accept()) {
                // A successful TCP accept is the complete readiness response. No protocol is exposed.
            } catch (IOException e) {
                if (running) break;
            }
        }
    }

    private void closeReadinessSocket() {
        ServerSocket socket = readinessSocket;
        readinessSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        Thread thread = readinessThread;
        readinessThread = null;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }
}
