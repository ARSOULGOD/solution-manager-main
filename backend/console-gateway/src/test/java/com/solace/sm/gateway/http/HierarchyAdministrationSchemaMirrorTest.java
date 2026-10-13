package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.cp.api.authz.ConsoleOperations;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
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
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Plan 013 T2.3's slice of the BE-D10 lock: the seven hierarchy and entitlement operations and the schemas they use,
 * against {@code contracts/openapi/console.yaml}, as equality and not presence.
 *
 * <p>It also pins what the contract SAYS about the {@code 409}s, because that wording is a decision and not decoration:
 * {@code OR-123} makes a case-only sibling clash a {@code 409} (the exact name is the {@code 200} replay), and the
 * organization rename's {@code 409} is install-wide; {@code OR-125} (3) closes the W2 review's W2D-2 by renaming an
 * organization at install scope only, so that {@code 409} reaches install principals alone and is no cross-tenant
 * oracle; it must still not be documented as sibling-only.
 */
class HierarchyAdministrationSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();

    // ------------------------------------------------------------------------------------------- operations

    @Test
    void theSevenOperationsAreWhereTheContractPutsThemWithTheContractsVerbAndScope() {
        Class<?> hierarchy = HierarchyAdministrationController.class;
        String orgs = "/cp/hierarchy/organizations/{organizationId}";
        String subs = orgs + "/sub-orgs";
        String sub = subs + "/{subOrganizationId}";
        String envs = sub + "/environments";
        String env = envs + "/{environmentId}";
        assertOperation(hierarchy, "createSubOrganization", "post", subs, "hierarchy.edit", "organization",
                PostMapping.class, OrganizationNodeResolver.class);
        assertOperation(hierarchy, "createEnvironment", "post", envs, "hierarchy.edit", "sub-organization",
                PostMapping.class, SubOrganizationNodeResolver.class);
        // OR-125 (3): an organization is renamed at INSTALL scope only
        assertOperation(hierarchy, "renameOrganization", "patch", orgs, "hierarchy.edit", "install",
                PatchMapping.class, InstallOrganizationResolver.class);
        assertOperation(hierarchy, "renameSubOrganization", "patch", sub, "hierarchy.edit", "sub-organization",
                PatchMapping.class, SubOrganizationNodeResolver.class);
        assertOperation(hierarchy, "renameEnvironment", "patch", env, "hierarchy.edit", "environment",
                PatchMapping.class, EnvironmentNodeResolver.class);
        Class<?> entitlements = EntitlementController.class;
        assertOperation(entitlements, "listEntitlements", "get", "/cp/entitlements", "solution.view", "organization",
                GetMapping.class, SessionOrganizationResourceResolver.class);
        assertOperation(entitlements, "setEntitlement", "put", "/cp/entitlements/{solution}", "entitlement.set",
                "install", PutMapping.class, InstallResourceResolver.class);
    }

    private static void assertOperation(Class<?> controller, String operationId, String verb, String contractPath,
            String permission, String scope, Class<? extends java.lang.annotation.Annotation> mapping,
            Class<? extends ResourceResolver> resolver) {
        Method handler = Arrays.stream(controller.getDeclaredMethods())
                .filter(m -> m.getName().equals(operationId)).findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + operationId));
        ConsoleOperation annotation = AnnotatedElementUtils.findMergedAnnotation(handler, ConsoleOperation.class);
        assertEquals(operationId, annotation.value());
        assertEquals(resolver, annotation.resource(), operationId + " resolver");
        assertTrue(handler.isAnnotationPresent(mapping), operationId + " mapping verb");
        String classPrefix = controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class)
                .value()[0];
        String methodPath = mappedValue(handler, mapping);
        assertEquals("/api" + contractPath, classPrefix + methodPath, operationId + ": the contract's path beneath /api");

        JsonNode operation = SPEC.at("/paths/" + pointer(contractPath) + "/" + verb);
        assertEquals(operationId, operation.path("operationId").asText(), "the contract names the operation here");
        assertEquals(permission, operation.path("x-sm-permission").asText());
        assertEquals(scope, operation.path("x-sm-scope").asText());
        assertEquals(permission, ConsoleOperations.permissionFor(operationId).orElseThrow().id(),
                operationId + ": the server's verb is the contract's");
        boolean mutation = !verb.equals("get");
        assertEquals(mutation, operation.path("parameters").toString().contains("#/components/parameters/CsrfToken"),
                operationId + ": a mutation declares the CSRF header, a read does not");
    }

    private static String mappedValue(Method handler, Class<? extends java.lang.annotation.Annotation> mapping) {
        String[] values;
        if (mapping == PostMapping.class) {
            values = handler.getAnnotation(PostMapping.class).value();
        } else if (mapping == PatchMapping.class) {
            values = handler.getAnnotation(PatchMapping.class).value();
        } else if (mapping == PutMapping.class) {
            values = handler.getAnnotation(PutMapping.class).value();
        } else {
            values = handler.getAnnotation(GetMapping.class).value();
        }
        return values.length == 0 ? "" : values[0];
    }

    @Test
    void everyOperationDeclaresTheStatusesTheGatewayCanAnswer() {
        for (String[] op : new String[][] {
                {"/cp/hierarchy/organizations/{organizationId}/sub-orgs", "post"},
                {"/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments", "post"},
                {"/cp/hierarchy/organizations/{organizationId}", "patch"},
                {"/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}", "patch"},
                {"/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments/{environmentId}",
                        "patch"}}) {
            JsonNode responses = SPEC.at("/paths/" + pointer(op[0]) + "/" + op[1] + "/responses");
            for (String status : new String[] {"400", "401", "403", "404", "409"}) {
                assertTrue(responses.has(status), op[0] + " " + op[1] + " declares " + status);
            }
            assertTrue(responses.has("200"));
            assertEquals(op[1].equals("post"), responses.has("201"), "only a create answers 201");
        }
        JsonNode set = SPEC.at("/paths/" + pointer("/cp/entitlements/{solution}") + "/put/responses");
        for (String status : new String[] {"200", "400", "401", "403", "404", "409"}) {
            assertTrue(set.has(status), "setEntitlement declares " + status);
        }
        JsonNode list = SPEC.at("/paths/" + pointer("/cp/entitlements") + "/get/responses");
        for (String status : new String[] {"200", "401", "403"}) {
            assertTrue(list.has(status), "listEntitlements declares " + status);
        }
    }

    // ---------------------------------------------------------------------- OR-123 and W2D-2: the 409 wording

    @Test
    void theCreatesStateThatACaseOnlyDifferenceIsAConflictAndOnlyTheExactNameIsTheReplay() {
        for (String path : new String[] {"/cp/hierarchy/organizations/{organizationId}/sub-orgs",
                "/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments"}) {
            JsonNode post = SPEC.at("/paths/" + pointer(path) + "/post");
            String text = post.path("description").asText() + " " + post.at("/responses/409/description").asText();
            assertTrue(text.contains("OR-123"), path + ": cites the ruling");
            assertTrue(text.contains("case"), path + ": says the clash is case-insensitive");
            assertFalse(text.contains("409` is not used"), path + ": the draft's 'is not used' is gone");
            assertTrue(post.at("/responses/409/content/application~1problem+json").isObject()
                    || post.at("/responses/409/content").has("application/problem+json"), path + ": 409 is a Problem");
        }
    }

    @Test
    void theSubOrganizationAndEnvironmentRenameConflictsAreSiblingClashesComparedWithoutRegardToCase() {
        for (String path : new String[] {
                "/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}",
                "/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments/{environmentId}"}) {
            String text = SPEC.at("/paths/" + pointer(path) + "/patch/responses/409/description").asText();
            assertTrue(text.contains("sibling") && text.contains("case"), path + ": " + text);
        }
    }

    @Test
    void theSubOrganizationAndEnvironmentRenameConflictsStateTheAcceptedSiblingDisclosureInsideOneOrganization() {
        // OR-125 (3): the sibling-name 409 inside one organization is accepted, and console.yaml says so (no longer an open
        // owner question)
        for (String path : new String[] {
                "/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}",
                "/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments/{environmentId}"}) {
            String text = SPEC.at("/paths/" + pointer(path) + "/patch/responses/409/description").asText();
            assertTrue(text.contains("OR-125"), path + ": cites the ruling: " + text);
            assertTrue(text.contains("accepted"), path + ": says it is accepted: " + text);
            assertTrue(text.contains("invisible") || text.contains("cannot see") || text.contains("cannot list"),
                    path + ": still says the clash covers siblings the caller cannot see: " + text);
            assertFalse(text.contains("Open owner question") || text.contains("changes with the ruling"),
                    path + ": no longer an open question: " + text);
            assertFalse(text.contains("W4D-1") && text.contains("(c) answer"), path + ": the option list is gone");
        }
    }

    @Test
    void theOrganizationRenameIsInstallScopedAndItsInstallWideConflictIsDocumentedAsNoCrossTenantOracle() {
        JsonNode patch = SPEC.at("/paths/" + pointer("/cp/hierarchy/organizations/{organizationId}") + "/patch");
        String conflict = patch.at("/responses/409/description").asText();
        String all = patch.path("description").asText() + " " + conflict;
        assertFalse(conflict.toLowerCase().contains("a sibling already"),
                "it must not be documented as a sibling-only conflict: " + conflict);
        assertTrue(conflict.contains("another organization"), conflict);
        assertTrue(conflict.contains("install"), conflict);
        assertTrue(all.contains("OR-125"), "the ruling is cited where the behaviour is documented");
        assertTrue(all.contains("OR-123"));
        assertTrue(patch.path("description").asText().toLowerCase().contains("install scope only"),
                "the operation says it is install scope only");
        assertTrue(patch.path("description").asText().contains("organization-bound"),
                "and that an organization-bound Administrator is refused");
        assertTrue(patch.at("/responses/403/$ref").asText().endsWith("/Forbidden")
                || patch.at("/responses/403").isObject(), "403 is declared");
        assertFalse(all.contains("is OPEN") || all.contains("Open owner") || all.contains("recorded here, not decided"),
                "the owner question is closed by OR-125 (3): " + all);
        assertFalse(all.contains("this description will change with the ruling"), all);
        assertFalse(all.contains("owner decision pending"), all);
    }

    @Test
    void aRenameToTheNodesOwnNameIsDocumentedAsASuccessThatWritesNothing() {
        String text = SPEC.at("/paths/" + pointer("/cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}")
                + "/patch/description").asText();
        assertTrue(text.contains("own name") || text.contains("its own name"), text);
    }

    // ---------------------------------------------------------------------------------------------- schemas

    @Test
    void requestSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        JsonNode name = schema("NodeNameRequest");
        assertFalse(name.path("additionalProperties").asBoolean(true));
        assertEquals(propertyNames(name), components(NodeNameRequest.class));
        assertEquals(Set.of("name"), textSet(name.path("required")));
        assertEquals(1, name.at("/properties/name/minLength").asInt());
        assertEquals(100, name.at("/properties/name/maxLength").asInt());
        assertFalse(propertyNames(name).contains("organizationId"), "I5");

        JsonNode set = schema("SetEntitlementRequest");
        assertFalse(set.path("additionalProperties").asBoolean(true));
        assertEquals(propertyNames(set), components(SetEntitlementRequest.class));
        assertEquals(Set.of("maxInstances"), textSet(set.path("required")));
        assertEquals(1, set.at("/properties/maxInstances/minimum").asInt(),
                "the table's CHECK (max_instances > 0) and the service's range, not the draft's 0");
        assertEquals(EntitlementAdministration.MAX_INSTANCES_LIMIT, set.at("/properties/maxInstances/maximum").asInt());
    }

    @Test
    void responseSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        for (Object[] pair : new Object[][] {{"Entitlement", Entitlement.class}, {"EntitlementList", EntitlementList.class}}) {
            JsonNode schema = schema((String) pair[0]);
            Class<?> record = (Class<?>) pair[1];
            assertEquals(propertyNames(schema), components(record), pair[0] + " properties");
            assertEquals(textSet(schema.path("required")), requiredComponents(record), pair[0] + " required");
        }
    }

    @Test
    void theRequestRecordsEnforceWhatTheirSchemasSay() {
        assertEquals("x".repeat(100), NodeNameRequest.of("x".repeat(100)).value());
        assertThrows(IllegalArgumentException.class, () -> NodeNameRequest.of(null));
        assertThrows(IllegalArgumentException.class, () -> NodeNameRequest.of(""));
        assertThrows(IllegalArgumentException.class, () -> NodeNameRequest.of(" \t "));
        assertThrows(IllegalArgumentException.class, () -> NodeNameRequest.of("x".repeat(101)));
        assertThrows(IllegalArgumentException.class, () -> NodeNameRequest.of("a\u0007b"));
        assertThrows(IllegalArgumentException.class, () -> new NodeNameRequest(IntNode.valueOf(7)),
                "a number is not a name");
        assertThrows(IllegalArgumentException.class, () -> new SetEntitlementRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new SetEntitlementRequest(DoubleNode.valueOf(1.5)));
        assertThrows(IllegalArgumentException.class, () -> new SetEntitlementRequest(DoubleNode.valueOf(3.0)),
                "3.0 is not an integer on the wire");
        assertThrows(IllegalArgumentException.class, () -> new SetEntitlementRequest(TextNode.valueOf("3")));
        assertThrows(IllegalArgumentException.class, () -> SetEntitlementRequest.of(0));
        assertThrows(IllegalArgumentException.class,
                () -> SetEntitlementRequest.of(EntitlementAdministration.MAX_INSTANCES_LIMIT + 1));
        assertEquals(1, SetEntitlementRequest.of(1).limit());
        assertEquals(EntitlementAdministration.MAX_INSTANCES_LIMIT,
                SetEntitlementRequest.of(EntitlementAdministration.MAX_INSTANCES_LIMIT).limit());
    }

    @Test
    void theSolutionPathParameterIsTheCatalogGrammarAndTheJavaPatternIsTheSame() {
        JsonNode parameter = SPEC.at("/paths/" + pointer("/cp/entitlements/{solution}") + "/parameters/0");
        assertEquals("solution", parameter.path("name").asText());
        assertEquals(EntitlementAdministration.SOLUTION_NAME_PATTERN, parameter.at("/schema/pattern").asText());
        assertEquals(EntitlementController.SOLUTION_NAME.pattern(), EntitlementAdministration.SOLUTION_NAME_PATTERN);
        assertNotEquals("", EntitlementAdministration.SOLUTION_NAME_PATTERN);
    }

    @Test
    void theEntitlementResponseNeverCarriesWhoSetItOrAnOrganization() {
        Set<String> names = propertyNames(schema("Entitlement"));
        for (String forbidden : new String[] {"organizationId", "setBy", "setByPrincipalId", "principalId"}) {
            assertFalse(names.contains(forbidden), forbidden);
        }
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
