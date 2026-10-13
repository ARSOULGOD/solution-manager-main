package com.solace.sm.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * BE-D10's lock: the schema files and the Java mirror must be EQUAL — both directions. A schema
 * edit without the matching Java edit fails here; a Java edit without the schema edit fails here.
 * Everything is set-equality, never containment (containment lets one side grow silently).
 *
 * Deliberately NOT checked here: format semantics (EnvelopeGoldenTest round-trips real bytes),
 * topic derivation (NotifyTopicsTest owns the manifest bidirectionally), and the null-on-wire
 * asymmetry around packageVersion (pinned as AnyType below, adjudicated by the BE-D4 amendment).
 */
class SchemaMirrorLockTest {

    private static final JsonNode ENVELOPE =
            ContractArtifacts.json(ContractArtifacts.dir("events").resolve("envelope.schema.json"));
    private static final JsonNode NOTIFY =
            ContractArtifacts.json(ContractArtifacts.dir("events").resolve("notify-events.schema.json"));

    // ---- envelope ----------------------------------------------------------------------------

    @Test
    void envelopeRequiredEqualsPropertiesAndMatchesTheRecord() {
        // Derived from the record, never restated as a literal list — a literal here is a third
        // mirror: add traceId to EventEnvelope, forget the schema, and a literal lock stays green
        // while a real producer emits a schema-invalid top-level property.
        Set<String> components = recordComponents(EventEnvelope.class);
        assertEquals(components, textSet(ENVELOPE.get("required")));
        assertEquals(components, fieldNames(ENVELOPE.get("properties")));
        assertTrue(isFalseBoolean(ENVELOPE.get("additionalProperties")),
                "the envelope must stay additionalProperties: false");
    }

    @Test
    void producerEnumEqualsTheJavaConstant() {
        assertEquals(sorted(EventEnvelope.PRODUCERS),
                textSet(ENVELOPE.at("/properties/producer/enum")));
    }

    @Test
    void eventEnumEqualsTheJavaBranchTable() {
        assertEquals(sorted(NotifyEventShapes.handledEvents()),
                textSet(ENVELOPE.at("/properties/event/enum")));
    }

    @Test
    void entityShapeMatchesEntityRefAndTheKindsTheTableUses() {
        JsonNode entity = ENVELOPE.at("/properties/entity");
        // 'kind' is the schema's requiredness choice, not derivable from the record (all EntityRef
        // components are nullable Java-side) — the one deliberate literal here, pinned:
        assertEquals(set("kind"), textSet(entity.get("required")));
        assertEquals(recordComponents(EntityRef.class), fieldNames(entity.get("properties")));
        assertTrue(isFalseBoolean(entity.get("additionalProperties")),
                "entity must stay additionalProperties: false");
        Set<String> kindsInTable = new TreeSet<>();
        for (NotifyEventShapes.BranchRule r : NotifyEventShapes.BRANCHES.values()) {
            kindsInTable.add(r.kind());
        }
        assertEquals(kindsInTable, textSet(entity.at("/properties/kind/enum")));
    }

