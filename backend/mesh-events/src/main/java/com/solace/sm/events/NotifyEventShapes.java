package com.solace.sm.events;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-event shape validation for v1 notify events — the Java mirror of
 * {@code contracts/events/notify-events.schema.json}'s six {@code oneOf} branches.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link EventEnvelope}'s compact constructor mirrors {@code envelope.schema.json} only: required
 * fields, non-blank tenant, non-negative {@code stateVersion}, and {@code producer} within the
 * schema's closed {@code [cp-core, dp-core]} enum. Everything that makes an event a
 * <i>particular</i> event — which {@code entity.kind} pairs with which {@code event}, which entity
 * ids are required and which are forbidden, which payload keys exist and what may be in them — lived
 * exclusively in the JSON Schema, which no Java producer runs. A producer could therefore emit
 * {@code instance.health} with {@code entity.kind=instance} and no {@code placementId} and the
 * publisher would happily put it on the wire, silently undoing the R5 identity-scoping fix.
 *
 * <p>This class closes that hole the way {@link NotifyTopics} closes the topic hole: the constraints
 * are <b>hand-mirrored from the contract file</b>, and {@code NotifyEventShapesTest} golden-tests both
 * halves against the same {@code contracts/events/fixtures/} and {@code contracts/events/negative/}
 * documents the Python validator uses ({@code UI-D15}: one fixture suite, two implementations, and
 * divergence fails a build). No JSON-Schema engine is added to the backend for this — a runtime
 * schema dependency would be a heavier answer to a closed, six-branch problem.
 *
 * <p><b>The constraints below were copied out of {@code notify-events.schema.json}, never recalled</b>
 * ({@code I2}). When that file changes, this class changes with it, in the same reviewed commit —
 * contracts are single-writer.
 *
 * <h2>The six branches, exactly as the schema states them</h2>
 *
 * <pre>
 * event                   entity.kind  entity required          entity forbidden        payload (closed)
 * instance.health         placement    instanceId, placementId  —                       state (enum)
 * instance.config-state   instance     instanceId               placementId             reached (enum)
 * instance.version        instance     instanceId               placementId             state (enum)
 * instance.rollout        placement    instanceId, placementId  —                       applyId, replicaId, step
 * catalog.package         solution     solution                 instanceId, placementId solution, packageVersion
 * metrics.quarantine      instance     instanceId               —                       count (integer &gt;= 1)
 * </pre>
 *
 * <p>Two asymmetries in that table are the <b>schema's</b>, faithfully reproduced rather than tidied:
 * {@code metrics.quarantine} does <i>not</i> forbid {@code placementId} although the other two
 * instance-scoped events do, and {@code catalog.package}'s {@code packageVersion} is
 * {@code "packageVersion": {}} — required but of any JSON type. Both are noted in
 * {@code contracts/events/README.md}'s Known gaps (gap 4 for {@code packageVersion}) and belong to
 * the pending {@code BE-D4} amendment. Resolving either here would be this class inventing contract,
 * which is exactly what it exists to prevent.
 *
 * <h2>Failure mode</h2>
 *
 * <p>{@link IllegalArgumentException}, always — a shape-invalid event is a <b>caller bug</b>, not a
 * runtime condition, and it must never reach the wire. That is the same class {@link NotifyTopics}
 * throws for an unaddressable event and, like it, is on the loud/propagate side of
 * {@code JcsmpEventPublisher}'s asymmetry.
 */
public final class NotifyEventShapes {

    // ---- closed vocabularies, copied verbatim from notify-events.schema.json ------------------

    /**
     * The SCREAMING rollup keys plus the four {@code NOTFOUND} sub-states as distinct keys, per
     * {@code STATE-VOCABULARY.md} §2 and §5. Collapsing the four is what the source forbids.
     */
    private static final Set<String> HEALTH_STATE = Set.of(
            "GREEN", "RED", "RESTART_EXPECTED", "INACTIVE_REPLICA", "NOT_APPLICABLE",
            "unreachable", "not_exposed", "missing", "bootstrap");

