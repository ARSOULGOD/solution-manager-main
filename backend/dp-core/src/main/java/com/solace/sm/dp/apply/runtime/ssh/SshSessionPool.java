package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One reused, bounded, kept-alive SSH session per TARGET, for the health poller (plan 010 T7.1, {@code OR-39}). Plan
 * 011's metrics collector shares it under a per-target reserve ({@code OR-72}) through {@link HostMetricExec}
 * ({@link #curl}, {@link #inspect}); nothing here builds that reserve.
 *
 * <h2>What is pooled, and what is not</h2>
 * <ul>
 *   <li><b>Per target, not per instance or placement.</b> The key is {@code (tenant, host, port, user)}: every
 *       placement on a target probes down the same connection.</li>
 *   <li><b>The apply path never borrows.</b> {@code SshjDockerTransport} keeps its connection per operation (T6.1), so
 *       an apply never queues behind a probe, and a probe holding a slot can never delay a restart.
 *       {@code SshSessionPoolTest} proves no apply-path class names this one.</li>
 *   <li><b>A session is reusable only for the record it was opened from.</b> The target's connection record is re-read
 *       for every poll (OR-28), and the placement the caller passes carries the credential reference and the pinned
 *       host key. A session opened with a different credential or a different pin is closed and replaced, so a
 *       rotated credential is used by the next session and a session verified against an old pin is never trusted
 *       past a change to it.</li>
 * </ul>
 *
 * <h2>Bounds</h2>
 * {@code maxSessions} (cap, with least-recently-used idle close), {@code idleTimeout} ({@link #evictIdle}),
 * {@code maxConcurrentProbes} (the bounded probe pool across all targets) and {@code maxProbesPerSession}. A probe that
 * cannot get a slot within {@code acquireTimeout}, or a new session that would exceed the cap with no idle one to
 * close, is refused {@code ssh.pool.busy}: a load signal, never an observation about the host.
 *
 * <h2>Keep-alive and reconnect</h2>
 * The connector enables transport keep-alive; a failed keep-alive kills the transport, which {@link
 * HostConnection#isAlive()} then reports. A dead session is replaced <b>before</b> the probe is sent. A reused session
 * that dies under the probe ({@link #CONNECTION_LOST}) is replaced and the probe repeated exactly once; a fresh
 * session that fails is not retried (nothing stale to blame), so there is no reconnect loop.
 *
 * <p>No thread of its own: idle close is driven by the poller calling {@link #evictIdle()} after each round.
 */
public final class SshSessionPool implements HostProbe, HostMetricExec, AutoCloseable {

    /** The classifier a {@link HostConnection} throws when its transport failed under a call. */
    public static final String CONNECTION_LOST = "ssh.connection.lost";

    /** The classifier of a command that did not answer by its deadline (W2-03); never a reason to reconnect and repeat. */
    public static final String TIMEOUT = "timeout";

    /**
     * W2-03: how long past its own bound a command may take before the connection gives up on it. The host-side bound
     * ({@code curl --max-time}, {@code timeout --kill-after=2 n}) fires first on a healthy transport; this is the margin
     * for the kill grace plus one second of round trip.
     */
    static final Duration COMMAND_GRACE = Duration.ofSeconds(3);

    /** W2-03: how long after the connection's own deadline the pool stops waiting for it and frees the slot. */
    static final Duration BACKSTOP = Duration.ofSeconds(2);

    static final ErrorClass BUSY = new ErrorClass("ssh.pool.busy");
    static final ErrorClass CLOSED = new ErrorClass("ssh.pool.closed");

    /** A new session's connect and authentication are bounded by the transport's own timeouts; this is a backstop. */
    private static final long CONNECT_WAIT_SECONDS = 30;

    private record PoolKey(TenantId tenant, String host, int port, String user) {
    }

    /** What a session was opened FROM; a change to either makes it unusable. */
    private record Identity(UUID credentialRef, String pinnedHostKeyFingerprint) {
    }

    private static final class Entry {
        final PoolKey key;
        final Identity identity;
        final CompletableFuture<HostConnection> connection = new CompletableFuture<>();
        final Semaphore slots;
        int leases;
        Instant lastUsed;
        boolean retired;

        Entry(PoolKey key, Identity identity, int slots, Instant now) {
            this.key = key;
            this.identity = identity;
            this.slots = new Semaphore(slots);
            this.lastUsed = now;
        }

        boolean opened() {
            return connection.isDone() && !connection.isCompletedExceptionally();
        }
    }

    private final HostConnector connector;
    private final SshSessionPoolProperties properties;
    private final Clock clock;
    private final Semaphore probeSlots;
    private final Duration commandGrace;
    private final Duration backstop;
    /** W2-03: runs the commands, so a call that never returns can be abandoned and its slot freed. */
    private final java.util.concurrent.ExecutorService workers;
    private final Map<PoolKey, Entry> entries = new HashMap<>();
    private boolean closed;

    /**
     * Production: sshj connections authenticated from the secret store, with transport keep-alive, each connecting only
     * to an address {@code addressPolicy} accepted for the stored host (plan 013 T1.2).
     */
    public SshSessionPool(SshCredentialSource credentials, SshSessionPoolProperties properties,
            TargetAddressPolicy addressPolicy) {
        this(credentials, properties, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    /** As above, and a pinned key's type discovered on a connection is reported to {@code recorder} (OR-125 (2)). */
    public SshSessionPool(SshCredentialSource credentials, SshSessionPoolProperties properties,
            TargetAddressPolicy addressPolicy, HostKeyTypeRecorder recorder) {
        this(new SshjHostConnector(credentials, properties.keepAliveInterval(), addressPolicy, recorder), properties,
                Clock.systemUTC());
    }

    SshSessionPool(HostConnector connector, SshSessionPoolProperties properties, Clock clock) {
        this(connector, properties, clock, COMMAND_GRACE, BACKSTOP);
    }

    SshSessionPool(HostConnector connector, SshSessionPoolProperties properties, Clock clock, Duration commandGrace,
            Duration backstop) {
        this.commandGrace = Objects.requireNonNull(commandGrace, "commandGrace");
        this.backstop = Objects.requireNonNull(backstop, "backstop");
        java.util.concurrent.atomic.AtomicInteger threads = new java.util.concurrent.atomic.AtomicInteger();
        this.workers = java.util.concurrent.Executors.newCachedThreadPool(task -> {
            Thread t = new Thread(task, "sm-ssh-command-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.connector = Objects.requireNonNull(connector, "connector");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.probeSlots = new Semaphore(properties.maxConcurrentProbes());
    }

    @Override
    public ProbeResult probe(SshDockerPlacement placement, int port, String path, Duration timeout) {
        // Validated first, exactly as the per-operation transport does: a refused request opens no session.
        String command = SshjDockerTransport.probeCommand(port, path, timeout);
        Objects.requireNonNull(placement, "placement");
        Duration deadline = timeout.plus(commandGrace);
        return onPool(placement, deadline, connection -> connection.probe(command, deadline));
    }

    /**
     * Plan 011's metrics {@code curl} (P11-4.2b): the same session, the same bounded probe slots and the same
     * reconnect-once policy as a health probe. The command is a {@link HostMetricProbe.Curl}, which only
     * {@link HostMetricProbe} can build, so no caller-supplied string reaches the host. The per-target reservation
     * that keeps health from being starved by metrics is the collector's (P11-4.3), not this pool's.
     */
    @Override
    public ProbeResult curl(SshDockerPlacement placement, HostMetricProbe.Curl command) {
        Objects.requireNonNull(placement, "placement");
        String fixed = Objects.requireNonNull(command, "command").command();
        Duration deadline = command.timeout().plus(commandGrace);
        return onPool(placement, deadline, connection -> connection.probe(fixed, deadline));
    }

    /** Plan 011's fixed {@code docker inspect} (P11-4.2b); see {@link #curl}. */
    @Override
    public InspectResult inspect(SshDockerPlacement placement, HostMetricProbe.Inspect command) {
        Objects.requireNonNull(placement, "placement");
        String fixed = Objects.requireNonNull(command, "command").command();
        Duration deadline = command.timeout().plus(commandGrace);
        return onPool(placement, deadline, connection -> connection.inspect(fixed, deadline));
    }

    /** One fixed command on the target's pooled session, inside one of the bounded probe slots. */
    private <T> T onPool(SshDockerPlacement placement, Duration deadline,
            java.util.function.Function<HostConnection, T> operation) {
        acquire(probeSlots);
        try {
            return runOnPool(placement, deadline, operation);
        } finally {
            probeSlots.release();
        }
    }

    private <T> T runOnPool(SshDockerPlacement placement, Duration deadline,
            java.util.function.Function<HostConnection, T> operation) {
        Lease first = lease(placement);
        try {
            return first.run(operation, deadline);
        } catch (RuntimeAdapterException e) {
            if (!CONNECTION_LOST.equals(e.errorClass().token())) {
                throw e;
            }
            first.discard();
            if (first.fresh) {
                throw e;
            }
        } finally {
            first.release();
        }
        // A reused session died under the command: one reconnect, one repeat, and no more.
        Lease again = lease(placement);
        try {
            return again.run(operation, deadline);
        } catch (RuntimeAdapterException e) {
            if (CONNECTION_LOST.equals(e.errorClass().token())) {
                again.discard();
            }
            throw e;
        } finally {
            again.release();
        }
    }

    /** Number of sessions currently held (including one being opened). */
    public synchronized int openSessions() {
        return entries.size();
    }

    /** Closes every session idle for longer than {@code idleTimeout}. Called by the poller after each round. */
    public void evictIdle() {
        List<HostConnection> toClose = new ArrayList<>();
        synchronized (this) {
            Instant cutoff = clock.instant().minus(properties.idleTimeout());
            entries.values().removeIf(e -> {
                if (e.leases == 0 && e.opened() && e.lastUsed.isBefore(cutoff)) {
                    e.retired = true;
                    toClose.add(e.connection.join());
                    return true;
                }
                return false;
            });
        }
        toClose.forEach(HostConnection::close);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        List<HostConnection> toClose = new ArrayList<>();
        synchronized (this) {
            closed = true;
            for (Entry e : entries.values()) {
                e.retired = true;
                if (e.opened()) {
                    toClose.add(e.connection.join());
                }
            }
            entries.clear();
        }
        toClose.forEach(HostConnection::close);
    }

    // ---- leasing ------------------------------------------------------------------------------------------

    private Lease lease(SshDockerPlacement placement) {
        PoolKey key = new PoolKey(placement.tenant(), placement.host(), placement.port(), placement.user());
        Identity identity = new Identity(placement.credentialRef(), placement.pinnedHostKeyFingerprint());
        List<HostConnection> toClose = new ArrayList<>();
        Entry entry;
        boolean opener = false;
        synchronized (this) {
            if (closed) {
                throw new RuntimeAdapterException(CLOSED);
            }
            Entry existing = entries.get(key);
            if (existing != null && (!existing.identity.equals(identity) || isDead(existing))) {
                entries.remove(key);
                retire(existing, toClose);
                existing = null;
            }
            if (existing == null) {
                makeRoom(toClose);
                existing = new Entry(key, identity, properties.maxProbesPerSession(), clock.instant());
                entries.put(key, existing);
                opener = true;
            }
            entry = existing;
            entry.leases++;
            entry.lastUsed = clock.instant();
        }
        toClose.forEach(HostConnection::close);
        if (opener) {
            try {
                entry.connection.complete(connector.connect(placement));
            } catch (RuntimeException | Error e) {
                synchronized (this) {
                    entries.remove(key, entry);
                    entry.retired = true;
                }
                entry.connection.completeExceptionally(e);
                release(entry);
                throw e;
            }
        }
        HostConnection connection = await(entry);
        try {
            acquire(entry.slots);
        } catch (RuntimeException e) {
            release(entry);
            throw e;
        }
        return new Lease(entry, connection, opener);
    }

    /** A dead session: the connection exists and says it is no longer alive. A session still opening is not dead. */
    private static boolean isDead(Entry e) {
        return e.opened() && !e.connection.join().isAlive();
    }

    /** Under the lock: close the least recently used IDLE session if the cap is reached; refuse if none is idle. */
    private void makeRoom(List<HostConnection> toClose) {
        if (entries.size() < properties.maxSessions()) {
            return;
        }
        Entry victim = null;
        for (Entry e : entries.values()) {
            if (e.leases == 0 && e.opened() && (victim == null || e.lastUsed.isBefore(victim.lastUsed))) {
                victim = e;
            }
        }
        if (victim == null) {
            throw new RuntimeAdapterException(BUSY);
        }
        entries.remove(victim.key);
        retire(victim, toClose);
    }

    /** Under the lock. An entry with probes still in flight is closed by the last lease to be released. */
    private static void retire(Entry e, List<HostConnection> toClose) {
        e.retired = true;
        if (e.leases == 0 && e.opened()) {
            toClose.add(e.connection.join());
        }
    }

    private HostConnection await(Entry e) {
        try {
            return e.connection.get(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            release(e);
            throw new RuntimeAdapterException(BUSY);
        } catch (ExecutionException ex) {
            release(e);
            if (ex.getCause() instanceof RuntimeAdapterException r) {
                throw r;
            }
            throw new RuntimeAdapterException(new ErrorClass("ssh.connection.failed"));
        } catch (TimeoutException ex) {
            release(e);
            throw new RuntimeAdapterException(BUSY);
        }
    }

    private void release(Entry e) {
        HostConnection toClose = null;
        synchronized (this) {
            e.leases--;
            e.lastUsed = clock.instant();
            if (e.retired && e.leases <= 0 && e.opened()) {
                toClose = e.connection.join();
            }
        }
        if (toClose != null) {
            toClose.close();
        }
    }

    private void acquire(Semaphore semaphore) {
        try {
            if (!semaphore.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new RuntimeAdapterException(BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeAdapterException(BUSY);
        }
    }

    /** One probe's hold on a session: a per-session slot, and one count on the session's leases. */
    private final class Lease {
        private final Entry entry;
        private final HostConnection connection;
        final boolean fresh;
        private boolean released;

        Lease(Entry entry, HostConnection connection, boolean fresh) {
            this.entry = entry;
            this.connection = connection;
            this.fresh = fresh;
        }

        /**
         * Runs the command on a worker and waits at most {@code deadline} plus the backstop (W2-03). The connection
         * bounds its own call at {@code deadline}; this is for the call that does not (a transport that stopped
         * answering, a stream with no read timeout): the slot is freed, the connection is closed so the stuck worker
         * unblocks, and the caller is told {@link #TIMEOUT}. Never reconnect-and-repeat: a command that timed out is
         * not evidence of a stale session.
         */
        <T> T run(java.util.function.Function<HostConnection, T> operation, Duration deadline) {
            java.util.concurrent.Future<T> call;
            try {
                call = workers.submit(() -> operation.apply(connection));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                throw new RuntimeAdapterException(CLOSED);
            }
            try {
                return call.get(deadline.plus(backstop).toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                call.cancel(true);
                discard();
                throw new RuntimeAdapterException(new ErrorClass(TIMEOUT));
            } catch (InterruptedException e) {
                call.cancel(true);
                Thread.currentThread().interrupt();
                throw new RuntimeAdapterException(BUSY);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException r) {
                    throw r;
                }
                if (cause instanceof Error err) {
                    throw err;
                }
                throw new RuntimeAdapterException(new ErrorClass(CONNECTION_LOST));
            }
        }

        /** The session failed under a call: take it out of the pool so no other probe is sent down it. */
        void discard() {
            synchronized (SshSessionPool.this) {
                entries.remove(entry.key, entry);
                entry.retired = true;
            }
            connection.close();
        }

        void release() {
            if (released) {
                return;
            }
            released = true;
            entry.slots.release();
            SshSessionPool.this.release(entry);
        }
    }
}
