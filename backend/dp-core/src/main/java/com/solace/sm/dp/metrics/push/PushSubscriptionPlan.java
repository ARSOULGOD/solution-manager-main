package com.solace.sm.dp.metrics.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.inventory.InstanceRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * What the Data Plane subscribes to, and how it will attribute what arrives, for one organization (plan 011 P11-5.1; OR-107),
 * computed from DP rows only ("the DP reads no package"). A pure function of {@link PushRows}: no network, no secret store, no
 * clock.
 *
 * <p><b>Subscriptions.</b> One per DISTINCT topic pattern per broker connection, never one per instance (OQ-5, payload form).
 * Every pattern's last level is a literal ({@link TopicPattern}), so a subscription names its states and a {@code start} event,
 * which may carry a plaintext broker password (facts F15, F16), is never delivered. A definition outside the grammar (planted past
 * the validators) is refused and counted, and contributes nothing: no pattern, no route.
 *
 * <p><b>Attribution keys.</b> For an {@code active} instance, in the generation in force (the newest that reached
 * {@code restart-observed}, as the collector's list is, M-Q15), each component instance of the metric's component type yields the
 * value of its {@code instanceKey.configField} from the draft values of record. {@code I6}: that value must be a short, non-blank
 * STRING that is not a secret reference (a {@code secretRef://} value is what a secret-typed field holds in a draft). Gate 16
 * already refuses a package whose key names a secret field; this is the second enforcer for a definition planted past it, and it
 * works from the DATA (the reference shape), because the Data Plane holds no package to read a field's declared type from. A value
 * that fails is refused and counted; it is never read from the secret store, never put in a route and never in a message.
 *
 * <p>An environment appears only when it has at least one route. Its broker connection is whatever is live for it, or none: with
 * none, nothing is subscribed and the instance's push metrics are simply not collected.
 */
public final class PushSubscriptionPlan {

    /** One environment's plan: the connection to subscribe on (if any) and the routing for what arrives on it. */
    public record EnvironmentPush(UUID environmentId, Optional<BrokerConnectionRecord> connection, PushRouting routing) {
    }

    /** An organization's plan. {@code refused} counts definitions and key values the plan would not use; it carries no value. */
    public record TenantPush(List<EnvironmentPush> environments, int refused) {

        public TenantPush {
            environments = List.copyOf(environments);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_REFERENCE_PREFIX = "secretRef://";
    private static final int MAX_KEY_LENGTH = 256;

    private final PushRows rows;

    public PushSubscriptionPlan(PushRows rows) {
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    private record Version(UUID solution, String version) {
    }

    private record Metrics(List<PushMetric> metrics, int refused) {
    }

    /** Per environment: metric -> key value -> targets, in the order they were found. */
    private static final class EnvironmentBuilder {
        final Map<PushMetric, Map<String, List<PushRouting.Target>>> routes = new LinkedHashMap<>();
    }

    public TenantPush build(UUID tenantId) {
        Map<Version, Metrics> versions = new HashMap<>();
        Map<UUID, EnvironmentBuilder> environments = new LinkedHashMap<>();
        int[] refused = {0};
        for (InstanceRecord instance : rows.activeInstances(tenantId)) {
            Version version = new Version(instance.catalogSolutionId(), instance.declaredPackageVersion());
            Metrics declared = versions.computeIfAbsent(version, v -> {
                Metrics parsed = parse(rows.definitions(tenantId, v.solution(), v.version()));
                refused[0] += parsed.refused();   // once per package version, not once per instance of it
                return parsed;
            });
            if (declared.metrics().isEmpty()) {
                continue;
            }
            OptionalLong generation = rows.inForceGeneration(tenantId, instance.instanceId());
            if (generation.isEmpty()) {
                continue;   // its configuration has never been observed running: nothing it publishes can be attributed yet
            }
            Set<String> active = rows.activeMetrics(tenantId, instance.instanceId(), generation.getAsLong())
                    .map(Set::copyOf).orElse(Set.of());
            List<PushMetric> wanted = declared.metrics().stream().filter(m -> active.contains(m.name())).toList();
            if (wanted.isEmpty()) {
                continue;
            }
            Optional<PushRows.ConfigContent> content = rows.config(tenantId, instance.instanceId(), generation.getAsLong());
            if (content.isEmpty()) {
                continue;
            }
            JsonNode selection = tree(content.get().componentSelectionJson());
            JsonNode draft = tree(content.get().draftValuesJson());
            if (selection == null || draft == null) {
                refused[0]++;
                continue;
            }
            List<UUID> live = rows.livePlacements(tenantId, instance.instanceId());
            for (PushMetric metric : wanted) {
                for (JsonNode component : selection.path("instances")) {
                    if (!metric.componentType().equals(component.path("type").asText(null))) {
                        continue;
                    }
                    String instanceKey = component.path("instanceKey").asText(null);
                    JsonNode value = instanceKey == null ? null : draft.path(instanceKey).path(metric.keyConfigField());
                    if (value == null || value.isMissingNode() || value.isNull()) {
                        continue;   // not configured: nothing identifies it, which is not an error
                    }
                    String key = usableKey(value);
                    if (key == null) {
                        refused[0]++;
                        continue;
                    }
                    environments.computeIfAbsent(instance.environmentId(), e -> new EnvironmentBuilder()).routes
                            .computeIfAbsent(metric, m -> new LinkedHashMap<>())
                            .computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new PushRouting.Target(instance.instanceId(), live));
                }
            }
        }
        List<EnvironmentPush> out = new ArrayList<>();
        environments.forEach((environment, builder) -> {
            List<PushRouting.Route> routes = new ArrayList<>();
            builder.routes.forEach((metric, byKey) -> routes.add(new PushRouting.Route(metric, byKey)));
            out.add(new EnvironmentPush(environment, rows.liveConnection(tenantId, environment), new PushRouting(routes)));
        });
        return new TenantPush(out, refused[0]);
    }

    private static Metrics parse(List<MetricDefinitionSpec> definitions) {
        List<PushMetric> metrics = new ArrayList<>();
        Set<PushMetric> seen = new HashSet<>();
        int refused = 0;
        for (MetricDefinitionSpec spec : definitions) {
            try {
                Optional<PushMetric> metric = PushMetric.of(spec);
                if (metric.isPresent() && seen.add(metric.get())) {
                    metrics.add(metric.get());
                }
            } catch (IllegalArgumentException e) {
                refused++;   // outside the grammar: refused, never used, and the message names no part of the source
            }
        }
        return new Metrics(metrics, refused);
    }

    /** The key a configured value gives, or null when it must not be used (not a string, blank, long, a secret reference). */
    private static String usableKey(JsonNode value) {
        if (!value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        if (text.isBlank() || text.length() > MAX_KEY_LENGTH || text.startsWith(SECRET_REFERENCE_PREFIX)) {
            return null;
        }
        return text;
    }

    private static JsonNode tree(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }
}
