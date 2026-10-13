package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.metrics.push.PushAttribution.Attributed;
import com.solace.sm.dp.metrics.push.PushAttribution.Hit;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1, attribution (OR-107 OQ-5 payload form, OQ-20 (b), OR-92 / OQ-15): an event goes to the instance whose
 * configured key EQUALS the key the event carries (exact, case-sensitive: MF26), then to that instance's SINGLE live
 * placement. Anything else is dropped and counted on the broker-connection-level counter, once per event and reason, and a
 * guessed placement is never used.
 */
class PushAttributionTest {

    private final FakePushRows rows = new FakePushRows();
    private final UUID environment = UUID.randomUUID();
    private final PushAttribution attribution = new PushAttribution(new BrokerSampleDecoder(64 * 1024));

    private PushRouting routing() {
        rows.connection(environment);
        return new PushSubscriptionPlan(rows).build(rows.tenant).environments().get(0).routing();
    }

    @Test
    void anEventIsAttributedToTheInstancesSingleLivePlacement() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID placement = UUID.randomUUID();
        rows.instance(environment, "ADAPTER_A", placement);

        Attributed result = attribution.attribute(routing(), PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"));

        assertEquals(1, result.hits().size());
        Hit hit = result.hits().get(0);
        assertEquals(placement, hit.placementId());
        assertEquals("filemesh.heartbeats", hit.metric().name());
        assertEquals(PushMetric.Extraction.COUNT, hit.metric().extraction());
        assertTrue(result.brokerDrops().isEmpty());
    }

