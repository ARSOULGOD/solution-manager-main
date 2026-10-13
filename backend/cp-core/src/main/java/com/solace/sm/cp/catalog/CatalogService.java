package com.solace.sm.cp.catalog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageRejectedException;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.catalog.CatalogRepository.EntryPackageRow;
import com.solace.sm.cp.catalog.CatalogRepository.StoredPackage;
import com.solace.sm.cp.catalog.validation.GateFailure;
import com.solace.sm.cp.catalog.validation.OverlapGate;
import com.solace.sm.cp.catalog.validation.PackageValidator;
import com.solace.sm.cp.catalog.validation.ValidationResult;
import com.solace.sm.cp.events.CatalogEventEmitter;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The package catalog ({@code OR-40}, {@code OR-41}): a load is validated by every gate, checked against the
 * organization's entitlement, and only then stored - the entry (first load of a solution in the organization) and
 * the package in ONE audited transaction ({@code PACKAGE_LOADED}). A load that fails anywhere writes nothing, and
 * the order is the guarantee: nothing is read from the database until the document has passed gates 1, 2 and the
 * semantic gates, and nothing is written until the entitlement and gate 3 have passed.
 *
 * <p>{@code I5}: the organization is an argument of every method and is bound in every query; the gateway passes
 * the session's own. {@code I6}: nothing here puts a value from the document into an exception or a log.
 */
public final class CatalogService implements SolutionCatalog {

    private static final String UNSIGNED = "unsigned";

    /**
     * Strict about duplicate keys: Jackson's tree reader keeps the last of two equal keys silently, so a document
     * could validate against one value and be stored with another reading. Floats stay exact for the digest.
     */
    private static final ObjectMapper READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final PackageValidator validator;
    private final CatalogRepository catalog;
    private final EntitlementService entitlements;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Supplier<UUID> ids;
    private final CatalogEventEmitter packageEvents;
    private final FirstLoadEntitlement firstLoad;

