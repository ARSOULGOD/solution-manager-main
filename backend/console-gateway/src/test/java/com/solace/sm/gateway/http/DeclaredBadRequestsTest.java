package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;

/**
 * Fable review (code-GW-4): a status the gateway answers must be one the contract declares, or a generated client, a
 * generated type and the Postman test script (whose allowed set is the declared codes) call a legitimate refusal an error.
 *
 * <p>The three operations below answer {@code 400 BAD_REQUEST} today: {@code observeApply} explicitly for a {@code limit}
 * outside 1..200 or a negative {@code sinceStateVersion} ({@code ApplyController}), and all three through
 * {@code GatewayErrorAdvice} for a path or query value that is not a UUID ({@code MethodArgumentTypeMismatchException}).
 */
class DeclaredBadRequestsTest {

    @Test
    void everyOperationThatAnswersBadRequestDeclaresIt() {
        JsonNode paths = spec().get("paths");
        for (String operationId : List.of("observeApply", "listInstances", "observeFleetApply")) {
            boolean found = false;
            for (JsonNode path : paths) {
                for (JsonNode operation : path) {
                    if (operation.isObject() && operationId.equals(operation.path("operationId").asText())) {
                        found = true;
                        assertTrue(operation.path("responses").has("400"),
                                operationId + " answers 400 BAD_REQUEST and must declare it");
                    }
                }
            }
            assertTrue(found, operationId + " is in the contract");
        }
    }

    // ------------------------------------------------ every answerable 400, enumerated from the handlers (OR-112 (6), GW-4)

    /**
     * The operations whose handler answers {@code 400} with NO parameter the framework converts: a bounded string or an
     * RFC 3339 value parsed by hand, a body checked by hand. Found by reading each site below; {@link
     * #everyExplicitBadRequestSiteIsAccountedFor} fails when a site is added or removed without this map being revisited.
     */
    private static final Map<String, String> EXPLICIT = Map.of(
            "listMetricInstances", "MetricsCatalogController: entityType and search are bounded strings",
            "getMetricsCatalog", "MetricsCatalogController: entityType is a bounded string",
            "getLabelValues", "MetricsCatalogController: labelKey and entityType are bounded strings");

    /** {@code GatewayRejectionException(ErrorCode.BAD_REQUEST)} call sites per source file: the tripwire for {@link #EXPLICIT}. */
    private static final Map<String, Integer> EXPLICIT_SITES = Map.ofEntries(
            Map.entry("LogLevelOrchestrator.java", 2), Map.entry("ApplyController.java", 1), Map.entry("LogsController.java", 4),
            Map.entry("ExportController.java", 2), Map.entry("MetricsCatalogController.java", 3),
            Map.entry("DashboardController.java", 2), Map.entry("QueryComposer.java", 1),
            // plan 013 T2.3: setEntitlement's {solution} outside the catalog's name grammar (the operation is ALSO
            // framework-400: a request body, which the enumeration above finds)
            Map.entry("EntitlementController.java", 1),
            // plan 008 S3: retirePlacement's ?workload= must be exactly leave-running; the operation is ALSO framework-400
            // (a UUID path value), which the enumeration above finds, so only the site needs accounting for here
            Map.entry("PlacementController.java", 1),
            // plan 012 T5: undeclareInstance's ?workload= must be exactly leave-running (also framework-400: a UUID path value)
            Map.entry("UndeclareController.java", 1),
            // plan 012 T7: listApplySchedules's ?limit= outside 1..200 (the operation is ALSO framework-400: a UUID path value)
            Map.entry("ScheduleController.java", 1),
            // plan 013 T1.4: a body the ownership-scope resolver cannot read as a scope, and a body past the peek bound;
            // scanHostKeys and registerTarget are ALSO framework-400 (a request body), which the enumeration finds
            Map.entry("OwnershipScopeResolver.java", 2), Map.entry("PeekableBodyRequest.java", 3),
            // plan 013 T4, BE-D66: updateConnection's contract minProperties 1 - an empty body binds fine (every
            // field is optional) but names no change; the operation is ALSO framework-400 (an unknown top-level key,
            // via UpdateConnectionRequest's any-setter), which the enumeration above finds.
            Map.entry("ConnectionManagementController.java", 1),
            // plan 013 T3.2: a grant body whose scope the resolver cannot read, and a username the service will not store
            // (createUser is ALSO framework-400: a request body; grantAssignment likewise)
            Map.entry("GrantedScopeResolver.java", 2), Map.entry("UserAdministrationController.java", 1));

