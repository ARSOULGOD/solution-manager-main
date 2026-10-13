package com.solace.sm.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.session.Session;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The package catalog's two operations ({@code OR-40}, {@code OR-41}, plan 010 T3.2).
 *
 * <p><b>{@code loadPackage}</b> is a CATALOG operation: {@link AuthenticationInterceptor} resolves its resource
 * (the caller's own organization, {@link SessionOrganizationResourceResolver}) and requires {@code package.load}
 * there BEFORE this method runs. Every gate failure, the entitlement refusal and the audit are the catalog's, not
 * this class's: it only translates.
 *
 * <p><b>{@code listSolutions}</b> is {@code x-sm-scope: reach} (S0 point 24, {@code OR-51}): the interceptor has
 * already required {@code solution.view} at one or more grants in the session's organization. ⚠️ <b>This handler
 * applies NO per-item filter, deliberately.</b> A catalog entry carries no scope path of its own to filter by - it
 * spans every environment that will ever declare an instance of it - so its only boundary is the organization,
 * which {@link SolutionCatalog#list} binds in its own query from the session ({@code I5}). Filtering the entries
 * through {@code ReachAuthorization#authorizedSubset} at the organization would empty the list for exactly the
 * environment-bound operator this classification exists to serve.
 */
@RestController
@RequestMapping("/api/cp/solutions")
public class SolutionController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SolutionCatalog catalog;
    private final RequestOriginFactory requestOriginFactory;

    public SolutionController(SolutionCatalog catalog, RequestOriginFactory requestOriginFactory) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.requestOriginFactory = Objects.requireNonNull(requestOriginFactory, "requestOriginFactory");
    }

    @GetMapping
    @ConsoleOperation("listSolutions")
    public ResponseEntity<SolutionList> listSolutions(Session session) {
        List<Solution> items = catalog.list(session.organizationId()).stream().map(SolutionController::toWire).toList();
        return ResponseEntity.ok(new SolutionList(null, items));
    }

    @PostMapping("/load")
    @ConsoleOperation(value = "loadPackage", resource = SessionOrganizationResourceResolver.class)
    public ResponseEntity<LoadPackageResult> loadPackage(@RequestBody LoadPackageRequest request, Session session) {
        PackageLoad load = catalog.load(session.principal(), requestOriginFactory.create(),
                session.organizationId(), request.pkg().toString());
        LoadedPackage loaded = load.loaded();
        LoadPackageResult body = new LoadPackageResult(loaded.stateVersion(), null, loaded.solutionId(),
                loaded.packageVersion().stream().map(PackageVersionId::value).toList(), loaded.signature());
        // 201 when this call registered the package; 200 for the idempotent re-load of an identical one.
        return ResponseEntity.status(load.newlyLoaded() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    /**
     * {@code getSolutionPackage} (D-4): the raw document the browser's local {@code project()}
     * needs, for Designer live editing (`FE-D33`) and the Solutions screen's package preview
     * (plan 005) — {@code getDesigner}'s own response carries only the server's already-projected
     * {@code viewModel} (`OR-34`). Read-only, reuses {@code solution.view}: see {@link ConsoleOperations}.
     *
     * <p>{@code x-sm-scope: reach}, like {@code listSolutions} and for the same reason (this class's own
     * {@code listSolutions} javadoc): a catalog entry carries no scope path of its own, so the interceptor's reach
     * pre-check (held at >= 1 grant anywhere in the organization) runs instead of a per-resource {@code require} —
     * no {@link ConsoleOperation#resource()} is declared, and none may be ({@link OperationCoverageValidator}).
     *
     * <p>{@code {solutionId}} is checked against the caller's own organization inside {@link SolutionCatalog}'s
     * org-bound query ({@code I5}) — a foreign or unknown id and a version no loaded package of this entry serves
     * are the same {@code 404}, deliberately indistinguishable ({@link ResourceNotFoundException}).
     */
    @GetMapping("/{solutionId}/package")
    @ConsoleOperation("getSolutionPackage")
    public ResponseEntity<SolutionPackageDocument> getSolutionPackage(@PathVariable UUID solutionId,
            @RequestParam(name = "packageVersion", required = false) String packageVersion, Session session) {
        UUID organizationId = session.organizationId();
        String version = packageVersion != null ? packageVersion : latestVersionOf(organizationId, solutionId);
        LoadedPackage loaded = catalog.find(organizationId, solutionId, version)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.SOLUTION_PACKAGE));
        String document = catalog.documentOf(organizationId, solutionId, version)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.SOLUTION_PACKAGE));
        JsonNode pkg = parseStored(document);
        return ResponseEntity.ok(new SolutionPackageDocument(loaded.stateVersion(), null, solutionId,
                resolvedVersionOf(loaded, version), pkg));
    }

    /** The entry's own {@code latestPackageVersion}, as the canonical JSON text {@link SolutionCatalog} expects. */
    private String latestVersionOf(UUID organizationId, UUID solutionId) {
        return catalog.list(organizationId).stream()
                .filter(entry -> entry.solutionId().equals(solutionId))
                .findFirst()
                .map(entry -> canonicalText(entry.latestPackageVersion()))
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.SOLUTION_PACKAGE));
    }

    /** The one member of {@code loaded.packageVersion()} this response resolved to — never the whole array. */
    private static Object resolvedVersionOf(LoadedPackage loaded, String version) {
        return loaded.packageVersion().stream()
                .filter(id -> canonicalText(id).equals(version))
                .findFirst()
                .map(PackageVersionId::value)
                // find()/documentOf() agreed on `version`, so one member always matches; this is unreachable.
                .orElseGet(() -> loaded.packageVersion().get(loaded.packageVersion().size() - 1).value());
    }

    /** An integer id as plain digits, a string id as a canonical (quoted, escaped) JSON string — see `PackageVersionId`. */
    private static String canonicalText(PackageVersionId id) {
        Object value = id.value();
        return value instanceof Long number ? String.valueOf(number) : TextNode.valueOf((String) value).toString();
    }

    private static JsonNode parseStored(String document) {
        try {
            return MAPPER.readTree(document);
        } catch (JsonProcessingException e) {
            // The stored document is this class's own canonicalized write (PackageCanonicalizer) — never
            // request-derived, so quoting it here is not an I6/log-injection concern. An unparseable stored
            // document is a storage-layer bug, not a client error.
            throw new IllegalStateException("stored package document is not valid JSON", e);
        }
    }

    private static Solution toWire(CatalogEntry entry) {
        return new Solution(entry.solutionId(), entry.solution(), entry.name(),
                entry.packageVersions().stream().map(PackageVersionId::value).toList(),
                entry.latestPackageVersion().value(), entry.signature(), entry.stateVersion());
    }
}