    public CatalogService(PackageValidator validator, CatalogRepository catalog, EntitlementService entitlements,
            AuditContext auditContext, TransactionOperations transactions, Clock clock, Supplier<UUID> ids,
            CatalogEventEmitter packageEvents, FirstLoadEntitlement firstLoad) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.entitlements = Objects.requireNonNull(entitlements, "entitlements");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.packageEvents = Objects.requireNonNull(packageEvents, "packageEvents");
        this.firstLoad = Objects.requireNonNull(firstLoad, "firstLoad");
    }

    @Override
    public PackageLoad load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(packageJson, "packageJson");

        // Gates first, before any database read: production runs gate 2 with dev mode OFF.
        JsonNode document = parse(packageJson);
        ValidationResult result = validator.validate(document, false);
        if (!result.ok()) {
            throw new PackageRejectedException(
                    new ArrayList<>(new LinkedHashSet<>(result.failures().stream().map(GateFailure::gate).toList())));
        }

        String solution = document.get("solution").asText();
        String versionText = PackageCanonicalizer.canonical(document.get("packageVersion"));
        String appliesTo = document.get("appliesTo").asText();
        String canonicalDocument = PackageCanonicalizer.canonical(document);
        String digest = PackageCanonicalizer.digest(canonicalDocument);

        return transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.PACKAGE_LOADED, origin.correlationId(),
                    origin.clientAddress());
            catalog.lockEntry(organizationId, solution);
            if (!entitlements.isEntitled(organizationId, solution)) {
                if (!firstLoad.enabled()) {
                    throw new EntitlementRefusedException();
                }
                // OR-148: a first load entitles the organization, audited as its own ENTITLEMENT_SET beside
                // PACKAGE_LOADED. The audit trigger reads the transaction-local context at each write, so the
                // operation is switched for exactly this insert and restored. lockEntry (above) serialises two
                // first loads; the insert is insert-only, so a row an administrator set meanwhile stands.
                auditContext.set(actor.id(), AuditOperations.ENTITLEMENT_SET, origin.correlationId(),
                        origin.clientAddress());
                entitlements.createIfAbsent(organizationId, solution, firstLoad.maxInstances(), actor.id());
                auditContext.set(actor.id(), AuditOperations.PACKAGE_LOADED, origin.correlationId(),
                        origin.clientAddress());
            }
            Optional<UUID> existingEntry = catalog.findEntryId(organizationId, solution);
            List<StoredPackage> loaded = existingEntry
                    .map(id -> catalog.packagesOf(organizationId, id)).orElse(List.of());

            Optional<StoredPackage> sameVersion = loaded.stream()
                    .filter(p -> p.packageVersionText().equals(versionText)).findFirst();
            if (sameVersion.isPresent()) {
                if (sameVersion.get().digest().equals(digest)) {
                    return new PackageLoad(
                            toLoaded(sameVersion.get(), catalog.stateVersionOf(organizationId, existingEntry.get())),
                            false);
                }
                throw PackageRejectedException.versionAlreadyLoaded();
            }

            List<OverlapGate.NamedPackage> claims = new ArrayList<>();
            for (StoredPackage p : loaded) {
                claims.add(new OverlapGate.NamedPackage("load " + p.loadSeq(), claimOf(solution, p.appliesTo())));
            }
            claims.add(new OverlapGate.NamedPackage("this package", claimOf(solution, appliesTo)));
            if (!OverlapGate.check(claims).isEmpty()) {
                throw new PackageRejectedException(List.of("3"));
            }

            Instant now = clock.instant();
            UUID entryId = existingEntry.orElseGet(() -> {
                UUID minted = ids.get();
                catalog.insertEntry(organizationId, minted, solution, now);
                return minted;
            });
            long loadSeq = catalog.insertPackage(organizationId, entryId, versionText, appliesTo, digest, UNSIGNED,
                    canonicalDocument, actor.id(), now);
            StoredPackage stored = new StoredPackage(loadSeq, entryId, solution, versionText, appliesTo, digest,
                    UNSIGNED);
            PackageLoad announced = new PackageLoad(toLoaded(stored, catalog.stateVersionOf(organizationId, entryId)),
                    true);
            // Offered INSIDE this transaction on purpose: the control plane's after-commit decorator holds it until
            // the commit and discards it on rollback (BE-D1 rule 4), so a refused or failed load announces nothing.
            // Only a load that stored something announces; the idempotent re-load above returned early.
            packageEvents.onPackageLoaded(new TenantId(organizationId), solution, versionText,
                    announced.loaded().stateVersion());
            return announced;
        });
    }

    @Override
    public List<CatalogEntry> list(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        Map<UUID, List<EntryPackageRow>> byEntry = new LinkedHashMap<>();
        for (EntryPackageRow row : catalog.entryPackagesOf(organizationId)) {
            byEntry.computeIfAbsent(row.catalogSolutionId(), id -> new ArrayList<>()).add(row);
        }
        List<CatalogEntry> entries = new ArrayList<>();
        for (List<EntryPackageRow> rows : byEntry.values()) {
            Set<PackageVersionId> versions = new LinkedHashSet<>();
            for (EntryPackageRow row : rows) {
                versions.addAll(versionsOf(row.packageVersionText()));
            }
            EntryPackageRow newest = rows.get(rows.size() - 1);       // rows are in load order
            List<PackageVersionId> newestVersions = versionsOf(newest.packageVersionText());
            entries.add(new CatalogEntry(newest.catalogSolutionId(), newest.solution(), newest.solution(),
                    List.copyOf(versions), newestVersions.get(newestVersions.size() - 1), newest.signature(),
                    newest.loadSeq()));
        }
        return List.copyOf(entries);
    }

    @Override
    public Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
        return canonicalVersionId(packageVersion)
                .flatMap(id -> catalog.findServing(organizationId, catalogSolutionId, id))
                .map(p -> toLoaded(p, catalog.stateVersionOf(organizationId, catalogSolutionId)));
    }

    @Override
    public Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(catalogSolutionId, "catalogSolutionId");
        return canonicalVersionId(packageVersion)
                .flatMap(id -> catalog.documentServing(organizationId, catalogSolutionId, id));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static JsonNode parse(String packageJson) {
        try {
            JsonNode node = READER.readTree(packageJson);
            if (node == null || node.isMissingNode()) {
                throw new PackageRejectedException(List.of("1"));
            }
            if (containsNul(node)) {
                // Both gate-1 implementations accept U+0000, then PostgreSQL's jsonb refuses it with an error whose
                // CONTEXT line quotes the document: a 500 that logs a value (I6). Refused here, before any write.
                throw new PackageRejectedException(List.of("1"));
            }
            return node;
        } catch (JsonProcessingException e) {
            // Never the parser's own message: it quotes the offending input.
            throw new PackageRejectedException(List.of("1"));
        }
    }

    private static boolean containsNul(JsonNode node) {
        if (node.isTextual()) {
            return node.textValue().indexOf('\u0000') >= 0;
        }
        if (node.isObject()) {
            for (java.util.Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext();) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().indexOf('\u0000') >= 0 || containsNul(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode element : node) {
                if (containsNul(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gate 3 reads only {@code solution} and {@code appliesTo}; the stored column carries the latter. */
    private static JsonNode claimOf(String solution, String appliesTo) {
        ObjectNode claim = READER.createObjectNode();
        claim.put("solution", solution);
        claim.put("appliesTo", appliesTo);
        return claim;
    }

    private static LoadedPackage toLoaded(StoredPackage stored, long stateVersion) {
        return new LoadedPackage(stored.catalogSolutionId(), stored.solution(),
                versionsOf(stored.packageVersionText()), stored.digest(), stored.signature(), stateVersion);
    }

    /** The stored text is canonical JSON this class wrote: an array of positive integers and non-empty strings. */
    private static List<PackageVersionId> versionsOf(String packageVersionText) {
        try {
            JsonNode array = READER.readTree(packageVersionText);
            List<PackageVersionId> versions = new ArrayList<>();
            for (JsonNode element : array) {
                versions.add(element.isTextual() ? PackageVersionId.of(element.textValue())
                        : PackageVersionId.of(element.longValue()));
            }
            return versions;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored package_version is not canonical JSON", e);
        }
    }

    /** One package version id as canonical JSON, or empty when {@code text} is not a valid id. */
    private static Optional<String> canonicalVersionId(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = READER.readTree(text);
            if (node != null && node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 1) {
                return Optional.of(Long.toString(node.longValue()));
            }
            if (node != null && node.isTextual() && !node.textValue().isEmpty()) {
                return Optional.of(PackageCanonicalizer.canonical(node));
            }
            return Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
