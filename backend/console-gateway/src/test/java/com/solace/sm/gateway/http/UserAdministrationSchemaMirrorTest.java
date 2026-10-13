package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.solace.sm.cp.api.authz.ConsoleOperations;
import com.solace.sm.cp.api.identity.PrincipalAdministration;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Plan 013 T3.2's slice of the BE-D10 lock: the eight user and role operations, and the {@code login} change of
 * {@code OR-125} (4), against {@code contracts/openapi/console.yaml}, as equality and not presence.
 *
 * <p>It also pins what the contract SAYS about the username {@code 409}, because that wording is a decision and not
 * decoration: {@code OR-125} (4) makes a username unique per organization, so a {@code 409} must never be described as
 * (or be) an install-wide clash, and the W3D-1 oracle it closed must not be documented as still open.
 */
class UserAdministrationSchemaMirrorTest {

    private static final JsonNode SPEC = readSpec();

    // ------------------------------------------------------------------------------------------- operations

    @Test
    void theEightOperationsAreWhereTheContractPutsThemWithTheContractsVerbScopeAndResolver() {
        Class<?> users = UserAdministrationController.class;
        Class<?> roles = RoleAssignmentController.class;
        assertOperation(users, "listUsers", "get", "/cp/admin/users", "user.view", "organization", GetMapping.class,
                SessionOrganizationResourceResolver.class);
        assertOperation(users, "createUser", "post", "/cp/admin/users", "user.create", "organization",
                PostMapping.class, SessionOrganizationResourceResolver.class);
        assertOperation(users, "deactivateUser", "post", "/cp/admin/users/{userId}/deactivate", "user.deactivate",
                "organization", PostMapping.class, UserResolver.class);
        assertOperation(users, "resetUserCredential", "put", "/cp/admin/users/{userId}/credential", "user.edit",
                "organization", PutMapping.class, UserResolver.class);
        assertOperation(roles, "listRoles", "get", "/cp/admin/roles", "role.view", "organization", GetMapping.class,
                SessionOrganizationResourceResolver.class);
        assertOperation(roles, "listAssignments", "get", "/cp/admin/assignments", "role.view", "organization",
                GetMapping.class, SessionOrganizationResourceResolver.class);
        assertOperation(roles, "grantAssignment", "post", "/cp/admin/assignments", "role.assign", "named",
                PostMapping.class, GrantedScopeResolver.class);
        assertOperation(roles, "revokeAssignment", "delete", "/cp/admin/assignments/{assignmentId}", "role.assign",
                "named", DeleteMapping.class, AssignmentResolver.class);
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
        assertEquals(List.of("admin"), textList(operation.path("tags")));
        boolean mutation = !verb.equals("get");
        assertEquals(mutation, operation.path("parameters").toString().contains("#/components/parameters/CsrfToken"),
                operationId + ": a mutation declares the CSRF header, a read does not");
    }

    private static String mappedValue(Method handler, Class<? extends java.lang.annotation.Annotation> mapping) {
        String[] values;
        if (mapping == PostMapping.class) {
            values = handler.getAnnotation(PostMapping.class).value();
        } else if (mapping == PutMapping.class) {
            values = handler.getAnnotation(PutMapping.class).value();
        } else if (mapping == DeleteMapping.class) {
            values = handler.getAnnotation(DeleteMapping.class).value();
        } else {
            values = handler.getAnnotation(GetMapping.class).value();
        }
        return values.length == 0 ? "" : values[0];
    }