    /**
     * Every envelope property's keyword set, pinned <b>exactly</b>. The bounds test below pins the
     * keywords the Java side mirrors; this one pins that there are no OTHERS. Without it the lock is
     * containment on the envelope side, and containment lets the schema grow silently:
     * {@code "organization": {..., "maxLength": 4}} is a narrowing the Python validator enforces and no
     * Java check honours, and both gates stay green while a five-character organization is a
     * schema-invalid document Java happily emits.
     *
     * <p>Annotation keywords ({@code description}, {@code $comment}, {@code title}) are tolerated
     * everywhere — they carry no validation semantics, so they can never be the narrowing this
     * guards against.
     */
    @Test
    void everyEnvelopePropertyPinsItsKeywordSetExactly() {
        // Copied property-by-property from contracts/events/envelope.schema.json while writing —
        // never recalled (I2). A property added to the schema without a line here is a red.
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("eventId", new String[] {"type", "format"});
        expected.put("occurredAtUtc", new String[] {"type", "format"});
        expected.put("producer", new String[] {"enum"});
        expected.put("organization", new String[] {"type", "minLength", "pattern"});
        expected.put("entity", new String[] {"type", "required", "additionalProperties", "properties"});
        expected.put("stateVersion", new String[] {"type", "minimum", "maximum"});
        expected.put("event", new String[] {"enum"});
        expected.put("payload", new String[] {"type"});

        JsonNode properties = ENVELOPE.get("properties");
        assertEquals(new TreeSet<>(expected.keySet()), fieldNames(properties),
                "every envelope property must be pinned here — an unpinned property is one whose "
                        + "keywords no Java check mirrors");
        expected.forEach((name, keywords) ->
                assertOnlyKeywords("envelope." + name, properties.get(name), keywords));

        // entity is the one nested object, and its sub-properties get the same treatment.
        Map<String, String[]> entityExpected = new LinkedHashMap<>();
        entityExpected.put("kind", new String[] {"enum"});
        entityExpected.put("instanceId", new String[] {"type"});
        entityExpected.put("placementId", new String[] {"type"});
        entityExpected.put("solution", new String[] {"type"});

        JsonNode entityProperties = ENVELOPE.at("/properties/entity/properties");
        assertEquals(new TreeSet<>(entityExpected.keySet()), fieldNames(entityProperties),
                "every entity property must be pinned here too");
        entityExpected.forEach((name, keywords) ->
                assertOnlyKeywords("envelope.entity." + name, entityProperties.get(name), keywords));
    }

    @Test
    void organizationAndStateVersionBoundsMatchTheJavaChecks() {
        // EventEnvelope's compact constructor: stateVersion >= 0 <-> minimum 0; organization non-blank
        // <-> minLength 1 PLUS the "\S" pattern, which the schema now carries — the reviewed
        // contract change closed the R11 blank-organization drift ("   " satisfied minLength while Java's
        // isBlank() refused it) and the pattern pin below is what keeps it closed. The Java side is
        // code, not data, so the schema keywords are pinned here: relaxing or tightening either is a
        // contract change and must turn this red.
        //
        // Every pin reads through requireText/requireInt rather than bare asText()/asInt(): those
        // return "" and 0 for a MissingNode, and 0 is exactly stateVersion's expected minimum — so
        // an unguarded pin stays green when the keyword is DELETED from the schema.
        assertEquals("string",
                requireText(ENVELOPE.at("/properties/organization/type"), "organization.type"));
        assertEquals(1,
                requireInt(ENVELOPE.at("/properties/organization/minLength"), "organization.minLength"));
        assertEquals("\\S",
                requireText(ENVELOPE.at("/properties/organization/pattern"), "organization.pattern"),
                "organization must require a non-whitespace character — the schema half of EventEnvelope's"
                        + " isBlank() guard; removing it reopens the blank-organization drift (R11)");
        assertEquals("integer",
                requireText(ENVELOPE.at("/properties/stateVersion/type"), "stateVersion.type"));
        assertEquals(0,
                requireInt(ENVELOPE.at("/properties/stateVersion/minimum"), "stateVersion.minimum"));
        // The maximum IS Java's long-ness, pinned as a schema keyword: EventEnvelope.stateVersion is
        // a primitive `long`, so Jackson refuses 2**63 at read while a schema carrying only
        // `minimum: 0` accepted it in Python — the R12 boundary split, closed in the same direction
        // as R11's organization pattern (the schema hardens to Java, because Java's boundary is the real
        // one). Read through requireLong, never asInt(): asInt() truncates Long.MAX_VALUE to -1.
        assertEquals(Long.MAX_VALUE,
                requireLong(ENVELOPE.at("/properties/stateVersion/maximum"), "stateVersion.maximum"),
                "stateVersion's schema maximum must be Long.MAX_VALUE — schema validity and JVM "
                        + "representability have to agree, or Python accepts a document Java cannot read");
        assertEquals("object",
                requireText(ENVELOPE.at("/properties/payload/type"), "payload.type"));
    }

    // ---- composition ----------------------------------------------------------------------------