    @Test
    void anInstanceWithTwoLivePlacementsHasItsEventDroppedNotGuessed() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID(), UUID.randomUUID());

        Attributed result = attribution.attribute(routing(), PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"));

        assertTrue(result.hits().isEmpty(), "no sample lands on either placement: no field in the event names one (facts F20)");
        assertEquals(EnumSet.of(CollectorReason.AMBIGUOUS_PLACEMENT), result.brokerDrops());
    }

    @Test
    void twoInstancesWithOneKeyAreAmbiguousToo() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "DUP", UUID.randomUUID());
        rows.instance(environment, "DUP", UUID.randomUUID());

        Attributed result = attribution.attribute(routing(), PushFixtures.topic("DUP", "heartbeat"),
                PushFixtures.heartbeat("DUP"));

        assertTrue(result.hits().isEmpty());
        assertEquals(EnumSet.of(CollectorReason.AMBIGUOUS_PLACEMENT), result.brokerDrops());
    }

    @Test
    void anEventForNoKnownInstanceIsDroppedAndCountedAtTheConnection() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());

        Attributed result = attribution.attribute(routing(), PushFixtures.topic("SOMEONE_ELSE", "heartbeat"),
                PushFixtures.heartbeat("SOMEONE_ELSE"));

        assertTrue(result.hits().isEmpty());
        assertEquals(EnumSet.of(CollectorReason.UNDECLARED_DROPPED), result.brokerDrops());
    }

    @Test
    void theKeyMatchIsExactAndCaseSensitive() {
        // two captured adapter ids differ only by case (facts 2.4): they are two adapters, and so are these
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID placement = UUID.randomUUID();
        rows.instance(environment, "SOURCE_V1", placement);
        PushRouting routing = routing();

        Attributed lower = attribution.attribute(routing, PushFixtures.topic("source_v1", "heartbeat"),
                PushFixtures.heartbeat("source_v1"));
        Attributed padded = attribution.attribute(routing, PushFixtures.topic("SOURCE_V1 ", "heartbeat"),
                PushFixtures.heartbeat("SOURCE_V1 "));

        assertTrue(lower.hits().isEmpty());
        assertEquals(EnumSet.of(CollectorReason.UNDECLARED_DROPPED), lower.brokerDrops());
        assertTrue(padded.hits().isEmpty());
        assertEquals(placement, attribution.attribute(routing, PushFixtures.topic("SOURCE_V1", "heartbeat"),
                PushFixtures.heartbeat("SOURCE_V1")).hits().get(0).placementId());
    }

    @Test
    void anInstanceWithNoLivePlacementIsNotAttributedAndIsNotAnAmbiguity() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "ADAPTER_A");

        Attributed result = attribution.attribute(routing(), PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"));

        assertTrue(result.hits().isEmpty());
        assertEquals(EnumSet.of(CollectorReason.UNDECLARED_DROPPED), result.brokerDrops());
    }

    @Test
    void aBodyThatDoesNotParseOrHasNoKeyIsCountedOnceAsParseFailed() {
        rows.definitions = List.of(PushFixtures.heartbeats(), PushFixtures.runtime());
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        PushRouting routing = routing();

        Attributed garbage = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "heartbeat"),
                "not json".getBytes(StandardCharsets.UTF_8));
        Attributed noKey = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "heartbeat"),
                "{\"header\":{}}".getBytes(StandardCharsets.UTF_8));

        assertEquals(EnumSet.of(CollectorReason.PARSE_FAILED), garbage.brokerDrops());
        assertEquals(EnumSet.of(CollectorReason.PARSE_FAILED), noKey.brokerDrops());
        assertTrue(garbage.hits().isEmpty() && noKey.hits().isEmpty());
    }

    @Test
    void anEventOfAnotherTopicIsIgnoredNotCounted() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());

        Attributed result = attribution.attribute(routing(), PushFixtures.topic("ADAPTER_A", "fcomplete"),
                PushFixtures.fileComplete("ADAPTER_A", "10"));

        assertTrue(result.hits().isEmpty());
        assertTrue(result.brokerDrops().isEmpty(), "this connection delivers other states only by a broader pattern: not a drop");
    }

    @Test
    void aNumberAtTheDeclaredPointerIsAValueHitAndANonNumberIsAHitThatCarriesNoValue() {
        rows.definitions = List.of(PushFixtures.runtime());
        UUID placement = UUID.randomUUID();
        rows.instance(environment, "ADAPTER_A", placement);
        PushRouting routing = routing();

        Attributed good = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "fcomplete"),
                PushFixtures.fileComplete("ADAPTER_A", "1234"));
        Attributed text = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "fcomplete"),
                PushFixtures.fileComplete("ADAPTER_A", "\"SENTINEL-not-a-number\""));
        Attributed infinite = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "fcomplete"),
                PushFixtures.fileComplete("ADAPTER_A", "1e400"));

        assertEquals(new BrokerSampleDecoder.NumberRead.Finite(1234.0), good.hits().get(0).read());
        assertEquals(BrokerSampleDecoder.NumberRead.NOT_A_FINITE_NUMBER, text.hits().get(0).read());
        assertEquals(BrokerSampleDecoder.NumberRead.NOT_A_FINITE_NUMBER, infinite.hits().get(0).read());
        assertEquals(placement, text.hits().get(0).placementId(), "attributed: the failure is counted against ITS placement");
        assertTrue(!text.toString().contains("SENTINEL"), text.toString());
    }

    @Test
    void theFilterDecidesWhichEventsCount() {
        MetricDefinitionFilter filtered = new MetricDefinitionFilter();
        rows.definitions = List.of(filtered.runtimeFilteredTo("12"));
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        PushRouting routing = routing();

        Attributed passes = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "fcomplete"),
                PushFixtures.fileComplete("ADAPTER_A", "5"));
        byte[] otherType = new String(PushFixtures.fileComplete("ADAPTER_A", "5"), StandardCharsets.UTF_8)
                .replace("\"eventType\":12", "\"eventType\":7").getBytes(StandardCharsets.UTF_8);
        Attributed other = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "fcomplete"), otherType);

        assertEquals(1, passes.hits().size(), "eventType 12 equals the filter");
        assertTrue(other.hits().isEmpty() && other.brokerDrops().isEmpty(), "a filtered-out event is not an error and not a drop");
    }

    @Test
    void twoMetricsOnOneEventBothHitAndADropIsCountedOncePerEventAndReason() {
        rows.definitions = List.of(PushFixtures.heartbeats(), new MetricDefinitionFilter().secondCounterOn(PushFixtures.HEARTBEAT_PATTERN));
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        PushRouting routing = routing();

        Attributed both = attribution.attribute(routing, PushFixtures.topic("ADAPTER_A", "heartbeat"),
                PushFixtures.heartbeat("ADAPTER_A"));
        Attributed unknown = attribution.attribute(routing, PushFixtures.topic("NOBODY", "heartbeat"),
                PushFixtures.heartbeat("NOBODY"));

        assertEquals(Set.of("filemesh.heartbeats", "filemesh.beats_again"),
                Set.copyOf(both.hits().stream().map(h -> h.metric().name()).toList()));
        assertEquals(EnumSet.of(CollectorReason.UNDECLARED_DROPPED), unknown.brokerDrops(), "a set: counted once per event");
    }
}