    @Test
    void theTwoOperationsThatTakeACredentialAreMarkedAndTheOthersAreNot() throws Exception {
        for (String name : new String[] {"createUser", "resetUserCredential"}) {
            Method handler = Arrays.stream(UserAdministrationController.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(handler.isAnnotationPresent(CredentialBearing.class), name + " refuses an insecure channel");
            assertTrue(SPEC.at("/paths/" + pointer(name.equals("createUser") ? "/cp/admin/users"
                            : "/cp/admin/users/{userId}/credential") + "/" + (name.equals("createUser") ? "post" : "put")
                    + "/description").asText().contains("INSECURE_TRANSPORT"), name + ": the contract says so");
        }
        for (String name : new String[] {"listUsers", "deactivateUser"}) {
            Method handler = Arrays.stream(UserAdministrationController.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            assertFalse(handler.isAnnotationPresent(CredentialBearing.class), name);
        }
    }

    @Test
    void everyOperationDeclaresTheStatusesTheGatewayCanAnswer() {
        assertStatuses("/cp/admin/users", "get", "200", "401", "403");
        assertStatuses("/cp/admin/users", "post", "201", "400", "401", "403", "409");
        assertStatuses("/cp/admin/users/{userId}/deactivate", "post", "200", "400", "401", "403", "404", "409");
        assertStatuses("/cp/admin/users/{userId}/credential", "put", "200", "400", "401", "403", "404", "409");
        assertStatuses("/cp/admin/roles", "get", "200", "401", "403");
        assertStatuses("/cp/admin/assignments", "get", "200", "400", "401", "403");
        assertStatuses("/cp/admin/assignments", "post", "200", "201", "400", "401", "403", "404", "409", "422");
        assertStatuses("/cp/admin/assignments/{assignmentId}", "delete", "204", "400", "401", "403", "404", "409");
    }

    private static void assertStatuses(String path, String verb, String... statuses) {
        JsonNode responses = SPEC.at("/paths/" + pointer(path) + "/" + verb + "/responses");
        assertEquals(new TreeSet<>(Arrays.asList(statuses)), fieldNames(responses), path + " " + verb);
    }

    // ---------------------------------------------------------------- OR-125 (4): the wording about the 409

    @Test
    void theUsernameConflictIsDocumentedAsPerOrganizationAndTheOracleIsNotLeftOpen() {
        JsonNode post = SPEC.at("/paths/" + pointer("/cp/admin/users") + "/post");
        String text = post.path("description").asText() + " " + post.at("/responses/409/description").asText();
        assertTrue(text.contains("OR-125"), "cites the ruling");
        assertTrue(text.contains("per organization") || text.contains("PER ORGANIZATION"), text);
        assertTrue(text.contains("case"), "compared without regard to case");
        assertFalse(text.toLowerCase().contains("install-wide unique"), "the old rule is gone: " + text);
        assertFalse(text.contains("W3D-1"), "the oracle is closed, not documented as open: " + text);
        assertTrue(post.at("/responses/409/content").has("application/problem+json"), "409 is a Problem");
    }

    @Test
    void theLoginBodyCarriesTheOrganizationAndTheContractSaysAnUnknownOneIsARefusedCredential() {
        JsonNode login = SPEC.at("/paths/" + pointer("/cp/auth/login") + "/post");
        JsonNode body = login.at("/requestBody/content/application~1json/schema");
        // OR-131 Q3: the organization is declared and bounded but OPTIONAL
        assertEquals(Set.of("username", "credential"), textSet(body.path("required")));
        assertTrue(body.at("/properties").has("organization"));
        assertEquals(1, body.at("/properties/organization/minLength").asInt());
        assertEquals(256, body.at("/properties/organization/maxLength").asInt());
        String description = login.path("description").asText();
        assertTrue(description.contains("OR-125"), description);
        assertTrue(description.contains("exactly as a wrong credential"), description);
        assertTrue(description.contains("OR-131") && description.contains("OPTIONAL"), description);
        String refused = SPEC.at("/components/responses/LoginRefused/description").asText();
        assertTrue(refused.contains("Unknown organization"), refused);
    }

    // ---------------------------------------------------------------------------------------------- schemas

    @Test
    void requestSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        JsonNode create = schema("CreateUserRequest");
        assertFalse(create.path("additionalProperties").asBoolean(true));
        assertEquals(propertyNames(create), components(CreateUserRequest.class));
        assertEquals(Set.of("username", "initialCredential"), textSet(create.path("required")));
        assertEquals(CreateUserRequest.MAX_USERNAME_LENGTH, create.at("/properties/username/maxLength").asInt());
        assertEquals(PrincipalAdministration.MAX_USERNAME_LENGTH, CreateUserRequest.MAX_USERNAME_LENGTH);
        assertEquals(CreateUserRequest.MIN_CREDENTIAL_LENGTH, create.at("/properties/initialCredential/minLength").asInt());
        assertEquals(PrincipalAdministration.MIN_CREDENTIAL_LENGTH, CreateUserRequest.MIN_CREDENTIAL_LENGTH);
        assertEquals(CreateUserRequest.MAX_CREDENTIAL_LENGTH, create.at("/properties/initialCredential/maxLength").asInt());
        assertEquals(PrincipalAdministration.MAX_CREDENTIAL_LENGTH, CreateUserRequest.MAX_CREDENTIAL_LENGTH);
        assertTrue(create.at("/properties/initialCredential/writeOnly").asBoolean(), "I6: write-only");
        assertFalse(propertyNames(create).contains("organizationId"), "I5");

        JsonNode reset = SPEC.at("/paths/" + pointer("/cp/admin/users/{userId}/credential")
                + "/put/requestBody/content/application~1json/schema");
        assertFalse(reset.path("additionalProperties").asBoolean(true));
        assertEquals(propertyNames(reset), components(ResetUserCredentialRequest.class));
        assertEquals(Set.of("resetCredential"), textSet(reset.path("required")));
        assertEquals(CreateUserRequest.MIN_CREDENTIAL_LENGTH, reset.at("/properties/resetCredential/minLength").asInt());
        assertEquals(CreateUserRequest.MAX_CREDENTIAL_LENGTH, reset.at("/properties/resetCredential/maxLength").asInt());
        assertTrue(reset.at("/properties/resetCredential/writeOnly").asBoolean(), "I6: write-only");

        JsonNode grant = schema("GrantAssignmentRequest");
        assertFalse(grant.path("additionalProperties").asBoolean(true));
        assertEquals(propertyNames(grant), components(GrantAssignmentRequest.class));
        assertEquals(Set.of("principalId", "roleId", "scope"), textSet(grant.path("required")));
        assertEquals("#/components/schemas/ScopeRef", grant.at("/properties/scope/$ref").asText(),
                "check 14 (I5): a request body names a scope without an organization");
    }

    @Test
    void responseSchemasAndRecordsAgreeOnPropertiesAndRequiredness() {
        for (Object[] pair : new Object[][] {{"User", User.class}, {"UserList", UserList.class}, {"Role", Role.class},
                {"RoleList", RoleList.class}, {"Assignment", Assignment.class}, {"AssignmentList", AssignmentList.class}}) {
            JsonNode schema = schema((String) pair[0]);
            Class<?> record = (Class<?>) pair[1];
            assertEquals(propertyNames(schema), components(record), pair[0] + " properties");
            assertEquals(textSet(schema.path("required")), requiredComponents(record), pair[0] + " required");
            assertFalse(schema.path("additionalProperties").asBoolean(true), pair[0] + " is closed");
        }
        assertEquals(Set.of("active", "deactivated"), textSet(schema("User").at("/properties/status/enum")));
        assertEquals(Set.of("active", "deactivated"),
                Arrays.stream(UserStatus.values()).map(UserStatus::wireValue).collect(Collectors.toSet()));
        assertEquals(Set.of("local"), textSet(schema("User").at("/properties/source/enum")),
                "only a local user is administered here");
        assertEquals(Set.of("Administrator", "Operator", "Viewer"), textSet(schema("Role").at("/properties/name/enum")));
    }

    @Test
    void aUserNeverCarriesACredentialAHashOrAnOrganization() {
        Set<String> names = propertyNames(schema("User"));
        for (String forbidden : new String[] {"credential", "credentialHash", "hash", "password", "initialCredential",
                "resetCredential", "organizationId", "organization"}) {
            assertFalse(names.contains(forbidden), forbidden);
        }
    }

    @Test
    void theRequestRecordsEnforceWhatTheirSchemasSay() {
        ObjectMapper m = new ObjectMapper();
        String twelve = "x".repeat(12);
        assertEquals("u", new CreateUserRequest(m.valueToTree("u"), m.valueToTree(twelve)).usernameValue());
        for (JsonNode[] bad : new JsonNode[][] {
                {null, m.valueToTree(twelve)}, {m.valueToTree(""), m.valueToTree(twelve)},
                {m.valueToTree("x".repeat(65)), m.valueToTree(twelve)}, {IntNode.valueOf(7), m.valueToTree(twelve)},
                {m.valueToTree("u"), null}, {m.valueToTree("u"), m.valueToTree("x".repeat(11))},
                {m.valueToTree("u"), m.valueToTree("x".repeat(1025))}, {m.valueToTree("u"), IntNode.valueOf(123456789)}}) {
            assertThrows(IllegalArgumentException.class, () -> new CreateUserRequest(bad[0], bad[1]));
        }
        assertEquals(twelve.length(), new ResetUserCredentialRequest(m.valueToTree(twelve)).credential().length);
        assertThrows(IllegalArgumentException.class, () -> new ResetUserCredentialRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new ResetUserCredentialRequest(m.valueToTree("short")));
        assertThrows(IllegalArgumentException.class, () -> new ResetUserCredentialRequest(IntNode.valueOf(1)));
        // 64 code points: a supplementary character counts once, as the schema counts it
        String astral = "😀".repeat(64);
        assertEquals(astral, new CreateUserRequest(m.valueToTree(astral), m.valueToTree(twelve)).usernameValue());
        assertThrows(IllegalArgumentException.class,
                () -> new CreateUserRequest(m.valueToTree(astral + "x"), m.valueToTree(twelve)));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantAssignmentRequest(null, UUID.randomUUID(), ScopeRef.ORGANIZATION));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantAssignmentRequest(UUID.randomUUID(), null, ScopeRef.ORGANIZATION));
        assertThrows(IllegalArgumentException.class,
                () -> new GrantAssignmentRequest(UUID.randomUUID(), UUID.randomUUID(), null));
    }

    @Test
    void theUserIdPathParameterIsAUuid() {
        JsonNode parameter = SPEC.at("/components/parameters/UserId");
        assertEquals("userId", parameter.path("name").asText());
        assertEquals("path", parameter.path("in").asText());
        assertEquals("uuid", parameter.at("/schema/format").asText());
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
        return fieldNames(schema.path("properties"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
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
        return textSet(node).stream().toList();
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