    /**
     * The notify catalog is only a complete event schema because it composes the envelope. Delete
     * the {@code allOf} and every branch still validates — the branches constrain {@code event},
     * {@code entity} and {@code payload} and say nothing about {@code eventId}, {@code producer},
     * {@code organization} or {@code stateVersion}, so the catalog silently stops requiring them. Neither
     * gate notices: the fixtures still pass (they carry the fields anyway) and the Java mirror
     * checks the branches, not the composition. Hence this pin.
     */
    @Test
    void theNotifyCatalogComposesTheEnvelopeExactlyOnce() {
        JsonNode allOf = NOTIFY.get("allOf");
        assertNotNull(allOf, "notify-events.schema.json must compose the envelope through allOf");
        assertEquals(1, allOf.size(),
                "the catalog composes exactly one schema — the envelope: " + allOf);
        assertEquals(requireText(ENVELOPE.get("$id"), "envelope.$id"),
                requireText(NOTIFY.at("/allOf/0/$ref"), "notify.allOf[0].$ref"),
                "allOf[0].$ref must be the envelope's own $id — a catalog that does not compose the "
                        + "envelope stops requiring eventId, producer, organization and stateVersion");
    }

    // ---- the seven branches ---------------------------------------------------------------------

    @Test
    void everyBranchMatchesTheJavaTableExactly() {
        Map<String, JsonNode> schemaBranches = branchesByEvent();
        assertEquals(sorted(NotifyEventShapes.handledEvents()),
                new TreeSet<>(schemaBranches.keySet()),
                "schema branches and Java branch table must name the same events");

        for (Map.Entry<String, JsonNode> e : schemaBranches.entrySet()) {
            String event = e.getKey();
            JsonNode branch = e.getValue();
            NotifyEventShapes.BranchRule java = NotifyEventShapes.BRANCHES.get(event);

            // A branch constrains three things and nothing else. The lock reads a branch by
            // navigating INTO /properties/{event,entity,payload}, so anything it does not navigate
            // to is invisible to it: a branch-level `producer: {"const":"cp-core"}` makes every
            // dp-core-produced event of that branch schema-invalid in Python while Java sees no
            // change at all. Pinned as set-equality at both levels so a fourth constrained property
            // — or a keyword beside `properties` — is a red rather than a silent narrowing.
            // Via assertOnlyKeywords, not a bare set-equality: annotations (description etc.) are
            // tolerated here exactly as everywhere else, and the failure message teaches "add a
            // pin", never "annotations are forbidden in branches".
            assertOnlyKeywords(event + " branch", branch, "properties");
            assertEquals(set("event", "entity", "payload"), fieldNames(branch.get("properties")),
                    event + ": a branch constrains exactly event, entity and payload — a property "
                            + "the Java table has no column for is unenforced Java-side");
            // The discriminator itself, closed for the same reason as everything below it: the lock
            // reads /properties/event/const and navigates nowhere else, so a keyword sitting beside
            // that const is Python-enforced and Java-blind.
            assertOnlyKeywords(event + " event", branch.at("/properties/event"), "const");

            // entity: kind const, required ids (minus 'kind'), forbidden ids from BOTH not-shapes
            JsonNode entity = branch.at("/properties/entity");

            // The branch's identity-scoping surface, keyword-closed at every level. Without this the
            // lock is containment on the branch side exactly as it was on the envelope side before
            // `everyEnvelopePropertyPinsItsKeywordSetExactly`: the lock reads entity.required,
            // entity.not and entity.properties.kind, so `"maxLength": 64` on instance.health's
            // entity.properties.instanceId is a narrowing the Python validator enforces, no Java
            // check honours, and both gates pass (design §3's rule: any keyword the lock does not
            // name is unenforced Java-side).
            //
            // `not` is allowed only when actually present — never blanket. Its CONTENT is locked
            // separately by forbiddenEntityIds below, and a branch that grows an empty `not` is
            // already a red there (the unparsed-shape fail: "entity 'not' uses a shape the lock
            // does not parse").
            assertOnlyKeywords(event + " entity", entity,
                    entity.has("not")
                            ? new String[] {"required", "properties", "not"}
                            : new String[] {"required", "properties"});
            assertEquals(set("kind"), fieldNames(entity.get("properties")),
                    event + ": a branch's entity constrains ONLY 'kind' — the Java table has a "
                            + "column for the kind const, for required ids and for forbidden ids, "
                            + "and none for a per-id schema constraint. An id constraint written at "
                            + "branch level is enforced in Python and mirrored by no Java check: "
                            + "model it in BranchRule in the same commit, or do not write it");
            assertOnlyKeywords(event + " entity.kind", entity.at("/properties/kind"), "const");

            assertEquals(java.kind(), entity.at("/properties/kind/const").asText(),
                    event + ": entity.kind const");
            Set<String> required = textSet(entity.get("required"));
            assertTrue(required.remove("kind"), event + ": entity.required must include 'kind'");
            assertEquals(sorted(java.requiredEntity()), required, event + ": required entity ids");
            assertEquals(sorted(java.forbiddenEntity()), forbiddenEntityIds(entity),
                    event + ": forbidden entity ids");

            // payload: required == properties == the Java key set; additionalProperties: false
            JsonNode payload = branch.at("/properties/payload");
            // The payload NODE itself, keyword-closed like every other node the lock navigates —
            // the R13 re-review measured the escape this closes: a `not` hung on this node (e.g.
            // forbidding state:GREEN) is Python-enforced, rejects an event Java's HEALTH_STATE
            // legitimately produces, and both gates stayed green.
            assertOnlyKeywords(event + " payload", payload,
                    "type", "required", "properties", "additionalProperties");
            assertTrue(isFalseBoolean(payload.get("additionalProperties")),
                    event + ": payload must stay additionalProperties: false");
            Set<String> javaKeys = sorted(java.payload().keySet());
            assertEquals(javaKeys, textSet(payload.get("required")), event + ": required payload");
            assertEquals(javaKeys, fieldNames(payload.get("properties")), event + ": payload keys");

            // every payload property's constraint == the Java FieldRule it routes to
            payload.get("properties").properties().forEach(prop -> assertFieldRule(event,
                    prop.getKey(), prop.getValue(), java.payload().get(prop.getKey())));
        }
    }

