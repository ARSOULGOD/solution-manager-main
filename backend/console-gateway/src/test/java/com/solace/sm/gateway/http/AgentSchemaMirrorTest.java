package com.solace.sm.gateway.http;

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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Plan 015 T4.3's slice of the BE-D10 lock: the five agent operations and the schemas they use, against
 * {@code contracts/openapi/console.yaml}, as equality and not presence.
 */
class AgentSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------------------------------- operations

    @Test
    void theFiveOperationsAreWhereTheContractPutsThemWithTheContractsVerbScopeAndResolver() {
        assertOperation("issueAgentEnrolment", "post", "/cp/environments/{environmentId}/agent-enrolments", "agent.enrol",
                "environment", EnvironmentResolver.class);
        assertOperation("listAgents", "get", "/cp/agents", "agent.view", "reach", NoResource.class);
        assertOperation("getAgent", "get", "/cp/agents/{agentId}", "agent.view", "named", AgentResolver.class);
        assertOperation("revokeAgent", "post", "/cp/agents/{agentId}/revoke", "agent.revoke", "named", AgentResolver.class);
        assertOperation("drainAgent", "post", "/dp/agents/{agentId}/drain", "agent.drain", "named", AgentResolver.class);
    }

    private static void assertOperation(String operationId, String verb, String contractPath, String permission, String scope,
            Class<?> resolver) {
        Method handler = Arrays.stream(AgentController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(operationId)).findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + operationId));
        ConsoleOperation annotation = AnnotatedElementUtils.findMergedAnnotation(handler, ConsoleOperation.class);
        assertEquals(operationId, annotation.value());
        assertEquals(resolver, annotation.resource(), operationId + ": the resource is resolved from the path, never the body");
        String mapped = verb.equals("get") ? handler.getAnnotation(GetMapping.class).value()[0]
                : handler.getAnnotation(PostMapping.class).value()[0];
        assertEquals("/api" + contractPath, "/api" + mapped, operationId + ": the contract's path beneath the /api server");

        JsonNode operation = SPEC.at("/paths/" + pointer(contractPath) + "/" + verb);
        assertEquals(operationId, operation.path("operationId").asText(), "the contract names the operation here");
        assertEquals(permission, operation.path("x-sm-permission").asText());
        assertEquals(scope, operation.path("x-sm-scope").asText());
        assertEquals(permission, ConsoleOperations.permissionFor(operationId).orElseThrow().id(),
                operationId + ": the server's verb is the contract's");
        assertEquals(operationId.equals("issueAgentEnrolment") ? List.of("target.create") : List.of(),
                textList(operation.path("x-sm-permission-also")), operationId + ": the second verb");
        assertEquals(Set.of("agents"), textSet(operation.path("tags")));
    }

    @Test
    void onlyTheIssueIsCredentialBearingBecauseOnlyItsResponseCarriesACredential() {
        for (Method method : AgentController.class.getDeclaredMethods()) {
            ConsoleOperation operation = method.getAnnotation(ConsoleOperation.class);
            if (operation == null) {
                continue;
            }
            assertEquals(operation.value().equals("issueAgentEnrolment"), method.isAnnotationPresent(CredentialBearing.class),
                    operation.value());
        }
    }

    @Test
    void theVerbLevelsAreOr128Four() {
        assertEquals(com.solace.sm.cp.api.authz.ConsequenceClass.HIGH, com.solace.sm.cp.api.authz.Permission.AGENT_ENROL.consequence());
        assertEquals(com.solace.sm.cp.api.authz.ConsequenceClass.HIGH, com.solace.sm.cp.api.authz.Permission.AGENT_REVOKE.consequence());
        assertEquals(com.solace.sm.cp.api.authz.ConsequenceClass.STANDARD, com.solace.sm.cp.api.authz.Permission.AGENT_DRAIN.consequence());
        assertTrue(OperationPolicy.classify("listAgents") instanceof OperationClassification.Reach);
        assertTrue(OperationPolicy.classify("getAgent") instanceof OperationClassification.Catalog);
    }

    // ---------------------------------------------------------------------------------------------- schemas

    @Test
    void theRequestSchemaAndRecordAgreeAndForbidAdditionalProperties() {
        JsonNode schema = schema("IssueAgentEnrolmentRequest");
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(propertyNames(schema), components(IssueAgentEnrolmentRequest.class));
        assertEquals(textSet(schema.path("required")), components(IssueAgentEnrolmentRequest.class), "every field is required");
        assertFalse(propertyNames(schema).contains("organizationId") || propertyNames(schema).contains("environmentId")
                || propertyNames(schema).contains("capabilities"), "I5: scope is the session's and the path's; capabilities are the server's");
    }

    @Test
    void theResponseSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        mirrors("AgentEnrolment", AgentEnrolment.class);
        mirrors("AgentSession", AgentSession.class);
        mirrors("AgentDrainAccepted", AgentDrainAccepted.class);
        mirrors("Agent", Agent.class);
        mirrors("AgentList", AgentList.class);
        assertFalse(allProperties(schema("Agent")).contains("stateVersion"), "OR-38: an agent carries no stateVersion, as Target");
        assertEquals(Set.of("pending", "expired", "active", "revoked"), textSet(property("Agent", "status").path("enum")));
        assertEquals(Arrays.stream(Agent.Status.values()).map(Agent.Status::wire).collect(Collectors.toSet()),
                textSet(property("Agent", "status").path("enum")));
        assertEquals(Set.of("connected", "disconnected"), textSet(schema("AgentSession").at("/properties/state/enum")));
        assertEquals(Arrays.stream(AgentSession.State.values()).map(AgentSession.State::wire).collect(Collectors.toSet()),
                textSet(schema("AgentSession").at("/properties/state/enum")));
    }

    private static void mirrors(String schemaName, Class<?> record) {
        JsonNode schema = schema(schemaName);
        assertEquals(allProperties(schema), components(record), schemaName + " properties");
        assertEquals(allRequired(schema), requiredComponents(record), schemaName + " required");
    }

    @Test
    void theTokenIsInExactlyOneSchemaAndNoSchemaOfTheseOperationsCanCarryCredentialMaterial() {
        for (String name : new String[] {"Agent", "AgentList", "AgentSession", "AgentDrainAccepted"}) {
            Set<String> names = allProperties(schema(name));
            for (String forbidden : new String[] {"enrollmentToken", "token", "tokenDigest", "digest", "thumbprint",
                    "certificate", "certificateChain", "privateKey", "csr", "nonce", "secret", "hash"}) {
                assertFalse(names.contains(forbidden), name + " must not carry " + forbidden);
            }
        }
        JsonNode token = schema("AgentEnrolment").at("/properties/enrollmentToken");
        assertTrue(token.path("readOnly").asBoolean(), "the token is readOnly in the contract");
        assertEquals("password", token.path("format").asText());
        assertEquals(Set.of("enrollmentToken"), Arrays.stream(AgentEnrolment.class.getRecordComponents())
                .map(RecordComponent::getName).filter(n -> n.toLowerCase().contains("token")).collect(Collectors.toSet()));
        assertFalse(Arrays.stream(Agent.class.getRecordComponents()).anyMatch(c -> c.getName().toLowerCase().contains("token")));
    }

    @Test
    void theEnrolmentRecordNeverPrintsItsTokenAndTheWireCarriesItOnce() throws Exception {
        AgentEnrolment enrolment = new AgentEnrolment(UUID.randomUUID(), "super-secret-token-value", "2026-10-12T00:00:00Z",
                UUID.randomUUID(), "edge", "/srv/sm", "sm-", UUID.randomUUID(), 3L);
        assertFalse(enrolment.toString().contains("super-secret-token-value"));
        JsonNode wire = MAPPER.readTree(MAPPER.writeValueAsString(enrolment));
        assertEquals("super-secret-token-value", wire.get("enrollmentToken").asText());
        assertEquals(3, wire.get("packageVersion").asInt(), "a PackageVersionId is the integer itself");
    }

    @Test
    void theIssueBodyGrammarsAreTheContractsAStructuralFaultIsA400AndAValueFaultIsNamedForA422() {
        JsonNode schema = schema("IssueAgentEnrolmentRequest");
        assertEquals("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$", schema.at("/properties/allowedConfigRoot/pattern").asText());
        assertEquals("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$", schema.at("/properties/allowedContainerPrefix/pattern").asText());
        assertEquals(100, schema.at("/properties/targetName/maxLength").asInt());
        JsonNode one = MAPPER.valueToTree(1);
        UUID solution = UUID.randomUUID();
        assertEquals(null, new IssueAgentEnrolmentRequest("edge", "/srv/sm", "sm-", solution, one).envelopeFault());
        new IssueAgentEnrolmentRequest("edge", "/srv/sm", "sm-", solution, MAPPER.valueToTree("v2"));
        // a value outside its grammar is a 422 (named field), not a structural 400
        for (String[] value : new String[][] {{"srv/sm", "sm-"}, {"/srv/../sm", "sm-"}, {"/srv/sm/", "sm-"}}) {
            assertEquals("allowedConfigRoot", new IssueAgentEnrolmentRequest("edge", value[0], value[1], solution, one).envelopeFault());
        }
        for (String prefix : new String[] {"-sm", "s".repeat(65), "a b"}) {
            assertEquals("allowedContainerPrefix", new IssueAgentEnrolmentRequest("edge", "/srv/sm", prefix, solution, one).envelopeFault());
        }
        for (Object[] bad : new Object[][] {
                {"", "/srv/sm", "sm-", solution, one}, {"x".repeat(101), "/srv/sm", "sm-", solution, one},
                {"edge", null, "sm-", solution, one}, {"edge", "", "sm-", solution, one}, {"edge", "/srv/sm", "", solution, one},
                {"edge", "/srv/sm", "sm-", null, one}, {"edge", "/srv/sm", "sm-", solution, null},
                {"edge", "/srv/sm", "sm-", solution, MAPPER.valueToTree(0)}, {"edge", "/srv/sm", "sm-", solution, MAPPER.valueToTree("")},
                {"edge", "/srv/sm", "sm-", solution, MAPPER.valueToTree(List.of(1))}}) {
            assertThrows(IllegalArgumentException.class, () -> new IssueAgentEnrolmentRequest((String) bad[0], (String) bad[1],
                    (String) bad[2], (UUID) bad[3], (JsonNode) bad[4]), Arrays.toString(bad));
        }
    }

    @Test
    void theContractDeclaresEveryStatusTheGatewayCanAnswerForTheseOperations() {
        assertStatuses("/cp/environments/{environmentId}/agent-enrolments", "post", Set.of("201", "400", "401", "403", "404", "409", "422"));
        assertStatuses("/cp/agents", "get", Set.of("200", "401", "403"));
        assertStatuses("/cp/agents/{agentId}", "get", Set.of("200", "400", "401", "403", "404"));
        assertStatuses("/cp/agents/{agentId}/revoke", "post", Set.of("200", "400", "401", "403", "404"));
        assertStatuses("/dp/agents/{agentId}/drain", "post", Set.of("202", "400", "401", "403", "404", "409"));
    }

    @Test
    void theAgentAcquisitionModeIsInTheSharedVocabularyAndThePackageSpellingMatches() {
        assertEquals(List.of("direct-http", "via-ssh", "agent"), textList(schema("Acquisition").at("/properties/mode/enum")));
        assertEquals("agent", com.solace.sm.dp.api.inventory.AcquisitionMode.AGENT.wire());
    }

    @Test
    void everyAgentRefusalReasonMapsToACodeTheContractDeclares() {
        Set<String> enumerated = textSet(schema("ErrorCode").path("enum"));
        for (AgentRefusedException.Reason reason : AgentRefusedException.Reason.values()) {
            assertTrue(enumerated.contains(reason.code().name()), reason.toString());
        }
        assertEquals(409, ErrorCode.AGENT_NOT_CONNECTED.status());
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

    /** Properties across the schema and every {@code allOf} member it composes (a {@code $ref} resolved). */
    private static Set<String> allProperties(JsonNode schema) {
        Set<String> names = new TreeSet<>();
        collect(schema, "properties", names);
        return names;
    }

    private static Set<String> allRequired(JsonNode schema) {
        Set<String> names = new TreeSet<>();
        collect(schema, "required", names);
        return names;
    }

    private static void collect(JsonNode schema, String field, Set<String> into) {
        JsonNode resolved = schema.has("$ref") ? schema(schema.get("$ref").asText().substring("#/components/schemas/".length())) : schema;
        if (field.equals("properties")) {
            resolved.path("properties").fieldNames().forEachRemaining(into::add);
        } else {
            resolved.path("required").forEach(v -> into.add(v.asText()));
        }
        resolved.path("allOf").forEach(member -> collect(member, field, into));
    }

    private static JsonNode property(String schemaName, String property) {
        for (JsonNode member : schema(schemaName).path("allOf")) {
            if (member.path("properties").has(property)) {
                return member.path("properties").get(property);
            }
        }
        return schema(schemaName).path("properties").path(property);
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

    private static List<String> textList(JsonNode node) {
        List<String> out = new java.util.ArrayList<>();
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
