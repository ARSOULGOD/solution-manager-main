package com.solace.sm.cp.persistence;

import com.solace.sm.cp.catalog.CatalogRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link CatalogRepository} over {@code sm_control}, served with the {@code sm_cp_runtime} credential. Every
 * statement binds its parameters ({@code I5}) and names its organization in its own {@code WHERE}.
 *
 * <p>{@code sm_cp_runtime} holds no {@code UPDATE} on the catalog tables, so the entry cannot be row-locked
 * ({@code FOR UPDATE} needs that privilege). {@link #lockEntry} takes a transaction-scoped advisory lock instead,
 * which releases itself at commit or rollback and needs no grant.
 */
public final class JdbcCatalogRepository implements CatalogRepository {

    private static final String PACKAGE_COLUMNS = """
            p.load_seq, p.catalog_solution_id, c.solution, p.package_version, p.applies_to, p.digest,
            p.signature_status""";

    private static final RowMapper<StoredPackage> STORED = (rs, row) -> new StoredPackage(rs.getLong("load_seq"),
            rs.getObject("catalog_solution_id", UUID.class), rs.getString("solution"),
            rs.getString("package_version"), rs.getString("applies_to"), rs.getString("digest"),
            rs.getString("signature_status"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void lockEntry(UUID organizationId, String solution) {
        // The function returns void: nothing to read, only the lock it takes until the transaction ends.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "catalog:" + organizationId + ":" + solution),
                (ResultSetExtractor<Void>) rs -> null);
    }

    @Override
    public Optional<UUID> findEntryId(UUID organizationId, String solution) {
        return jdbc.query("""
                SELECT catalog_solution_id FROM sm_control.catalog_solution
                 WHERE organization_id = :organization AND solution = :solution
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("solution", solution), (rs, row) -> rs.getObject("catalog_solution_id", UUID.class))
                .stream().findFirst();
    }

    @Override
    public void insertEntry(UUID organizationId, UUID catalogSolutionId, String solution, Instant createdAt) {
        jdbc.update("""
                INSERT INTO sm_control.catalog_solution (organization_id, catalog_solution_id, solution, created_at)
                VALUES (:organization, :id, :solution, :createdAt)
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId).addValue("solution", solution)
                .addValue("createdAt", Timestamp.from(createdAt)));
    }

    @Override
    public List<StoredPackage> packagesOf(UUID organizationId, UUID catalogSolutionId) {
        return jdbc.query("SELECT " + PACKAGE_COLUMNS + """

                  FROM sm_control.solution_package p
                  JOIN sm_control.catalog_solution c
                    ON c.organization_id = p.organization_id AND c.catalog_solution_id = p.catalog_solution_id
                 WHERE p.organization_id = :organization AND p.catalog_solution_id = :id
                 ORDER BY p.load_seq
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId), STORED);
    }

    @Override
    public long insertPackage(UUID organizationId, UUID catalogSolutionId, String packageVersionText,
            String appliesTo, String digest, String signature, String documentJson, UUID loadedBy,
            Instant loadedAt) {
        return jdbc.queryForObject("""
                INSERT INTO sm_control.solution_package (organization_id, catalog_solution_id, package_version,
                       applies_to, digest, signature_status, document, loaded_by, loaded_at)
                VALUES (:organization, :id, :version, :appliesTo, :digest, :signature, CAST(:document AS jsonb),
                        :loadedBy, :loadedAt)
                RETURNING load_seq
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId).addValue("version", packageVersionText)
                .addValue("appliesTo", appliesTo).addValue("digest", digest).addValue("signature", signature)
                .addValue("document", documentJson).addValue("loadedBy", loadedBy)
                .addValue("loadedAt", Timestamp.from(loadedAt)), Long.class);
    }

    @Override
    public long stateVersionOf(UUID organizationId, UUID catalogSolutionId) {
        Long max = jdbc.queryForObject("""
                SELECT max(load_seq) FROM sm_control.solution_package
                 WHERE organization_id = :organization AND catalog_solution_id = :id
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId), Long.class);
        return max == null ? 0L : max;
    }

    @Override
    public List<EntryPackageRow> entryPackagesOf(UUID organizationId) {
        return jdbc.query("""
                SELECT c.catalog_solution_id, c.solution, p.load_seq, p.package_version, p.signature_status
                  FROM sm_control.catalog_solution c
                  JOIN sm_control.solution_package p
                    ON p.organization_id = c.organization_id AND p.catalog_solution_id = c.catalog_solution_id
                 WHERE c.organization_id = :organization
                 ORDER BY c.solution, p.load_seq
                """, new MapSqlParameterSource("organization", organizationId),
                (rs, row) -> new EntryPackageRow(rs.getObject("catalog_solution_id", UUID.class),
                        rs.getString("solution"), rs.getLong("load_seq"), rs.getString("package_version"),
                        rs.getString("signature_status")));
    }

    @Override
    public Optional<StoredPackage> findServing(UUID organizationId, UUID catalogSolutionId,
            String packageVersionIdJson) {
        return jdbc.query("SELECT " + PACKAGE_COLUMNS + """

                  FROM sm_control.solution_package p
                  JOIN sm_control.catalog_solution c
                    ON c.organization_id = p.organization_id AND c.catalog_solution_id = p.catalog_solution_id
                 WHERE p.organization_id = :organization AND p.catalog_solution_id = :id
                   AND CAST(p.package_version AS jsonb) @> CAST(:versionId AS jsonb)
                 ORDER BY p.load_seq DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId).addValue("versionId", packageVersionIdJson), STORED)
                .stream().findFirst();
    }

    @Override
    public Optional<String> documentServing(UUID organizationId, UUID catalogSolutionId,
            String packageVersionIdJson) {
        return jdbc.query("""
                SELECT p.document::text AS document
                  FROM sm_control.solution_package p
                 WHERE p.organization_id = :organization AND p.catalog_solution_id = :id
                   AND CAST(p.package_version AS jsonb) @> CAST(:versionId AS jsonb)
                 ORDER BY p.load_seq DESC LIMIT 1
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId).addValue("versionId", packageVersionIdJson),
                (rs, row) -> rs.getString("document")).stream().findFirst();
    }
}
