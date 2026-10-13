package com.solace.sm.dp.apply.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.meshbridge.MeshProperties;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyTopics;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The apply-side notices on a real local broker (plan 010 T6.4): the production composition - emitter over
 * {@link AfterCommitEventPublisher} + {@link SpringTransactionBoundary} over the real {@link JcsmpEventPublisher} -
 * publishes inside a (datastore-less, genuinely Spring-managed) transaction, and a second session subscribed to the
 * tenant's notify tree receives each event on the DERIVED topic with the exact bytes {@code EventJson} writes.
 *
 * <p>The database half - the engine's real landings, with real stamps - is {@code ApplyEventsLandingIT}; the bottom
 * transport is the only thing that differs, which is why this one needs no PostgreSQL.
 *
 * <p><b>Gated</b> on {@code -Dbroker.host=localhost}, reported as skipped with its reason otherwise (never silently
 * absent). It uses the G1 spike stack exactly as {@code JcsmpEventPublisherIT} does:
 * <pre>
 * docker compose -f backend/spikes/mesh-authz/docker-compose.yml up -d solace
 * mvn -o -f backend/pom.xml -pl dp-core -am verify -Dit.test=ApplyOutcomePublishIT -Dtest=NoSuchUnitTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dbroker.host=localhost
 * </pre>
 */
@Tag("broker")
@EnabledIfSystemProperty(named = "broker.host", matches = ".+",
        disabledReason = "needs the G1 spike broker: run with -Dbroker.host=localhost")
class ApplyOutcomePublishIT {

    private static final int SMF_WEBSOCKET_PORT = 8008;
    private static final String VPN = "sm-spike";
    /** Spike-local throwaway credential, committed in provision.sh. Never a production secret. */
    private static final String INTERNAL_USER = "sm-internal";
    private static final String INTERNAL_PASS = "sm-internal";

    private record Delivered(String topic, String body) {
    }

    /** A real {@link AbstractPlatformTransactionManager} with no resource: the synchronization is Spring's own. */
    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Test
    void applyOutcomeIsObservedOnALocalBrokerWithTheDerivedTopic() throws Exception {
        String host = "ws://" + System.getProperty("broker.host") + ":" + SMF_WEBSOCKET_PORT;
        TenantId tenant = new TenantId(UUID.randomUUID());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        PlacementId placement = new PlacementId(UUID.randomUUID());
        ApplyRequestRow landed = new ApplyRequestRow(ApplyId.next(), new ScopeKey(tenant, instance, placement),
                Operation.APPLY, 7L, Strategy.ROLLING_HEALTH_GATED, null, "operator-1",
                Instant.parse("2026-10-05T09:59:00Z"), RequestState.APPLIED, Instant.parse("2026-10-05T09:59:30Z"),
                1234L);

        List<EventEnvelope> published = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<Delivered> received = new ArrayBlockingQueue<>(16);
        JCSMPSession consumerSession = openSession(host);
        try (JcsmpEventPublisher transport =
                new JcsmpEventPublisher(new MeshProperties(host, VPN, INTERNAL_USER, INTERNAL_PASS))) {
            // Tee: remember what the emitter built so the assertion compares broker bytes against the SAME envelope.
            ApplyEventEmitter emitter = new ApplyEventEmitter(
                    new AfterCommitEventPublisher(event -> {
                        published.add(event);
                        transport.publish(event);
                    }, new SpringTransactionBoundary()),
                    Clock.systemUTC(), UUID::randomUUID);

            XMLMessageConsumer consumer = consumerSession.getMessageConsumer(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    String body = message instanceof TextMessage text
                            ? text.getText()
                            : new String(message.getBytes(), StandardCharsets.UTF_8);
                    received.offer(new Delivered(message.getDestination().getName(), body));
                }

                @Override
                public void onException(JCSMPException e) {
                    System.err.println("consumer error: " + e);
                }
            });
            consumerSession.addSubscription(JCSMPFactory.onlyInstance()
                    .createTopic("sm/v1/" + tenant.value() + "/notify/instance/>"), true);
            consumer.start();

            new TransactionTemplate(new NoResourceTransactionManager()).executeWithoutResult(status -> {
                emitter.onRollout(landed, new ReplicaRef("cache-1"), RolloutStep.STAGED);
                emitter.onApplyOutcome(landed);
                emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 1235L);
            });

            assertEquals(3, published.size(), "three events were published after the commit");
            for (EventEnvelope sent : published) {
                Delivered got = received.poll(15, TimeUnit.SECONDS);
                assertNotNull(got, "no message arrived for " + sent.event() + " within 15s");
                assertEquals(NotifyTopics.topicFor(sent), got.topic(), sent.event() + " arrives on its derived topic");
                assertEquals(EventJson.write(sent), got.body(), sent.event() + ": the bytes on the wire are EventJson's");
            }
            EventEnvelope outcome = published.stream().filter(e -> e.event().equals("instance.apply-outcome"))
                    .findFirst().orElseThrow();
            assertTrue(NotifyTopics.topicFor(outcome).endsWith("/apply-outcome/" + landed.applyId().value()),
                    "the apply-outcome topic ends in the applyId");
        } finally {
            consumerSession.closeSession();
        }
    }

    private static JCSMPSession openSession(String host) throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST, host);
        props.setProperty(JCSMPProperties.VPN_NAME, VPN);
        props.setProperty(JCSMPProperties.USERNAME, INTERNAL_USER);
        props.setProperty(JCSMPProperties.PASSWORD, INTERNAL_PASS);
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props);
        session.connect();
        return session;
    }
}
