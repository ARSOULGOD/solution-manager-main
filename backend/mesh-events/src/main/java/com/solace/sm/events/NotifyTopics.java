package com.solace.sm.events;

/**
 * Topic derivation for v1 notify events — the Java half of {@code BE-D4}'s taxonomy (backend design
 * §4), mirroring {@code contracts/events/validate-events.py}'s {@code derive_topic} exactly. The
 * fixtures' {@code expected-topics.json} manifest is the shared golden input for both.
 *
 * <pre>
 * sm/v1/&lt;tenant&gt;/notify/instance/&lt;instanceId&gt;/health
 * sm/v1/&lt;tenant&gt;/notify/instance/&lt;instanceId&gt;/config-state
 * sm/v1/&lt;tenant&gt;/notify/instance/&lt;instanceId&gt;/version
 * sm/v1/&lt;tenant&gt;/notify/instance/&lt;instanceId&gt;/quarantine
 * sm/v1/&lt;tenant&gt;/notify/instance/&lt;instanceId&gt;/rollout/&lt;applyId&gt;
 * sm/v1/&lt;tenant&gt;/notify/catalog/package
 * </pre>
 *
 * <p>Two properties are load-bearing and are why this is a closed switch rather than string
 * assembly at call sites: <b>{@code v1} is in every topic from the first commit</b>, and
 * <b>tenant is a topic level</b> so broker ACLs can scope subscriptions by prefix — the mesh-side
 * half of {@code I5}. An unknown {@code event} is rejected rather than guessed at; publishing an
 * SM event on an underivable topic would put it outside every ACL the design reasons about.
 *
 * <p>Note {@code instance.health} and {@code instance.rollout} are placement-scoped in the envelope
 * ({@code SM-D23}) but <b>instance-scoped in the topic</b>: {@code placementId} rides in the
 * entity, not in the address. That is design §4's shape, not an omission.
 */
public final class NotifyTopics {

    private static final String PREFIX = "sm/v1/";

    private NotifyTopics() {
    }

    /** The topic a producer must publish this event on. */
    public static String topicFor(EventEnvelope e) {
        String tenant = levelSafe("tenant", e.tenant());
        return switch (e.event()) {
            case "instance.health" -> instanceTopic(tenant, e, "health");
            case "instance.config-state" -> instanceTopic(tenant, e, "config-state");
            case "instance.version" -> instanceTopic(tenant, e, "version");
            case "metrics.quarantine" -> instanceTopic(tenant, e, "quarantine");
            case "instance.rollout" -> instanceTopic(tenant, e, "rollout") + "/" + applyId(e);
            case "catalog.package" -> PREFIX + tenant + "/notify/catalog/package";
            default -> throw new IllegalArgumentException("no topic rule for event '" + e.event() + "'");
        };
    }

    private static String instanceTopic(String tenant, EventEnvelope e, String aspect) {
        String instanceId = e.entity().instanceId();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException(
                    "event '" + e.event() + "' needs entity.instanceId to address a topic");
        }
        return PREFIX + tenant + "/notify/instance/" + levelSafe("entity.instanceId", instanceId)
                + "/" + aspect;
    }

    private static String applyId(EventEnvelope e) {
        Object applyId = e.payload().get("applyId");
        if (!(applyId instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    "instance.rollout needs a non-blank payload.applyId to address a topic");
        }
        return levelSafe("payload.applyId", s);
    }

    /**
     * Rejects a value that would change the <b>shape</b> of the topic it is interpolated into.
     *
     * <p>A {@code /} adds topic levels; {@code *} and {@code >} are Solace subscription wildcards.
     * Either turns an addressed event into something the broker matches differently from what the
     * taxonomy says — and tenant is a topic level precisely so ACLs can prefix-match on it (the mesh
     * half of {@code I5}), so a tenant of {@code "acme/../evil"} or {@code ">"} is an isolation
     * defect, not a cosmetic one. This is the single place SM builds a topic string, so it is the
     * single place the check belongs. Mirrored in {@code contracts/events/validate-events.py}'s
     * {@code derive_topic}, which holds the same three values to the same rule.
     */
    private static String levelSafe(String field, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '*' || c == '>') {
                throw new IllegalArgumentException(
                        field + " must not contain a topic separator or wildcard ('/', '*', '>'): '"
                                + value + "'");
            }
        }
        return value;
    }
}
