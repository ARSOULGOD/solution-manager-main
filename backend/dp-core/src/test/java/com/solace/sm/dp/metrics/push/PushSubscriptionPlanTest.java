package com.solace.sm.dp.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan.EnvironmentPush;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan.TenantPush;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-5.1: what the Data Plane subscribes to, and how it will attribute what arrives, from DP rows only. A
 * subscription names a state (the last level is a literal) and never an instance (OR-107: one broad subscription per distinct
 * pattern per broker connection); the attribution key is read from the configuration of record in force, never a secret.
 */
class PushSubscriptionPlanTest {

    private final FakePushRows rows = new FakePushRows();
    private final UUID environment = UUID.randomUUID();

    private EnvironmentPush only(TenantPush plan) {
        assertEquals(1, plan.environments().size(), plan.toString());
        return plan.environments().get(0);
    }

    @Test
    void aDeclaredMetricOfAnInstanceInForceIsPlannedWithItsPatternAndItsKey() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID placement = UUID.randomUUID();
        UUID instance = rows.instance(environment, "ADAPTER_A", placement);
        rows.connection(environment);

        EnvironmentPush env = only(new PushSubscriptionPlan(rows).build(rows.tenant));

        assertEquals(Set.of(PushFixtures.HEARTBEAT_PATTERN), env.routing().topicPatterns());
        assertTrue(env.connection().isPresent());
        PushRouting.Route route = env.routing().routes().get(0);
        assertEquals("filemesh.heartbeats", route.metric().name());
        assertEquals(List.of(new PushRouting.Target(instance, List.of(placement))), route.byKey().get("ADAPTER_A"));
        assertEquals(1, route.byKey().size(), "the SINK component's decoy value is not the source metric's key");
    }

    @Test
    void theSubscriptionNamesAStateAndNeverAnInstanceOrAWildcardLastLevel() {
        rows.definitions = List.of(PushFixtures.heartbeats(), PushFixtures.runtime());
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        rows.instance(environment, "ADAPTER_B", UUID.randomUUID());
        rows.connection(environment);

        Set<String> patterns = only(new PushSubscriptionPlan(rows).build(rows.tenant)).routing().topicPatterns();

        assertEquals(Set.of(PushFixtures.HEARTBEAT_PATTERN, PushFixtures.FCOMPLETE_PATTERN), patterns,
                "two instances, two metrics: one subscription per DISTINCT pattern, none per instance");
        for (String pattern : patterns) {
            String last = pattern.substring(pattern.lastIndexOf('/') + 1);
            assertTrue(last.matches("[A-Za-z0-9_.-]+"), "the last level is a literal: " + pattern);
            assertFalse(pattern.contains("ADAPTER"), "no instance is in a subscription: " + pattern);
            assertFalse(pattern.endsWith("/start"), "a start event is never subscribed: " + pattern);
        }
    }

    @Test
    void aStartEventIsNeverSubscribedBecauseAWildcardLastLevelPlantedPastTheValidatorsIsRefused() {
        for (String planted : new String[] {"a/b/>", "a/b/*", ">", "a/b/c*", "a/b/{instanceKey}"}) {
            FakePushRows planted1 = new FakePushRows();
            planted1.definitions = List.of(new MetricDefinitionSpec("filemesh.evil", PushFixtures.COMPONENT, "counter",
                    "events", "solace_event", PushFixtures.source(planted, "count", null, null, PushFixtures.ADAPTER_ID_FIELD),
                    null, true));
            planted1.instance(environment, "ADAPTER_A", UUID.randomUUID());
            planted1.connection(environment);

            TenantPush plan = new PushSubscriptionPlan(planted1).build(planted1.tenant);

            assertTrue(plan.environments().isEmpty() || plan.environments().get(0).routing().topicPatterns().isEmpty(),
                    "'" + planted + "' must never become a subscription: " + plan);
            assertEquals(1, plan.refused(), planted);
        }
    }

    @Test
    void aSecretTypedInstanceKeyIsNeverResolvedIntoARouteOrASubscription() {
        // a definition planted as superuser past gate 16 names a field whose configured value is a SECRET REFERENCE
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "secretRef://" + UUID.randomUUID(), UUID.randomUUID());
        rows.connection(environment);

        TenantPush plan = new PushSubscriptionPlan(rows).build(rows.tenant);

        assertTrue(plan.environments().isEmpty() || plan.environments().get(0).routing().topicPatterns().isEmpty(),
                "no subscription: " + plan);
        assertTrue(plan.environments().isEmpty() || plan.environments().get(0).routing().routes().isEmpty(), plan.toString());
        assertEquals(1, plan.refused());
        assertFalse(plan.toString().contains("secretRef"), "the plan's text never carries the reference: " + plan);
    }

    @Test
    void aKeyThatIsNotAShortNonBlankStringIsRefused() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        for (String bad : new String[] {"\"\"", "\"   \"", "12", "true", "null", "{\"a\":1}", "[\"x\"]",
                "\"" + "x".repeat(257) + "\""}) {
            FakePushRows one = new FakePushRows();
            one.definitions = rows.definitions;
            one.instanceRaw(environment, bad, UUID.randomUUID());
            one.connection(environment);

            TenantPush plan = new PushSubscriptionPlan(one).build(one.tenant);

            assertTrue(plan.environments().isEmpty() || plan.environments().get(0).routing().routes().isEmpty(),
                    "'" + bad + "' is not a key: " + plan);
        }
    }

    @Test
    void aMetricNotInTheGenerationsActiveListIsNotPlanned() {
        rows.definitions = List.of(PushFixtures.heartbeats(), PushFixtures.runtime());
        UUID instance = rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        rows.active.put(instance, List.of("filemesh.heartbeats"));
        rows.connection(environment);

        EnvironmentPush env = only(new PushSubscriptionPlan(rows).build(rows.tenant));

        assertEquals(Set.of(PushFixtures.HEARTBEAT_PATTERN), env.routing().topicPatterns());
    }

    @Test
    void anInstanceWhoseConfigHasNeverBeenObservedRunningIsNotPlannedAndItsConfigIsNotRead() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID instance = rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        rows.inForce.remove(instance);
        rows.connection(environment);

        TenantPush plan = new PushSubscriptionPlan(rows).build(rows.tenant);

        assertTrue(plan.environments().isEmpty() || plan.environments().get(0).routing().routes().isEmpty());
        assertEquals(0, rows.configReads, "no generation is in force, so none is read");
    }

    @Test
    void aMetricOfAnotherKindOrAHistogramIsNotAPushMetric() {
        rows.definitions = List.of(
                new MetricDefinitionSpec("cache.size", PushFixtures.COMPONENT, "gauge", "entries", "http_json",
                        "{\"kind\":\"http_json\",\"path\":\"/x\",\"port\":1,\"onUnavailable\":\"degrade\"}", 30, true),
                new MetricDefinitionSpec("filemesh.h", PushFixtures.COMPONENT, "histogram", "ms", "solace_event",
                        PushFixtures.source(PushFixtures.HEARTBEAT_PATTERN, "count", null, null, PushFixtures.ADAPTER_ID_FIELD),
                        null, true));
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());
        rows.connection(environment);

        TenantPush plan = new PushSubscriptionPlan(rows).build(rows.tenant);

        assertTrue(plan.environments().isEmpty() || plan.environments().get(0).routing().routes().isEmpty(), plan.toString());
    }

    @Test
    void anEnvironmentWithoutABrokerConnectionIsPlannedWithNoConnection() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        rows.instance(environment, "ADAPTER_A", UUID.randomUUID());

        EnvironmentPush env = only(new PushSubscriptionPlan(rows).build(rows.tenant));

        assertTrue(env.connection().isEmpty(), "nothing to subscribe on: the instance's push metric is simply not collected");
    }

    @Test
    void eachEnvironmentHasItsOwnConnectionAndItsOwnRouting() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID other = UUID.randomUUID();
        rows.instance(environment, "SHARED_ID", UUID.randomUUID());
        rows.instance(other, "SHARED_ID", UUID.randomUUID());
        UUID c1 = rows.connection(environment);
        UUID c2 = rows.connection(other);

        TenantPush plan = new PushSubscriptionPlan(rows).build(rows.tenant);

        assertEquals(2, plan.environments().size());
        for (EnvironmentPush e : plan.environments()) {
            assertEquals(1, e.routing().routes().get(0).byKey().get("SHARED_ID").size(),
                    "the same key in two environments is two routes of two connections, never one route of two instances");
        }
        assertEquals(Set.of(c1, c2), Set.copyOf(plan.environments().stream()
                .map(e -> e.connection().orElseThrow().brokerConnectionId()).toList()));
    }

    @Test
    void twoInstancesOfOneEnvironmentWithOneKeyBothAppearUnderIt() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID a = rows.instance(environment, "DUP", UUID.randomUUID());
        UUID b = rows.instance(environment, "DUP", UUID.randomUUID());
        rows.connection(environment);

        EnvironmentPush env = only(new PushSubscriptionPlan(rows).build(rows.tenant));

        List<PushRouting.Target> targets = env.routing().routes().get(0).byKey().get("DUP");
        assertEquals(Set.of(a, b), Set.copyOf(targets.stream().map(PushRouting.Target::instanceId).toList()));
    }

    @Test
    void anInstanceWithNoLivePlacementStillAppearsSoItsEventsCanBeCountedAsNotAttributable() {
        rows.definitions = List.of(PushFixtures.heartbeats());
        UUID instance = rows.instance(environment, "ADAPTER_A");
        rows.connection(environment);

        EnvironmentPush env = only(new PushSubscriptionPlan(rows).build(rows.tenant));

        assertEquals(List.of(new PushRouting.Target(instance, List.of())), env.routing().routes().get(0).byKey().get("ADAPTER_A"));
    }
}
