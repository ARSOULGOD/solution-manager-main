package com.solace.sm.cp.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Catalog persistence port; its JDBC adapter belongs in {@code cp.persistence}. Every method is bound to one
 * organization in its own query ({@code I5}).
 *
 * <p>The write methods are called inside the transaction {@link CatalogService#load} opens, after the audit
 * context is set; none of them opens a transaction of its own.
 */
public interface CatalogRepository {

    /** One package row, without its document (up to ~111 KB each, and no caller of this record wants it). */
    record StoredPackage(long loadSeq, UUID catalogSolutionId, String solution, String packageVersionText,
            String appliesTo, String digest, String signature) {
    }

    /** One (entry, package) pair, for assembling {@code listSolutions} in one query. */
    record EntryPackageRow(UUID catalogSolutionId, String solution, long loadSeq, String packageVersionText,
            String signature) {
    }

    /** Takes a transaction-scoped lock on {@code (organization, solution)}, so two loads of one entry serialize. */
    void lockEntry(UUID organizationId, String solution);

    Optional<UUID> findEntryId(UUID organizationId, String solution);

    void insertEntry(UUID organizationId, UUID catalogSolutionId, String solution, Instant createdAt);

    /** The entry's packages, oldest load first. */
    List<StoredPackage> packagesOf(UUID organizationId, UUID catalogSolutionId);

    /** @return the new package's {@code load_seq} */
    long insertPackage(UUID organizationId, UUID catalogSolutionId, String packageVersionText, String appliesTo,
            String digest, String signature, String documentJson, UUID loadedBy, Instant loadedAt);

    long stateVersionOf(UUID organizationId, UUID catalogSolutionId);

    /** Every package of every entry of the organization, ordered by solution then load order. */
    List<EntryPackageRow> entryPackagesOf(UUID organizationId);

    /** The most recently loaded package of the entry whose {@code packageVersion} array contains the id. */
    Optional<StoredPackage> findServing(UUID organizationId, UUID catalogSolutionId, String packageVersionIdJson);

    /** That package's document text exactly as the database stores it. */
    Optional<String> documentServing(UUID organizationId, UUID catalogSolutionId, String packageVersionIdJson);
}
