package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * BE-D10 lock for the first executable OpenAPI slice. This is equality, not a presence check:
 * validation the contract expresses but the Java types do not represent is a contract split.
 */
class SchemaMirrorLockTest {

    private static final JsonNode SPEC = readSpec();

    @Test
    void everyAuthOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.AuthConsoleApi");
        assertEquals(Set.of("login", "logout", "getIdentity", "rotateOwnCredential"),
                operationIdsUnder("/cp/auth/"));
        assertMethod(handler, "login", "LoginRequest", "Identity");
        assertMethod(handler, "logout", "String", "void");
        assertMethod(handler, "getIdentity", "Identity");
        assertMethod(handler, "rotateOwnCredential", "RotateOwnCredentialRequest", "String", "void");
    }

    @Test
    void authRequestRecordsMatchRequiredFieldsAndBoundsExactly() {
        assertRequestRecord("/cp/auth/login", "LoginRequest", Map.of(
                "username", new String[] {"type", "minLength", "maxLength"},
                "credential", new String[] {"type", "minLength", "format"}));
        assertRequestRecord("/cp/auth/credential", "RotateOwnCredentialRequest", Map.of(
                "currentCredential", new String[] {"type", "format"},
                "newCredential", new String[] {"type", "format", "minLength"}));
        JsonNode login = requestSchema("/cp/auth/login");
        assertRejected("LoginRequest", new Object[] {"", "credential"});
        assertRejected("LoginRequest", new Object[] {
                "x".repeat(login.at("/properties/username/maxLength").asInt() + 1), "credential"});
        assertRejected("LoginRequest", new Object[] {"operator", ""});
        assertRejected("RotateOwnCredentialRequest", new Object[] {"current", "x".repeat(
                requestSchema("/cp/auth/credential").at("/properties/newCredential/minLength").asInt() - 1)});
    }

    @Test
    void identityResponseMatchesItsRequirednessAndEnums() {
        JsonNode identity = schema("Identity");
        assertTrue(identity.path("allOf").isMissingNode(), "Identity is not a notify-versioned entity");

        Class<?> identityType = requireClass("com.solace.sm.gateway.http.Identity");
        assertEquals(flattenedRequired(identity), requiredRecordComponents(identityType));
        assertEquals(flattenedProperties(identity), recordComponents(identityType));
        assertEquals(textSet(flattenedProperties(identity).contains("source")
                        ? flattenedSchema(identity, "source").get("enum") : null),
                wireValues(requireClass("com.solace.sm.gateway.http.IdentitySource")));
        assertEquals(flattenedRequired(schema("ScopePath")),
                requiredRecordComponents(requireClass("com.solace.sm.gateway.http.ScopePath")));
        assertEquals(fieldNames(schema("ScopePath").get("properties")),
                recordComponents(requireClass("com.solace.sm.gateway.http.ScopePath")));
    }

    @Test
    void optionalScopeFieldsUseJacksonOmission() {
        Class<?> scopePath = requireClass("com.solace.sm.gateway.http.ScopePath");
        Object emptyScopePath = construct(scopePath, new Object[] {null, null, null, null, null});
        assertEquals(Set.of(), fieldNames(new ObjectMapper().valueToTree(emptyScopePath)),
                "all schema-optional ScopePath fields must be omitted on the wire when absent");

    }

    @Test
    void authRequestToStringsRedactCredentials() {
        String loginSecret = "login-secret-sentinel";
        String currentSecret = "current-secret-sentinel";
        String newSecret = "new-secret-sentinel";

        String login = new LoginRequest("operator", loginSecret).toString();
        String rotation = new RotateOwnCredentialRequest(currentSecret, newSecret).toString();

        assertFalse(login.contains(loginSecret), "login toString must not leak credential material");
        assertFalse(rotation.contains(currentSecret), "rotation toString must not leak currentCredential");
        assertFalse(rotation.contains(newSecret), "rotation toString must not leak newCredential");
        assertTrue(login.contains("[REDACTED]"), "login toString must make redaction explicit");
        assertTrue(rotation.contains("[REDACTED]"), "rotation toString must make redaction explicit");
    }

    @Test
    void authRequestJsonWireShapeRemainsEqualToOpenApi() {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode login = mapper.valueToTree(new LoginRequest("operator", "login-secret"));
        JsonNode rotation = mapper.valueToTree(new RotateOwnCredentialRequest("current-secret", "new-secret-value"));

        assertEquals(fieldNames(requestSchema("/cp/auth/login").get("properties")), fieldNames(login));
        assertEquals(fieldNames(requestSchema("/cp/auth/credential").get("properties")), fieldNames(rotation));
        assertEquals("login-secret", login.path("credential").asText());
        assertEquals("current-secret", rotation.path("currentCredential").asText());
        assertEquals("new-secret-value", rotation.path("newCredential").asText());
    }

    @Test
    void nonLiveAvailabilityRequiresANonBlankReason() {
        assertThrows(IllegalArgumentException.class,
                () -> new Availability(AvailabilityState.DEGRADED, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Availability(AvailabilityState.UNAVAILABLE, null, "  ", null));
        assertDoesNotThrow(() -> new Availability(AvailabilityState.LIVE, null, null, null));
        assertDoesNotThrow(() -> new Availability(AvailabilityState.DEGRADED, null, "broker unavailable", null));
    }

    @Test
    void identitySourceSerializesItsOpenApiWireValues() {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("local", mapper.valueToTree(IdentitySource.LOCAL).asText());
        assertEquals("idp", mapper.valueToTree(IdentitySource.IDP).asText());
    }

    @Test
    void identityToStringRedactsCsrfTokenWithoutChangingJson() {
        String csrfToken = "csrf-secret-sentinel";
        Identity identity = new Identity(UUID.randomUUID(), "operator", IdentitySource.LOCAL,
                false, java.util.List.of(), csrfToken);

        assertFalse(identity.toString().contains(csrfToken), "Identity toString must not leak csrfToken");
        assertTrue(identity.toString().contains("[REDACTED]"), "Identity toString must make redaction explicit");
        JsonNode json = new ObjectMapper().valueToTree(identity);
        assertEquals("csrf-secret-sentinel", json.path("csrfToken").asText());
        assertEquals(flattenedRequired(schema("Identity")), fieldNames(json));

        assertEquals(flattenedProperties(schema("Identity")), fieldNames(json));
    }

    private static void assertRequestRecord(String path, String typeName, Map<String, String[]> expectedKeywords) {
        JsonNode body = requestSchema(path);
        assertTrue(body.isObject(), path + " must declare an application/json request body");
        assertEquals(expectedKeywords.keySet(), textSet(body.get("required")), path + " required fields");
        assertEquals(expectedKeywords.keySet(), fieldNames(body.get("properties")), path + " properties");
        Class<?> type = requireClass("com.solace.sm.gateway.http." + typeName);
        assertEquals(expectedKeywords.keySet(), requiredRecordComponents(type), typeName + " required components");
        expectedKeywords.forEach((field, keywords) -> assertEquals(Set.of(keywords),
                fieldNames(body.at("/properties/" + field)), path + "." + field + " keywords"));
    }

    private static void assertRejected(String typeName, Object[] arguments) {
        Class<?> type = requireClass("com.solace.sm.gateway.http." + typeName);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> type.getDeclaredConstructors()[0].newInstance(arguments));
        assertTrue(failure.getCause() instanceof IllegalArgumentException,
                typeName + " must enforce its OpenAPI string bounds");
    }

    private static JsonNode requestSchema(String path) {
        return SPEC.at("/paths/" + pointer(path) + "/post/requestBody/content/application~1json/schema");
    }

    private static void assertMethod(Class<?> type, String name, String... simpleTypesAndReturn) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " lacks " + name));
        String expectedReturn = simpleTypesAndReturn[simpleTypesAndReturn.length - 1];
        assertEquals(expectedReturn, method.getReturnType().getSimpleName(), name + " return type");
        assertEquals(Arrays.asList(simpleTypesAndReturn).subList(0, simpleTypesAndReturn.length - 1),
                Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList(), name + " parameters");
    }

    private static Set<String> wireValues(Class<?> enumType) {
        try {
            Method wireValue = enumType.getDeclaredMethod("wireValue");
            return Arrays.stream(enumType.getEnumConstants())
                    .map(value -> invoke(wireValue, value))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(enumType.getSimpleName() + " must expose its wire values", e);
        }
    }

    private static String invoke(Method method, Object value) {
        try {
            return (String) method.invoke(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?> requireClass(String name) {
        return assertDoesNotThrow(() -> Class.forName(name), name + " must exist");
    }

    private static Set<String> recordComponents(Class<?> type) {
        assertTrue(type.isRecord(), type.getSimpleName() + " must be a record");
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> requiredRecordComponents(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .filter(component -> Arrays.stream(component.getDeclaredAnnotations())
                        .noneMatch(annotation -> annotation.annotationType().getSimpleName().equals("WireOptional")))
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> flattenedProperties(JsonNode schema) {
        return fieldNames(flattenedSchema(schema));
    }

    private static Set<String> flattenedRequired(JsonNode schema) {
        Set<String> result = new TreeSet<>();
        flatten(schema, result, new TreeSet<>());
        return result;
    }

    private static JsonNode flattenedSchema(JsonNode schema, String property) {
        JsonNode direct = schema.path("properties").get(property);
        if (direct != null) {
            return direct;
        }
        for (JsonNode part : schema.path("allOf")) {
            JsonNode found = flattenedSchema(resolve(part), property);
            if (!found.isMissingNode()) {
                return found;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private static JsonNode flattenedSchema(JsonNode schema) {
        com.fasterxml.jackson.databind.node.ObjectNode result = new ObjectMapper().createObjectNode();
        flattenProperties(schema, result);
        return result;
    }

    private static void flattenProperties(JsonNode schema, com.fasterxml.jackson.databind.node.ObjectNode result) {
        schema.path("allOf").forEach(part -> flattenProperties(resolve(part), result));
        schema.path("properties").fields().forEachRemaining(field -> result.set(field.getKey(), field.getValue()));
    }

    private static void flatten(JsonNode schema, Set<String> result, Set<String> visitedReferences) {
        schema.path("allOf").forEach(part -> flatten(resolve(part), result, visitedReferences));
        result.addAll(textSetOrEmpty(schema.get("required")));
    }

    private static JsonNode resolve(JsonNode schema) {
        if (!schema.has("$ref")) {
            return schema;
        }
        String reference = schema.path("$ref").asText();
        assertTrue(reference.startsWith("#/components/schemas/"), "unsupported schema reference " + reference);
        return schema(reference.substring("#/components/schemas/".length()));
    }

    private static Object construct(Class<?> type, Object[] arguments) {
        try {
            return type.getDeclaredConstructors()[0].newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot construct " + type.getSimpleName(), e);
        }
    }

    private static Set<String> operationIdsUnder(String prefix) {
        Set<String> result = new TreeSet<>();
        SPEC.path("paths").fields().forEachRemaining(path -> {
            if (path.getKey().startsWith(prefix)) {
                path.getValue().fields().forEachRemaining(operation -> {
                    if (operation.getValue().has("operationId")) {
                        result.add(operation.getValue().path("operationId").asText());
                    }
                });
            }
        });
        return result;
    }

    private static JsonNode schema(String name) {
        JsonNode result = SPEC.at("/components/schemas/" + name);
        assertTrue(!result.isMissingNode(), "missing schema " + name);
        return result;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static Set<String> textSet(JsonNode node) {
        assertNotNull(node, "expected an array");
        return textSetOrEmpty(node);
    }

    private static Set<String> textSetOrEmpty(JsonNode node) {
        Set<String> result = new TreeSet<>();
        if (node == null) {
            return result;
        }
        node.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static String pointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
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
