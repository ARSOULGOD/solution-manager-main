package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * BE-D10 lock for the first executable OpenAPI slice. This is equality, not a presence check:
 * validation the contract expresses but the Java types do not represent is a contract split.
 *
 * <p>⚠️ Mirrors {@link AuthController} directly rather than a separate hand-written
 * {@code AuthConsoleApi} interface. That interface existed once but was never implemented and had
 * drifted stale (it took raw CSRF token {@code String} parameters where the real handlers take a
 * resolved {@link com.solace.sm.cp.api.session.Session}) — deleted rather than corrected, because
 * making {@link AuthController} actually implement it would require reworking its methods off
 * {@code ResponseEntity} (which is how cookies/headers get attached) just to satisfy an interface
 * whose only reader was this test. Checking the concrete controller is the smaller change and
 * keeps this lock meaningful: {@link #wireReturnTypeName} unwraps one level of
 * {@code ResponseEntity<T>} so the assertion is still against the WIRE type, not the wrapper.
 */
class SchemaMirrorLockTest {

    private static final JsonNode SPEC = readSpec();

    @Test
    void everyAuthOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.AuthController");
        assertEquals(Set.of("login", "logout", "getIdentity", "rotateOwnCredential"),
                operationIdsUnder("/cp/auth/"));
        assertMethod(handler, "login", "LoginRequest", "Identity");
        assertMethod(handler, "logout", "Session", "void");
        assertMethod(handler, "getIdentity", "Session", "Identity");
        assertMethod(handler, "rotateOwnCredential", "RotateOwnCredentialRequest", "Session", "void");
    }

    @Test
    void everyHierarchyOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.HierarchyController");
        // plan 013 T2.3: the five writes join the three reads; their handlers and schemas are pinned by
        // HierarchyAdministrationSchemaMirrorTest, this lock keeps the reads' signatures
        assertEquals(Set.of("listOrganizations", "listSubOrganizations", "listEnvironments", "createSubOrganization",
                        "createEnvironment", "renameOrganization", "renameSubOrganization", "renameEnvironment"),
                operationIdsUnder("/cp/hierarchy/"));
        assertMethod(handler, "listOrganizations", "Session", "ScopeNodeList");
        assertMethod(handler, "listSubOrganizations", "UUID", "Session", "ScopeNodeList");
        assertMethod(handler, "listEnvironments", "UUID", "UUID", "Session", "ScopeNodeList");
    }

    @Test
    void everyCatalogOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.SolutionController");
        assertEquals(Set.of("listSolutions", "loadPackage", "getSolutionPackage"), operationIdsUnder("/cp/solutions"));
        assertMethod(handler, "listSolutions", "Session", "SolutionList");
        assertMethod(handler, "loadPackage", "LoadPackageRequest", "Session", "LoadPackageResult");
        assertMethod(handler, "getSolutionPackage", "UUID", "String", "Session", "SolutionPackageDocument");
    }

    @Test
    void catalogResponseTypesMatchTheirRequirednessAndPropertiesExactly() {
        for (String name : new String[] {"Solution", "SolutionList", "LoadPackageResult"}) {
            JsonNode schema = schema(name);
            Class<?> type = requireClass("com.solace.sm.gateway.http." + name);
            assertEquals(flattenedRequired(schema), requiredRecordComponents(type), name + " required");
            assertEquals(flattenedProperties(schema), recordComponents(type), name + " properties");
        }
        assertEquals(Set.of("unsigned"), textSet(schema("Solution").at("/properties/signature/enum")));
        assertEquals(Set.of("unsigned"), textSet(flattenedSchema(schema("LoadPackageResult"), "signature").get("enum")));
        assertTrue(schema("SolutionList").path("allOf").toString().contains("AvailabilityOnly"),
                "a collection carries versions per item (OR-55): the list has no stateVersion of its own");
    }

    @Test
    void loadPackageRequestIsExactlyThePackageKeyAndRefusesAnyOther() throws Exception {
        JsonNode body = requestSchema("/cp/solutions/load");
        assertEquals(Set.of("package"), textSet(body.get("required")));
        assertEquals(Set.of("package"), fieldNames(body.get("properties")));
        assertFalse(body.path("additionalProperties").asBoolean(true), "the contract forbids additional properties");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode wire = mapper.valueToTree(mapper.readValue("{\"package\":{\"solution\":\"cache\"}}",
                LoadPackageRequest.class));
        assertEquals(Set.of("package"), fieldNames(wire));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> mapper.readValue(
                "{\"package\":{},\"organizationId\":\"" + UUID.randomUUID() + "\"}", LoadPackageRequest.class),
                "I5: a body naming an organization is refused, not ignored");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue("{\"package\":[1]}", LoadPackageRequest.class));
    }

    @Test
    void scopeNodeAndScopeNodeListMatchTheirRequirednessExactly() {
        JsonNode scopeNodeList = schema("ScopeNodeList");
        Class<?> listType = requireClass("com.solace.sm.gateway.http.ScopeNodeList");
        assertEquals(flattenedRequired(scopeNodeList), requiredRecordComponents(listType));
        assertEquals(flattenedProperties(scopeNodeList), recordComponents(listType));

        JsonNode scopeNode = schema("ScopeNode");
        Class<?> nodeType = requireClass("com.solace.sm.gateway.http.ScopeNode");
        assertEquals(flattenedRequired(scopeNode), requiredRecordComponents(nodeType));
        assertEquals(flattenedProperties(scopeNode), recordComponents(nodeType));
    }

    @Test
    void everyTargetOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.TargetController");
        // plan 013 T1.4: registerTarget is a sibling method of the same path (its mirror is TargetRegistrationSchemaMirrorTest);
        // T1.5: retireTarget lives under /cp/targets/{targetId}/retire (its mirror is TargetManagementSchemaMirrorTest)
        assertEquals(Set.of("listTargets", "registerTarget", "editTarget", "retireTarget"), operationIdsUnder("/cp/targets"));
        assertMethod(handler, "listTargets", "Session", "TargetList");
    }

    @Test
    void targetAndTargetListMatchTheirRequirednessPropertiesAndEnumsExactly() {
        JsonNode targetList = schema("TargetList");
        Class<?> listType = requireClass("com.solace.sm.gateway.http.TargetList");
        assertEquals(flattenedRequired(targetList), requiredRecordComponents(listType));
        assertEquals(flattenedProperties(targetList), recordComponents(listType));

        JsonNode target = schema("Target");
        Class<?> targetType = requireClass("com.solace.sm.gateway.http.Target");
        assertEquals(flattenedRequired(target), requiredRecordComponents(targetType));
        assertEquals(flattenedProperties(target), recordComponents(targetType));
        assertEquals(textSet(flattenedSchema(target, "kind").get("enum")),
                wireValues(requireClass("com.solace.sm.gateway.http.TargetKind")));
        assertEquals(textSet(flattenedSchema(target, "transport").get("enum")),
                wireValues(requireClass("com.solace.sm.gateway.http.TargetTransport")));

        // The envelope is optional on the wire: a target with no connection record is listed without it, and a
        // schema-optional component must be OMITTED, not serialized as null.
        Object bare = construct(targetType, new Object[] {UUID.randomUUID(), "vm",
                enumConstant("com.solace.sm.gateway.http.TargetKind", "DOCKER"),
                enumConstant("com.solace.sm.gateway.http.TargetTransport", "SSH_DOCKER"),
                construct(requireClass("com.solace.sm.gateway.http.ScopePath"), new Object[] {null, null, null, null, null}),
                null, null, null});
        Set<String> serialized = fieldNames(new ObjectMapper().valueToTree(bare));
        assertFalse(serialized.contains("allowedConfigRoot") || serialized.contains("allowedContainerPrefix"),
                "schema-optional Target fields must be omitted on the wire when absent: " + serialized);
    }

    @Test
    void everyInstanceOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.InstanceController");
        assertEquals("listInstances", operationIdAt("/dp/solutions/{solutionId}/instances", "get"));
        assertEquals("declareInstance", operationIdAt("/dp/environments/{environmentId}/instances", "post"));
        assertEquals("getInstance", operationIdAt("/dp/instances/{instanceId}", "get"));
        assertMethod(handler, "listInstances", "UUID", "Session", "InstanceList");
        assertMethod(handler, "declareInstance", "UUID", "DeclareInstanceRequest", "Session", "Instance");
        assertMethod(handler, "getInstance", "UUID", "Session", "Instance");
    }

    @Test
    void instanceTypesMatchTheirRequirednessAndPropertiesExactly() {
        for (String name : new String[] {"Instance", "InstanceList", "Acquisition"}) {
            JsonNode schema = schema(name);
            Class<?> type = requireClass("com.solace.sm.gateway.http." + name);
            assertEquals(flattenedRequired(schema), requiredRecordComponents(type), name + " required");
            assertEquals(flattenedProperties(schema), recordComponents(type), name + " properties");
        }
        assertEquals(Set.of("direct-http", "via-ssh", "agent"), textSet(schema("Acquisition").at("/properties/mode/enum")));
        assertFalse(schema("Acquisition").path("additionalProperties").asBoolean(true),
                "OR-82: the wire carries the mode and no host");
        assertTrue(schema("InstanceList").path("allOf").toString().contains("AvailabilityOnly"),
                "a collection carries versions per item (OR-55)");

        JsonNode placement = flattenedSchema(schema("Instance"), "placements").at("/items");
        Class<?> placementType = requireClass("com.solace.sm.gateway.http.InstancePlacement");
        assertEquals(textSet(placement.get("required")), requiredRecordComponents(placementType));
        assertEquals(fieldNames(placement.get("properties")), recordComponents(placementType));
        assertEquals(textSet(placement.at("/properties/probeStrength/enum")),
                new TreeSet<>(Set.of("readiness", "liveness", "none")),
                "the wire strengths are the package vocabulary plus none");
        Set<String> wireStrengths = Arrays.stream(com.solace.sm.dp.api.inventory.ProbeStrength.values())
                .map(com.solace.sm.dp.api.inventory.ProbeStrength::wire).collect(Collectors.toCollection(TreeSet::new));
        wireStrengths.add("none");
        assertEquals(textSet(placement.at("/properties/probeStrength/enum")), wireStrengths);
    }

    @Test
    void declareInstanceRequestIsExactlyTheContractsBodyAndRefusesAnyOtherKey() throws Exception {
        JsonNode body = requestSchema("/dp/environments/{environmentId}/instances");
        Class<?> type = requireClass("com.solace.sm.gateway.http.DeclareInstanceRequest");
        assertEquals(textSet(body.get("required")), requiredRecordComponents(type));
        assertEquals(fieldNames(body.get("properties")), recordComponents(type));
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertFalse(fieldNames(body.get("properties")).contains("organizationId"), "I5");
        assertFalse(fieldNames(body.get("properties")).contains("host"), "OR-82");

        JsonNode placement = body.at("/properties/placement");
        Class<?> placementType = requireClass("com.solace.sm.gateway.http.DeclareInstanceRequest$Placement");
        assertEquals(textSet(placement.get("required")), requiredRecordComponents(placementType));
        assertEquals(fieldNames(placement.get("properties")), recordComponents(placementType));
        assertFalse(placement.path("additionalProperties").asBoolean(true));
        assertEquals(128, body.at("/properties/name/maxLength").asInt());

        ObjectMapper mapper = new ObjectMapper();
        String valid = "{\"solutionId\":\"" + UUID.randomUUID() + "\",\"name\":\"n\",\"targetId\":\""
                + UUID.randomUUID() + "\",\"declaredPackageVersion\":1,\"placement\":{\"containerName\":\"c\","
                + "\"configDirectory\":\"/d\",\"containerConfigPath\":\"/p\",\"fileName\":\"f\","
                + "\"healthPort\":1},\"acquisition\":{\"mode\":\"direct-http\"}";
        assertDoesNotThrow(() -> mapper.readValue(valid + "}", DeclareInstanceRequest.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(valid + ",\"organizationId\":\"" + UUID.randomUUID() + "\"}",
                        DeclareInstanceRequest.class), "I5: a body naming an organization is refused, not ignored");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(valid.replace("\"healthPort\":1}", "\"healthPort\":1,\"healthPath\":\"/x\"}")
                        + "}", DeclareInstanceRequest.class), "the probe is the package's, never the body's");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(valid.replace("\"name\":\"n\"", "\"name\":\"" + "x".repeat(129) + "\"")
                        + "}", DeclareInstanceRequest.class), "name is bounded at 128");
    }

    /**
     * Integration plan 014: the host binding is exactly one of {@code targetId} and {@code connectionId}, so neither is
     * in the contract's {@code required} and both are {@code @WireOptional}; {@code deploymentMode} is the closed
     * {@code DeploymentMode} vocabulary, the same three members on the wire, in the gateway's structural check and in
     * the data plane's enum (whose wire form the V039 CHECK repeats). A member outside it is a {@code 400}.
     */
    @Test
    void declareInstanceRequestCarriesTheConnectionBindingAndTheDeploymentModeVocabulary() throws Exception {
        JsonNode body = requestSchema("/dp/environments/{environmentId}/instances");
        Set<String> required = textSet(body.get("required"));
        assertFalse(required.contains("targetId") || required.contains("connectionId") || required.contains("deploymentMode"),
                "exactly-one-of is the orchestrator's 422, not the schema's: " + required);
        assertEquals("uuid", body.at("/properties/connectionId/format").asText());
        assertTrue(body.at("/properties/deploymentMode").toString().contains("#/components/schemas/DeploymentMode"),
                "the vocabulary is referenced, never copied");

        Set<String> wire = textSet(schema("DeploymentMode").get("enum"));
        assertEquals(Set.of("dp-remote", "host-local", "federated"), wire);
        assertEquals("dp-remote", schema("DeploymentMode").get("default").asText());
        assertEquals(wire, new TreeSet<>(DeclareInstanceRequest.DEPLOYMENT_MODES));
        assertEquals(wire, Arrays.stream(com.solace.sm.dp.api.inventory.DeploymentMode.values())
                .map(com.solace.sm.dp.api.inventory.DeploymentMode::wire).collect(Collectors.toCollection(TreeSet::new)));

        ObjectMapper mapper = new ObjectMapper();
        String connectionBacked = "{\"solutionId\":\"" + UUID.randomUUID() + "\",\"name\":\"n\",\"connectionId\":\""
                + UUID.randomUUID() + "\",\"deploymentMode\":\"%s\",\"declaredPackageVersion\":1,\"placement\":"
                + "{\"containerName\":\"c\",\"configDirectory\":\"/d\",\"containerConfigPath\":\"/p\",\"fileName\":\"f\","
                + "\"healthPort\":1},\"acquisition\":{\"mode\":\"direct-http\"}}";
        for (String member : wire) {
            assertDoesNotThrow(() -> mapper.readValue(String.format(connectionBacked, member), DeclareInstanceRequest.class),
                    "a member is well-formed (a reserved one is the orchestrator's 422): " + member);
        }
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(String.format(connectionBacked, "kubernetes"), DeclareInstanceRequest.class));
        assertNull(mapper.readValue(String.format(connectionBacked, "dp-remote"), DeclareInstanceRequest.class).targetId());

        Set<String> placementRequired = textSet(flattenedSchema(schema("Instance"), "placements").at("/items/required"));
        assertFalse(placementRequired.contains("targetId"), "a connection-backed placement has no target");
        assertTrue(fieldNames(flattenedSchema(schema("Instance"), "placements").at("/items/properties"))
                .contains("connectionId"));
    }

    /**
     * Plan 011 P11-1.2, M-Q14 {@code OR-74}: {@code placement.metricsPorts} maps a package source port to the host
     * port it is published on. The generic equality test above locks the NAMES and the required set; this one locks
     * what that cannot see, the map's own bounds (1-65535 on key and value, no leading zero or sign in a key) on both
     * sides, and that the host-side ports stay inside {@code placement}, beside {@code healthPort}, rather than becoming
     * a second place for them. Accepted and not yet acted on: P11-4.1 acts on it.
     */
    @Test
    void declareInstanceRequestMirrorsTheDeclareBodyIncludingPlacementMetricsPorts() throws Exception {
        JsonNode body = requestSchema("/dp/environments/{environmentId}/instances");
        JsonNode placement = body.at("/properties/placement");
        assertTrue(fieldNames(placement.get("properties")).contains("metricsPorts"),
                "the contract's placement carries metricsPorts");
        assertFalse(fieldNames(body.get("properties")).contains("metricsPorts"),
                "OR-74's declare-body map sits beside healthPort in placement, never as a second top-level home");
        assertFalse(textSet(placement.get("required")).contains("metricsPorts"), "metricsPorts is optional");
        assertFalse(fieldNames(schema("Acquisition").get("properties")).contains("metricsPorts"),
                "acquisition stays closed and hostless (OR-71 as amended by OR-82)");

        JsonNode map = placement.at("/properties/metricsPorts");
        assertEquals("object", map.path("type").asText());
        assertEquals(1, map.at("/additionalProperties/minimum").asInt(), "host port lower bound");
        assertEquals(65_535, map.at("/additionalProperties/maximum").asInt(), "host port upper bound");
        String keyPattern = map.at("/propertyNames/pattern").asText();
        assertFalse(keyPattern.isEmpty(), "the source-port key is bounded by a pattern");
        java.util.regex.Pattern key = java.util.regex.Pattern.compile(keyPattern);
        for (String valid : new String[] {"1", "80", "8081", "65535"}) {
            assertTrue(key.matcher(valid).find(), valid + " is a source port");
        }
        for (String invalid : new String[] {"0", "65536", "99999", "080", "+80", "-1", "8081.0", "http", ""}) {
            assertFalse(key.matcher(invalid).find(), invalid + " is not a source port");
        }

        Class<?> placementType = requireClass("com.solace.sm.gateway.http.DeclareInstanceRequest$Placement");
        assertEquals(fieldNames(placement.get("properties")), new TreeSet<>(recordComponents(placementType)),
                "the record's placement components are the contract's, metricsPorts included");
        assertEquals(textSet(placement.get("required")), new TreeSet<>(requiredRecordComponents(placementType)),
                "metricsPorts is optional on the wire: the record marks it @WireOptional");
        RecordComponent metricsPorts = Arrays.stream(placementType.getRecordComponents())
                .filter(component -> component.getName().equals("metricsPorts")).findFirst()
                .orElseThrow(() -> new AssertionError("Placement lacks metricsPorts"));
        assertEquals(Map.class, metricsPorts.getType(), "a map of source port to host port");

        ObjectMapper mapper = new ObjectMapper();
        String declare = "{\"solutionId\":\"" + UUID.randomUUID() + "\",\"name\":\"n\",\"targetId\":\""
                + UUID.randomUUID() + "\",\"declaredPackageVersion\":1,\"placement\":{\"containerName\":\"c\","
                + "\"configDirectory\":\"/d\",\"containerConfigPath\":\"/p\",\"fileName\":\"f\",\"healthPort\":1%s},"
                + "\"acquisition\":{\"mode\":\"direct-http\"}}";
        DeclareInstanceRequest absent = mapper.readValue(String.format(declare, ""), DeclareInstanceRequest.class);
        assertEquals(null, absent.placement().metricsPorts(), "absent means every source port defaults to itself");
        DeclareInstanceRequest present = mapper.readValue(
                String.format(declare, ",\"metricsPorts\":{\"8081\":28081,\"9090\":9090}"), DeclareInstanceRequest.class);
        assertEquals(Map.of("8081", 28_081, "9090", 9_090), present.placement().metricsPorts());
        assertDoesNotThrow(() -> mapper.readValue(String.format(declare, ",\"metricsPorts\":{}"),
                DeclareInstanceRequest.class));
        assertDoesNotThrow(() -> mapper.readValue(String.format(declare, ",\"metricsPorts\":{\"65535\":65535,\"1\":1}"),
                DeclareInstanceRequest.class));
        for (String refused : new String[] {
                ",\"metricsPorts\":{\"0\":8081}", ",\"metricsPorts\":{\"65536\":8081}", ",\"metricsPorts\":{\"abc\":8081}",
                ",\"metricsPorts\":{\"08081\":8081}", ",\"metricsPorts\":{\"+80\":8081}",
                ",\"metricsPorts\":{\"8081\":0}", ",\"metricsPorts\":{\"8081\":65536}", ",\"metricsPorts\":{\"8081\":-1}",
                ",\"metricsPorts\":{\"8081\":null}", ",\"metricsPorts\":[8081]", ",\"metricsPorts\":5"}) {
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> mapper.readValue(String.format(declare, refused), DeclareInstanceRequest.class),
                    "refused as a 400, never ignored: " + refused);
        }
        // The host is never the body's (OR-82): an address beside the map is still an unknown key.
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> mapper.readValue(
                String.format(declare, ",\"metricsPorts\":{\"8081\":28081},\"metricsHost\":\"10.0.0.1\""),
                DeclareInstanceRequest.class), "OR-82: the body carries no host of any kind");
        // And a top-level metricsPorts is refused: the only home is placement.
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> mapper.readValue(
                String.format(declare, "").replace("\"acquisition\"", "\"metricsPorts\":{\"8081\":28081},\"acquisition\""),
                DeclareInstanceRequest.class), "a second home for host-side ports is refused");
    }

    // ---- plan 008 S3: addPlacement, retirePlacement -----------------------------------------------------------

    @Test
    void everyPlacementOperationHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.PlacementController");
        assertEquals("addPlacement", operationIdAt("/dp/instances/{instanceId}/placements", "post"));
        assertEquals("retirePlacement", operationIdAt("/dp/instances/{instanceId}/placements/{placementId}", "delete"));
        assertMethod(handler, "addPlacement", "UUID", "AddPlacementRequest", "Session", "Instance");
        assertMethod(handler, "retirePlacement", "UUID", "UUID", "String", "Session", "PlacementRetirement");
    }

    /**
     * {@code addPlacement}'s body is {@code declareInstance}'s {@code placement} object plus a {@code targetId}: the same
     * record, and the same contract text, so the two cannot disagree on a field, a bound or the {@code metricsPorts} grammar.
     */
    @Test
    void addPlacementRequestIsExactlyTheContractsBodyAndItsPlacementIsDeclaresPlacement() throws Exception {
        JsonNode body = requestSchema("/dp/instances/{instanceId}/placements");
        Class<?> type = requireClass("com.solace.sm.gateway.http.AddPlacementRequest");
        assertEquals(textSet(body.get("required")), requiredRecordComponents(type));
        assertEquals(fieldNames(body.get("properties")), recordComponents(type));
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertFalse(fieldNames(body.get("properties")).contains("organizationId"), "I5");
        assertFalse(fieldNames(body.get("properties")).contains("acquisition"), "the instance's own, not repeated (OR-71)");
        assertFalse(fieldNames(body.get("properties")).contains("declaredPackageVersion"),
                "the probe and the metrics come from the instance's DECLARED version, never the body");

        JsonNode placement = body.at("/properties/placement");
        JsonNode declared = requestSchema("/dp/environments/{environmentId}/instances").at("/properties/placement");
        assertEquals(withoutDescriptions(declared), withoutDescriptions(placement),
                "one contract SHAPE for the two placement objects (the prose of a description differs by operation)");
        assertEquals(DeclareInstanceRequest.Placement.class,
                Arrays.stream(requireClass("com.solace.sm.gateway.http.AddPlacementRequest").getRecordComponents())
                        .filter(component -> component.getName().equals("placement")).findFirst().orElseThrow().getType(),
                "one record for the two");

        ObjectMapper mapper = new ObjectMapper();
        String valid = "{\"targetId\":\"" + UUID.randomUUID() + "\",\"placement\":{\"containerName\":\"c\","
                + "\"configDirectory\":\"/d\",\"containerConfigPath\":\"/p\",\"fileName\":\"f\",\"healthPort\":1}";
        assertDoesNotThrow(() -> mapper.readValue(valid + "}", AddPlacementRequest.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(valid + ",\"organizationId\":\"" + UUID.randomUUID() + "\"}",
                        AddPlacementRequest.class), "I5: a body naming an organization is refused, not ignored");
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> mapper.readValue(valid + ",\"acquisition\":{\"mode\":\"via-ssh\"}}", AddPlacementRequest.class));
    }

    private static JsonNode withoutDescriptions(JsonNode node) {
        JsonNode copy = node.deepCopy();
        stripDescriptions(copy);
        return copy;
    }

    private static void stripDescriptions(JsonNode node) {
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("description");
        }
        node.forEach(SchemaMirrorLockTest::stripDescriptions);
    }

    @Test
    void addPlacementAndRetirePlacementDeclareTheirVerbsScopeAndEveryStatusTheyAnswer() {
        JsonNode add = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/placements") + "/post");
        JsonNode retire = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/placements/{placementId}") + "/delete");
        assertEquals("instance.bindTarget", add.path("x-sm-permission").asText());
        assertEquals(Set.of("target.use"), textSet(add.get("x-sm-permission-also")));
        assertEquals("instance", add.path("x-sm-scope").asText(), "OQ-8.3s (i): at the instance, not the environment");
        assertEquals("instance.bindTarget", retire.path("x-sm-permission").asText());
        assertTrue(retire.path("x-sm-permission-also").isMissingNode(), "removal touches no target");
        assertEquals("instance", retire.path("x-sm-scope").asText());
        assertEquals(Set.of("201", "400", "401", "403", "404", "409", "422"), fieldNames(add.get("responses")));
        assertEquals(Set.of("200", "400", "401", "403", "404", "409"), fieldNames(retire.get("responses")));
        JsonNode workload = retire.at("/parameters/1");
        assertEquals("workload", workload.path("name").asText());
        assertEquals("query", workload.path("in").asText());
        assertTrue(workload.path("required").asBoolean(false), "the request must say what happens to the container");
        assertEquals(Set.of("leave-running"), textSet(workload.at("/schema/enum")));
    }

    @Test
    void placementRetirementMatchesItsRecordExactly() {
        JsonNode schema = schema("PlacementRetirement");
        Class<?> type = requireClass("com.solace.sm.gateway.http.PlacementRetirement");
        assertEquals(textSet(schema.get("required")), requiredRecordComponents(type));
        assertEquals(fieldNames(schema.get("properties")), recordComponents(type));
        assertEquals(Set.of("left-running"), textSet(schema.at("/properties/workload/enum")));
        assertEquals("left-running", PlacementRetirement.LEFT_RUNNING);
    }

    // ---- plan 012 T5: undeclareInstance -----------------------------------------------------------------------

    @Test
    void undeclareInstanceHasAHandWrittenHandlerSignature() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.UndeclareController");
        assertEquals("undeclareInstance", operationIdAt("/dp/instances/{instanceId}", "delete"));
        assertMethod(handler, "undeclareInstance", "UUID", "String", "Session", "InstanceRetirement");
    }

    @Test
    void undeclareInstanceDeclaresItsVerbScopeWorkloadAndEveryStatusItAnswers() {
        JsonNode undeclare = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}") + "/delete");
        assertEquals("instance.undeclare", undeclare.path("x-sm-permission").asText(), "OPS-Q3 P-1: a verb of its own");
        assertTrue(undeclare.path("x-sm-permission-also").isMissingNode(), "it touches no target and no host");
        assertEquals("instance", undeclare.path("x-sm-scope").asText());
        assertEquals(Set.of("200", "400", "401", "403", "404", "409", "503"), fieldNames(undeclare.get("responses")),
                "no 501 remains once the verb exists; 503 is the half-window guard");
        JsonNode workload = undeclare.at("/parameters/1");
        assertEquals("workload", workload.path("name").asText());
        assertEquals("query", workload.path("in").asText());
        assertTrue(workload.path("required").asBoolean(false), "the request must say what happens to the container");
        assertEquals(Set.of("leave-running"), textSet(workload.at("/schema/enum")));
        assertTrue(undeclare.path("requestBody").isMissingNode(), "RFC 9110 section 9.3.5: a DELETE carries no body");
    }

    @Test
    void instanceRetirementMatchesItsRecordsExactly() throws Exception {
        JsonNode schema = schema("InstanceRetirement");
        Class<?> type = requireClass("com.solace.sm.gateway.http.InstanceRetirement");
        assertEquals(flattenedRequired(schema), requiredRecordComponents(type), "required");
        assertEquals(flattenedProperties(schema), recordComponents(type), "properties");
        assertTrue(schema.path("allOf").toString().contains("Versioned"), "a notify entity carries stateVersion (OR-35)");
        assertEquals(Set.of("left-running"), textSet(flattenedSchema(schema, "workload").path("enum")));
        assertEquals("left-running", InstanceRetirement.LEFT_RUNNING);

        JsonNode placement = flattenedSchema(schema, "placements").at("/items");
        Class<?> placementType = requireClass("com.solace.sm.gateway.http.InstanceRetirement$RetiredPlacement");
        assertEquals(textSet(placement.get("required")), requiredRecordComponents(placementType));
        assertEquals(fieldNames(placement.get("properties")), recordComponents(placementType));
        // the persisted vocabulary is getHealth's own (V012's CHECK), plus null for a placement never probed
        Set<String> health = textSet(placement.at("/properties/lastHealth/enum"));
        health.remove("null");
        assertEquals(textSet(flattenedSchema(schema("Health"), "placements").at("/items/properties/state/enum")), health,
                "lastHealth speaks the health vocabulary");

        UUID placementId = UUID.randomUUID();
        String json = new ObjectMapper().writeValueAsString(new InstanceRetirement(7L, null,
                UUID.randomUUID(), "2026-10-08T10:00:00Z", InstanceRetirement.LEFT_RUNNING,
                List.of(new InstanceRetirement.RetiredPlacement(placementId, null))));
        JsonNode wire = new ObjectMapper().readTree(json);
        assertTrue(wire.get("placements").get(0).has("lastHealth") && wire.get("placements").get(0).get("lastHealth").isNull(),
                "a never-probed placement says null; it is not omitted: " + json);
        assertEquals("left-running", wire.get("workload").asText());
        assertFalse(wire.has("availability"), "an absent availability is omitted");
    }

    @Test
    void everyDesignerAndLadderOperationHasAHandWrittenHandlerSignature() {
        Class<?> designer = requireClass("com.solace.sm.gateway.http.DesignerController");
        Class<?> governance = requireClass("com.solace.sm.gateway.http.GovernanceController");
        assertEquals("getDesigner", operationIdAt("/dp/instances/{instanceId}/designer", "get"));
        assertEquals("stageConfig", operationIdAt("/dp/instances/{instanceId}/config", "post"));
        assertEquals("getConfigHistory", operationIdAt("/dp/instances/{instanceId}/config/history", "get"));
        assertEquals("getConfigLadder", operationIdAt("/dp/instances/{instanceId}/config-ladder", "get"));
        assertMethod(designer, "getDesigner", "UUID", "Session", "Designer");
        assertMethod(designer, "stageConfig", "UUID", "StageConfigRequest", "Session", "StagedConfig");
        assertMethod(designer, "getConfigHistory", "UUID", "Session", "ConfigHistory");
        assertMethod(governance, "getConfigLadder", "UUID", "Session", "ConfigLadder");
    }

    /** Plan 010 T10.1 / OR-60 point 17: one call, {@code 200 StagedConfig} without {@code unchanged}, a write-only value. */
    @Test
    void setConfigSecretIsExactlyTheContractsOneCallShapeAndNeverCarriesAnUnchangedFlagOrTheValueBack() throws Exception {
        String path = "/dp/instances/{instanceId}/config/secrets/{fieldKey}";
        assertEquals("setConfigSecret", operationIdAt(path, "post"));
        assertMethod(requireClass("com.solace.sm.gateway.http.ConfigSecretController"), "setConfigSecret", "UUID",
                "String", "SetConfigSecretRequest", "Session", "ConfigSecretResult");

        JsonNode body = requestSchema(path);
        Class<?> request = requireClass("com.solace.sm.gateway.http.SetConfigSecretRequest");
        assertEquals(Set.of("value", "baseStateVersion"), textSet(body.get("required")));
        assertEquals(textSet(body.get("required")), new TreeSet<>(requiredRecordComponents(request)));
        assertEquals(fieldNames(body.get("properties")), new TreeSet<>(recordComponents(request)));
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertTrue(body.at("/properties/value/writeOnly").asBoolean(false), "the literal is write-only");
        assertFalse(fieldNames(body.get("properties")).contains("organizationId"), "I5");
        assertFalse(fieldNames(body.get("properties")).contains("instanceKey"),
                "the contract names no component instance: the field key alone is the target");

        JsonNode ok = SPEC.at("/paths/" + pointer(path) + "/post/responses/200/content/application~1json/schema");
        assertEquals("#/components/schemas/StagedConfig", ok.path("$ref").asText(),
                "the plain StagedConfig, not stageConfig's allOf with unchanged");
        Class<?> result = requireClass("com.solace.sm.gateway.http.ConfigSecretResult");
        assertEquals(flattenedRequired(schema("StagedConfig")), new TreeSet<>(requiredRecordComponents(result)));
        assertEquals(flattenedProperties(schema("StagedConfig")), new TreeSet<>(recordComponents(result)));
        assertFalse(recordComponents(result).contains("unchanged"));
        assertEquals("config.edit", SPEC.at("/paths/" + pointer(path) + "/post/x-sm-permission").asText());
        assertEquals("instance", SPEC.at("/paths/" + pointer(path) + "/post/x-sm-scope").asText());

        ObjectMapper mapper = new ObjectMapper();
        assertDoesNotThrow(() -> mapper.readValue("{\"value\":\"v\",\"baseStateVersion\":3}", SetConfigSecretRequest.class));
        for (String bad : new String[] {"{\"baseStateVersion\":3}", "{\"value\":\"v\"}",
                "{\"value\":\"v\",\"baseStateVersion\":-1}",
                "{\"value\":\"v\",\"baseStateVersion\":3,\"organizationId\":\"" + UUID.randomUUID() + "\"}"}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(bad, SetConfigSecretRequest.class), bad);
        }
    }

    @Test
    void getHealthHasAHandWrittenHandlerSignatureAndItsResponseMirrorsTheContractExactly() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.HealthController");
        assertEquals("getHealth", operationIdAt("/dp/instances/{instanceId}/health", "get"));
        assertMethod(handler, "getHealth", "UUID", "Session", "Health");

        JsonNode health = schema("Health");
        Class<?> healthType = requireClass("com.solace.sm.gateway.http.Health");
        assertEquals(flattenedRequired(health), new TreeSet<>(requiredRecordComponents(healthType)), "Health required");
        assertEquals(flattenedProperties(health), new TreeSet<>(recordComponents(healthType)), "Health properties");
        assertFalse(flattenedProperties(health).contains("state") || flattenedProperties(health).contains("stateVersion"),
                "OR-39/OR-55: no top-level state or stateVersion");
        JsonNode item = flattenedSchema(health, "placements").at("/items");
        Class<?> itemType = requireClass("com.solace.sm.gateway.http.Health$PlacementHealth");
        assertEquals(textSet(item.get("required")), new TreeSet<>(requiredRecordComponents(itemType)));
        assertEquals(fieldNames(item.get("properties")), new TreeSet<>(recordComponents(itemType)));
        assertEquals(textSet(item.at("/properties/state/enum")),
                Arrays.stream(com.solace.sm.dp.api.health.HealthState.values())
                        .map(com.solace.sm.dp.api.health.HealthState::wire).collect(Collectors.toCollection(TreeSet::new)),
                "the DP health vocabulary is the contract's");
    }

    // ---- plan 010 T9.3 (OR-103): getLogLevel, setLogLevel --------------------------------------------------

    @Test
    void theLogLevelOperationsHaveHandWrittenHandlersAndTheLogLevelResponseMirrorsTheContractExactly() {
        Class<?> controller = requireClass("com.solace.sm.gateway.http.LogLevelController");
        assertEquals("getLogLevel", operationIdAt("/dp/instances/{instanceId}/log-levels/{component}", "get"));
        assertEquals("setLogLevel", operationIdAt("/dp/instances/{instanceId}/log-levels/{component}", "post"));
        assertMethod(controller, "getLogLevel", "UUID", "String", "UUID", "Session", "LogLevel");
        assertMethod(controller, "setLogLevel", "UUID", "String", "SetLogLevelRequest", "Session", "ApplyAccepted");

        JsonNode logLevel = schema("LogLevel");
        Class<?> type = requireClass("com.solace.sm.gateway.http.LogLevel");
        assertEquals(flattenedRequired(logLevel), new TreeSet<>(requiredRecordComponents(type)), "LogLevel required");
        assertEquals(flattenedProperties(logLevel), new TreeSet<>(recordComponents(type)), "LogLevel properties");
        assertEquals(textSet(logLevel.at("/allOf/1/properties/liveLevel/enum")),
                Arrays.stream(com.solace.sm.dp.api.logs.LoggerLevel.values()).map(Enum::name)
                        .collect(Collectors.toCollection(TreeSet::new)),
                "the DP's level vocabulary is the contract's: no OFF, no FATAL");
    }

    @Test
    void setLogLevelRequestIsExactlyTheContractsBodyAndRefusesAnyOtherKey() throws Exception {
        JsonNode body = requestSchema("/dp/instances/{instanceId}/log-levels/{component}");
        Class<?> type = requireClass("com.solace.sm.gateway.http.SetLogLevelRequest");
        assertEquals(fieldNames(body.get("properties")), new TreeSet<>(recordComponents(type)));
        assertEquals(Set.of("placementId", "level", "mode"), fieldNames(body.get("properties")),
                "no logger name: the package names the logger, never the browser (OR-103)");
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertEquals(textSet(body.get("required")), Set.of("placementId", "level", "mode"));
        assertEquals(textSet(body.at("/properties/mode/enum")), Set.of("live", "config"));
        assertEquals(textSet(body.at("/properties/level/enum")), Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));

        ObjectMapper mapper = new ObjectMapper();
        String ok = "{\"placementId\":\"" + UUID.randomUUID() + "\",\"level\":\"DEBUG\",\"mode\":\"config\"}";
        assertDoesNotThrow(() -> mapper.readValue(ok, SetLogLevelRequest.class));
        for (String stray : new String[] {ok.replace("}", ",\"logger\":\"ROOT\"}"),
                ok.replace("}", ",\"organizationId\":\"" + UUID.randomUUID() + "\"}")}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(stray, SetLogLevelRequest.class), stray);
        }
    }

    @Test
    void theLogLevelOperationsAreDeclaredWithTheVerbsAndTheStatusesTheServerAnswers() {
        JsonNode read = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/log-levels/{component}") + "/get");
        JsonNode set = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/log-levels/{component}") + "/post");
        assertEquals("logs.read", read.path("x-sm-permission").asText());
        assertEquals("logs.setLevel", set.path("x-sm-permission").asText());
        assertEquals("instance", read.path("x-sm-scope").asText());
        assertEquals("instance", set.path("x-sm-scope").asText());
        for (String status : List.of("404")) {
            assertTrue(read.at("/responses/" + status).isObject(), status);
            assertTrue(set.at("/responses/" + status).isObject(), status);
        }
        assertTrue(set.at("/responses/422").isObject(), "mode live on a direct-HTTP instance");
        assertTrue(set.at("/responses/501").isObject(), "every level change is refused in this release (OR-103)");
        assertTrue(set.at("/responses/501/description").asText().contains("live"),
                "the 501 names the live mode too, not only config");
    }

    @Test
    void readLogsHasAHandWrittenHandlerSignatureAndItsResponseMirrorsTheContractExactly() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.LogsController");
        assertEquals("readLogs", operationIdAt("/dp/instances/{instanceId}/logs/{component}", "get"));
        assertMethod(handler, "readLogs", "UUID", "String", "Integer", "UUID", "String", "String", "Session", "LogPage");

        JsonNode page = schema("LogPage");
        Class<?> pageType = requireClass("com.solace.sm.gateway.http.LogPage");
        assertEquals(flattenedRequired(page), new TreeSet<>(requiredRecordComponents(pageType)), "LogPage required");
        assertEquals(flattenedProperties(page), new TreeSet<>(recordComponents(pageType)), "LogPage properties");
        assertFalse(flattenedProperties(page).contains("stateVersion"), "OR-38: LogPage carries no stateVersion");
        JsonNode item = flattenedSchema(page, "lines").at("/items");
        Class<?> itemType = requireClass("com.solace.sm.gateway.http.LogPage$Line");
        assertEquals(textSet(item.get("required")), new TreeSet<>(requiredRecordComponents(itemType)));
        assertEquals(fieldNames(item.get("properties")), new TreeSet<>(recordComponents(itemType)));
        JsonNode gap = flattenedSchema(page, "gaps").at("/items");
        Class<?> gapType = requireClass("com.solace.sm.gateway.http.LogPage$Gap");
        assertEquals(textSet(gap.get("required")), new TreeSet<>(requiredRecordComponents(gapType)), "LogPage gap required");
        assertEquals(fieldNames(gap.get("properties")), new TreeSet<>(recordComponents(gapType)), "LogPage gap properties");
        assertEquals(textSet(gap.at("/properties/reason/enum")), new TreeSet<>(com.solace.sm.dp.api.logs.LogGapView.REASONS) {{
            add(com.solace.sm.dp.api.logs.LogGapView.UNKNOWN);
        }}, "the gap reasons are the agent's closed vocabulary plus UNKNOWN");
        assertFalse(requiredRecordComponents(pageType).contains("gaps") || requiredRecordComponents(pageType).contains("truncated"),
                "gaps and truncated are optional: an SSH page is unchanged");
    }

    @Test
    void theHomeOperationsHaveHandWrittenHandlerSignaturesAndTheirResponsesMirrorTheContractExactly() {
        Class<?> handler = requireClass("com.solace.sm.gateway.http.HomeController");
        assertEquals("getHomeSummary", operationIdAt("/cp/home/summary", "get"));
        assertEquals("getHomeAttention", operationIdAt("/cp/home/attention", "get"));
        assertMethod(handler, "getHomeSummary", "Session", "HomeSummary");
        assertMethod(handler, "getHomeAttention", "Session", "HomeAttention");

        // HomeSummary: AvailabilityOnly + solutions. No top-level stateVersion (OR-55): a per-row one only.
        JsonNode summary = schema("HomeSummary");
        Class<?> summaryType = requireClass("com.solace.sm.gateway.http.HomeSummary");
        assertEquals(flattenedRequired(summary), new TreeSet<>(requiredRecordComponents(summaryType)), "HomeSummary required");
        assertEquals(flattenedProperties(summary), new TreeSet<>(recordComponents(summaryType)), "HomeSummary properties");
        assertFalse(flattenedProperties(summary).contains("stateVersion"), "OR-55: no top-level stateVersion");
        JsonNode row = flattenedSchema(summary, "solutions").at("/items");
        Class<?> rowType = requireClass("com.solace.sm.gateway.http.HomeSummary$SolutionRow");
        assertEquals(textSet(row.get("required")), new TreeSet<>(requiredRecordComponents(rowType)), "row required");
        assertEquals(fieldNames(row.get("properties")), new TreeSet<>(recordComponents(rowType)), "row properties");
        assertEquals(textSet(row.at("/properties/byHealth/propertyNames/enum")),
                Arrays.stream(com.solace.sm.dp.api.health.HealthState.values())
                        .map(com.solace.sm.dp.api.health.HealthState::wire).collect(Collectors.toCollection(TreeSet::new)),
                "byHealth's nine keys are the DP health vocabulary");

        // HomeAttention: AvailabilityOnly + items, each item with its own stateVersion (OR-60).
        JsonNode attention = schema("HomeAttention");
        Class<?> attentionType = requireClass("com.solace.sm.gateway.http.HomeAttention");
        assertEquals(flattenedRequired(attention), new TreeSet<>(requiredRecordComponents(attentionType)), "HomeAttention required");
        assertEquals(flattenedProperties(attention), new TreeSet<>(recordComponents(attentionType)), "HomeAttention properties");
        assertFalse(flattenedProperties(attention).contains("stateVersion"), "a filtered collection has no version of its own");
        JsonNode item = flattenedSchema(attention, "items").at("/items");
        Class<?> itemType = requireClass("com.solace.sm.gateway.http.HomeAttention$Item");
        assertEquals(textSet(item.get("required")), new TreeSet<>(requiredRecordComponents(itemType)), "item required");
        assertEquals(fieldNames(item.get("properties")), new TreeSet<>(recordComponents(itemType)), "item properties");
        assertTrue(textSet(item.get("required")).contains("stateVersion"), "OR-60: every item carries its own stateVersion");
        assertEquals(textSet(item.at("/properties/reason/enum")),
                Arrays.stream(com.solace.sm.dp.api.home.AttentionReason.values())
                        .map(com.solace.sm.dp.api.home.AttentionReason::wire).collect(Collectors.toCollection(TreeSet::new)),
                "the attention reasons are the contract's");
        assertEquals(textSet(item.at("/properties/state/enum")),
                Arrays.stream(com.solace.sm.dp.api.health.HealthState.values())
                        .map(com.solace.sm.dp.api.health.HealthState::wire).collect(Collectors.toCollection(TreeSet::new)));
    }

    @Test
    void designerResponseTypesMatchTheirRequirednessAndPropertiesExactly() {
        // Designer.generation is `integer | null` and NOT required, yet is written as an explicit null before the
        // first generation ("Null before any generation exists"), so the record cannot mark it @WireOptional.
        JsonNode designer = schema("Designer");
        Class<?> designerType = requireClass("com.solace.sm.gateway.http.Designer");
        Set<String> designerRequired = new TreeSet<>(flattenedRequired(designer));
        designerRequired.add("generation");
        assertEquals(designerRequired, new TreeSet<>(requiredRecordComponents(designerType)), "Designer required");
        assertEquals(flattenedProperties(designer), new TreeSet<>(recordComponents(designerType)), "Designer properties");

        // stageConfig's 200 is `allOf [StagedConfig, {unchanged}]`.
        JsonNode staged = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/config")
                + "/post/responses/200/content/application~1json/schema");
        Class<?> stagedType = requireClass("com.solace.sm.gateway.http.StagedConfig");
        assertEquals(flattenedRequired(staged), new TreeSet<>(requiredRecordComponents(stagedType)), "StagedConfig required");
        assertEquals(flattenedProperties(staged), new TreeSet<>(recordComponents(stagedType)), "StagedConfig properties");

        JsonNode history = schema("ConfigHistory");
        Class<?> historyType = requireClass("com.solace.sm.gateway.http.ConfigHistory");
        assertEquals(flattenedRequired(history), new TreeSet<>(requiredRecordComponents(historyType)));
        assertEquals(flattenedProperties(history), new TreeSet<>(recordComponents(historyType)));
        JsonNode entry = flattenedSchema(history, "entries").at("/items");
        Class<?> entryType = requireClass("com.solace.sm.gateway.http.ConfigHistory$Entry");
        assertEquals(textSet(entry.get("required")), new TreeSet<>(requiredRecordComponents(entryType)));
        assertEquals(fieldNames(entry.get("properties")), new TreeSet<>(recordComponents(entryType)));

        JsonNode ladder = schema("ConfigLadder");
        Class<?> ladderType = requireClass("com.solace.sm.gateway.http.ConfigLadder");
        assertEquals(flattenedRequired(ladder), new TreeSet<>(requiredRecordComponents(ladderType)));
        assertEquals(flattenedProperties(ladder), new TreeSet<>(recordComponents(ladderType)));
        JsonNode rung = flattenedSchema(ladder, "rungs").at("/items");
        Class<?> rungType = requireClass("com.solace.sm.gateway.http.ConfigLadder$Rung");
        assertEquals(textSet(rung.get("required")), new TreeSet<>(requiredRecordComponents(rungType)));
        assertEquals(fieldNames(rung.get("properties")), new TreeSet<>(recordComponents(rungType)));
        JsonNode placement = flattenedSchema(ladder, "placements").at("/items");
        Class<?> placementType = requireClass("com.solace.sm.gateway.http.ConfigLadder$PlacementRung");
        assertEquals(textSet(placement.get("required")), new TreeSet<>(requiredRecordComponents(placementType)));
        assertEquals(fieldNames(placement.get("properties")), new TreeSet<>(recordComponents(placementType)));

        Set<String> rungs = textSet(rung.at("/properties/rung/enum"));
        assertEquals(rungs, textSet(ladder.at("/allOf/1/properties/reached/enum")));
        assertEquals(rungs, Arrays.stream(com.solace.sm.dp.api.config.LadderRung.values())
                .map(com.solace.sm.dp.api.config.LadderRung::wire).collect(Collectors.toCollection(TreeSet::new)),
                "the DP ladder vocabulary is the contract's");
    }

    /** OR-96: the contract says where layout lives, and getDesigner has a place to return it. */
    @Test
    void theDesignerReturnsLayoutAsInstanceKeysToNumericPositionsAndTheContractSaysItIsOutsideConfigHistory() {
        JsonNode layout = flattenedSchema(schema("Designer"), "layout");
        assertEquals("object", layout.path("type").asText(), "Designer.layout");
        assertTrue(flattenedRequired(schema("Designer")).contains("layout"), "always present, empty when none was saved");
        JsonNode position = layout.at("/additionalProperties");
        assertEquals(Set.of("x", "y"), textSet(position.get("required")));
        assertEquals("number", position.at("/properties/x/type").asText());
        assertEquals("number", position.at("/properties/y/type").asText());
        assertFalse(position.path("additionalProperties").asBoolean(true), "a position is exactly x and y");

        String getDescription = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/designer")
                + "/get/description").asText();
        String stageDescription = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/config")
                + "/post/description").asText();
        for (String description : List.of(getDescription, stageDescription)) {
            assertTrue(description.contains("OR-96"), description);
            assertTrue(description.contains("presentation record"), description);
            assertTrue(description.contains("config_generation") || description.contains("generation"), description);
        }
        assertTrue(stageDescription.contains("config.edit"), "authorized by the existing verb, no new one");
    }

    @Test
    void stageConfigRequestIsExactlyTheContractsBodyAndRefusesAnyOtherKey() throws Exception {
        JsonNode body = requestSchema("/dp/instances/{instanceId}/config");
        Class<?> type = requireClass("com.solace.sm.gateway.http.StageConfigRequest");
        assertEquals(textSet(body.get("required")), new TreeSet<>(requiredRecordComponents(type)));
        assertEquals(fieldNames(body.get("properties")), new TreeSet<>(recordComponents(type)));
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertFalse(fieldNames(body.get("properties")).contains("organizationId"), "I5");
        assertFalse(fieldNames(body.get("properties")).contains("instanceId"), "the instance is the path's");

        JsonNode selection = body.at("/properties/componentSelection");
        Class<?> selectionType = requireClass("com.solace.sm.gateway.http.StageConfigRequest$ComponentSelection");
        assertEquals(textSet(selection.get("required")), new TreeSet<>(requiredRecordComponents(selectionType)));
        assertEquals(fieldNames(selection.get("properties")), new TreeSet<>(recordComponents(selectionType)));
        JsonNode instance = selection.at("/properties/instances/items");
        Class<?> instanceType = requireClass("com.solace.sm.gateway.http.StageConfigRequest$Instance");
        assertEquals(textSet(instance.get("required")), requiredRecordComponentsExcept(instanceType, "layout"));
        assertEquals(fieldNames(instance.get("properties")), new TreeSet<>(recordComponents(instanceType)));
        JsonNode layout = instance.at("/properties/layout");
        Class<?> layoutType = requireClass("com.solace.sm.gateway.http.StageConfigRequest$Layout");
        assertEquals(textSet(layout.get("required")), new TreeSet<>(requiredRecordComponents(layoutType)));
        assertEquals(fieldNames(layout.get("properties")), new TreeSet<>(recordComponents(layoutType)));
        JsonNode rendered = schema("RenderedEntry");
        Class<?> renderedType = requireClass("com.solace.sm.gateway.http.StageConfigRequest$Rendered");
        assertEquals(textSet(rendered.get("required")), new TreeSet<>(requiredRecordComponents(renderedType)));
        assertEquals(fieldNames(rendered.get("properties")), new TreeSet<>(recordComponents(renderedType)));

        ObjectMapper mapper = new ObjectMapper();
        String valid = "{\"baseStateVersion\":3,\"componentSelection\":{\"instances\":[{\"instanceKey\":\"i1\","
                + "\"type\":\"t\",\"layout\":{\"x\":1,\"y\":2}}]},\"draftValues\":{\"i1\":{\"k\":null}},"
                + "\"rendered\":[{\"instanceKey\":\"i1\",\"renderedPath\":\"p\",\"value\":{\"a\":[1]}}]";
        assertDoesNotThrow(() -> mapper.readValue(valid + "}", StageConfigRequest.class));
        for (String stray : new String[] {valid + ",\"organizationId\":\"" + UUID.randomUUID() + "\"}",
                valid.replace("\"baseStateVersion\":3,", "\"baseStateVersion\":3,\"extra\":1,") + "}",
                valid.replace("\"type\":\"t\",", "\"type\":\"t\",\"extra\":1,") + "}",
                valid.replace("\"x\":1,", "\"x\":1,\"z\":1,") + "}",
                valid.replace("\"renderedPath\":\"p\",", "\"renderedPath\":\"p\",\"extra\":1,") + "}"}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(stray, StageConfigRequest.class), stray);
        }
    }

    private static Set<String> requiredRecordComponentsExcept(Class<?> type, String... optional) {
        Set<String> result = new TreeSet<>(requiredRecordComponents(type));
        result.removeAll(Arrays.asList(optional));
        return result;
    }

    private static Object enumConstant(String className, String name) {
        for (Object constant : requireClass(className).getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new AssertionError(className + " has no constant " + name);
    }

    @Test
    void authRequestRecordsMatchRequiredFieldsAndBoundsExactly() {
        assertRequestRecord("/cp/auth/login", "LoginRequest", Set.of("organization"), Map.of(
                "organization", new String[] {"type", "minLength", "maxLength"},
                "username", new String[] {"type", "minLength", "maxLength"},
                "credential", new String[] {"type", "minLength", "format"}));
        assertRequestRecord("/cp/auth/credential", "RotateOwnCredentialRequest", Map.of(
                "currentCredential", new String[] {"type", "format"},
                "newCredential", new String[] {"type", "format", "minLength"}));
        JsonNode login = requestSchema("/cp/auth/login");
        assertRejected("LoginRequest", new Object[] {"acme", "", "credential"});
        assertRejected("LoginRequest", new Object[] {"acme",
                "x".repeat(login.at("/properties/username/maxLength").asInt() + 1), "credential"});
        assertRejected("LoginRequest", new Object[] {"acme", "operator", ""});
        // OR-125 (4) / OR-131 Q3: the organization is bounded like the username, and OPTIONAL: absent is a valid request
        // (the service decides), present-but-empty and whitespace-padded are shape faults (W5D-7: never trimmed)
        assertRejected("LoginRequest", new Object[] {"", "operator", "credential"});
        assertRejected("LoginRequest", new Object[] {" acme", "operator", "credential"});
        assertRejected("LoginRequest", new Object[] {"acme\t", "operator", "credential"});
        assertRejected("LoginRequest", new Object[] {"   ", "operator", "credential"});
        assertNull(new LoginRequest(null, "operator", "credential").organization());
        assertRejected("LoginRequest", new Object[] {
                "x".repeat(login.at("/properties/organization/maxLength").asInt() + 1), "operator", "credential"});
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
        // OR-116: Identity.organizations is required (empty array, never absent) and its items mirror
        // VisibleOrganization exactly.
        assertTrue(flattenedRequired(identity).contains("organizations"));
        JsonNode visibleOrganization = schema("VisibleOrganization");
        Class<?> visibleOrganizationType = requireClass("com.solace.sm.gateway.http.VisibleOrganization");
        assertEquals(flattenedRequired(visibleOrganization), requiredRecordComponents(visibleOrganizationType));
        assertEquals(flattenedProperties(visibleOrganization), recordComponents(visibleOrganizationType));
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

        String login = new LoginRequest("acme", "operator", loginSecret).toString();
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
        JsonNode login = mapper.valueToTree(new LoginRequest("acme", "operator", "login-secret"));
        JsonNode rotation = mapper.valueToTree(new RotateOwnCredentialRequest("current-secret", "new-secret-value"));

        assertEquals(fieldNames(requestSchema("/cp/auth/login").get("properties")), fieldNames(login));
        assertEquals(fieldNames(requestSchema("/cp/auth/credential").get("properties")), fieldNames(rotation));
        assertEquals("login-secret", login.path("credential").asText());
        assertEquals("current-secret", rotation.path("currentCredential").asText());
        assertEquals("new-secret-value", rotation.path("newCredential").asText());
    }

    @Test
    void availabilityEnumsSerializeAsTheContractsLowerCaseValues() throws Exception {
        // The contract's wire values are lower-case (live, degraded, unavailable / events, polling); a Java constant
        // name on the wire makes a generated client reject the response.
        JsonNode availability = schema("Availability");
        assertEquals(textSet(availability.at("/properties/state/enum")),
                serializedValues(requireClass("com.solace.sm.gateway.http.AvailabilityState")));
        assertEquals(textSet(availability.at("/properties/mode/enum")),
                serializedValues(requireClass("com.solace.sm.gateway.http.AvailabilityMode")));
    }

    private static Set<String> serializedValues(Class<?> enumType) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> values = new TreeSet<>();
        for (Object constant : enumType.getEnumConstants()) {
            values.add(mapper.readTree(mapper.writeValueAsString(constant)).asText());
        }
        return values;
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
                false, java.util.List.of(), java.util.List.of(), csrfToken);

        assertFalse(identity.toString().contains(csrfToken), "Identity toString must not leak csrfToken");
        assertTrue(identity.toString().contains("[REDACTED]"), "Identity toString must make redaction explicit");
        JsonNode json = new ObjectMapper().valueToTree(identity);
        assertEquals("csrf-secret-sentinel", json.path("csrfToken").asText());
        assertEquals(flattenedRequired(schema("Identity")), fieldNames(json));

        assertEquals(flattenedProperties(schema("Identity")), fieldNames(json));
    }

    @Test
    void problemBodyMatchesTheProblemSchemaExactly() {
        JsonNode problem = schema("Problem");
        Class<?> problemBodyType = requireClass("com.solace.sm.gateway.http.ProblemBody");

        assertEquals(flattenedRequired(problem), requiredRecordComponents(problemBodyType));
        assertEquals(flattenedProperties(problem), recordComponents(problemBodyType));
    }

    @Test
    void errorCodeEnumMatchesTheOpenApiErrorCodeSchemaExactly() {
        JsonNode errorCode = schema("ErrorCode");
        Class<?> errorCodeType = requireClass("com.solace.sm.gateway.http.ErrorCode");

        Set<String> openApiValues = textSet(errorCode.get("enum"));
        Set<String> javaValues = Arrays.stream(errorCodeType.getEnumConstants())
                .map(value -> ((Enum<?>) value).name())
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(openApiValues, javaValues);
    }

    /**
     * The enum test above locks the NAMES; the status each code carries is documented only as prose
     * in the schema's description ({@code - `CODE` (409) — ...}), so a status edited on one side
     * (T0.1 carried {@code ENTITLEMENT_REFUSED} 409 as an unpinned gap) drifted silently. Read the
     * documented status out of that prose and require the Java one to equal it, code for code.
     */
    @Test
    void everyErrorCodeCarriesTheHttpStatusItsOpenApiDescriptionDocuments() {
        String description = schema("ErrorCode").get("description").asText();
        java.util.regex.Matcher documented = java.util.regex.Pattern
                .compile("(?m)^\\s*-\\s+`([A-Z_]+)`\\s+\\((\\d{3})\\)").matcher(description);
        Map<String, Integer> openApiStatus = new java.util.TreeMap<>();
        while (documented.find()) {
            openApiStatus.put(documented.group(1), Integer.parseInt(documented.group(2)));
        }
        Map<String, Integer> javaStatus = new java.util.TreeMap<>();
        for (ErrorCode code : ErrorCode.values()) {
            javaStatus.put(code.name(), code.status());
        }

        assertEquals(javaStatus, openApiStatus,
                "ErrorCode statuses must equal the `- `CODE` (status)` lines of the ErrorCode description");
    }

    /**
     * S4 review (MINOR): {@code declareInstance} answered {@code 404} for an environment or target the caller cannot
     * see (design 5.6 rule 4) while the contract declared no 404, and the {@code ErrorCode} parity test cannot see an
     * undeclared per-operation status. Every operation that names a resource the authorizer resolves BEFORE the body
     * is read ({@code x-sm-scope: environment | instance}) answers 404 for another organization's or a sibling's
     * resource, so every one of them must declare it.
     */
    @Test
    void everyOperationScopedToAResourceDeclaresTheNotFoundTheServerAnswers() {
        Set<String> missing = new TreeSet<>();
        Set<String> checked = new TreeSet<>();
        SPEC.get("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(method -> {
            JsonNode operation = method.getValue();
            String scope = operation.path("x-sm-scope").asText("");
            if (operation.has("operationId") && ("environment".equals(scope) || "instance".equals(scope))) {
                checked.add(operation.get("operationId").asText());
                if (!operation.path("responses").has("404")) {
                    missing.add(operation.get("operationId").asText());
                }
            }
        }));

        assertTrue(checked.contains("declareInstance") && checked.contains("getInstance"),
                "the scan must reach the resource-scoped operations: " + checked);
        assertEquals(Set.of(), missing, "resource-scoped operations that do not declare 404");
    }

    // ---- plan 010 T6.3: submitApply, observeApply, stopInstance ---------------------------------------------

    @Test
    void everyApplyOperationHasAHandWrittenHandlerSignature() {
        Class<?> controller = requireClass("com.solace.sm.gateway.http.ApplyController");
        assertEquals("submitApply", operationIdAt("/dp/instances/{instanceId}/apply", "post"));
        assertEquals("observeApply", operationIdAt("/dp/instances/{instanceId}/apply/observe", "get"));
        assertEquals("stopInstance", operationIdAt("/dp/instances/{instanceId}/stop", "post"));
        assertMethod(controller, "submitApply", "UUID", "SubmitApplyRequest", "Session", "AcceptedRequest");
        assertMethod(controller, "observeApply", "UUID", "UUID", "Long", "Integer", "Session", "ApplyObservationList");
        assertMethod(controller, "stopInstance", "UUID", "StopRequest", "Session", "ApplyAccepted");
    }

    @Test
    void applyResponseTypesMatchTheirRequirednessAndPropertiesExactly() {
        for (String name : List.of("ApplyAccepted", "ApplyObservation", "ApplyObservationList")) {
            JsonNode schema = schema(name);
            Class<?> type = requireClass("com.solace.sm.gateway.http." + name);
            assertEquals(flattenedRequired(schema), new TreeSet<>(requiredRecordComponents(type)), name + " required");
            assertEquals(flattenedProperties(schema), new TreeSet<>(recordComponents(type)), name + " properties");
        }
        JsonNode step = flattenedSchema(schema("ApplyObservation"), "steps").at("/items");
        Class<?> stepType = requireClass("com.solace.sm.gateway.http.ApplyObservation$Step");
        assertEquals(textSet(step.get("required")), new TreeSet<>(requiredRecordComponents(stepType)));
        assertEquals(fieldNames(step.get("properties")), new TreeSet<>(recordComponents(stepType)));
        // T6.9: what a gate can be reported to have proved is the package vocabulary's strengths, nothing wider
        Set<String> wireStrengths = new TreeSet<>();
        for (com.solace.sm.dp.api.inventory.ProbeStrength strength : com.solace.sm.dp.api.inventory.ProbeStrength.values()) {
            wireStrengths.add(strength.wire());
        }
        assertEquals(textSet(step.at("/properties/gateProved/enum")), wireStrengths, "gateProved enum");
    }

    @Test
    void submitApplyRequestIsExactlyTheContractsBodyAndItsStrategiesAreTheContractsFive() throws Exception {
        JsonNode body = requestSchema("/dp/instances/{instanceId}/apply");
        Class<?> type = requireClass("com.solace.sm.gateway.http.SubmitApplyRequest");
        assertEquals(textSet(body.get("required")), requiredRecordComponentsExcept(type, "outageConfirmedBy", "schedule"));
        assertEquals(fieldNames(body.get("properties")), new TreeSet<>(recordComponents(type)));
        assertTrue(body.at("/properties/schedule/$ref").asText().endsWith("/ScheduleWindow"),
                "plan 012 T7: the optional window is the contract's ScheduleWindow");
        assertFalse(fieldNames(body.get("properties")).contains("organizationId"), "I5");
        assertFalse(fieldNames(body.get("properties")).contains("requestedBy"), "the actor is the session's");
        assertTrue(textSet(body.get("required")).contains("placementId"), "BE-D23: instance-wide apply is refused");

        Set<String> contractStrategies = textSet(schema("Strategy").get("enum"));
        assertEquals(contractStrategies, new TreeSet<>(SubmitApplyRequest.STRATEGIES), "the five engine tokens");
        assertEquals(Set.of("manual", "rolling-health-gated", "rolling-no-validation", "restart-all"),
                new TreeSet<>(ApplyOrchestrator.SERVED_STRATEGIES),
                "the four strategies that RUN; scheduled is a window on submitApply/rollbackApply, not a fifth (plan 012 T7)");
        assertTrue(contractStrategies.containsAll(ApplyOrchestrator.SERVED_STRATEGIES));

        ObjectMapper mapper = new ObjectMapper();
        String valid = "{\"generation\":3,\"strategy\":\"manual\",\"placementId\":\"" + UUID.randomUUID() + "\"}";
        assertDoesNotThrow(() -> mapper.readValue(valid, SubmitApplyRequest.class));
        for (String bad : new String[] {valid.replace("\"generation\":3,", ""),
                valid.replace("\"generation\":3", "\"generation\":0"),
                valid.replace("\"generation\":3", "\"generation\":1.5"),
                valid.replace("\"generation\":3", "\"generation\":\"3\""),
                valid.replace("manual", "yolo"), valid.replaceAll(",\"placementId\":\"[^\"]*\"", "")}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(bad, SubmitApplyRequest.class), bad);
        }
    }

    @Test
    void stopRequestIsExactlyTheContractsBodyAndRefusesAGenerationAStrategyAndAnyOtherKey() throws Exception {
        JsonNode body = requestSchema("/dp/instances/{instanceId}/stop");
        Class<?> type = requireClass("com.solace.sm.gateway.http.StopRequest");
        assertEquals(fieldNames(body.get("properties")), new TreeSet<>(recordComponents(type)));
        assertFalse(body.path("additionalProperties").asBoolean(true));
        assertEquals(Set.of("placementId"), fieldNames(body.get("properties")),
                "no generation, no strategy, no outageConfirmedBy: the engine rejects all three on a stop");
        assertFalse(SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/stop") + "/post/requestBody/required")
                .asBoolean(true), "the body is optional: absent means instance-wide");

        ObjectMapper mapper = new ObjectMapper();
        assertDoesNotThrow(() -> mapper.readValue("{}", StopRequest.class));
        assertDoesNotThrow(() -> mapper.readValue("{\"placementId\":null}", StopRequest.class));
        for (String stray : new String[] {"{\"generation\":1}", "{\"strategy\":\"manual\"}",
                "{\"outageConfirmedBy\":\"x\"}", "{\"organizationId\":\"" + UUID.randomUUID() + "\"}"}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(stray, StopRequest.class), stray);
        }
    }

    @Test
    void applyAndStopAreDeclaredAsAdministratorOnlyAndAsAlsoNeedingRestartExactlyWhereTheServerChecksIt() {
        JsonNode submit = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/apply") + "/post");
        assertEquals("config.apply", submit.path("x-sm-permission").asText());
        assertEquals(Set.of("instance.restart", "instance.restart.overrideSafety"),
                textSet(submit.get("x-sm-permission-also")), "plan 012 T2: the ungated strategies need the second verb");
        assertEquals("instance", submit.path("x-sm-scope").asText());
        JsonNode stop = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/stop") + "/post");
        assertEquals("instance.stop", stop.path("x-sm-permission").asText());
        assertEquals("instance", stop.path("x-sm-scope").asText(), "OR-16: authorized at the owning instance");
        assertFalse(stop.has("x-sm-permission-also"));
        JsonNode observe = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/apply/observe") + "/get");
        assertEquals("config.view", observe.path("x-sm-permission").asText());
        for (JsonNode operation : List.of(submit, stop)) {
            assertTrue(operation.at("/responses/202").isObject(), "202 Accepted");
            assertTrue(operation.at("/responses/404").isObject(), "404 is declared");
        }
        assertFalse(submit.at("/responses/501").isObject(), "plan 012 T7 built scheduled: nothing on submitApply is 501 now");
        assertTrue(submit.at("/responses/422").isObject(), "the scheduled token, a bad window, an unstaged generation");
        assertTrue(submit.at("/responses/409").isObject() && stop.at("/responses/409").isObject());
    }

    // ---- plan 012 T7: scheduled applies and rollbacks (OPS-Q9 Sch-2) ---------------------------------------------

    @Test
    void theScheduleOperationsAreDeclaredWithTheirVerbsAndHaveHandWrittenHandlers() {
        Class<?> controller = requireClass("com.solace.sm.gateway.http.ScheduleController");
        assertEquals("listApplySchedules", operationIdAt("/dp/instances/{instanceId}/apply-schedules", "get"));
        assertEquals("cancelApplySchedule",
                operationIdAt("/dp/instances/{instanceId}/apply-schedules/{scheduleId}", "delete"));
        assertMethod(controller, "listApplySchedules", "UUID", "Integer", "Session", "ApplyScheduleList");
        assertMethod(controller, "cancelApplySchedule", "UUID", "UUID", "Session", "ApplySchedule");
        JsonNode list = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/apply-schedules") + "/get");
        JsonNode cancel = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/apply-schedules/{scheduleId}") + "/delete");
        assertEquals("config.view", list.path("x-sm-permission").asText());
        assertEquals("config.apply", cancel.path("x-sm-permission").asText(),
                "any config.apply holder at the instance may cancel, not only the submitter");
        assertEquals("instance", list.path("x-sm-scope").asText());
        assertEquals("instance", cancel.path("x-sm-scope").asText());
        assertFalse(list.has("x-sm-permission-also") || cancel.has("x-sm-permission-also"));
        assertTrue(list.at("/responses/200").isObject() && cancel.at("/responses/200").isObject());
        assertTrue(cancel.at("/responses/409").isObject(), "a schedule that is no longer pending");
        for (JsonNode operation : List.of(list, cancel)) {
            assertTrue(operation.at("/responses/404").isObject(), "404 is declared");
            assertTrue(operation.at("/responses/403").isObject(), "403 is declared");
        }
        assertEquals(Set.of("limit"), new TreeSet<>(list.get("parameters").findValuesAsText("name")),
                "the list is bounded by limit alone");
        assertEquals(Set.of(1, 200, 50), Set.of(list.at("/parameters/0/schema/minimum").asInt(),
                list.at("/parameters/0/schema/maximum").asInt(), list.at("/parameters/0/schema/default").asInt()),
                "limit 1..200, default 50, as observeApply");
        assertEquals(ScheduleController.DEFAULT_LIMIT, list.at("/parameters/0/schema/default").asInt());
        assertEquals(ScheduleController.MAX_LIMIT, list.at("/parameters/0/schema/maximum").asInt());
    }

    @Test
    void submitAndRollbackAnswer202WithEitherAnApplyAcceptedOrAScheduleAcceptedAndRollbackNames422() {
        Class<?> controller = requireClass("com.solace.sm.gateway.http.ApplyController");
        assertMethod(controller, "rollbackApply", "UUID", "RollbackRequest", "Session", "AcceptedRequest");
        for (String path : List.of("/dp/instances/{instanceId}/apply", "/dp/instances/{instanceId}/apply/rollback")) {
            JsonNode accepted = SPEC.at("/paths/" + pointer(path) + "/post/responses/202/content/application~1json/schema/oneOf");
            assertEquals(Set.of("#/components/schemas/ApplyAccepted", "#/components/schemas/ScheduleAccepted"),
                    new TreeSet<>(accepted.findValuesAsText("$ref")), path);
            assertTrue(SPEC.at("/paths/" + pointer(path) + "/post/responses/422").isObject(), path + " names 422");
        }
        assertEquals(Set.of("com.solace.sm.gateway.http.ApplyAccepted", "com.solace.sm.gateway.http.ScheduleAccepted"),
                Arrays.stream(requireClass("com.solace.sm.gateway.http.AcceptedRequest").getPermittedSubclasses())
                        .map(Class::getName).collect(Collectors.toCollection(TreeSet::new)),
                "the sealed pair is the contract's oneOf");
        JsonNode rollback = SPEC.at("/paths/" + pointer("/dp/instances/{instanceId}/apply/rollback") + "/post");
        assertTrue(rollback.at("/requestBody/content/application~1json/schema/properties/schedule/$ref").asText()
                .endsWith("/ScheduleWindow"));
    }

    @Test
    void scheduleWindowIsExactlyTheContractsObjectAndEveryOtherShapeIsRejected() {
        JsonNode contract = schema("ScheduleWindow");
        assertEquals(Set.of("notBefore", "notAfter"), textSet(contract.get("required")));
        assertEquals(Set.of("notBefore", "notAfter"), fieldNames(contract.get("properties")));
        assertFalse(contract.path("additionalProperties").asBoolean(true), "a stray key is refused, not ignored");
        assertEquals("date-time", contract.at("/properties/notBefore/format").asText());

        ObjectMapper mapper = new ObjectMapper();
        assertDoesNotThrow(() -> ScheduleWindow.parse(mapper.readTree(
                "{\"notBefore\":\"2030-01-02T02:00:00Z\",\"notAfter\":\"2030-01-02T09:30:00+05:30\"}")));
        assertEquals(java.time.Instant.parse("2030-01-01T22:00:00Z"), assertDoesNotThrow(() -> ScheduleWindow.parse(
                mapper.readTree("{\"notBefore\":\"2030-01-02T03:30:00+05:30\",\"notAfter\":\"2030-01-03T00:00:00Z\"}")))
                .notBefore(), "an offset is converted, not dropped");
        for (String bad : new String[] {"null", "\"02:00\"", "[]", "{}", "{\"notBefore\":\"2030-01-02T02:00:00Z\"}",
                "{\"notAfter\":\"2030-01-02T02:00:00Z\"}",
                "{\"notBefore\":\"2030-01-02\",\"notAfter\":\"2030-01-03\"}",
                "{\"notBefore\":\"2030-01-02T02:00:00\",\"notAfter\":\"2030-01-02T03:00:00\"}",
                "{\"notBefore\":1893463200,\"notAfter\":1893466800}",
                "{\"notBefore\":null,\"notAfter\":\"2030-01-02T03:00:00Z\"}",
                "{\"notBefore\":\"2030-01-02T03:00:00Z\",\"notAfter\":\"2030-01-02T03:00:00Z\"}",
                "{\"notBefore\":\"2030-01-02T04:00:00Z\",\"notAfter\":\"2030-01-02T03:00:00Z\"}",
                "{\"notBefore\":\"2030-01-02T02:00:00Z\",\"notAfter\":\"2030-01-02T03:00:00Z\",\"repeat\":\"daily\"}"}) {
            assertThrows(IllegalArgumentException.class, () -> ScheduleWindow.parse(mapper.readTree(bad)), bad);
        }
    }

    @Test
    void submitAndRollbackRequestsTakeAScheduleOrNoneAndAnExplicitNullIsNotNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String placement = "\"placementId\":\"" + UUID.randomUUID() + "\"";
        String window = "\"schedule\":{\"notBefore\":\"2030-01-02T02:00:00Z\",\"notAfter\":\"2030-01-02T04:00:00Z\"}";
        String submit = "{\"generation\":3,\"strategy\":\"manual\"," + placement;
        String rollback = "{\"generation\":2,\"strategy\":\"manual\"," + placement;
        assertNull(mapper.readValue(submit + "}", SubmitApplyRequest.class).schedule(), "absent is an ordinary apply");
        assertNull(mapper.readValue(rollback + "}", RollbackRequest.class).schedule());
        assertNotNull(mapper.readValue(submit + "," + window + "}", SubmitApplyRequest.class).scheduleWindow());
        assertNotNull(mapper.readValue(rollback + "," + window + "}", RollbackRequest.class).scheduleWindow());
        for (String bad : new String[] {"\"schedule\":null", "\"schedule\":{}", "\"schedule\":\"tomorrow\"",
                "\"schedule\":{\"notBefore\":\"2030-01-02T02:00:00Z\"}"}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(submit + "," + bad + "}", SubmitApplyRequest.class), bad);
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(rollback + "," + bad + "}", RollbackRequest.class), bad);
        }
    }

    @Test
    void theScheduleResponseTypesMatchTheirRequirednessPropertiesAndClosedVocabulariesExactly() {
        for (String name : List.of("ScheduleAccepted", "ApplySchedule", "ApplyScheduleList")) {
            JsonNode schema = schema(name);
            Class<?> type = requireClass("com.solace.sm.gateway.http." + name);
            assertEquals(flattenedRequired(schema), new TreeSet<>(requiredRecordComponents(type)), name + " required");
            assertEquals(flattenedProperties(schema), new TreeSet<>(recordComponents(type)), name + " properties");
        }
        JsonNode item = schema("ApplySchedule");
        assertEquals(Set.of("PENDING", "ADMITTED", "CANCELLED", "MISSED", "REFUSED"), textSet(item.at("/properties/state/enum")));
        assertEquals(Set.of("apply", "rollback"), textSet(item.at("/properties/operation/enum")));
        assertEquals(new TreeSet<>(ApplyOrchestrator.SERVED_STRATEGIES), textSet(item.at("/properties/strategy/enum")),
                "the strategy that runs at fire is one of the four, never scheduled");
        Set<String> reasons = new TreeSet<>();
        for (com.solace.sm.dp.api.apply.ScheduleReason reason : com.solace.sm.dp.api.apply.ScheduleReason.values()) {
            reasons.add(reason.wire());
        }
        assertEquals(reasons, textSet(item.at("/properties/reason/enum")), "V025's CHECK, the port's enum and the wire agree");
        assertFalse(fieldNames(item.get("properties")).stream().anyMatch(n -> n.toLowerCase().contains("by")
                || n.toLowerCase().contains("confirm")), "no principal and no confirmation on the wire");
        assertTrue(schema("ScheduleAccepted").at("/properties/applyId").isMissingNode(), "nothing was admitted");
    }

    // ---- plan 012 T2: the two ungated strategies on the four operations (OPS-Q7 C-2, OPS-Q6 B-1) -------------

    @Test
    void theFourStrategyOperationsDeclareBothSecondaryVerbsAndAnOptionalOutageConfirmationAndNoneIs501() {
        for (String path : List.of("/dp/instances/{instanceId}/apply", "/dp/instances/{instanceId}/apply/rollback",
                "/dp/instances/{instanceId}/config/restore-runtime", "/dp/fleet-applies")) {
            JsonNode operation = SPEC.at("/paths/" + pointer(path) + "/post");
            assertEquals(Set.of("instance.restart", "instance.restart.overrideSafety"),
                    textSet(operation.get("x-sm-permission-also")), path);
            JsonNode body = path.endsWith("fleet-applies") ? schema("FleetApplyRequest") : requestSchema(path);
            assertTrue(body.at("/properties/outageConfirmedBy").isObject(), path + " carries outageConfirmedBy (C-2)");
            assertFalse(textSet(body.path("required")).contains("outageConfirmedBy"),
                    path + ": optional - required only for restart-all, by the handler");
            String description = body.at("/properties/outageConfirmedBy/description").asText();
            assertTrue(description.contains("principal") && description.contains("ignored"),
                    path + ": says the server records the session principal and ignores the typed value: " + description);
            // plan 012 T7 built scheduled, so no STRATEGY is 501 (NOT_IMPLEMENTED). Plan 015 T3.3 adds the one other 501 these
            // operations may carry: AGENT_OPERATION_UNSUPPORTED, for a placement whose target is an agent - never a strategy.
            JsonNode notBuilt = operation.at("/responses/501");
            assertTrue(notBuilt.isMissingNode() || (notBuilt.at("/description").asText().contains("AGENT_OPERATION_UNSUPPORTED")
                    && !notBuilt.at("/description").asText().contains("NOT_IMPLEMENTED")),
                    path + ": plan 012 T7 built scheduled; nothing here is a NOT_IMPLEMENTED 501");
            String refused = operation.at("/responses/422/description").asText();
            assertTrue(refused.contains("scheduled") && !refused.contains("restart-all")
                    && !refused.contains("rolling-no-validation"), path + ": scheduled is the 422 token: " + refused);
        }
    }

    @Test
    void rollbackAndRestoreRequestsMirrorTheirContractBodiesIncludingTheConfirmation() throws Exception {
        for (String[] each : new String[][] {{"/dp/instances/{instanceId}/apply/rollback", "RollbackRequest"},
                {"/dp/instances/{instanceId}/config/restore-runtime", "RestoreRuntimeRequest"}}) {
            JsonNode body = requestSchema(each[0]);
            Class<?> type = requireClass("com.solace.sm.gateway.http." + each[1]);
            assertEquals(fieldNames(body.get("properties")), new TreeSet<>(recordComponents(type)), each[1]);
            assertFalse(fieldNames(body.get("properties")).contains("organizationId"), "I5");
            assertFalse(fieldNames(body.get("properties")).contains("requestedBy"), "the actor is the session's");
        }
        ObjectMapper mapper = new ObjectMapper();
        String placement = "\"placementId\":\"" + UUID.randomUUID() + "\"";
        for (String bad : new String[] {"{" + placement + ",\"strategy\":\"restart-all\"}",
                "{" + placement + ",\"strategy\":\"restart-all\",\"outageConfirmedBy\":\"\"}",
                "{" + placement + ",\"strategy\":\"restart-all\",\"outageConfirmedBy\":\"  \"}"}) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(bad, RestoreRuntimeRequest.class), bad);
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(bad.replace("{", "{\"generation\":2,"), RollbackRequest.class), bad);
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(bad.replace("{", "{\"generation\":2,"), SubmitApplyRequest.class), bad);
        }
        String confirmed = "{" + placement + ",\"strategy\":\"restart-all\",\"outageConfirmedBy\":\"x\"}";
        assertDoesNotThrow(() -> mapper.readValue(confirmed, RestoreRuntimeRequest.class));
        assertDoesNotThrow(() -> mapper.readValue(confirmed.replace("{", "{\"generation\":2,"), RollbackRequest.class));
        assertDoesNotThrow(() -> mapper.readValue(confirmed.replace("{", "{\"generation\":2,"), SubmitApplyRequest.class));
    }

    private static void assertRequestRecord(String path, String typeName, Map<String, String[]> expectedKeywords) {
        assertRequestRecord(path, typeName, Set.of(), expectedKeywords);
    }

    /** {@code optional}: the schema-optional fields (declared, not required; {@code @WireOptional} in the record). */
    private static void assertRequestRecord(String path, String typeName, Set<String> optional,
            Map<String, String[]> expectedKeywords) {
        JsonNode body = requestSchema(path);
        assertTrue(body.isObject(), path + " must declare an application/json request body");
        Set<String> required = new TreeSet<>(expectedKeywords.keySet());
        required.removeAll(optional);
        assertEquals(required, new TreeSet<>(textSet(body.get("required"))), path + " required fields");
        assertEquals(expectedKeywords.keySet(), fieldNames(body.get("properties")), path + " properties");
        Class<?> type = requireClass("com.solace.sm.gateway.http." + typeName);
        assertEquals(required, new TreeSet<>(requiredRecordComponents(type)), typeName + " required components");
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
        assertEquals(expectedReturn, wireReturnTypeName(method), name + " return type");
        assertEquals(Arrays.asList(simpleTypesAndReturn).subList(0, simpleTypesAndReturn.length - 1),
                Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName).toList(), name + " parameters");
    }

    /**
     * The gateway's handler methods return {@code ResponseEntity<T>} so they can attach cookies
     * and headers; this mirror test cares about the WIRE type {@code T}, so it unwraps exactly
     * one level of that generic parameter rather than reporting "ResponseEntity" for every method.
     */
    private static String wireReturnTypeName(Method method) {
        if (ResponseEntity.class.equals(method.getReturnType())
                && method.getGenericReturnType() instanceof ParameterizedType parameterized) {
            Type argument = parameterized.getActualTypeArguments()[0];
            if (argument instanceof Class<?> clazz) {
                return Void.class.equals(clazz) ? "void" : clazz.getSimpleName();
            }
        }
        return method.getReturnType().getSimpleName();
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

    private static String operationIdAt(String path, String method) {
        return SPEC.at("/paths/" + pointer(path) + "/" + method + "/operationId").asText();
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