    @Test
    void everyOperationTheFrameworkOrAHandlerCanAnswer400ForDeclaresIt() throws Exception {
        Map<String, String> answerable = new TreeMap<>(EXPLICIT);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.solace.sm.gateway")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                ConsoleOperation operation = AnnotatedElementUtils.findMergedAnnotation(method, ConsoleOperation.class);
                if (operation == null) {
                    continue;
                }
                String why = frameworkReason(method);
                if (why != null) {
                    answerable.merge(operation.value(), why, (a, b) -> a);
                }
            }
        }
        assertTrue(answerable.size() > 20, "the enumeration found the handlers: " + answerable.keySet());

        JsonNode paths = spec().get("paths");
        Map<String, JsonNode> operations = new TreeMap<>();
        for (JsonNode path : paths) {
            for (JsonNode operation : path) {
                if (operation.isObject() && operation.has("operationId")) {
                    operations.put(operation.get("operationId").asText(), operation);
                }
            }
        }
        List<String> undeclared = new ArrayList<>();
        List<String> notProblem = new ArrayList<>();
        for (Map.Entry<String, String> entry : answerable.entrySet()) {
            JsonNode operation = operations.get(entry.getKey());
            assertTrue(operation != null, entry.getKey() + " has a handler and is not in the contract");
            JsonNode bad = operation.path("responses").path("400");
            if (bad.isMissingNode()) {
                undeclared.add(entry.getKey() + " (" + entry.getValue() + ")");
            } else if (!bad.toString().contains("#/components/responses/BadRequest")
                    && !bad.toString().contains("#/components/schemas/Problem")) {
                notProblem.add(entry.getKey());
            }
        }
        assertEquals(List.of(), undeclared, "each of these answers 400 BAD_REQUEST and must declare it");
        assertEquals(List.of(), notProblem, "and declares it as the shared Problem body (the shared response, or inline with the reason)");
    }

    /**
     * Why the FRAMEWORK answers {@code 400} for this handler, or null: a path or query value it must convert (a UUID, a
     * number, a date), a required query parameter or header that may be missing, or a request body that may be unreadable or
     * of another media type. {@code GatewayErrorAdvice.handleBadRequest} maps each of these exceptions to {@code 400}.
     */
    private static String frameworkReason(Method method) {
        for (Parameter parameter : method.getParameters()) {
            Class<?> type = parameter.getType();
            if (parameter.isAnnotationPresent(RequestBody.class)) {
                return "a request body";
            }
            PathVariable path = parameter.getAnnotation(PathVariable.class);
            if (path != null && type != String.class) {
                return "path value " + type.getSimpleName();
            }
            RequestParam query = parameter.getAnnotation(RequestParam.class);
            if (query != null) {
                if (type != String.class) {
                    return "query value " + type.getSimpleName();
                }
                if (query.required() && ValueConstants.DEFAULT_NONE.equals(query.defaultValue())) {
                    return "a required query parameter";
                }
            }
            RequestHeader header = parameter.getAnnotation(RequestHeader.class);
            if (header != null && header.required()) {
                return "a required header";
            }
        }
        return null;
    }

    @Test
    void everyExplicitBadRequestSiteIsAccountedFor() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("console-gateway/src/main/java"))) {
            root = root.getParent();
        }
        Path main = root == null ? Path.of("src/main/java") : root.resolve("console-gateway/src/main/java");
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = Files.readString(file);
                int count = text.split("new GatewayRejectionException\\(ErrorCode\\.BAD_REQUEST\\)", -1).length - 1;
                if (count > 0) {
                    found.put(file.getFileName().toString(), count);
                }
            }
        }
        assertEquals(new TreeMap<>(EXPLICIT_SITES), found, "a handler's explicit 400 sites changed: revisit EXPLICIT above");
        assertEquals(new TreeSet<>(List.of("getLabelValues", "getMetricsCatalog", "listMetricInstances")),
                new TreeSet<>(EXPLICIT.keySet()));
    }

    private static JsonNode spec() {
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
        throw new IllegalStateException("contracts/openapi/console.yaml not found above " + Path.of("").toAbsolutePath());
    }
}
