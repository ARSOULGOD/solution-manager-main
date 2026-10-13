package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.secret.KeyId;
import com.solace.sm.dp.secret.SealedSecret;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretRow;
import com.solace.sm.dp.secret.SecretRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_secret.secret} over the runtime credential (plan 010 T2.4). It lives in this package because
 * JDBC is confined here ({@code jdbcIsConfinedToPersistencePackages}); the crypto in {@code dp.secret}
 * reaches it only through {@link SecretRows}.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}. The runtime role holds a
 * table-wide {@code SELECT}, so it is this {@code WHERE} clause, plus the AAD that binds the tenant a second
 * time, that stops a cross-tenant read; the database does not (D-KEY 9 note 1).
 *
 * <p>Nothing here binds a plaintext. Spring's TRACE bind-value logging would print one; ciphertext and
 * labels are what cross this seam, and {@code byte[]} binds render as an identity hash.
 */
public final class JdbcSecretRepository implements SecretRows {

    private static final String COLUMNS = "tenant_id, secret_id, purpose, key_id, wrapped_dek, dek_iv, value_iv,"
            + " ciphertext, aad_version, key_version, created_at, created_by";

    private static final RowMapper<SecretRow> MAP = JdbcSecretRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSecretRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SecretRow row) {
        SealedSecret s = row.sealed();
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_secret.secret (" + COLUMNS + ") VALUES (:tenant, :secret, :purpose, :keyId,"
                        + " :wrapped, :dekIv, :valueIv, :ciphertext, :aadVersion, :keyVersion, :createdAt,"
                        + " :createdBy)",
                new MapSqlParameterSource()
                        .addValue("tenant", row.tenantId()).addValue("secret", row.secretId())
                        .addValue("purpose", row.purpose().name()).addValue("keyId", s.keyId().value())
                        .addValue("wrapped", s.wrappedDek()).addValue("dekIv", s.dekIv())
                        .addValue("valueIv", s.valueIv()).addValue("ciphertext", s.ciphertext())
                        .addValue("aadVersion", s.aadVersion())
                        .addValue("keyVersion", row.keyVersion(), Types.INTEGER)
                        .addValue("createdAt", Sql.at(row.createdAt()))
                        .addValue("createdBy", row.createdBy(), Types.OTHER)));
    }

    @Override
    public Optional<SecretRow> find(UUID tenantId, UUID secretId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM sm_secret.secret WHERE tenant_id = :tenant AND secret_id = :secret",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("secret", secretId), MAP)
                .stream().findFirst();
    }

    @Override
    public boolean exists(UUID tenantId, UUID secretId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_secret.secret WHERE tenant_id = :tenant AND secret_id = :secret)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("secret", secretId),
                Boolean.class));
    }

    @Override
    public Optional<SecretRow> findHashKey(UUID tenantId, int keyVersion) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM sm_secret.secret WHERE tenant_id = :tenant"
                        + " AND purpose = 'CONFIG_HASH_KEY' AND key_version = :version",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("version", keyVersion), MAP)
                .stream().findFirst();
    }

    @Override
    public Optional<SecretRow> latestHashKey(UUID tenantId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM sm_secret.secret WHERE tenant_id = :tenant"
                        + " AND purpose = 'CONFIG_HASH_KEY' ORDER BY key_version DESC LIMIT 1",
                new MapSqlParameterSource().addValue("tenant", tenantId), MAP)
                .stream().findFirst();
    }

    @Override
    public boolean insertHashKeyIfAbsent(SecretRow row) {
        SealedSecret s = row.sealed();
        return Sql.write(() -> jdbc.update(
                "INSERT INTO sm_secret.secret (" + COLUMNS + ") VALUES (:tenant, :secret, :purpose, :keyId,"
                        + " :wrapped, :dekIv, :valueIv, :ciphertext, :aadVersion, :keyVersion, :createdAt,"
                        + " :createdBy) ON CONFLICT (tenant_id, key_version) DO NOTHING",
                new MapSqlParameterSource()
                        .addValue("tenant", row.tenantId()).addValue("secret", row.secretId())
                        .addValue("purpose", row.purpose().name()).addValue("keyId", s.keyId().value())
                        .addValue("wrapped", s.wrappedDek()).addValue("dekIv", s.dekIv())
                        .addValue("valueIv", s.valueIv()).addValue("ciphertext", s.ciphertext())
                        .addValue("aadVersion", s.aadVersion())
                        .addValue("keyVersion", row.keyVersion(), Types.INTEGER)
                        .addValue("createdAt", Sql.at(row.createdAt()))
                        .addValue("createdBy", row.createdBy(), Types.OTHER))) == 1;
    }

    // ---- rotation (KeyRotationMain) -----------------------------------------------------------------------

    /**
     * Up to {@code limit} of ONE tenant's rows whose data key is wrapped under {@code keyId}, locked for the
     * enclosing transaction. Always tenant-bound: the re-wrap never reads a DEK without its tenant.
     * {@code FOR UPDATE} needs only an {@code UPDATE} privilege on one column, which the three rotation
     * columns provide.
     */
    List<SecretRow> lockBatchUnderKey(UUID tenantId, KeyId keyId, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM sm_secret.secret WHERE tenant_id = :tenant AND key_id = :keyId"
                        + " ORDER BY secret_id LIMIT :limit FOR UPDATE",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("keyId", keyId.value())
                        .addValue("limit", limit), MAP);
    }

    /**
     * ONE statement sets {@code key_id}, {@code wrapped_dek} and {@code dek_iv} together, guarded by the old
     * id: a concurrent reader (READ COMMITTED) sees the row entirely old or entirely new, and a second
     * command or a re-run after a crash updates zero rows.
     *
     * @return rows updated: 0 means the row was no longer under {@code from}
     */
    int rewrap(UUID tenantId, UUID secretId, KeyId from, SealedSecret moved) {
        return jdbc.update(
                "UPDATE sm_secret.secret SET key_id = :to, wrapped_dek = :wrapped, dek_iv = :dekIv"
                        + " WHERE tenant_id = :tenant AND secret_id = :secret AND key_id = :from",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("secret", secretId)
                        .addValue("from", from.value()).addValue("to", moved.keyId().value())
                        .addValue("wrapped", moved.wrappedDek()).addValue("dekIv", moved.dekIv()));
    }

    /** How many of one tenant's rows are still wrapped under {@code keyId}. */
    long countUnderKey(UUID tenantId, KeyId keyId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :tenant AND key_id = :keyId",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("keyId", keyId.value()),
                Long.class);
        return n == null ? 0 : n;
    }

    private static SecretRow row(ResultSet rs, int i) throws SQLException {
        int keyVersion = rs.getInt("key_version");
        Integer version = rs.wasNull() ? null : keyVersion;
        return new SecretRow(rs.getObject("tenant_id", UUID.class), rs.getObject("secret_id", UUID.class),
                SecretPurpose.valueOf(rs.getString("purpose")),
                new SealedSecret(new KeyId(rs.getString("key_id")), rs.getBytes("wrapped_dek"),
                        rs.getBytes("dek_iv"), rs.getBytes("value_iv"), rs.getBytes("ciphertext"),
                        rs.getInt("aad_version")),
                version, Sql.instant(rs, "created_at"), rs.getObject("created_by", UUID.class));
    }
}
