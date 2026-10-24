package com.solace.sm.agw;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sm.agent.gateway")
public class AgentGatewayProperties {
    private boolean enabled;
    private String bindAddress = "0.0.0.0";
    private int port = 9443;
    private String certificateFile = "";
    private String privateKeyFile = "";
    private String trustBundleFile = "";
    private int readinessPort;
    private Duration pollInterval = Duration.ofSeconds(1);
    private int pollBatchSize = 32;
    private int initialTelemetryCredits = 8;
    private int maxMessageBytes = 16 * 1024 * 1024;
    /** How often live sessions are re-checked against the control plane (revocation, permission changes). */
    private Duration revocationCheckInterval = Duration.ofSeconds(5);
    /** Planned drain (AGW-D18): the longest the gateway waits for in-flight work before it closes sessions. */
    private Duration drainWindow = Duration.ofSeconds(30);
    /** Planned drain: the earliest an agent is told to come back. */
    private Duration drainReconnectMin = Duration.ofSeconds(2);
    /** Planned drain: reconnect_after is staggered evenly across [min, min + spread] so agents do not return as one wave. */
    private Duration drainReconnectSpread = Duration.ofSeconds(30);
    /** Planned drain: after sessions are closed, how long the transport gets to finish before it is cut. */
    private Duration shutdownGrace = Duration.ofSeconds(5);
    /** Threads that run the full poll of sessions with work to deliver. */
    private int pollThreads = 4;
    /** Threads that run gRPC calls (each blocks on the database). Bounded: an unbounded executor turns overload into a thread explosion.
     * Sized for blocking I/O, not CPU count: a CPU-derived default gave 8 threads in a 2-CPU container and queued every call behind
     * telemetry batches (measured p95 heartbeat 836 ms against 3 ms). The datasource pool is the real concurrency bound. */
    private int threads = 32;
    /** A Connect stream that has not sent a usable SessionOpen (or, for an expired lease, its LeaseRenew) in this long is closed. */
    private Duration sessionOpenTimeout = Duration.ofSeconds(15);
    /** Management listener (authenticated health + Prometheus metrics). 0 disables it; a negative value binds an ephemeral port (tests). */
    private int managementPort;
    private String managementBindAddress = "127.0.0.1";
    private String managementToken = "";
    /** Admission: live sessions this replica will hold. A value is a bound, set from the measured capacity (see the capacity evidence). */
    private int maxSessions = 2000;
    /** Admission: sustained session claims per second (token bucket). The claim is a database transaction. */
    private double admissionRatePerSecond = 100;
    /** Admission: claims allowed in a burst above the sustained rate. */
    private int admissionBurst = 200;
    /** Admission: the least time between two claims by one agent; faster is a duplicate identity or a fault. */
    private Duration claimMinInterval = Duration.ofSeconds(1);
    /** A telemetry stream held at zero credits is re-granted its window after this long (see AgentGatewayService). */
    private Duration telemetryCreditRecoveryInterval = Duration.ofSeconds(5);
    /**
     * How long an unfinished command waits before the session that already heard about it is told again. It must
     * be well above the poll interval; it is what recovers a lost acknowledgement inside a live session.
     */
    private Duration noticeRetryInterval = Duration.ofSeconds(15);
    /**
     * Log requests (pages and exports) in flight to one agent at once; more are answered with a BUSY page, never queued. The agent
     * allows 4 by default and answers more with BUSY itself (plan 016 spec 4.2 note 5), so keep this at or below the agent's bound.
     */
    private int logMaxInFlightPerAgent = 4;
    /**
     * How long a config view request (plan 017) waits for the agent's reply, counted from the moment it is on the wire. An agent that
     * predates the request never answers it, so this is also how long such an agent's view takes to come back UNAVAILABLE.
     */
    private Duration configViewDeadline = Duration.ofSeconds(10);

    public Duration getConfigViewDeadline() { return configViewDeadline; }
    public void setConfigViewDeadline(Duration configViewDeadline) { this.configViewDeadline = configViewDeadline; }

