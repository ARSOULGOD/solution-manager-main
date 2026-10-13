package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.secret.CanaryRow;
import com.solace.sm.dp.secret.CanaryRows;
import com.solace.sm.dp.secret.KeyId;
import com.solace.sm.dp.secret.SealedSecret;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_secret.kek_canary} over the runtime credential (D-KEY 4.3). Install-level: no statement here
 * names a tenant. The one statement that touches {@code sm_secret.secret} is a bare {@code EXISTS}, which returns a
 * boolean and no tenant id, key id or value, so the boot check still reads no tenant data (B4).
 */
public final class JdbcKekCanaryRepository implements CanaryRows {

    private static final RowMapper<CanaryRow> MAP = JdbcKekCanaryRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcKekCanaryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CanaryRow> find(KeyId keyId) {
        return jdbc.query(
                "SELECT key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version, created_at"
                        + " FROM sm_secret.kek_canary WHERE key_id = :keyId",
                new MapSqlParameterSource("keyId", keyId.value()), MAP).stream().findFirst();
    }

    @Override
    public boolean anyExists() {
        return Boolean.TRUE.equals(jdbc.getJdbcOperations().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_secret.kek_canary)", Boolean.class));
    }

    @Override
    public boolean anySecretExists() {
        return Boolean.TRUE.equals(jdbc.getJdbcOperations().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_secret.secret)", Boolean.class));
    }

    @Override
    public boolean insertIfAbsent(CanaryRow row) {
        SealedSecret s = row.sealed();
        return Sql.write(() -> jdbc.update(
                "INSERT INTO sm_secret.kek_canary (key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version,"
                        + " created_at) VALUES (:keyId, :wrapped, :dekIv, :valueIv, :ciphertext, :aadVersion,"
                        + " :createdAt) ON CONFLICT (key_id) DO NOTHING",
                new MapSqlParameterSource().addValue("keyId", s.keyId().value())
                        .addValue("wrapped", s.wrappedDek()).addValue("dekIv", s.dekIv())
                        .addValue("valueIv", s.valueIv()).addValue("ciphertext", s.ciphertext())
                        .addValue("aadVersion", s.aadVersion())
                        .addValue("createdAt", Sql.at(row.createdAt())))) == 1;
    }

    private static CanaryRow row(ResultSet rs, int i) throws SQLException {
        return new CanaryRow(new SealedSecret(new KeyId(rs.getString("key_id")), rs.getBytes("wrapped_dek"),
                rs.getBytes("dek_iv"), rs.getBytes("value_iv"), rs.getBytes("ciphertext"),
                rs.getInt("aad_version")), Sql.instant(rs, "created_at"));
    }
}
