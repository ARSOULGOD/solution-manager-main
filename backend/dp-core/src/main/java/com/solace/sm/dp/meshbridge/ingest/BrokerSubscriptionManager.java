package com.solace.sm.dp.meshbridge.ingest;

import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds one broker session per connection (per organization AND connection id, W3-15) and keeps its subscriptions equal to what the plan says (plan 011 P11-5.1;
 * OR-107 OQ-3 (a), OQ-5 payload form): one session per (organization, environment), one subscription per DISTINCT pattern, never
 * one per instance.
 *
 * <p><b>It never holds the caller.</b> The metrics loop calls {@link #reconcile} on its own thread every tick. A broker that does
 * not answer parks the SDK's connect and subscribe calls (they wait out a reconnect), so every call is made on ONE control
 * thread of this class's own: {@code reconcile} records the desired state and returns, and at most one apply is scheduled per
 * connection at a time. A request that arrives while an apply is running is simply picked up by the next tick (ten seconds).
 *
 * <p><b>The credential</b> is resolved from the secret store for {@code BROKER_CREDENTIAL}, in memory, for the one connect, and
 * the manager's copy is zeroised the moment the factory returns. A failure to resolve, to connect or to subscribe is a counted
 * log line with a classifier and the connection id, never the host, the VPN, the username or the SDK's message, and is retried on
 * the next tick: a down broker costs a gap in the pushed series, never a failed boot or a stopped collector.
 */
public final class BrokerSubscriptionManager implements BrokerSubscriptions, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerSubscriptionManager.class);

    private record Desired(UUID tenant, BrokerEndpoint endpoint, UUID credentialRef, Set<String> patterns,
            BrokerMessageSink sink) {
    }

    /** What the control thread holds for one connection. */
    private record Held(UUID tenant, BrokerEndpoint endpoint, UUID credentialRef, SampleSubscriber subscriber,
            Set<String> patterns) {
    }

    private final SecretResolver secrets;
    private final SampleSubscriberFactory factory;
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sm-push-control");
        t.setDaemon(true);
        return t;
    });
    private final Map<Key, Desired> desired = new ConcurrentHashMap<>();
    private final Set<Key> scheduled = ConcurrentHashMap.newKeySet();
    private final Map<Key, Held> held = new ConcurrentHashMap<>();
    private final Set<Key> failing = ConcurrentHashMap.newKeySet();

    public BrokerSubscriptionManager(SecretResolver secrets, SampleSubscriberFactory factory) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public void reconcile(UUID tenantId, UUID connectionId, BrokerEndpoint endpoint, UUID credentialRef,
            Set<String> patterns, BrokerMessageSink sink) {
        if (patterns.isEmpty()) {
            release(tenantId, connectionId);
            return;
        }
        Key key = new Key(tenantId, connectionId);
        desired.put(key, new Desired(tenantId, endpoint, credentialRef, Set.copyOf(patterns), sink));
        schedule(key);
    }

    @Override
    public void release(UUID tenantId, UUID connectionId) {
        Key key = new Key(tenantId, connectionId);
        desired.remove(key);
        schedule(key);
    }

    @Override
    public Set<Key> held() {
        return Set.copyOf(held.keySet());
    }

    /** Waits until everything handed to the control thread so far has been applied; false if it is still busy (tests). */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Future<?> barrier;
        try {
            barrier = control.submit(() -> { });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return true;
        }
        try {
            barrier.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return true;
        }
    }

    private void schedule(Key connectionId) {
        if (!scheduled.add(connectionId)) {
            return;   // an apply for this connection is already queued or running; it reads the latest desired state
        }
        try {
            control.execute(() -> {
                scheduled.remove(connectionId);   // from here a new request schedules a fresh apply
                try {
                    apply(connectionId, desired.get(connectionId));
                } catch (RuntimeException e) {
                    LOG.warn("push control: connection {} could not be reconciled ({})", connectionId.connectionId(),
                            e.getClass().getSimpleName());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            scheduled.remove(connectionId);   // closed
        }
    }

    // ---- the control thread only ------------------------------------------------------------------------------------

    private void apply(Key connectionId, Desired want) {
        Held have = held.get(connectionId);
        if (want == null) {
            if (have != null) {
                held.remove(connectionId);
                close(have.subscriber());
            }
            failing.remove(connectionId);
            return;
        }
        if (have != null && (!have.endpoint().equals(want.endpoint()) || !have.credentialRef().equals(want.credentialRef())
                || !have.tenant().equals(want.tenant()) || !have.subscriber().isOpen())) {
            held.remove(connectionId);
            close(have.subscriber());
            have = null;
        }
        if (have == null) {
            have = open(connectionId, want);
            if (have == null) {
                return;
            }
        }
        try {
            Set<String> add = new HashSet<>(want.patterns());
            add.removeAll(have.patterns());
            Set<String> remove = new HashSet<>(have.patterns());
            remove.removeAll(want.patterns());
            for (String pattern : remove) {
                have.subscriber().unsubscribe(pattern);
                have.patterns().remove(pattern);
            }
            for (String pattern : add) {
                have.subscriber().subscribe(pattern);
                have.patterns().add(pattern);
            }
            failing.remove(connectionId);
        } catch (RuntimeException e) {
            // a session that cannot take a subscription is closed, so the next tick starts from a clean one
            held.remove(connectionId);
            close(have.subscriber());
            noteFailure(connectionId, e);
        }
    }

    private Held open(Key connectionId, Desired want) {
        char[] password = null;
        try (SecretMaterial material = secrets.resolve(want.tenant(), want.credentialRef(), SecretPurpose.BROKER_CREDENTIAL)) {
            password = material.chars();
            UUID tenant = want.tenant();
            BrokerMessageSink sink = want.sink();
            SampleSubscriber subscriber = factory.open(want.endpoint(), password,
                    message -> sink.accept(tenant, connectionId.connectionId(), message));
            Held opened = new Held(tenant, want.endpoint(), want.credentialRef(), subscriber, new HashSet<>());
            held.put(connectionId, opened);
            return opened;
        } catch (RuntimeException e) {
            noteFailure(connectionId, e);
            return null;
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    /** The first failure of a run is a WARN, the repeats DEBUG: a broker that stays away must not fill the log every ten seconds. */
    private void noteFailure(Key connectionId, RuntimeException e) {
        String what = e.getClass().getSimpleName();
        if (failing.add(connectionId)) {
            LOG.warn("push control: connection {} is not subscribed ({}); it is retried every tick", connectionId.connectionId(), what);
        } else {
            LOG.debug("push control: connection {} is still not subscribed ({})", connectionId.connectionId(), what);
        }
    }

    private static void close(SampleSubscriber subscriber) {
        try {
            subscriber.close();
        } catch (RuntimeException e) {
            // closing a session whose broker is already gone is not a failure
        }
    }

    @Override
    public void close() {
        control.shutdownNow();
        held.values().forEach(h -> close(h.subscriber()));
        held.clear();
        desired.clear();
    }
}