    public int getLogMaxInFlightPerAgent() { return logMaxInFlightPerAgent; }
    public void setLogMaxInFlightPerAgent(int logMaxInFlightPerAgent) { this.logMaxInFlightPerAgent = logMaxInFlightPerAgent; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBindAddress() { return bindAddress; }
    public void setBindAddress(String bindAddress) { this.bindAddress = bindAddress; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getCertificateFile() { return certificateFile; }
    public void setCertificateFile(String certificateFile) { this.certificateFile = certificateFile; }
    public String getPrivateKeyFile() { return privateKeyFile; }
    public void setPrivateKeyFile(String privateKeyFile) { this.privateKeyFile = privateKeyFile; }
    public String getTrustBundleFile() { return trustBundleFile; }
    public void setTrustBundleFile(String trustBundleFile) { this.trustBundleFile = trustBundleFile; }
    public int getReadinessPort() { return readinessPort; }
    public void setReadinessPort(int readinessPort) { this.readinessPort = readinessPort; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public int getPollBatchSize() { return pollBatchSize; }
    public void setPollBatchSize(int pollBatchSize) { this.pollBatchSize = pollBatchSize; }
    public int getInitialTelemetryCredits() { return initialTelemetryCredits; }
    public void setInitialTelemetryCredits(int initialTelemetryCredits) { this.initialTelemetryCredits = initialTelemetryCredits; }
    public int getMaxMessageBytes() { return maxMessageBytes; }
    public void setMaxMessageBytes(int maxMessageBytes) { this.maxMessageBytes = maxMessageBytes; }

    public Duration getRevocationCheckInterval() { return revocationCheckInterval; }
    public void setRevocationCheckInterval(Duration value) { this.revocationCheckInterval = value; }
    public Duration getDrainWindow() { return drainWindow; }
    public void setDrainWindow(Duration value) { this.drainWindow = value; }
    public Duration getDrainReconnectMin() { return drainReconnectMin; }
    public void setDrainReconnectMin(Duration value) { this.drainReconnectMin = value; }
    public Duration getDrainReconnectSpread() { return drainReconnectSpread; }
    public void setDrainReconnectSpread(Duration value) { this.drainReconnectSpread = value; }
    public Duration getNoticeRetryInterval() { return noticeRetryInterval; }
    public void setNoticeRetryInterval(Duration value) { this.noticeRetryInterval = value; }
    public Duration getTelemetryCreditRecoveryInterval() { return telemetryCreditRecoveryInterval; }
    public void setTelemetryCreditRecoveryInterval(Duration value) { this.telemetryCreditRecoveryInterval = value; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int value) { this.maxSessions = value; }
    public double getAdmissionRatePerSecond() { return admissionRatePerSecond; }
    public void setAdmissionRatePerSecond(double value) { this.admissionRatePerSecond = value; }
    public int getAdmissionBurst() { return admissionBurst; }
    public void setAdmissionBurst(int value) { this.admissionBurst = value; }
    public Duration getClaimMinInterval() { return claimMinInterval; }
    public void setClaimMinInterval(Duration value) { this.claimMinInterval = value; }
    public int getManagementPort() { return managementPort; }
    public void setManagementPort(int value) { this.managementPort = value; }
    public boolean isManagementEnabled() { return managementPort != 0; }
    public String getManagementBindAddress() { return managementBindAddress; }
    public void setManagementBindAddress(String value) { this.managementBindAddress = value; }
    public String getManagementToken() { return managementToken; }
    public void setManagementToken(String value) { this.managementToken = value; }
    public Duration getSessionOpenTimeout() { return sessionOpenTimeout; }
    public void setSessionOpenTimeout(Duration value) { this.sessionOpenTimeout = value; }
    public int getPollThreads() { return pollThreads; }
    public void setPollThreads(int value) { this.pollThreads = value; }
    public int getThreads() { return threads; }
    public void setThreads(int value) { this.threads = value; }
    public Duration getShutdownGrace() { return shutdownGrace; }
    public void setShutdownGrace(Duration value) { this.shutdownGrace = value; }

    public void validate() {
        requirePositive(revocationCheckInterval, "revocation-check-interval");
        requirePositive(drainWindow, "drain-window");
        requirePositive(shutdownGrace, "shutdown-grace");
        if (pollThreads < 1 || pollThreads > 64) throw new IllegalArgumentException("sm.agent.gateway.poll-threads must be 1..64");
        if (threads < 2 || threads > 512) throw new IllegalArgumentException("sm.agent.gateway.threads must be 2..512");
        requirePositive(sessionOpenTimeout, "session-open-timeout");
        if (maxSessions < 1) throw new IllegalArgumentException("sm.agent.gateway.max-sessions must be at least 1");
        if (admissionRatePerSecond <= 0) throw new IllegalArgumentException("sm.agent.gateway.admission-rate-per-second must be positive");
        if (admissionBurst < 1) throw new IllegalArgumentException("sm.agent.gateway.admission-burst must be at least 1");
        if (claimMinInterval == null || claimMinInterval.isNegative()) {
            throw new IllegalArgumentException("sm.agent.gateway.claim-min-interval must not be negative");
        }
        requirePositive(noticeRetryInterval, "notice-retry-interval");
        requirePositive(telemetryCreditRecoveryInterval, "telemetry-credit-recovery-interval");
        if (drainReconnectMin == null || drainReconnectMin.isNegative()) {
            throw new IllegalArgumentException("sm.agent.gateway.drain-reconnect-min must not be negative");
        }
        if (drainReconnectSpread == null || drainReconnectSpread.isNegative()) {
            throw new IllegalArgumentException("sm.agent.gateway.drain-reconnect-spread must not be negative");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("sm.agent.gateway.port is invalid");
        if (readinessPort < 0 || readinessPort > 65535) {
            throw new IllegalArgumentException("sm.agent.gateway.readiness-port is invalid");
        }
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("sm.agent.gateway.poll-interval must be positive");
        }
        if (noticeRetryInterval.compareTo(pollInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("sm.agent.gateway.notice-retry-interval must be at least twice poll-interval");
        }
        if (pollBatchSize < 1 || pollBatchSize > 1000) throw new IllegalArgumentException("poll-batch-size is invalid");
        if (initialTelemetryCredits < 0) throw new IllegalArgumentException("initial-telemetry-credits is invalid");
        if (logMaxInFlightPerAgent < 1 || logMaxInFlightPerAgent > 64) throw new IllegalArgumentException("log-max-in-flight-per-agent is invalid");
        if (configViewDeadline == null || configViewDeadline.compareTo(Duration.ofMillis(100)) < 0
                || configViewDeadline.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("sm.agent.gateway.config-view-deadline must be between 100ms and 5m");
        }
        if (maxMessageBytes < 1024) throw new IllegalArgumentException("max-message-bytes is invalid");
        if (certificateFile.isBlank() || privateKeyFile.isBlank() || trustBundleFile.isBlank()) {
            throw new IllegalArgumentException("TLS certificate, key and trust bundle are required when the gateway is enabled");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("sm.agent.gateway." + name + " must be positive");
        }
    }
}