    /** The {@code SM-D22} config ladder rungs. */
    private static final Set<String> CONFIG_REACHED = Set.of(
            "desired", "rendered", "persisted", "restart-observed", "effective-verified");

    /** {@code VersionState} from {@code contracts/view-models/src/types.ts}. */
    private static final Set<String> VERSION_STATE = Set.of(
            "OK", "VERSION_DRIFT", "VERSION_UNKNOWN", "VERSION_UNVERIFIED");

    /** Known gap 2: the one enum here with no canonical home yet. Mirrored, not adjudicated. */
    private static final Set<String> ROLLOUT_STEP = Set.of(
            "staged", "restart-requested", "restart-observed");

    private NotifyEventShapes() {
    }

    // ---- the branch table ----------------------------------------------------------------------

    /** One payload field's constraint, as the schema states it. Package-private for the lock test. */
    sealed interface FieldRule permits EnumOf, IsString, IntegerAtLeast, AnyType {
    }

    /** Schema {@code enum} of strings. */
    record EnumOf(Set<String> members) implements FieldRule {
    }

    /** Schema {@code "type": "string"}. */
    record IsString() implements FieldRule {
    }

    /** Schema {@code "type": "integer", "minimum": min}. */
    record IntegerAtLeast(long min) implements FieldRule {
    }

    /** Schema {@code {}} — required, any JSON type. The pinned Known-gap-4 hole; presence-only. */
    record AnyType() implements FieldRule {
    }

    /** One {@code oneOf} branch: entity scoping plus the closed payload. */
    record BranchRule(String kind, Set<String> requiredEntity, Set<String> forbiddenEntity,
            Map<String, FieldRule> payload) {
    }

    /**
     * The six branches, copied from notify-events.schema.json (I2: copied, never recalled) and
     * LOCKED to it by SchemaMirrorLockTest — schema edits and edits here fail the build until the
     * other side moves in the same reviewed commit. validate() consumes THIS table; nothing else
     * defines a branch.
     */
    static final Map<String, BranchRule> BRANCHES = Map.of(
            "instance.health", new BranchRule("placement",
                    Set.of("instanceId", "placementId"), Set.of(),
                    Map.of("state", new EnumOf(HEALTH_STATE))),
            "instance.config-state", new BranchRule("instance",
                    Set.of("instanceId"), Set.of("placementId"),
                    Map.of("reached", new EnumOf(CONFIG_REACHED))),
            "instance.version", new BranchRule("instance",
                    Set.of("instanceId"), Set.of("placementId"),
                    Map.of("state", new EnumOf(VERSION_STATE))),
            "instance.rollout", new BranchRule("placement",
                    Set.of("instanceId", "placementId"), Set.of(),
                    Map.of("applyId", new IsString(), "replicaId", new IsString(),
                            "step", new EnumOf(ROLLOUT_STEP))),
            // packageVersion is `{}` in the schema — required, any JSON type. Deliberately AnyType:
            // see contracts/events/README.md Known gap 4 (BE-D4 amendment territory).
            "catalog.package", new BranchRule("solution",
                    Set.of("solution"), Set.of("instanceId", "placementId"),
                    Map.of("solution", new IsString(), "packageVersion", new AnyType())),
            // NOTE: unlike config-state and version, this branch does NOT forbid placementId.
            // That is the schema's asymmetry, reproduced as-is (BE-D4 amendment territory).
            "metrics.quarantine", new BranchRule("instance",
                    Set.of("instanceId"), Set.of(),
                    Map.of("count", new IntegerAtLeast(1))));

    /** The branch keys — the Java side of the envelope's {@code event} enum. Package-private. */
    static Set<String> handledEvents() {
        return BRANCHES.keySet();
    }