    /**
     * Classifies one schema payload property and asserts the Java rule is the same one. Fails on
     * any schema shape this method does not recognize — an unmapped shape must be a red, never a
     * silently unlocked field (L7).
     *
     * <p>Recognition is by <b>distinguishing keyword</b>, which by itself says nothing about the
     * keywords sitting BESIDE it: {@code {"type":"string","maxLength":5}} classifies as
     * {@code IsString} while the Java rule enforces no length at all — a narrowing the schema
     * declares and no producer-side check honours. Each recognized shape therefore asserts its
     * keyword set is <b>exactly</b> the keywords the Java rule mirrors.
     */
    private static void assertFieldRule(String event, String key, JsonNode schema,
            NotifyEventShapes.FieldRule java) {
        assertNotNull(java, event + ": no Java rule for payload." + key);
        String at = event + ": payload." + key;
        if (schema.has("enum")) {
            assertTrue(java instanceof NotifyEventShapes.EnumOf, at + " must be EnumOf, was " + java);
            assertOnlyKeywords(at, schema, "enum");
            assertEquals(textSet(schema.get("enum")),
                    sorted(((NotifyEventShapes.EnumOf) java).members()), at + " enum members");
        } else if ("string".equals(schema.path("type").asText())) {
            assertTrue(java instanceof NotifyEventShapes.IsString, at + " must be IsString, was " + java);
            assertOnlyKeywords(at, schema, "type");
        } else if ("integer".equals(schema.path("type").asText()) && schema.has("minimum")) {
            assertTrue(java instanceof NotifyEventShapes.IntegerAtLeast,
                    at + " must be IntegerAtLeast, was " + java);
            assertOnlyKeywords(at, schema, "type", "minimum", "maximum");
            assertEquals(requireLong(schema.path("minimum"), at + ".minimum"),
                    ((NotifyEventShapes.IntegerAtLeast) java).min(), at + " minimum");
            // The maximum is not a second bound the Java rule mirrors as data — it IS the Java rule's
            // own edge: `NotifyEventShapes.asIntegral` returns null past the 64-bit boundary, so a
            // schema saying only `minimum` accepted 2**63 in Python and refused it in Java (R12).
            // Pinned to Long.MAX_VALUE so schema validity and JVM representability agree.
            // path(), not get(): a deleted keyword must reach requireLong as a MissingNode and be
            // reported, never NPE here. (assertOnlyKeywords above would already have reddened, but a
            // pin that depends on another assertion running first is one refactor from an NPE.)
            assertEquals(Long.MAX_VALUE, requireLong(schema.path("maximum"), at + ".maximum"),
                    at + ": an IntegerAtLeast field must cap at Long.MAX_VALUE — asIntegral's 64-bit "
                            + "boundary is the real one, and the schema has to say so");
        } else if (schema.isObject() && schema.isEmpty()) {
            // No assertOnlyKeywords call here, deliberately: isEmpty() IS the exactness check —
            // the arm's own recognition condition is "carries no keywords at all", so a fifth
            // closure call would restate it. (Annotations would take this arm off {} and onto the
            // unrecognized-shape fail below; that is the right red — an annotated hole is a
            // contract edit.)
            // The pinned Known-gap-4 hole. Located by event const, never by oneOf index, so a
            // branch reorder cannot turn this assertion vacuous.
            assertTrue(java instanceof NotifyEventShapes.AnyType, at + " must stay the pinned {} "
                    + "any-type hole until the BE-D4 amendment types it — do not narrow either side");
        } else {
            fail(at + ": schema shape not recognized by the lock — extend assertFieldRule in the "
                    + "same commit that introduces the new shape: " + schema);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Every oneOf branch, keyed by its event const. Vacuity-guarded. */
    private static Map<String, JsonNode> branchesByEvent() {
        // The two hard `7`s below are deliberate friction, not a missed derivation: deriving them
        // from NotifyEventShapes.handledEvents() would make an eighth event pass this method
        // silently on the strength of the Java table alone. A v1 catalog change should have to
        // raise a literal in the lock consciously, in the same commit as the schema.
        JsonNode oneOf = NOTIFY.get("oneOf");
        assertNotNull(oneOf, "notify-events.schema.json must dispatch through oneOf");
        assertEquals(7, oneOf.size(), "the v1 notify catalog must have exactly seven branches");
        Map<String, JsonNode> byEvent = new LinkedHashMap<>();
        for (JsonNode branch : oneOf) {
            JsonNode constNode = branch.at("/properties/event/const");
            assertFalse(constNode.isMissingNode() || constNode.asText().isBlank(),
                    "every branch must carry an event const: " + branch);
            byEvent.put(constNode.asText(), branch);
        }
        assertEquals(7, byEvent.size(), "duplicate event consts across branches");
        return byEvent;
    }

    /** Both not-shapes the schema uses: not.required and not.anyOf[].required. */
    private static Set<String> forbiddenEntityIds(JsonNode entity) {
        Set<String> ids = new TreeSet<>();
        JsonNode not = entity.get("not");
        if (not == null) {
            return ids; // explicitly parsed as empty — valid (health, rollout, quarantine)
        }
        if (not.has("required")) {
            JsonNode required = not.get("required");
            if (required.isArray() && required.size() > 1) {
                // The semantics diverge and the lock would launder the divergence into equality:
                // { "not": { "required": ["a","b"] } } forbids only the COMBINATION — a document
                // carrying just 'a' still validates — while the Java forbidden set refuses EACH id
                // on its own. Flattening the array into `ids` makes the two look equal. This fires
                // on the two ways it could arrive: someone "tidying" catalog.package's not.anyOf
                // into one required array, or a singleton growing a second entry.
                fail("entity 'not': a multi-element not.required means \"not BOTH\", while the "
                        + "Java forbidden set means \"not EACH\" — the two are different rules and "
                        + "this lock cannot tell them apart. Express per-id prohibition as "
                        + "not.anyOf of single-element required arrays: " + not);
            }
            ids.addAll(textSet(required));
        } else if (not.has("anyOf")) {
            JsonNode anyOf = not.get("anyOf");
            if (!anyOf.isArray() || anyOf.isEmpty()) {
                fail("entity 'not': anyOf must be a non-empty array of singleton-required children, "
                        + "one per forbidden id: " + not);
            }
            for (JsonNode child : anyOf) {
                // The same not-BOTH / not-EACH divergence the not.required arm above rejects, one
                // level down — and the arm that was actually reachable, because collapsing the two
                // singletons of catalog.package into ONE child {"required":["instanceId",
                // "placementId"]} keeps the not.anyOf shape while flipping the meaning: the schema
                // then forbids only the document carrying BOTH ids, the Java forbidden set still
                // refuses EACH id on its own, and flattening the child's array into `ids` made the
                // two compare equal and the lock green. So each child must be EXACTLY
                // {"required": ["<one id>"]}: one key, one entry. Any other keyword on the child —
                // an annotation included, since here the object is a subschema and not a pinned
                // keyword set — is a shape this lock does not parse, and an unparsed shape must be
                // a red naming itself, never a silent pass (L7).
                JsonNode required =
                        child.isObject() && child.size() == 1 ? child.get("required") : null;
                if (required == null || !required.isArray() || required.size() != 1) {
                    fail("entity 'not': every not.anyOf child must be exactly one singleton "
                            + "{\"required\": [\"<id>\"]} — a multi-element anyOf child means "
                            + "\"not both\"; Java's forbidden set means \"not each\" — use one "
                            + "singleton child per forbidden id, and put nothing else on the "
                            + "child. Was: " + child + " in " + not);
                }
                // textSet asserts textuality per member: a non-string id is a shape the lock does
                // not model, and asText() would launder it into one.
                ids.addAll(textSet(required));
            }
        } else {
            fail("entity 'not' uses a shape the lock does not parse: " + not);
        }
        assertFalse(ids.isEmpty(), "a present 'not' must forbid at least one id: " + not);
        return ids;
    }

    private static Set<String> set(String... members) {
        return new TreeSet<>(Set.of(members));
    }

    /**
     * {@code false}, and boolean — never a schema object. {@code asBoolean()} alone returns
     * {@code false} for ANY non-boolean node, so {@code "additionalProperties": {"type":"string"}},
     * which PERMITS extra properties, would satisfy a bare {@code !asBoolean()} check.
     */
    private static boolean isFalseBoolean(JsonNode additionalProperties) {
        return additionalProperties != null
                && additionalProperties.isBoolean()
                && !additionalProperties.asBoolean();
    }

    /**
     * Annotation keywords: no validation semantics, so their presence can never be a narrowing the
     * Java side fails to mirror. Tolerated wherever the lock pins a keyword set — which is what
     * lets {@link #everyEnvelopePropertyPinsItsKeywordSetExactly} pin the envelope side too,
     * where {@code description} is in legitimate use.
     */
    private static final Set<String> ANNOTATION_KEYWORDS = Set.of("description", "$comment", "title");

    /** The exact keyword set a pinned schema object may carry, annotations aside — used on both the
     *  payload shapes ({@link #assertFieldRule}) and the envelope properties. */
    private static void assertOnlyKeywords(String at, JsonNode schema, String... allowed) {
        assertNotNull(schema, at + ": expected a schema object, found nothing");
        assertTrue(schema.isObject(), at + ": expected a schema object, was " + schema);
        Set<String> present = new TreeSet<>();
        schema.fieldNames().forEachRemaining(name -> {
            if (!ANNOTATION_KEYWORDS.contains(name)) {
                present.add(name);
            }
        });
        assertEquals(new TreeSet<>(Set.of(allowed)), present,
                at + ": the lock pins exactly the keywords the Java side mirrors — a keyword the "
                        + "lock does not name is a schema constraint no producer honours, so teach "
                        + "both sides in one commit");
    }

    /** A pinned string keyword, asserted PRESENT. {@code asText()} on a MissingNode returns "",
     *  and on a non-string node something else again; an unguarded pin can therefore stay green
     *  after the keyword is deleted from the schema. */
    private static String requireText(JsonNode node, String at) {
        assertTrue(node.isTextual(), at + " must be present in the schema and a string — a deleted "
                + "keyword has to be a red, not a default that happens to match. Was: " + node);
        return node.asText();
    }

    /** A pinned integer keyword, asserted PRESENT — the {@code stateVersion.minimum} case, where
     *  {@code asInt()}'s MissingNode default of 0 IS the expected value. */
    private static int requireInt(JsonNode node, String at) {
        assertTrue(node.isInt(), at + " must be present in the schema and an integer — a deleted "
                + "keyword has to be a red, not a default that happens to match. Was: " + node);
        return node.asInt();
    }

    /**
     * A pinned integer keyword that does NOT fit in an {@code int} — the two {@code maximum} pins,
     * both {@link Long#MAX_VALUE}. {@code asInt()} is unusable here: Jackson silently truncates an
     * out-of-int-range value, so {@code asInt()} on {@code 9223372036854775807} returns {@code -1}
     * and a pin written that way would compare two wrong numbers. {@code canConvertToLong()} is
     * asserted before {@code longValue()} for the same reason one level up — a {@code maximum} the
     * schema raised past the long boundary is precisely the drift these pins exist to catch, and it
     * must be a red rather than a silent truncation.
     */
    private static long requireLong(JsonNode node, String at) {
        assertTrue(node.isIntegralNumber(), at + " must be present in the schema and an integer — a "
                + "deleted keyword has to be a red, not a default that happens to match. Was: " + node);
        assertTrue(node.canConvertToLong(), at + " must fit in a Java long — a bound outside the "
                + "64-bit range is exactly the schema/Java divergence this pin guards. Was: " + node);
        return node.longValue();
    }

    /** The record's component names — the Java wire shape, read reflectively so it is never
     *  restated as a test literal. Vacuity-guarded: a non-record or empty record is a red. */
    private static Set<String> recordComponents(Class<? extends Record> record) {
        java.lang.reflect.RecordComponent[] components = record.getRecordComponents();
        assertNotNull(components, record + " is not a record");
        assertTrue(components.length > 0, record + " has no components");
        Set<String> out = new TreeSet<>();
        for (java.lang.reflect.RecordComponent c : components) {
            out.add(c.getName());
        }
        return out;
    }

    private static Set<String> sorted(java.util.Collection<String> c) {
        return new TreeSet<>(c);
    }

    /**
     * A schema array of STRINGS — every {@code enum} and {@code required} the lock reads. Textuality
     * is asserted, not assumed: {@code asText()} stringifies any node, so a numeric enum member
     * {@code 1} would silently become {@code "1"} and compare equal to a Java enum member spelled
     * {@code "1"} — and, more to the point, every Java side this feeds ({@code EnumOf.members()},
     * record component names) is {@code String}-typed, so a non-textual member is a schema shape
     * this lock does not model at all. That has to be a red naming the shape, never a coercion.
     */
    private static Set<String> textSet(JsonNode array) {
        assertNotNull(array, "expected a JSON array, found nothing");
        assertTrue(array.isArray() && !array.isEmpty(), "expected a non-empty array: " + array);
        Set<String> out = new TreeSet<>();
        array.forEach(n -> {
            assertTrue(n.isTextual(), "a non-string enum/required member is a schema shape the lock "
                    + "does not model — Java's enums and key sets are strings, so asText() would "
                    + "launder it into one. Teach both sides in the same commit. Was: " + n
                    + " in " + array);
            out.add(n.asText());
        });
        return out;
    }

    private static Set<String> fieldNames(JsonNode object) {
        assertNotNull(object, "expected a JSON object, found nothing");
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        assertFalse(out.isEmpty(), "expected a non-empty object");
        return out;
    }
}
