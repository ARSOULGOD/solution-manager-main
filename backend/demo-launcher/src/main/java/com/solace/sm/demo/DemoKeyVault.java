package com.solace.sm.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Encrypted local SSH-key vault. PEM bytes never appear in API responses, logs, or instance rows. */
@Repository
class DemoKeyVault {
    private final JdbcTemplate jdbc;

    DemoKeyVault(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void put(String reference, byte[] pem, byte[] vaultKey) {
        if (reference == null || !reference.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                || pem == null || pem.length < 64 || pem.length > 1024 * 1024
                || !new String(pem, java.nio.charset.StandardCharsets.US_ASCII).startsWith("-----BEGIN")) {
            throw new DemoBadRequestException("The uploaded SSH key is not an accepted private-key document.");
        }
        DemoAccountStore.Encrypted encrypted = DemoAccountStore.encrypt(vaultKey, pem);
        jdbc.update("insert into demo_control.ssh_key (key_id, key_reference, encrypted_pem, iv, created_at) values (?, ?, ?, ?, ?) on conflict (key_reference) do update set encrypted_pem = excluded.encrypted_pem, iv = excluded.iv, created_at = excluded.created_at",
                UUID.randomUUID(), reference, encrypted.content(), encrypted.iv(), Timestamp.from(Instant.now()));
    }

    void requireReference(String reference) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from demo_control.ssh_key where key_reference = ?)", Boolean.class, reference))) {
            throw new DemoBadRequestException("Select an uploaded SSH key reference.");
        }
    }

    KeyFile materialize(String reference, byte[] vaultKey) {
        KeyRow row = jdbc.query("select encrypted_pem, iv from demo_control.ssh_key where key_reference = ?",
                (rs, ignored) -> new KeyRow(rs.getBytes(1), rs.getBytes(2)), reference).stream().findFirst()
                .orElseThrow(() -> new DemoBadRequestException("Select an uploaded SSH key reference."));
        byte[] pem = DemoAccountStore.decrypt(vaultKey, row.encryptedPem(), row.iv());
        try {
            Path path = Files.createTempFile("sm-demo-ssh-", ".pem");
            try {
                Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // macOS/Linux local demo supports POSIX permissions; do not broaden on unsupported filesystems.
            }
            Files.write(path, pem);
            java.util.Arrays.fill(pem, (byte) 0);
            return new KeyFile(path);
        } catch (IOException e) {
            throw new IllegalStateException("the local vault key could not be materialized", e);
        }
    }

    record KeyFile(Path path) implements AutoCloseable {
        @Override public void close() { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }
    }
    private record KeyRow(byte[] encryptedPem, byte[] iv) {}
}
