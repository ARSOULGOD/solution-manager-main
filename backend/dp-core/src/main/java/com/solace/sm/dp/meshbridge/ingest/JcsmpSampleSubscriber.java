package com.solace.sm.dp.meshbridge.ingest;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.SessionEvent;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JCSMP implementation of {@link SampleSubscriber} (plan 011 P11-5.1; OR-107 OQ-3 (a)): DIRECT delivery of the events the
 * managed integrations publish on the CUSTOMER's broker, received on a session of the Data Plane's own and handed out as bytes
 * and a topic. It publishes nothing, ever. The only place in the Data Plane that receives from a broker; it sits in
 * {@code meshbridge} beside the publisher because the SDK may not leave it ({@code onlyMeshbridgeTouchesJcsmp}).
 *
 * <p><b>Facts read from the SDK, not recalled ({@code I2}; sol-jcsmp 10.30.1 on the classpath, MF14).</b>
 * <ul>
 *   <li>{@code JCSMPSession.getMessageConsumer(XMLMessageListener)} returns the consumer, and {@code start()} begins delivery:
 *       the listener's {@code onReceive(BytesXMLMessage)} runs on the SDK's own receive thread, so the sink must not block.</li>
 *   <li>{@code addSubscription(Topic, true)} waits for the broker's confirmation. The SDK keeps the subscriptions of the session
 *       but applies them again after a reconnect ONLY with {@code REAPPLY_SUBSCRIPTIONS} (measured in
 *       {@code JcsmpSampleSubscriberIT}: without it a reconnected session is open and silent; with it an outage is a gap and
 *       the subscriptions are back with no call from here).</li>
 *   <li>{@code JCSMPChannelProperties}: {@code reconnectRetries = -1} retries a lost session forever, as the publisher's
 *       transport does; {@code connectRetries} stays at its default 0 so the FIRST connect fails fast and the manager retries
 *       it on the next tick (a long connect would park the control thread for nothing).</li>
 * </ul>
 *
 * <p><b>The credential limit:</b> the SDK's property map takes the password as a {@code String}, so a copy outlives the
 * caller's zeroised buffer inside the SDK for the life of the session (the same limit {@code SecretMaterial} states for sshj).
 * It is never logged here and this class's {@code toString} names nothing.
 *
 * <p>A text message is delivered as its UTF-8 bytes, any other as the bytes of its payload. Nothing of the body is read, parsed
 * or logged here.
 */
public final class JcsmpSampleSubscriber implements SampleSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(JcsmpSampleSubscriber.class);

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int RECONNECT_WAIT_MILLIS = 3_000;

    private final JCSMPSession session;
    private final XMLMessageConsumer consumer;

    private JcsmpSampleSubscriber(JCSMPSession session, XMLMessageConsumer consumer) {
        this.session = session;
        this.consumer = consumer;
    }

    /** Opens, connects and starts delivering; @throws BrokerSessionException a fixed sentence (the SDK's text is dropped) */
    public static JcsmpSampleSubscriber open(BrokerEndpoint endpoint, char[] credential,
            Consumer<RawBrokerMessage> sink, Clock clock) throws BrokerSessionException {
        Objects.requireNonNull(sink, "sink");
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST, endpoint.uri());
        props.setProperty(JCSMPProperties.VPN_NAME, endpoint.vpn());
        props.setProperty(JCSMPProperties.USERNAME, endpoint.clientUsername());
        props.setProperty(JCSMPProperties.PASSWORD, new String(credential));
        // MEASURED on the local broker (JcsmpSampleSubscriberIT): with the SDK default, a session that reconnects after a broker
        // restart is open again and delivers NOTHING, because its subscriptions were not applied again. REAPPLY_SUBSCRIPTIONS
        // makes the SDK re-send every subscription it holds on reconnect, so an outage is a gap and not a silent end. The two
        // IGNORE_* properties make a repeated subscribe, or the removal of one that is gone, a no-op instead of a broker refusal.
        props.setBooleanProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        props.setBooleanProperty(JCSMPProperties.IGNORE_DUPLICATE_SUBSCRIPTION_ERROR, true);
        props.setBooleanProperty(JCSMPProperties.IGNORE_SUBSCRIPTION_NOT_FOUND_ERROR, true);
        JCSMPChannelProperties channel = (JCSMPChannelProperties) props.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
        channel.setReconnectRetries(-1);
        channel.setReconnectRetryWaitInMillis(RECONNECT_WAIT_MILLIS);
        channel.setConnectTimeoutInMillis(CONNECT_TIMEOUT_MILLIS);
        JCSMPSession session = null;
        try {
            session = JCSMPFactory.onlyInstance().createSession(props, null, args -> {
                SessionEvent event = args.getEvent();
                if (event == SessionEvent.RECONNECTING || event == SessionEvent.RECONNECTED || event == SessionEvent.DOWN_ERROR) {
                    LOG.info("push session event: {}", event);
                }
            });
            session.connect();
            XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    try {
                        sink.accept(new RawBrokerMessage(message.getDestination().getName(), body(message), clock.instant()));
                    } catch (RuntimeException e) {
                        // nothing a sink throws may stop the receive thread; a classifier only, never the message
                        LOG.warn("push receive: a message could not be handed on ({})", e.getClass().getSimpleName());
                    }
                }

                @Override
                public void onException(JCSMPException e) {
                    LOG.warn("push receive: the consumer reported {}", e.getClass().getSimpleName());
                }
            });
            consumer.start();
            return new JcsmpSampleSubscriber(session, consumer);
        } catch (JCSMPException | RuntimeException e) {
            if (session != null) {
                try {
                    session.closeSession();
                } catch (RuntimeException ignored) {
                    // the session never connected
                }
            }
            throw new BrokerSessionException("the broker session could not be opened");
        }
    }

    private static byte[] body(BytesXMLMessage message) {
        if (message instanceof TextMessage text) {
            String content = text.getText();
            return content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        }
        byte[] bytes = message.getBytes();
        return bytes == null ? new byte[0] : bytes;
    }

    @Override
    public void subscribe(String topicPattern) throws BrokerSessionException {
        try {
            session.addSubscription(JCSMPFactory.onlyInstance().createTopic(topicPattern), true);
        } catch (JCSMPException | RuntimeException e) {
            throw new BrokerSessionException("the broker did not accept a subscription");
        }
    }

    @Override
    public void unsubscribe(String topicPattern) throws BrokerSessionException {
        try {
            session.removeSubscription(JCSMPFactory.onlyInstance().createTopic(topicPattern), true);
        } catch (JCSMPException | RuntimeException e) {
            throw new BrokerSessionException("the broker did not accept a removal of a subscription");
        }
    }

    @Override
    public boolean isOpen() {
        return !session.isClosed();
    }

    @Override
    public void close() {
        try {
            consumer.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
        try {
            session.closeSession();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    @Override
    public String toString() {
        return "JcsmpSampleSubscriber[" + (isOpen() ? "open" : "closed") + "]";
    }
}
