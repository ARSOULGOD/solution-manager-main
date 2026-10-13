package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.dp.api.readback.NotAcceptedReason;
import com.solace.sm.dp.api.secret.SecretCompare;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * BE-D10 lock for {@code acceptRuntimeConfig} (plan 010 T10.4): equality between the contract's schemas and the Java wire
 * records, in both directions, not a presence check. Kept beside {@link SchemaMirrorLockTest} rather than in it so the
 * lock for one operation is one reviewable file; it reads the same {@code console.yaml}.
 */
class AcceptRuntimeSchemaLockTest {

    private static final JsonNode SPEC = readSpec();

    @Test
    void theHandlerSignatureIsTheContractsOperation() throws Exception {
        Method handler = AcceptRuntimeController.class.getMethod("acceptRuntimeConfig", java.util.UUID.class,
                AcceptRuntimeRequest.class, com.solace.sm.cp.api.session.Session.class);
        assertEquals("org.springframework.http.ResponseEntity<com.solace.sm.gateway.http.AcceptedRuntimeConfig>",
                handler.getGenericReturnType().getTypeName());
        ConsoleOperation operation = handler.getAnnotation(ConsoleOperation.class);
        assertEquals("acceptRuntimeConfig", operation.value());
        JsonNode contract = SPEC.at("/paths/~1dp~1instances~1{instanceId}~1config~1accept-runtime/post");
        assertEquals("acceptRuntimeConfig", contract.get("operationId").asText());
        assertEquals("config.edit", contract.get("x-sm-permission").asText());
        assertEquals(Set.of("config.overrideWarning"), texts(contract.get("x-sm-permission-also")),
                "the second verb the orchestrator requires");
        assertTrue(contract.at("/responses/503").has("$ref"), "an unreadable host is a declared 503");
    }

    @Test
    void theRequestRecordIsTheRequestSchemaExactly() {
        JsonNode request = SPEC.at("/paths/~1dp~1instances~1{instanceId}~1config~1accept-runtime/post/requestBody"
                + "/content/application~1json/schema");
        assertEquals(texts(request.get("required")), required(AcceptRuntimeRequest.class));
        assertEquals(fieldNames(request.get("properties")), components(AcceptRuntimeRequest.class));
        assertFalse(request.get("additionalProperties").asBoolean(), "a stray key is a 400, so the schema closes the object");
    }

    @Test
    void theResponseRecordIsTheResponseSchemaExactly() {
        JsonNode schema = SPEC.at("/components/schemas/AcceptedRuntimeConfig");
        Set<String> required = new TreeSet<>();
        Set<String> properties = new TreeSet<>();
        flatten(schema, required, properties);
        assertEquals(required, required(AcceptedRuntimeConfig.class), "AcceptedRuntimeConfig required");
        assertEquals(properties, components(AcceptedRuntimeConfig.class), "AcceptedRuntimeConfig properties");

        JsonNode entry = SPEC.at("/components/schemas/AcceptedRuntimeConfig/allOf/1/properties/notAccepted/items");
        assertEquals(texts(entry.get("required")), required(AcceptedRuntimeConfig.NotAccepted.class));
        assertEquals(fieldNames(entry.get("properties")), components(AcceptedRuntimeConfig.NotAccepted.class));
        assertFalse(entry.get("additionalProperties").asBoolean());
        assertFalse(entry.get("properties").has("value"), "I6: no value-bearing property can exist on the wire type");
    }

    @Test
    void theReasonAndComparisonWordsAreTheDataPlanesClosedSetsAndNothingElse() {
        JsonNode entry = SPEC.at("/components/schemas/AcceptedRuntimeConfig/allOf/1/properties/notAccepted/items");
        assertEquals(Arrays.stream(NotAcceptedReason.values()).map(NotAcceptedReason::wire)
                .collect(Collectors.toCollection(TreeSet::new)), texts(entry.at("/properties/reason/enum")));
        assertEquals(Arrays.stream(SecretCompare.Outcome.values()).filter(o -> o != SecretCompare.Outcome.UNAVAILABLE)
                .map(o -> o.name().toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toCollection(TreeSet::new)),
                texts(entry.at("/properties/comparison/enum")),
                "an UNAVAILABLE comparison makes no claim, so it has no wire word");
        assertEquals(200, SPEC.at("/components/schemas/AcceptedRuntimeConfig/allOf/1/properties/notAccepted/maxItems").asInt());
    }

    @Test
    void theServiceUnavailableTextIsWidenedToADependencyThatDidNotAnswer() {
        String description = SPEC.at("/components/schemas/ErrorCode/description").asText();
        assertTrue(description.contains("`SERVICE_UNAVAILABLE` (503) — retryable; a dependency did not answer"), description);
        assertTrue(SPEC.at("/components/responses/ServiceUnavailable/description").asText().contains("a dependency did not answer"));
        assertEquals(503, ErrorCode.SERVICE_UNAVAILABLE.status());
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static void flatten(JsonNode schema, Set<String> required, Set<String> properties) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            flatten(SPEC.at("/components/schemas/" + ref.substring("#/components/schemas/".length())), required, properties);
            return;
        }
        required.addAll(texts(schema.get("required")));
        properties.addAll(fieldNames(schema.get("properties")));
        if (schema.has("allOf")) {
            schema.get("allOf").forEach(part -> flatten(part, required, properties));
        }
    }

    private static Set<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> required(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .filter(c -> Arrays.stream(c.getDeclaredAnnotations())
                        .noneMatch(a -> a.annotationType().getSimpleName().equals("WireOptional")))
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> out = new TreeSet<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> out = new TreeSet<>();
        if (object != null) {
            object.fieldNames().forEachRemaining(out::add);
        }
        return out;
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
        throw new IllegalStateException("cannot locate contracts/openapi/console.yaml");
    }
}
