package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * BE-D10 lock for the fleet slice (plan 010 T11.2): the wire records mirror {@code contracts/openapi/console.yaml}'s fleet
 * schemas - equality of properties and of what is required, not a presence check. A property the contract expresses but a
 * record does not represent is a contract split ({@link SchemaMirrorLockTest} is the same lock for the earlier slices; it is
 * not extended here because the fleet schemas are this task's).
 *
 * <p>{@code FleetRefused} is checked as a SUBSET of {@code Problem} plus {@code refusedMembers}: the body of a refused fleet
 * never carries {@code detail} or {@code instance}, both optional on {@code Problem}.
 */
class FleetSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();

    @Test
    void theRequestMirrorsFleetApplyRequestIncludingItsBoundsAndDefaults() {
        JsonNode schema = schema("FleetApplyRequest");
        assertEquals(names(schema.get("properties")), components(FleetApplyRequest.class));
        assertEquals(strings(schema.get("required")), Set.of("solutionId", "members", "strategy"),
                "the contract's own required list, pinned so a change to it is seen here");
        assertFalse(schema.path("additionalProperties").asBoolean(true), "closed: a stray key is a 400 (I5)");
        assertEquals(FleetApplyRequest.MAX_MEMBERS, schema.at("/properties/members/maxItems").asInt());
        assertEquals(1, schema.at("/properties/members/minItems").asInt());
        assertEquals(1, schema.at("/properties/batchSize/default").asInt(), "Q-F4");
        assertEquals("halt-remaining", schema.at("/properties/onFailure/default").asText(), "Q-F4");
        assertFalse(schema.at("/properties/proceedWithAuthorized/default").asBoolean(true), "OR-57");
        assertEquals(strings(schema.at("/properties/onFailure/enum")), FleetApplyRequest.ON_FAILURE);
        JsonNode member = schema.at("/properties/members/items");
        assertEquals(strings(member.get("required")), Set.of("instanceId", "placementId", "generation"));
        assertEquals(names(member.get("properties")), components(FleetApplyRequest.Member.class));
    }

    @Test
    void theResponsesMirrorTheirSchemasExactly() {
        for (Object[] each : new Object[][] {{"FleetMember", FleetMember.class}, {"FleetAccepted", FleetAccepted.class},
                {"FleetObservation", FleetObservation.class}}) {
            JsonNode schema = schema((String) each[0]);
            Class<?> type = (Class<?>) each[1];
            assertEquals(flattenedProperties(schema), components(type), each[0] + " properties");
            assertEquals(flattenedRequired(schema), requiredComponents(type), each[0] + " required");
        }
    }

    @Test
    void theEnumeratedValuesAreTheContractsAndTheDatabases() {
        assertEquals(Set.of("refused", "pending", "admitted", "not_admitted", "skipped"),
                strings(schema("FleetMember").at("/properties/disposition/enum")));
        assertEquals(Set.of("succeeded", "failed"), strings(schema("FleetMember").at("/properties/settled/enum")));
        Set<String> states = Set.of("RUNNING", "SUCCEEDED", "COMPLETED_WITH_FAILURES", "HALTED");
        assertEquals(states, strings(flattenedSchema(schema("FleetAccepted")).at("/state/enum")));
        assertEquals(states, strings(flattenedSchema(schema("FleetObservation")).at("/state/enum")));
    }

    @Test
    void aRefusedFleetIsProblemPlusRefusedMembers() {
        JsonNode schema = schema("FleetRefused");
        Set<String> contract = flattenedProperties(schema);
        assertTrue(contract.containsAll(components(FleetRefusedBody.class)),
                "every component of the body is a contract property: " + components(FleetRefusedBody.class));
        assertTrue(contract.contains("refusedMembers"));
        assertTrue(components(FleetRefusedBody.class).containsAll(flattenedRequired(schema)),
                "and every property the contract requires is present");
        JsonNode refused = flattenedSchema(schema).at("/refusedMembers/items");
        assertEquals(strings(refused.get("required")), Set.of("ordinal", "instanceId", "placementId", "code"));
        assertEquals(names(refused.get("properties")), components(FleetRefusedException.RefusedMember.class));
        assertEquals(Set.of("FORBIDDEN", "NOT_FOUND"), strings(refused.at("/properties/code/enum")));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static JsonNode schema(String name) {
        JsonNode result = SPEC.at("/components/schemas/" + name);
        assertTrue(!result.isMissingNode(), "missing schema " + name);
        return result;
    }

    /** The schema with every {@code $ref} resolved and every {@code allOf} merged: {@code properties} only. */
    private static JsonNode flattenedSchema(JsonNode schema) {
        com.fasterxml.jackson.databind.node.ObjectNode merged = new ObjectMapper().createObjectNode();
        collect(schema, merged, new TreeSet<>());
        return merged;
    }

    private static Set<String> flattenedProperties(JsonNode schema) {
        return names(flattenedSchema(schema));
    }

    private static Set<String> flattenedRequired(JsonNode schema) {
        Set<String> required = new TreeSet<>();
        collect(schema, new ObjectMapper().createObjectNode(), required);
        return required;
    }

    private static void collect(JsonNode node, com.fasterxml.jackson.databind.node.ObjectNode properties,
            Set<String> required) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            collect(SPEC.at("/components/schemas/" + ref.substring(ref.lastIndexOf('/') + 1)), properties, required);
        }
        node.path("allOf").forEach(part -> collect(part, properties, required));
        node.path("properties").fields().forEachRemaining(e -> properties.set(e.getKey(), e.getValue()));
        node.path("required").forEach(r -> required.add(r.asText()));
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Set<String> components(Class<?> type) {
        assertTrue(type.isRecord(), type.getSimpleName() + " must be a record");
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> requiredComponents(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .filter(c -> Arrays.stream(c.getDeclaredAnnotations())
                        .noneMatch(a -> a.annotationType().getSimpleName().equals("WireOptional")))
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static JsonNode readSpec() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/openapi/console.yaml");
            if (Files.isRegularFile(candidate)) {
                try {
                    return new ObjectMapper(new YAMLFactory()).readTree(candidate.toFile());
                } catch (IOException e) {
                    throw new IllegalStateException("cannot parse " + candidate, e);
                }
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("cannot locate contracts/openapi/console.yaml from "
                + Path.of("").toAbsolutePath());
    }
}
