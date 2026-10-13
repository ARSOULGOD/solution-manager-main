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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Plan 013 T1.4's slice of the BE-D10 lock: the three target operations and the schemas they use, against
 * {@code contracts/openapi/console.yaml}, as equality and not presence. (The long-standing
 * {@code SchemaMirrorLockTest} still pins {@code listTargets}.)
 */
class TargetRegistrationSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();
    private static final String PIN = "SHA256:" + "A".repeat(43);

    // ------------------------------------------------------------------------------------------- operations

    @Test
    void theThreeOperationsAreWhereTheContractPutsThemWithTheContractsVerbAndScope() throws Exception {
        Class<?> controller = TargetRegistrationController.class;
        assertOperation(controller, "scanHostKeys", "post", "/dp/host-key-scans", "target.create", "named",
                PostMapping.class, "/api/dp/host-key-scans", OwnershipScopeResolver.class);
        assertOperation(controller, "registerTarget", "post", "/cp/targets", "target.create", "named",
                PostMapping.class, "/api/cp/targets", OwnershipScopeResolver.class);
        assertOperation(controller, "editTarget", "patch", "/cp/targets/{targetId}", "target.edit", "named",
                PatchMapping.class, "/api/cp/targets/{targetId}", TargetResolver.class);
        assertOperation(controller, "getTargetConnection", "get", "/dp/targets/{targetId}/connection", "target.edit",
                "named", GetMapping.class, "/api/dp/targets/{targetId}/connection", TargetResolver.class);
    }

    private static void assertOperation(Class<?> controller, String operationId, String verb, String contractPath,
            String permission, String scope, Class<? extends java.lang.annotation.Annotation> mapping, String mapped,
            Class<? extends ResourceResolver> resolver) {
        Method handler = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(operationId)).findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + operationId));
        ConsoleOperation annotation = AnnotatedElementUtils.findMergedAnnotation(handler, ConsoleOperation.class);
        assertEquals(operationId, annotation.value());
        assertEquals(resolver, annotation.resource(), operationId + " resolver");
        assertTrue(handler.isAnnotationPresent(mapping), operationId + " mapping verb");
        String path = "/api" + switch (mapping.getSimpleName()) {
            case "PatchMapping" -> handler.getAnnotation(PatchMapping.class).value()[0];
            case "PostMapping" -> handler.getAnnotation(PostMapping.class).value()[0];
            default -> handler.getAnnotation(GetMapping.class).value()[0];
        };
        assertEquals(mapped, path);

        JsonNode operation = SPEC.at("/paths/" + pointer(contractPath) + "/" + verb);
        assertEquals(operationId, operation.path("operationId").asText(), "the contract names the operation here");
        assertEquals(permission, operation.path("x-sm-permission").asText());
        assertEquals(scope, operation.path("x-sm-scope").asText());
        assertEquals(permission, ConsoleOperations.permissionFor(operationId).orElseThrow().id(),
                operationId + ": the server's verb is the contract's");
        assertEquals("/api" + contractPath, path, "the contract's path beneath the /api server");
    }

    @Test
    void registerTargetIsTheOnlyCredentialBearingHandlerHere() throws Exception {
        for (Method method : TargetRegistrationController.class.getDeclaredMethods()) {
            ConsoleOperation operation = method.getAnnotation(ConsoleOperation.class);
            if (operation == null) {
                continue;
            }
            assertEquals(operation.value().equals("registerTarget"), method.isAnnotationPresent(CredentialBearing.class),
                    operation.value());
        }
    }

    // ---------------------------------------------------------------------------------------------- schemas

    private static void requestMirrors(String schemaName, Class<?> record) {
        JsonNode schema = schema(schemaName);
        assertFalse(schema.path("additionalProperties").asBoolean(true), schemaName + " forbids additional properties");
        assertEquals(propertyNames(schema), components(record), schemaName + " properties");
    }

    private static void responseMirrors(String schemaName, Class<?> record) {
        JsonNode schema = schema(schemaName);
        assertEquals(flattenedProperties(schema), components(record), schemaName + " properties");
        assertEquals(flattenedRequired(schema), requiredComponents(record), schemaName + " required");
    }

    @Test
    void requestSchemasAndRecordsAgreeOnProperties() {
        requestMirrors("ScopeRef", ScopeRef.class);
        requestMirrors("HostKeyScanRequest", HostKeyScanRequest.class);
        requestMirrors("TargetConnectionSpec", TargetConnectionSpecRequest.class);
        requestMirrors("RegisterTargetRequest", RegisterTargetRequest.class);
        requestMirrors("UpdateDeploymentTargetRequest", UpdateDeploymentTargetRequest.class);
    }

    @Test
    void responseSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        responseMirrors("HostKeyScan", HostKeyScan.class);
        responseMirrors("OfferedHostKey", OfferedHostKey.class);
        responseMirrors("TargetConnection", TargetConnection.class);
        responseMirrors("CredentialSummary", CredentialSummary.class);
        responseMirrors("TargetDetail", TargetDetail.class);
    }

    @Test
    void theRequestsRequiredFieldsAreRequiredByTheRecords() {
        assertEquals(Set.of("host", "ownershipScope"), required("HostKeyScanRequest"));
        assertThrows(IllegalArgumentException.class, () -> new HostKeyScanRequest(null, 22, ScopeRef.ORGANIZATION));
        assertThrows(IllegalArgumentException.class, () -> new HostKeyScanRequest("h", 22, null));
        assertDoesNotThrow(() -> new HostKeyScanRequest("h", null, ScopeRef.ORGANIZATION));

        assertEquals(Set.of("name", "kind", "transport", "ownershipScope"),
                required("RegisterTargetRequest"));
        assertEquals(Set.of("name", "connectionId", "runtime", "configRoot"),
                required("UpdateDeploymentTargetRequest"));
        assertEquals(Set.of("host", "sshUser", "hostKeyFingerprint", "allowedConfigRoot", "allowedContainerPrefix"),
                required("TargetConnectionSpec"));
        TargetConnectionSpecRequest spec = new TargetConnectionSpecRequest("h", null, "u", PIN, "/srv", "sm-");
        assertEquals(22, spec.portOrDefault(), "the contract's default");
        assertThrows(IllegalArgumentException.class,
                () -> new TargetConnectionSpecRequest(null, null, "u", PIN, "/srv", "sm-"));
        assertThrows(IllegalArgumentException.class,
                () -> new RegisterTargetRequest("n", null, TargetTransport.SSH_DOCKER, ScopeRef.ORGANIZATION, spec, "k"));
        assertThrows(IllegalArgumentException.class,
                () -> new RegisterTargetRequest("n", TargetKind.DOCKER, TargetTransport.SSH_DOCKER, null, spec, "k"));
    }

    @Test
    void theRequestLengthsAreTheContractsLengths() {
        JsonNode register = schema("RegisterTargetRequest").path("properties");
        assertEquals(RegisterTargetRequest.MAX_PRIVATE_KEY_CHARS, register.at("/privateKey/maxLength").asInt());
        assertTrue(register.at("/privateKey/writeOnly").asBoolean(), "I6: the key is write-only in the contract");
        assertEquals(100, register.at("/name/maxLength").asInt());
        TargetConnectionSpecRequest spec = new TargetConnectionSpecRequest("h", null, "u", PIN, "/srv", "sm-");
        assertDoesNotThrow(() -> new RegisterTargetRequest("n".repeat(100), TargetKind.DOCKER,
                TargetTransport.SSH_DOCKER, ScopeRef.ORGANIZATION, spec, "k".repeat(16384)));
        assertThrows(IllegalArgumentException.class, () -> new RegisterTargetRequest("n".repeat(101), TargetKind.DOCKER,
                TargetTransport.SSH_DOCKER, ScopeRef.ORGANIZATION, spec, "k"));
        assertThrows(IllegalArgumentException.class, () -> new RegisterTargetRequest("n", TargetKind.DOCKER,
                TargetTransport.SSH_DOCKER, ScopeRef.ORGANIZATION, spec, "k".repeat(16385)));
        assertEquals(254, schema("HostKeyScanRequest").at("/properties/host/maxLength").asInt());
        assertThrows(IllegalArgumentException.class,
                () -> new HostKeyScanRequest("h".repeat(255), null, ScopeRef.ORGANIZATION));
    }

    @Test
    void theEnumsAreTheVocabulariesOfTheWire() {
        JsonNode register = schema("RegisterTargetRequest").path("properties");
        assertEquals(Set.of("docker", "systemd"), textSet(register.at("/kind/enum")));
        assertTrue(wire(TargetKind.class).containsAll(textSet(register.at("/kind/enum"))));
        // registerTarget cannot create an agent target (plan 015 T3.5, BE-D71): its transport is the ssh-docker member alone
        assertEquals(Set.of("ssh-docker", "ssh"), textSet(register.at("/transport/enum")));
        assertTrue(wire(TargetTransport.class).containsAll(textSet(register.at("/transport/enum"))));
        assertEquals(wire(TargetTransport.class), textSet(schema("Target").at("/properties/transport/enum")));
        Set<String> variants = new LinkedHashSet<>();
        for (JsonNode variant : schema("TargetConnection").path("oneOf")) {
            variants.addAll(textSet(resolve(variant).at("/properties/transport/enum")));
        }
        assertEquals(Set.of("ssh-docker", "agent"), variants, "TargetConnection variants name the response transports");
        JsonNode discriminator = schema("TargetConnection").path("discriminator");
        assertEquals("transport", discriminator.path("propertyName").asText());
        assertEquals(Set.of("ssh-docker", "agent"), textSet(discriminator.path("mapping").fieldNames()));
    }

    /** Plan 015 T3.5 fix round: the answers that can only be the ssh variant are typed as it, so a generated client need not narrow a union. */
    @Test
    void theSshOnlyAnswersNameTheSshVariantAndOnlyGetTargetConnectionIsTheUnion() {
        String ssh = "#/components/schemas/SshDockerTargetConnection";
        assertEquals(ssh, SPEC.at("/paths/" + pointer("/dp/targets/{targetId}/connection") + "/patch/responses/200/content/application~1json/schema/$ref").asText());
        assertEquals(ssh, schema("TargetDetail").at("/allOf/1/properties/connection/$ref").asText());
        assertEquals("#/components/schemas/TargetConnection", SPEC.at("/paths/" + pointer("/dp/targets/{targetId}/connection")
                + "/get/responses/200/content/application~1json/schema/$ref").asText());
    }

    @Test
    void theErrorCodesAreTheContractsAndCarryTheContractsStatuses() {
        Set<String> contract = textSet(schema("ErrorCode").get("enum"));
        for (ErrorCode code : new ErrorCode[] {ErrorCode.CREDENTIAL_INVALID, ErrorCode.HOST_KEY_NOT_OFFERED,
                ErrorCode.TARGET_ADDRESS_REFUSED, ErrorCode.INSECURE_TRANSPORT}) {
            assertTrue(contract.contains(code.name()), code.name());
        }
        assertEquals(422, ErrorCode.CREDENTIAL_INVALID.status());
        assertEquals(422, ErrorCode.HOST_KEY_NOT_OFFERED.status());
        assertEquals(422, ErrorCode.TARGET_ADDRESS_REFUSED.status());
        assertEquals(403, ErrorCode.INSECURE_TRANSPORT.status());
    }

    /** Plan 015 T3.5: the codes about an agent target, each the contract's and each a {@code 409} (T3.3's is a {@code 501}). */
    @Test
    void theAgentTargetErrorCodesAreTheContractsWithTheContractsStatuses() {
        Set<String> contract = textSet(schema("ErrorCode").get("enum"));
        for (ErrorCode code : new ErrorCode[] {ErrorCode.TARGET_TRANSPORT_UNSUPPORTED, ErrorCode.AGENT_STILL_ENROLLED,
                ErrorCode.TARGET_IS_AGENT_MANAGED, ErrorCode.AGENT_NOT_CONNECTED}) {
            assertTrue(contract.contains(code.name()), code.name());
            assertEquals(409, code.status(), code.name());
        }
        assertTrue(contract.contains(ErrorCode.AGENT_OPERATION_UNSUPPORTED.name()));
        assertEquals(501, ErrorCode.AGENT_OPERATION_UNSUPPORTED.status());
    }

    /** The one record carries both variants of the union; each variant is exactly its schema's fields on the wire. */
    @Test
    void theUnionRecordBuildsEachVariantExactlyAsItsSchemaDescribesIt() throws Exception {
        ObjectMapper json = new ObjectMapper()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        java.util.UUID target = java.util.UUID.randomUUID();
        java.util.UUID agent = java.util.UUID.randomUUID();
        JsonNode agentWire = json.valueToTree(TargetConnection.agent(target, agent, "/srv/sm", "sm-"));
        Set<String> agentFields = textSet(agentWire.fieldNames());
        assertEquals(new TreeSet<>(variantProperties("AgentTargetConnection")), new TreeSet<>(agentFields));
        assertEquals("agent", agentWire.get("transport").asText());
        JsonNode sshWire = json.valueToTree(TargetConnection.ssh(target, "h", 22, "u", PIN, java.util.List.of(PIN), "/srv/sm",
                "sm-", new CredentialSummary(true, "SHA256:x", null)));
        Set<String> sshFields = textSet(sshWire.fieldNames());
        assertEquals(new TreeSet<>(variantProperties("SshDockerTargetConnection")), new TreeSet<>(sshFields));
        assertEquals("ssh-docker", sshWire.get("transport").asText());
        assertFalse(agentWire.has("host") || agentWire.has("credential"), "an agent target has no address and no credential");
    }

    private static Set<String> variantProperties(String schemaName) {
        return new TreeSet<>(propertyNames(schema(schemaName)));
    }

    @Test
    void noResponseSchemaOfTheseOperationsCanCarryKeyMaterial() {
        for (String name : new String[] {"TargetConnection", "CredentialSummary", "TargetDetail", "HostKeyScan"}) {
            Set<String> names = flattenedProperties(schema(name));
            for (String forbidden : new String[] {"privateKey", "credentialRef", "credential_ref", "secret", "hash",
                    "credentialHash"}) {
                assertFalse(names.contains(forbidden), name + " must not carry " + forbidden);
            }
        }
        assertFalse(SPEC.at("/components/schemas/RegisterTargetRequest/properties/privateKey").isMissingNode());
    }

    @Test
    void aScopeRefHasNoOrganization() {
        assertFalse(propertyNames(schema("ScopeRef")).contains("organizationId"), "I5");
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static Set<String> required(String schemaName) {
        return textSet(schema(schemaName).path("required"));
    }

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

    /** A union (plan 015 T3.5, a discriminated {@code oneOf}) has every property of any variant: one record carries them all. */
    private static Set<String> flattenedProperties(JsonNode schema) {
        Set<String> names = new TreeSet<>(propertyNames(schema));
        schema.path("allOf").forEach(part -> names.addAll(flattenedProperties(resolve(part))));
        schema.path("oneOf").forEach(variant -> names.addAll(flattenedProperties(resolve(variant))));
        return names;
    }

    /** A property is required of a union only if EVERY variant requires it; the rest are optional on the one record. */
    private static Set<String> flattenedRequired(JsonNode schema) {
        Set<String> names = new TreeSet<>(textSet(schema.path("required")));
        schema.path("allOf").forEach(part -> names.addAll(flattenedRequired(resolve(part))));
        if (schema.path("oneOf").isArray() && schema.path("oneOf").size() > 0) {
            Set<String> common = null;
            for (JsonNode variant : schema.path("oneOf")) {
                Set<String> required = flattenedRequired(resolve(variant));
                if (common == null) {
                    common = new TreeSet<>(required);
                } else {
                    common.retainAll(required);
                }
            }
            names.addAll(common);
        }
        return names;
    }

    private static JsonNode resolve(JsonNode node) {
        if (!node.has("$ref")) {
            return node;
        }
        String ref = node.get("$ref").asText();
        assertTrue(ref.startsWith("#/components/schemas/"), ref);
        return schema(ref.substring("#/components/schemas/".length()));
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

    private static Set<String> textSet(java.util.Iterator<String> names) {
        Set<String> out = new LinkedHashSet<>();
        names.forEachRemaining(out::add);
        return out;
    }

    private static Set<String> wire(Class<?> enumType) {
        try {
            Method wireValue = enumType.getDeclaredMethod("wireValue");
            return Arrays.stream(enumType.getEnumConstants()).map(v -> {
                try {
                    return (String) wireValue.invoke(v);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
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
