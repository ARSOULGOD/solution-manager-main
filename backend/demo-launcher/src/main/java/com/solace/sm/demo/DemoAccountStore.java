package com.solace.sm.demo;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;

/** The one local-demo administrator and the password-wrapped vault root key. */
@Repository
class DemoAccountStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final PasswordEncoder PASSWORDS = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final JdbcTemplate jdbc;

    DemoAccountStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean initialized() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from demo_control.account)", Boolean.class));
    }

    synchronized byte[] setup(String password) {
        requirePassword(password);
        if (initialized()) throw new DemoBadRequestException("Local setup is already complete.");
        byte[] salt = random(16);
        byte[] vault = random(32);
        Encrypted wrapped = encrypt(derive(password, salt), vault);
        jdbc.update("insert into demo_control.account (account_id, login_name, password_hash, vault_salt, wrapped_vault_key, wrap_iv, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), "bootstrap-super-admin", PASSWORDS.encode(password), salt, wrapped.content(), wrapped.iv(),
                Timestamp.from(Instant.now()));
        return vault;
    }

    byte[] unlock(String password) {
        Account account = jdbc.query("select password_hash, vault_salt, wrapped_vault_key, wrap_iv from demo_control.account where login_name = ?",
                (rs, ignored) -> new Account(rs.getString(1), rs.getBytes(2), rs.getBytes(3), rs.getBytes(4)), "bootstrap-super-admin")
                .stream().findFirst().orElseThrow(DemoUnauthorizedException::new);
        if (!PASSWORDS.matches(password == null ? "" : password, account.passwordHash())) throw new DemoUnauthorizedException();
        return decrypt(derive(password, account.salt()), account.wrappedVaultKey(), account.iv());
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 12) throw new DemoBadRequestException("Use an administrator password of at least 12 characters.");
    }
    static Encrypted encrypt(byte[] key, byte[] value) {
        try {
            byte[] iv = random(12); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new Encrypted(cipher.doFinal(value), iv);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("vault encryption failed", e); }
    }
    static byte[] decrypt(byte[] key, byte[] value, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException e) { throw new DemoUnauthorizedException(); }
    }
    private static byte[] derive(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 310_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) { throw new IllegalStateException("vault key derivation failed", e); }
    }
    private static byte[] random(int size) { byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return bytes; }
    record Encrypted(byte[] content, byte[] iv) {}
    private record Account(String passwordHash, byte[] salt, byte[] wrappedVaultKey, byte[] iv) {}
}