    /**
     * Validates an event against its branch of the notify catalog.
     *
     * @param e the event to check
     * @throws IllegalArgumentException if the event violates its branch — the message names the
     *     event and the specific rule broken — or if {@code event} is not one of the six v1 keys
     */
    public static void validate(EventEnvelope e) {
        BranchRule rule = BRANCHES.get(e.event());
        if (rule == null) {
            throw new IllegalArgumentException(
                    "unknown event '" + e.event() + "' — not one of the six v1 notify events");
        }
        entity(e, rule.requiredEntity(), rule.forbiddenEntity(), rule.kind());
        payload(e, rule.payload().keySet());
        for (String key : ordered(rule.payload().keySet())) {
            FieldRule fr = rule.payload().get(key);
            if (fr instanceof EnumOf en) {
                enumValue(e, key, en.members());
            } else if (fr instanceof IsString) {
                stringValue(e, key);
            } else if (fr instanceof IntegerAtLeast m) {
                integerAtLeast(e, key, m.min());
            }
            // AnyType: presence already asserted by payload(...); the hole stays open on purpose.
        }
    }

    // ---- entity ------------------------------------------------------------------------------

    /**
     * The branch's {@code entity} constraints: required ids, forbidden ids, and the {@code kind}
     * pairing.
     *
     * <p>Ids are checked <b>before</b> {@code kind} on purpose. A document that gets the identity
     * wrong usually gets both wrong — {@code negative/health-without-placement.json} carries
     * {@code kind: instance} <i>and</i> no {@code placementId} — and the missing or forbidden id is
     * the actionable half: it names the identity-scoping rule (design §3, the R5 fix) rather than
     * the derived pairing. Reversing the order would report every such document as a kind mismatch.
     *
     * <p>"Present" means non-null: {@link EventJson}'s {@code NON_NULL} inclusion is what makes an
     * inapplicable id <i>absent</i> on the wire, which is precisely what the schema's
     * {@code required} / {@code not: {required: [...]}} keywords test.
     */
    private static void entity(EventEnvelope e, Set<String> required, Set<String> forbidden,
            String kind) {
        for (String id : ordered(required)) {
            if (idOf(e, id) == null) {
                throw new IllegalArgumentException(
                        e.event() + ": entity." + id + " is required for this event");
            }
        }
        for (String id : ordered(forbidden)) {
            if (idOf(e, id) != null) {
                throw new IllegalArgumentException(
                        e.event() + ": entity." + id + " is forbidden for this event");
            }
        }
        if (!kind.equals(e.entity().kind())) {
            throw new IllegalArgumentException(e.event() + ": entity.kind must be '" + kind
                    + "', was '" + e.entity().kind() + "'");
        }
    }

    private static String idOf(EventEnvelope e, String field) {
        return switch (field) {
            case "instanceId" -> e.entity().instanceId();
            case "placementId" -> e.entity().placementId();
            case "solution" -> e.entity().solution();
            // Unreachable from this class's own call sites; a guard against a future branch naming
            // an entity field EntityRef does not have, which would otherwise validate vacuously.
            default -> throw new IllegalStateException("no entity accessor for '" + field + "'");
        };
    }

    // ---- payload -----------------------------------------------------------------------------

