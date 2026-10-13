package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.events.CatalogEventEmitter;
import com.solace.sm.cp.events.CpTransactionBoundary;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T3.3: the first production publisher wiring, measured in the real {@code SolutionManagerApplication} -
 * the production bean graph, a real PostgreSQL, the real catalog service - with {@code sm.mesh.enabled=true}.
 *
 * <p>Two halves, split by what they need:
 * <ul>
 *   <li><b>Without a broker</b> (always runs): the mesh is pointed at an unreachable port, so a broker that is down
 *       must cost counted drops and never a failed boot or a failed load. Each event the catalog offers after its
 *       commit therefore shows up on the ONE shared raw transport's drop counter, which makes "exactly one event
 *       per stored package, none for a re-load or a refusal" countable with no broker.</li>
 *   <li><b>With a broker</b> ({@code -Dbroker.host=...}, the G1 spike stack on its SMF-WebSocket port, as the dp-core
 *       broker ITs): the event is received on the contract topic. Without the property it is SKIPPED with its
 *       reason - never silently green. This test never contacts any broker but the one named.</li>
 * </ul>
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CatalogEventIT {

    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final String LOADER = "catalog-event-it-loader";
    private static final int SMF_WEBSOCKET_PORT = 8008;
    private static final String VPN = "sm-spike";
    private static final String INTERNAL_USER = "sm-internal";
    private static final String INTERNAL_PASS = "sm-internal";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static UUID loaderId;

    @Autowired
    private ApplicationContext context;
    @Autowired
    private SolutionCatalog catalog;
    /**
     * A REAL collection injection point, not {@code getBeanProvider().stream()} (which ignores candidate status and
     * would count three): this is the shape a future fan-out service has.
     */
    @Autowired
    private List<EventPublisher> unqualifiedPublishers;

    @DynamicPropertySource
    static void provisionAndEnableTheMesh(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        TwoSchemaTestDatabase db = provisioned.db();
        ownerJdbc = db.ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);

        // Enabled, and pointed at the broker the caller named - or at nothing (port 1), where publishes are drops.
        String broker = System.getProperty("broker.host");
        registry.add("sm.mesh.enabled", () -> "true");
        registry.add("sm.mesh.host", () -> broker == null || broker.isBlank()
                ? "ws://127.0.0.1:1" : "ws://" + broker + ":" + SMF_WEBSOCKET_PORT);
        registry.add("sm.mesh.vpn", () -> VPN);
        registry.add("sm.mesh.client-username", () -> INTERNAL_USER);
        registry.add("sm.mesh.password", () -> INTERNAL_PASS);

        loaderId = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash,"
                + " must_rotate) VALUES (:id, :o, 'local', :u, :h, false)", new MapSqlParameterSource()
                .addValue("id", loaderId).addValue("o", ORGANIZATION).addValue("u", LOADER)
                .addValue("h", new CredentialHasher().hash("catalog-event-it-credential-0001".toCharArray())));
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", provisioned.properties().get("sm.control.runtime.password"),
                ORGANIZATION, "cache", 3, LOADER);
    }

    // ------------------------------------------------------------------------------------ the bean graph

    @Test
    @Order(1)
    void theProductionBootHasExactlyOneRawBrokerTransportAndTheCpWrapperIsItsOwn() {
        assertEquals(1, context.getBeansOfType(JcsmpEventPublisher.class).size(),
                "one broker session and one drop counter, shared by both planes");
        Object raw = context.getBean("meshEventPublisher");

        AfterCommitEventPublisher cp = (AfterCommitEventPublisher) context.getBean("cpEventPublisher");
        AfterCommitEventPublisher dp = (AfterCommitEventPublisher) context.getBean("eventPublisher");

        assertSame(raw, cp.delegate(), "the control plane wraps the ONE shared raw transport");
        assertSame(raw, dp.delegate(), "...and so does the data plane");
        assertInstanceOf(CpTransactionBoundary.class, boundaryOf(cp), "the CP wrapper commits on the CP's transaction");
        assertEquals(false, boundaryOf(cp).getClass().equals(boundaryOf(dp).getClass()),
                "two planes, two boundaries - never one shared instance");
        assertNotNull(context.getBean(CatalogEventEmitter.class));
    }

    @Test
    @Order(2)
    void anUnqualifiedEventPublisherCollectionSeesOnlyTheDataPlanesPrimaryComposition() {
        List<EventPublisher> seen = unqualifiedPublishers;

        assertEquals(1, seen.size(), "the CP wrapper and the raw transport are not default candidates: a fan-out "
                + "service injecting List<EventPublisher> must not double-publish");
        assertSame(context.getBean("eventPublisher"), seen.get(0));
    }

    // ------------------------------------------------------------------------------- no broker needed

    @Test
    @Order(3)
    @DisabledIfSystemProperty(named = "broker.host", matches = ".+",
            disabledReason = "counts drops against an UNREACHABLE broker; with -Dbroker.host the broker test below runs")
    void eachStoredPackageOffersExactlyOneEventAfterCommitAndAReloadOrARefusalOffersNone() {
        JcsmpEventPublisher raw = context.getBean(JcsmpEventPublisher.class);
        long before = raw.droppedCount();

        catalog.load(new PrincipalRef(loaderId), origin(), ORGANIZATION, cacheVersion(1));
        long afterFirst = raw.droppedCount();
        catalog.load(new PrincipalRef(loaderId), origin(), ORGANIZATION, cacheVersion(1));    // identical re-load
        long afterReload = raw.droppedCount();
        catalog.load(new PrincipalRef(loaderId), origin(), ORGANIZATION, cacheVersion(2));
        long afterSecond = raw.droppedCount();

        assertEquals(before + 1, afterFirst, "one stored package, one event, reaching the raw transport post-commit");
        assertEquals(afterFirst, afterReload, "a re-load stores nothing, so it owes no notice");
        assertEquals(afterReload + 1, afterSecond);
    }

    // ----------------------------------------------------------------------------------- a local broker

    @Test
    @Order(4)
    @Tag("broker")
    @EnabledIfSystemProperty(named = "broker.host", matches = ".+",
            disabledReason = "needs the G1 spike broker: run with -Dbroker.host=localhost")
    void aPackageLoadInTheProductionBootPublishesOneCatalogPackageEventToALocalBroker() throws Exception {
        String host = "ws://" + System.getProperty("broker.host") + ":" + SMF_WEBSOCKET_PORT;
        String subscription = "sm/v1/" + ORGANIZATION + "/notify/catalog/package";
        BlockingQueue<String[]> received = new ArrayBlockingQueue<>(8);
        JCSMPSession consumerSession = openSession(host);
        try {
            XMLMessageConsumer consumer = consumerSession.getMessageConsumer(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage message) {
                    String body = message instanceof TextMessage text ? text.getText()
                            : new String(message.getBytes(), StandardCharsets.UTF_8);
                    received.offer(new String[] {message.getDestination().getName(), body});
                }

                @Override
                public void onException(JCSMPException e) {
                    System.err.println("consumer error: " + e);
                }
            });
            consumerSession.addSubscription(JCSMPFactory.onlyInstance().createTopic(subscription), true);
            consumer.start();

            catalog.load(new PrincipalRef(loaderId), origin(), ORGANIZATION, cacheVersion(3));

            String[] got = received.poll(15, TimeUnit.SECONDS);
            assertNotNull(got, "no catalog.package arrived on " + subscription + " within 15s");
            assertEquals(subscription, got[0]);
            JsonNode body = EventJson.mapper().readTree(got[1]);
            assertEquals("catalog.package", body.get("event").asText());
            assertEquals("cp-core", body.get("producer").asText());
            assertEquals(ORGANIZATION.toString(), body.get("tenant").asText());
            assertEquals("cache", body.at("/payload/solution").asText());
            assertTrue(received.isEmpty(), "exactly one event for one stored package");
        } finally {
            consumerSession.closeSession();
        }
    }

    // ---------------------------------------------------------------------------------------- helpers

    private static Object boundaryOf(AfterCommitEventPublisher wrapper) {
        try {
            var field = AfterCommitEventPublisher.class.getDeclaredField("boundary");
            field.setAccessible(true);
            return field.get(wrapper);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.90", UUID.randomUUID());
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

    private static String cacheVersion(int version) {
        try {
            var doc = (com.fasterxml.jackson.databind.node.ObjectNode) readFixture("cache-2.4.package.json");
            doc.set("packageVersion", MAPPER.createArrayNode().add(version));
            doc.put("appliesTo", ">=" + version + ".0 <" + (version + 1) + ".0");
            return doc.toString();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private static JsonNode readFixture(String name) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/validation/fixtures/" + name);
            if (Files.isRegularFile(candidate)) {
                try {
                    return MAPPER.readTree(Files.readString(candidate));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixture " + name + " not found from " + Path.of("").toAbsolutePath());
    }
}
