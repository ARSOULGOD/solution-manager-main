package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/**
 * The mechanical facts every agent-gateway repository repeats (plan 015 T2.1b), in one place: time crossing the driver as
 * {@link OffsetDateTime}, integrity refusals reshaped into a named {@link PgConstraintViolation} (no driver text, so no row
 * content reaches a log), PostgreSQL array literals, the capability spelling of {@code V028}'s CHECKs, and digests.
 *
 * <p>{@code Sql} in the parent package is package-private and these classes live one package down, so the few lines are
 * restated here rather than widening that class.
 */
final class AgentSql {

    private static final HexFormat HEX = HexFormat.of();

    private AgentSql() {
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Runs a write, reshaping an integrity refusal into a {@link PgConstraintViolation} (classifiers only) and rethrowing the rest. */
    static <T> T write(Supplier<T> body) {
        try {
            return body.get();
        } catch (DataAccessException e) {
            throw PgConstraintViolation.from(e).map(RuntimeException.class::cast).orElse(e);
        }
    }

    static GatewayPortException refused(GatewayCode code, String message) {
        return new GatewayPortException(code, message);
    }

    // ---- V028's spellings ----------------------------------------------------------------------------------------

    /** {@code APPLY_CONFIG} is {@code apply-config} in the CHECK. */
    static String capability(AgentCapability c) {
        return c.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static AgentCapability capability(String stored) {
        return AgentCapability.valueOf(stored.toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    static String capabilityArray(Collection<AgentCapability> capabilities) {
        return textArray(capabilities.stream().map(AgentSql::capability).sorted().toList());
    }

    /** A PostgreSQL array literal, to be bound as text and cast ({@code CAST(:x AS text[])}); elements are quoted. */
    static String textArray(Collection<String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }

    static String uuidArray(Collection<UUID> values) {
        return textArray(values.stream().map(UUID::toString).toList());
    }

    static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        List<String> out = new ArrayList<>();
        if (value instanceof Object[] values) {
            for (Object v : values) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    static List<AgentCapability> capabilities(ResultSet rs, String column) throws SQLException {
        return strings(rs, column).stream().map(AgentSql::capability).sorted().toList();
    }

    static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
        return strings(rs, column).stream().map(UUID::fromString).toList();
    }

    // ---- digests --------------------------------------------------------------------------------------------------

    /** The unkeyed SHA-256, lower hex. Only for values that are NOT configuration: a one-shot lease nonce, a public certificate. */
    static String sha256Hex(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The UUID for an agent-supplied id that is not itself a UUID: stable, and carrying nothing of the original. */
    static UUID uuidOf(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A name-based (version 5, SHA-1) UUID, as {@code AGW-D25} derives {@code incarnation_id}. */
    static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(java.nio.ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits())
                    .putLong(namespace.getLeastSignificantBits()).array());
            byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