    /**
     * Required keys present and <b>no key outside the branch's properties</b> — the schema's
     * {@code required} plus {@code additionalProperties: false}. The closed key set is the
     * executable form of {@code I6}: a notify payload carries identifiers only, so an extra key is
     * a leak, not an extension. {@code negative/value-in-notify.json} is that rule's contract case.
     *
     * <p><b>"Present" means non-null here, exactly as it does for entity ids</b> ({@code idOf}), and
     * that is a <i>wire-safety</i> rule rather than a stylistic one. {@link EventJson}'s mapper is
     * configured {@code JsonInclude.Value.construct(NON_NULL, NON_NULL)} — <b>content</b> inclusion
     * included — so a null value inside the payload {@code Map} is <b>deleted at serialization</b>.
     * A {@code containsKey}-only check therefore let
     * {@code payload={"solution":"cache","packageVersion":null}} pass validation and emit
     * {@code {"payload":{"solution":"cache"}}}: a document this class had just declared valid and
     * the Python validator rejects for a missing required property. Rejecting the null closes that
     * gap at the only place that can see both sides.
     *
     * <p><b>The deliberate Java/Python asymmetry this creates</b> ({@code contracts/events/README.md}
     * Known gap 4): {@code catalog.package}'s {@code packageVersion} is {@code {}} in the schema, so
     * a JSON {@code null} that is <i>literally on the wire</i> validates in Python. Java refuses to
     * construct it, because Java cannot put it on the wire. The asymmetry is narrower than letting a
     * required key vanish, and it disappears when the {@code BE-D4} amendment types the field.
     */
    private static void payload(EventEnvelope e, Set<String> allowed) {
        Map<String, Object> payload = e.payload();
        for (String key : ordered(allowed)) {
            // get(...) == null covers BOTH shapes of absence — the key missing entirely and the key
            // present with a null value — and containsKey then separates them for the message. A
            // caller that wrote the key needs to hear "null", not "missing", or it will look for a
            // line that is right there.
            if (payload.get(key) == null) {
                throw new IllegalArgumentException(payload.containsKey(key)
                        ? e.event() + ": payload." + key + " is required for this event and must"
                                + " not be null — a null value is stripped by the NON_NULL"
                                + " serializer, so the emitted document would be missing it"
                        : e.event() + ": payload." + key + " is required for this event");
            }
        }
        for (String key : payload.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(e.event() + ": payload key '" + key
                        + "' is not allowed — notify payloads carry identifiers only (I6);"
                        + " permitted keys are " + ordered(allowed));
            }
        }
    }

    /** A closed {@code enum} of strings: anything that is not one of the members fails. */
    private static void enumValue(EventEnvelope e, String key, Set<String> members) {
        Object v = e.payload().get(key);
        if (!(v instanceof String s) || !members.contains(s)) {
            throw new IllegalArgumentException(e.event() + ": payload." + key + " must be one of "
                    + ordered(members) + ", was " + describe(v));
        }
    }

    /** Schema {@code "type": "string"}. */
    private static void stringValue(EventEnvelope e, String key) {
        Object v = e.payload().get(key);
        if (!(v instanceof String)) {
            throw new IllegalArgumentException(
                    e.event() + ": payload." + key + " must be a string, was " + describe(v));
        }
    }

    /**
     * Schema {@code "type": "integer", "minimum": n}. Draft 2020-12 defines {@code integer} as any
     * number with a zero fractional part — {@code 12.0} is an integer there — so a whole-valued
     * {@code Double}/{@code BigDecimal} is accepted here too, matching the Python validator rather
     * than matching Java's type hierarchy.
     */
    private static void integerAtLeast(EventEnvelope e, String key, long min) {
        Object v = e.payload().get(key);
        Long n = asIntegral(v);
        if (n == null || n < min) {
            throw new IllegalArgumentException(e.event() + ": payload." + key
                    + " must be an integer >= " + min + ", was " + describe(v));
        }
    }

    private static Long asIntegral(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger b) {
            return b.bitLength() < 64 ? b.longValue() : null;
        }
        if (v instanceof BigDecimal d) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException ignored) {
                return null;
            }
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) <= Long.MAX_VALUE)
                    ? (long) d : null;
        }
        return null;
    }

    /** Stable, readable rendering of an offending value — type included, because {@code "12"} and
     * {@code 12} are indistinguishable otherwise and that is exactly the class of defect here. */
    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        return (v instanceof String s ? "'" + s + "'" : String.valueOf(v))
                + " (" + v.getClass().getSimpleName() + ")";
    }

    /** Deterministic ordering, so a failure message is the same on every JVM run. */
    private static Set<String> ordered(Set<String> s) {
        return new LinkedHashSet<>(s.stream().sorted().toList());
    }
}
