package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.4, in the PRODUCTION boot: the data plane's engine services are wired to the configured
 * {@code ApplyEventEmitter} (not the silent fallback), and a real submission that lands publishes its
 * {@code instance.apply-outcome} through the data plane's after-commit wrapper to the one raw transport. The mesh is
 * in its declared degraded state here (no {@code sm.mesh.*}), so the observable at the bottom is the transport's
 * counted drop - one apply-outcome for one landing, beside the config-state notice T6.5 adds when the ladder moves.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
class ApplyEventsProductionBootIT {

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    @Test
    void aLandedSubmissionPublishesExactlyOneApplyOutcomeThroughTheProductionWiring() throws Exception {
        assertNotNull(context.getBean(ApplyEventEmitter.class), "the production boot carries the configured emitter");
        Client http = new Client(port);
        Declared declared = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);
        long generation = ApplyItSupport.stage(http, declared.instance(), declared.version(), "orders");
        JcsmpEventPublisher raw = context.getBean(JcsmpEventPublisher.class);
        long before = raw.droppedCount();
        // The raw transport's counter cannot tell notices apart, and a landing now offers two (the apply-outcome and,
        // when the ladder's rung moved, the config-state notice: T6.5). The disabled mesh logs each drop with its
        // topic at DEBUG, so the topic is what is counted.
        Logger publisherLog = (Logger) LoggerFactory.getLogger(JcsmpEventPublisher.class);
        Level previousLevel = publisherLog.getLevel();
        ListAppender<ILoggingEvent> drops = new ListAppender<>();
        drops.start();
        publisherLog.setLevel(Level.DEBUG);
        publisherLog.addAppender(drops);
        try {
            Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
            ObjectNode submit = DeclareFixtures.MAPPER.createObjectNode();
            submit.put("generation", generation);
            submit.put("strategy", "manual");
            submit.put("placementId", declared.placement().toString());
            HttpResponse<String> accepted = http.post("/api/dp/instances/" + declared.instance() + "/apply", operator,
                    submit);
            assertEquals(202, accepted.statusCode(), accepted.body());
            String applyId = DeclareFixtures.MAPPER.readTree(accepted.body()).get("applyId").asText();
            JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, declared.instance(), applyId,
                    Duration.ofSeconds(20));
            assertEquals("STAGED", landed.get("state").asText(), landed.toString());

            // The publish follows the landing's commit by microseconds; the poll above can win that race, so wait.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (raw.droppedCount() < before + 2 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            Thread.sleep(300);
            List<String> topics = drops.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1L, topics.stream().filter(t -> t.contains("/apply-outcome/" + applyId)).count(),
                    "one landing = exactly one apply-outcome offered to the transport (counted, the mesh is disabled): "
                            + topics);
            assertEquals(1L, topics.stream().filter(t -> t.contains("/config-state")).count(),
                    "the landing moved the ladder's rung once, so exactly one config-state notice (T6.5): " + topics);
            assertEquals(2L, raw.droppedCount() - before, "and nothing else was offered by the landing: " + topics);
        } finally {
            publisherLog.detachAppender(drops);
            publisherLog.setLevel(previousLevel);
        }
    }
}
