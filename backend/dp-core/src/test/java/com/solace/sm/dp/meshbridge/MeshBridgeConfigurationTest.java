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
                .withPropertyValues("sm.mesh.host=ws://127.0.0.1:1")   // vpn + username missing
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

    private static boolean connectThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && "sm-mesh-connect".equals(t.getName()));
    }
}
