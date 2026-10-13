package com.solace.sm.cp.api.catalog;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The organization's package catalog ({@code OR-40}, {@code OR-41}).
 *
 * <p>⛔ Every method takes the organization explicitly and filters by it in its own query ({@code I5}); the
 * gateway passes {@code Session.organizationId()}, never a request field. There is no write port for an
 * entitlement: nothing over HTTP may set one in v1 ({@code OR-48}) - that is the {@code EntitlementMain}
 * operator command.
 */
public interface SolutionCatalog {

    /**
     * Validates every load gate, checks the entitlement, then stores the package in one audited transaction
     * ({@code PACKAGE_LOADED}). A load that fails anywhere writes nothing.
     *
     * @throws PackageRejectedException    a gate failed, or the version is already loaded with different content
     * @throws EntitlementRefusedException the organization holds no entitlement for the package's solution
     */
    PackageLoad load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson);

    /** Every catalog entry of the organization, ordered by solution name. */
    List<CatalogEntry> list(UUID organizationId);

    /**
     * The most recently loaded package of the entry that serves {@code packageVersion}: one member of its
     * {@code packageVersion} array, spelt as canonical JSON ({@code 1}, {@code "v2"}), because a declare picks one
     * member ({@code declaredPackageVersion}). Empty when the entry is not this organization's or no package of it
     * serves that version - the two are not distinguished.
     */
    Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion);

    /**
     * For projection and declare: the document as stored, found as {@link #find} finds it. The store is {@code jsonb},
     * which re-spells the text (key order, spacing) - semantically the loaded document, but NOT the byte sequence the
     * digest was computed over. To check integrity, re-canonicalise it and compare with {@link LoadedPackage#digest()};
     * never hash this text directly.
     */
    Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion);
}
