package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The bean graph under a real context, against a deliberately unreachable broker (port 1) — proving
 * the property that made this composition worth designing: a down broker costs counted drops, never
 * a failed boot and never an exception in a caller (BE-D1, design §9 row 1).
 */
class MeshBridgeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MeshBridgeConfiguration.class)
            .withPropertyValues(
                    "sm.mesh.enabled=true",            // T3.3: off by default; these cases are about the enabled path
                    "sm.mesh.host=ws://127.0.0.1:1",   // nothing listens on port 1 — connect fails fast
                    "sm.mesh.vpn=sm-vpn",
                    "sm.mesh.client-username=sm-backend");

    private static EventEnvelope healthFixture() throws IOException {
        return EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/fixtures").resolve("health-transition.json")));
    }

    @Test
    void aDownBrokerDoesNotFailContextStartupAndPublishesBecomeCountedDrops() {
        runner.run(context -> {
            assertNull(context.getStartupFailure(),
                    "BE-D1: a broker that is down at startup must never block boot");

            EventPublisher p = context.getBean(EventPublisher.class);
            assertInstanceOf(AfterCommitEventPublisher.class, p,
                    "the injected seam must be the after-commit composition, not the raw transport");

            EventEnvelope event = healthFixture();
            assertDoesNotThrow(() -> p.publish(event),
                    "a publish against a dead broker is freshness loss, never a caller failure");
            assertEquals(1L, context.getBean(JcsmpEventPublisher.class).droppedCount(),
                    "the drop must be counted on the one shared counter");
        });
    }

    @Test
    void thePropertiesBindFromSmMeshAndKeepTheirValidation() {
        runner.run(context -> {
            MeshProperties props = context.getBean(MeshProperties.class);
            assertEquals("sm-vpn", props.vpn());
            assertEquals("", props.password(), "an absent password binds to empty, never null");
        });
        new ApplicationContextRunner()
                .withUserConfiguration(MeshBridgeConfiguration.class)
                .withPropertyValues("sm.mesh.enabled=true", "sm.mesh.host=ws://127.0.0.1:1")   // vpn + username missing
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "MeshProperties' own requireText must still refuse an incomplete binding"));
    }

    @Test
    void contextCloseRetiresTheConnectThread() throws InterruptedException {
        runner.run(context -> assertNotNull(context.getBean(JcsmpEventPublisher.class),
                "the bean must exist before the context closes around it"));
        // The runner closed the context on lambda exit — and NOTHING in this test closed the
        // publisher by hand, deliberately: retirement below is therefore proof of the CONTEXT's
        // destroy hook (Spring's AutoCloseable inference on the @Bean), not of a close() the test
        // itself made. An earlier draft called publisher.close() inside the lambda and measurably
        // masked that proof — with @Bean(destroyMethod = "") it stayed green. (Manual-close
        // idempotency is ReconnectingTransportTest's unit-level property, not re-proven here.)
        // The interrupt only lands once any in-flight connect attempt returns, so give it a
        // patience window rather than racing it.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (connectThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(!connectThreadAlive(),
                "context shutdown must retire the background connect thread — a survivor here leaks "
                        + "one thread per context restart");
    }

    /**
     * The injection surface is EXACTLY ONE publisher. {@code @Primary} alone only settles
     * single-bean injection; a List/ObjectProvider injection point still saw both beans until the
     * raw bean was marked {@code defaultCandidate = false} — and a service injecting the list
     * would double-publish, one copy through the raw transport BEFORE commit. The probe is a real
     * injection point, not {@code getBeansOfType()}: getBeansOfType ignores candidate status and
     * would count two beans no matter what.
     */
    @Test
    void collectionInjectionSeesExactlyTheComposedPublisher() {
        runner.withUserConfiguration(PublisherListProbe.class).run(context -> {
            assertNull(context.getStartupFailure());
            PublisherListProbe probe = context.getBean(PublisherListProbe.class);
            assertEquals(1, probe.publishers.size(),
                    "a List<EventPublisher> injection point must see exactly one publisher — "
                            + "two means a future fan-out service double-publishes, one copy "
                            + "through the raw transport before commit");
            assertInstanceOf(AfterCommitEventPublisher.class, probe.publishers.get(0),
                    "and the one it sees must be the after-commit composition");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class PublisherListProbe {
        final List<EventPublisher> publishers;

        PublisherListProbe(List<EventPublisher> publishers) {
            this.publishers = publishers;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Plan 010 T3.3: the bridge is now imported by the production data plane, which must boot with NO
    // broker configured. Disabled is a declared degraded state, never a refusal to boot; a PARTIAL
    // enabled configuration still refuses (fail closed on a typo, not silently dropped).
    // ------------------------------------------------------------------------------------------------

    @Test
    void disabledNeedsNoBrokerPropertiesAndDropsAndCounts() throws IOException {
        // No sm.mesh.* at all: this is every existing launcher IT, and a fresh install.
        new ApplicationContextRunner().withUserConfiguration(MeshBridgeConfiguration.class).run(context -> {
            assertNull(context.getStartupFailure(), "no sm.mesh.* must never block boot");
            assertEquals(false, context.getBean(MeshProperties.class).enabled(), "default is disabled");

            EventPublisher p = context.getBean(EventPublisher.class);
            assertInstanceOf(AfterCommitEventPublisher.class, p);
            EventEnvelope event = healthFixture();
            assertDoesNotThrow(() -> p.publish(event));
            assertDoesNotThrow(() -> p.publish(event));
            assertEquals(2L, context.getBean(JcsmpEventPublisher.class).droppedCount(),
                    "every disabled publish is a counted drop, reachable by the raw bean's concrete type");
        });
        // The explicit spelling behaves the same.
        new ApplicationContextRunner().withUserConfiguration(MeshBridgeConfiguration.class)
                .withPropertyValues("sm.mesh.enabled=false").run(context -> assertNull(context.getStartupFailure()));
    }

    @Test
    void disabledLogsExactlyOneWarnAtBootAndNoneForDrops() throws IOException {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MeshBridgeConfiguration.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            EventEnvelope event = healthFixture();
            new ApplicationContextRunner().withUserConfiguration(MeshBridgeConfiguration.class).run(context -> {
                for (int i = 0; i < 3; i++) {
                    context.getBean(EventPublisher.class).publish(event);
                }
            });
        } finally {
            logger.detachAppender(appender);
        }
        java.util.List<String> warns = appender.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertEquals(List.of("mesh publishing disabled: sm.mesh.enabled=false"), warns,
                "one WARN at boot, naming the property; per-publish drops must not repeat it");
    }

    @Test
    void enabledWithAMissingHostRefusesToBoot() {
        new ApplicationContextRunner().withUserConfiguration(MeshBridgeConfiguration.class)
                .withPropertyValues("sm.mesh.enabled=true", "sm.mesh.vpn=sm-vpn", "sm.mesh.client-username=sm-backend")
                .run(context -> {
                    assertNotNull(context.getStartupFailure(), "enabled + no host is a typo, not a degraded state");
                    assertTrue(rootMessage(context.getStartupFailure()).contains("sm.mesh.host must be set"),
                            rootMessage(context.getStartupFailure()));
                });
        new ApplicationContextRunner().withUserConfiguration(MeshBridgeConfiguration.class)
                .withPropertyValues("sm.mesh.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure(), "enabled with nothing at all refuses too"));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    private static boolean connectThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && "sm-mesh-connect".equals(t.getName()));
    }
}
