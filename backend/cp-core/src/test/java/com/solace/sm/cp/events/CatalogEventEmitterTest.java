package com.solace.sm.cp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyEventShapes;
import com.solace.sm.events.NotifyTopics;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 010 T3.3 / plan 007 Task 5a: {@code catalog.package} is the CONTROL plane's event, published after the
 * CONTROL plane's commit. No broker and no database: a stand-in raw transport that does exactly what every real
 * implementation must (derive the topic, validate the shape, then hand bytes over), and two fake resource
 * transaction managers standing in for the two planes.
 */
class CatalogEventEmitterTest {

    private static final UUID ORGANIZATION = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    private final CapturingTransport transport = new CapturingTransport();
    private final List<EventEnvelope> offered = new ArrayList<>();

    /** The shared raw transport: loud guards first, then bytes - the contract every publisher implements. */
    private final EventPublisher raw = event -> {
        String topic = NotifyTopics.topicFor(event);
        NotifyEventShapes.validate(event);
        offered.add(event);
        transport.accept(topic, EventJson.write(event));
    };

    private final FakeResourceTransactionManager cp = new FakeResourceTransactionManager();
    private final FakeResourceTransactionManager dp = new FakeResourceTransactionManager();
    private final EventPublisher cpPublisher = new AfterCommitEventPublisher(raw, new CpTransactionBoundary(cp));
    private final CatalogEventEmitter emitter = new CatalogEventEmitter(cpPublisher, CLOCK, UUID::randomUUID);

    private void emit() {
        emitter.onPackageLoaded(new TenantId(ORGANIZATION), "cache", "[1]", 7L);
    }

    @Test
    void aCatalogPackageEventCarriesProducerCpCore() {
        emit();

        assertThat(offered).hasSize(1);
        assertThat(offered.get(0).producer()).isEqualTo("cp-core");
        assertThat(offered.get(0).event()).isEqualTo("catalog.package");
        assertThat(offered.get(0).stateVersion()).isEqualTo(7L);
        assertThat(offered.get(0).occurredAtUtc()).isEqualTo(CLOCK.instant());
    }

    @Test
    void itDerivesTheCatalogTopicAndCarriesNeitherInstanceNorPlacementIdentity() throws Exception {
        emit();

        assertThat(transport.sent().getKey()).isEqualTo("sm/v1/" + ORGANIZATION + "/notify/catalog/package");
        JsonNode body = EventJson.mapper().readTree(transport.sent().getValue());
        assertThat(body.at("/entity/kind").asText()).isEqualTo("solution");
        assertThat(body.at("/entity/solution").asText()).isEqualTo("cache");
        assertThat(body.at("/entity").has("instanceId")).isFalse();
        assertThat(body.at("/entity").has("placementId")).isFalse();
        assertThat(body.at("/payload/packageVersion").asText()).isEqualTo("[1]");
        assertThat(body.at("/payload").size()).as("identifiers only (I6)").isEqualTo(2);
    }

    @Test
    void nothingIsPublishedUntilTheCpTransactionCommits() {
        new TransactionTemplate(cp).executeWithoutResult(status -> {
            emit();
            assertThat(offered).as("deferred while the control-plane transaction is open").isEmpty();
        });

        assertThat(offered).as("published once the control-plane transaction committed").hasSize(1);
    }

    @Test
    void aRolledBackPackageLoadPublishesNothing() {
        new TransactionTemplate(cp).executeWithoutResult(status -> {
            emit();
            status.setRollbackOnly();
        });

        assertThat(offered).isEmpty();
        assertThat(transport.sent()).isNull();
    }

    @Test
    void aDataPlaneTransactionIsNotAControlPlaneCommitAndItsRollbackDoesNotDiscardOurs() {
        // Inside a DATA PLANE transaction only: there is no control-plane commit to order against, so the CP
        // publishes at once rather than holding the notice for a commit that is not its own.
        new TransactionTemplate(dp).executeWithoutResult(status -> {
            emit();
            assertThat(offered).as("not deferred to the data plane's commit").hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(offered).as("the data plane's rollback cannot unsend a control-plane notice").hasSize(1);
    }

    @Test
    void aControlPlaneCommitFlushesOnlyControlPlaneEventsAndTheDataPlaneCommitDoesNot() {
        // Sequential, as every two-plane composition runs: CP commits, then DP. The CP event leaves at the CP commit.
        new TransactionTemplate(cp).executeWithoutResult(status -> emit());
        assertThat(offered).hasSize(1);
        new TransactionTemplate(dp).executeWithoutResult(status -> {
        });
        assertThat(offered).as("the data plane's commit flushed nothing of ours, twice").hasSize(1);
    }

    @Test
    void theCpWrapperIsBoundToTheCpsOwnTransactionContext() throws Exception {
        CatalogEventsConfiguration configuration = new CatalogEventsConfiguration();
        Method factory = CatalogEventsConfiguration.class.getDeclaredMethod("cpEventPublisher", EventPublisher.class,
                org.springframework.transaction.support.ResourceTransactionManager.class);
        factory.setAccessible(true);

        Object wrapper = factory.invoke(configuration, raw, cp);

        assertThat(wrapper).isInstanceOf(AfterCommitEventPublisher.class);
        assertThat(wrapper).extracting("boundary").isInstanceOf(CpTransactionBoundary.class);
        assertThat(((AfterCommitEventPublisher) wrapper).delegate()).as("over the ONE shared raw transport")
                .isSameAs(raw);
    }

    @Test
    void anEmitterOverTheWrongProducerIsRefusedByTheEnvelope() {
        assertThatThrownBy(() -> new EventEnvelope(UUID.randomUUID(), CLOCK.instant(), "go-agent",
                ORGANIZATION.toString(), new com.solace.sm.events.EntityRef("solution", null, null, "cache"), 1,
                "catalog.package", java.util.Map.of("solution", "cache", "packageVersion", "[1]")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theEmitterHoldsNoBrokerSessionAndNoDataPlaneType() {
        List<Class<?>> held = new ArrayList<>();
        for (var field : CatalogEventEmitter.class.getDeclaredFields()) {
            held.add(field.getType());
        }
        for (var constructor : CatalogEventEmitter.class.getDeclaredConstructors()) {
            held.addAll(List.of(constructor.getParameterTypes()));
        }
        assertThat(held).allSatisfy(type -> assertThat(type.getName())
                .doesNotStartWith("com.solacesystems").doesNotStartWith("com.solace.sm.dp")
                .doesNotContain("Jcsmp"));
        assertThat(held).contains(EventPublisher.class);
    }
}
