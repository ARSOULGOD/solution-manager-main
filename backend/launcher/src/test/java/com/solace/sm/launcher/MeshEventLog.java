package com.solace.sm.launcher;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.SessionEvent;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * A subscriber on the LOCAL broker that remembers every message it is delivered (plan 010 T6.6): the receive side of the
 * walkthrough's "{@code instance.apply-outcome} was received on the local broker" assertion. It keeps the topic and the
 * body exactly as delivered, so a test compares the wire against {@code EventJson} and {@code NotifyTopics} rather than
 * against its own idea of them.
 *
 * <p>It is a plain recorder: no assertion lives here, and a lost session is a recorded fact ({@link #sessionLost()}),
 * not an exception, because the resilience scenarios take the broker down on purpose.
 */
final class MeshEventLog implements AutoCloseable {

    /** One delivered message. */
    record Received(String topic, String body, Instant at) {
    }

    private final JCSMPSession session;
    private final List<Received> received = new ArrayList<>();
    private final AtomicBoolean sessionLost;

    private MeshEventLog(JCSMPSession session, AtomicBoolean sessionLost) {
        this.session = session;
        this.sessionLost = sessionLost;
    }

    /** Connects as {@code username} and subscribes to {@code subscription}; the broker must be up. */
    static MeshEventLog open(String host, String vpn, String username, String password, String subscription)
            throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST, host);
        props.setProperty(JCSMPProperties.VPN_NAME, vpn);
        props.setProperty(JCSMPProperties.USERNAME, username);
        props.setProperty(JCSMPProperties.PASSWORD, password);
        AtomicBoolean lost = new AtomicBoolean();
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props, null, args -> {
            if (args.getEvent() == SessionEvent.DOWN_ERROR || args.getEvent() == SessionEvent.RECONNECTING) {
                lost.set(true);
            }
        });
        session.connect();
        MeshEventLog log = new MeshEventLog(session, lost);
        XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage message) {
                String body = message instanceof TextMessage text ? text.getText()
                        : new String(message.getBytes(), StandardCharsets.UTF_8);
                synchronized (log.received) {
                    log.received.add(new Received(message.getDestination().getName(), body, Instant.now()));
                }
            }

            @Override
            public void onException(JCSMPException e) {
                log.sessionLost.set(true);
            }
        });
        session.addSubscription(JCSMPFactory.onlyInstance().createTopic(subscription), true);
        consumer.start();
        return log;
    }

    /** A copy of everything delivered so far, oldest first. */
    List<Received> snapshot() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    /** Everything delivered so far whose topic matches. */
    List<Received> matching(Predicate<Received> filter) {
        return snapshot().stream().filter(filter).toList();
    }

    /** The first delivered message matching {@code filter}, waiting up to {@code timeout}; {@code null} if none. */
    Received awaitOne(Predicate<Received> filter, Duration timeout) throws InterruptedException {
        List<Received> found = awaitCount(filter, 1, timeout);
        return found.isEmpty() ? null : found.get(0);
    }

    /** At least {@code count} delivered messages matching {@code filter}, waiting up to {@code timeout}. */
    List<Received> awaitCount(Predicate<Received> filter, int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<Received> found = matching(filter);
        while (found.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(50);
            found = matching(filter);
        }
        return found;
    }

    /** Whether the broker told this subscriber its session is gone. */
    boolean sessionLost() {
        return sessionLost.get();
    }

    @Override
    public void close() {
        try {
            session.closeSession();
        } catch (RuntimeException e) {
            // closing a session whose broker is already gone must not mask a test's own failure
        }
    }
}
