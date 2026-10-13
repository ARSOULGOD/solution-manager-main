package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.cp.api.authz.ConsoleOperations;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Plan 013 T1.5's slice of the BE-D10 lock: the four operations on an existing target and the schemas they use, against
 * {@code contracts/openapi/console.yaml}, as equality and not presence (the registration's three are
 * {@code TargetRegistrationSchemaMirrorTest}).
 */
class TargetManagementSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();
    private static final String PIN = "SHA256:" + "A".repeat(43);

    // ------------------------------------------------------------------------------------------- operations

    @Test
    void theFourOperationsAreWhereTheContractPutsThemWithTheContractsVerbAndScope() throws Exception {
        Class<?> controller = TargetManagementController.class;
        assertOperation(controller, "editTargetConnection", "patch", "/dp/targets/{targetId}/connection", "target.edit",
                PatchMapping.class, "/api/dp/targets/{targetId}/connection");
        assertOperation(controller, "rotateTargetCredential", "put", "/dp/targets/{targetId}/credential", "target.edit",
                PutMapping.class, "/api/dp/targets/{targetId}/credential");
        assertOperation(controller, "testTargetConnection", "post", "/dp/targets/{targetId}/connection/test",
                "target.use", PostMapping.class, "/api/dp/targets/{targetId}/connection/test");
        assertOperation(controller, "retireTarget", "post", "/cp/targets/{targetId}/retire", "target.edit",
                PostMapping.class, "/api/cp/targets/{targetId}/retire");
    }

    private static void assertOperation(Class<?> controller, String operationId, String verb, String contractPath,
            String permission, Class<? extends java.lang.annotation.Annotation> mapping, String mapped) {
        Method handler = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(operationId)).findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + operationId));
        ConsoleOperation annotation = AnnotatedElementUtils.findMergedAnnotation(handler, ConsoleOperation.class);
        assertEquals(operationId, annotation.value());
        assertEquals(TargetResolver.class, annotation.resource(), operationId + ": the target's own scope, from the path");
        assertTrue(handler.isAnnotationPresent(mapping), operationId + " mapping verb");
        String path = "/api" + switch (mapping.getSimpleName()) {
            case "PatchMapping" -> handler.getAnnotation(PatchMapping.class).value()[0];
            case "PutMapping" -> handler.getAnnotation(PutMapping.class).value()[0];
            default -> handler.getAnnotation(PostMapping.class).value()[0];
        };
        assertEquals(mapped, path);

        JsonNode operation = SPEC.at("/paths/" + pointer(contractPath) + "/" + verb);
        assertEquals(operationId, operation.path("operationId").asText(), "the contract names the operation here");
        assertEquals(permission, operation.path("x-sm-permission").asText());
        assertEquals("named", operation.path("x-sm-scope").asText());
        assertEquals(permission, ConsoleOperations.permissionFor(operationId).orElseThrow().id(),
                operationId + ": the server's verb is the contract's");
        assertEquals("/api" + contractPath, path, "the contract's path beneath the /api server");
    }

    @Test
    void onlyTheRotationIsCredentialBearing() {
        for (Method method : TargetManagementController.class.getDeclaredMethods()) {
            ConsoleOperation operation = method.getAnnotation(ConsoleOperation.class);
            if (operation == null) {
                continue;
            }
            assertEquals(operation.value().equals("rotateTargetCredential"),
                    method.isAnnotationPresent(CredentialBearing.class), operation.value());
        }
    }

    // ---------------------------------------------------------------------------------------------- schemas

    @Test
    void theRequestSchemasAndRecordsAgreeOnPropertiesAndForbidAdditionalOnes() {
        for (var pair : new Object[][] {{"EditTargetConnectionRequest", EditTargetConnectionRequest.class},
                {"RotateTargetCredentialRequest", RotateTargetCredentialRequest.class}}) {
            JsonNode schema = schema((String) pair[0]);
            assertFalse(schema.path("additionalProperties").asBoolean(true), pair[0] + " forbids additional properties");
            assertEquals(propertyNames(schema), components((Class<?>) pair[1]), pair[0] + " properties");
        }
    }

    @Test
    void theResponseSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        responseMirrors("RotatedCredential", RotatedCredential.class);
        responseMirrors("TargetConnectionTest", ConnectionTestResult.class);
        responseMirrors("TargetConnectionDocker", ConnectionTestResult.DockerCheck.class);
        responseMirrors("Target", Target.class);
    }

    private static void responseMirrors(String schemaName, Class<?> record) {
        JsonNode schema = schema(schemaName);
        assertEquals(propertyNames(schema), components(record), schemaName + " properties");
        assertEquals(textSet(schema.path("required")), requiredComponents(record), schemaName + " required");
    }

    @Test
    void theEditRequiresOneFieldAndTheRecordsAgree() {
        JsonNode edit = schema("EditTargetConnectionRequest");
        assertEquals(1, edit.path("minProperties").asInt());
        assertEquals(Set.of("host", "port", "sshUser", "hostKeyFingerprint"), propertyNames(edit));
        assertTrue(textSet(edit.path("required")).isEmpty(), "every field is optional");
        assertThrows(IllegalArgumentException.class, () -> new EditTargetConnectionRequest(null, null, null, null));
        assertDoesNotThrow(() -> new EditTargetConnectionRequest(null, null, "deploy", null));
        assertDoesNotThrow(() -> new EditTargetConnectionRequest(null, 2222, null, null));
        assertThrows(IllegalArgumentException.class, () -> new EditTargetConnectionRequest("", null, null, null));
        assertEquals(254, edit.at("/properties/host/maxLength").asInt());
        assertThrows(IllegalArgumentException.class,
                () -> new EditTargetConnectionRequest("h".repeat(255), null, null, null));
        assertDoesNotThrow(() -> new EditTargetConnectionRequest(null, null, null, PIN));
        // the envelope is not a property, so it cannot be named
        assertFalse(propertyNames(edit).contains("allowedConfigRoot") || propertyNames(edit).contains("allowedContainerPrefix"));
        assertFalse(propertyNames(edit).contains("privateKey") || propertyNames(edit).contains("organizationId"));
    }

    @Test
    void theRotationRequestLengthsAreTheContractsAndTheKeyIsWriteOnly() {
        JsonNode key = schema("RotateTargetCredentialRequest").at("/properties/privateKey");
        assertEquals(RegisterTargetRequest.MAX_PRIVATE_KEY_CHARS, key.path("maxLength").asInt());
        assertTrue(key.path("writeOnly").asBoolean(), "I6: the key is write-only in the contract");
        assertEquals(Set.of("privateKey"), textSet(schema("RotateTargetCredentialRequest").path("required")));
        assertDoesNotThrow(() -> new RotateTargetCredentialRequest("k".repeat(16384)));
        assertThrows(IllegalArgumentException.class, () -> new RotateTargetCredentialRequest("k".repeat(16385)));
        assertThrows(IllegalArgumentException.class, () -> new RotateTargetCredentialRequest(""));
        assertThrows(IllegalArgumentException.class, () -> new RotateTargetCredentialRequest(null));
    }

    @Test
    void retiredAtIsOptionalReadOnlyAndAbsentFromEveryLiveTarget() {
        JsonNode retired = schema("Target").at("/properties/retiredAt");
        assertEquals("date-time", retired.path("format").asText());
        assertTrue(retired.path("readOnly").asBoolean());
        assertFalse(textSet(schema("Target").path("required")).contains("retiredAt"));
        Target live = new Target(java.util.UUID.randomUUID(), "n", TargetKind.DOCKER, TargetTransport.SSH_DOCKER,
                new ScopePath(java.util.UUID.randomUUID(), null, null, null, null), null, null);
        assertEquals(null, live.retiredAt());
    }

    @Test
    void noResponseSchemaOfTheseOperationsCanCarryKeyMaterial() {
        for (String name : new String[] {"RotatedCredential", "TargetConnectionTest", "TargetConnectionDocker"}) {
            Set<String> names = propertyNames(schema(name));
            for (String forbidden : new String[] {"privateKey", "credentialRef", "credential_ref", "secret", "hash",
                    "credentialHash", "host", "message", "error"}) {
                assertFalse(names.contains(forbidden), name + " must not carry " + forbidden);
            }
        }
    }

    @Test
    void theContractDeclaresEveryStatusTheGatewayCanAnswerForTheseOperations() {
        assertStatuses("/dp/targets/{targetId}/connection", "patch", Set.of("200", "400", "401", "403", "404", "409", "422", "429", "503"));
        assertStatuses("/dp/targets/{targetId}/credential", "put", Set.of("200", "400", "401", "403", "404", "409", "422"));
        assertStatuses("/dp/targets/{targetId}/connection/test", "post", Set.of("200", "400", "401", "403", "404", "409", "429"));   // 429: W4C-4; 409: TARGET_TRANSPORT_UNSUPPORTED (plan 015 T3.5)
        assertStatuses("/cp/targets/{targetId}/retire", "post", Set.of("200", "400", "401", "403", "404", "409"));
    }

    private static void assertStatuses(String path, String verb, Set<String> expected) {
        Set<String> declared = new TreeSet<>();
        SPEC.at("/paths/" + pointer(path) + "/" + verb + "/responses").fieldNames().forEachRemaining(declared::add);
        assertEquals(new TreeSet<>(expected), declared, path + " " + verb);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static Set<String> components(Class<?> record) {
        assertTrue(record.isRecord(), record.getSimpleName() + " must be a record");
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> requiredComponents(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .filter(c -> Arrays.stream(c.getDeclaredAnnotations())
                        .noneMatch(a -> a.annotationType().getSimpleName().equals("WireOptional")))
                .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> propertyNames(JsonNode schema) {
        Set<String> names = new TreeSet<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode schema(String name) {
        JsonNode schema = SPEC.at("/components/schemas/" + name);
        assertFalse(schema.isMissingNode(), "missing schema " + name);
        return schema;
    }

    private static Set<String> textSet(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        node.forEach(v -> out.add(v.asText()));
        return out;
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
        throw new IllegalStateException("cannot locate contracts/openapi/console.yaml");
    }
}
