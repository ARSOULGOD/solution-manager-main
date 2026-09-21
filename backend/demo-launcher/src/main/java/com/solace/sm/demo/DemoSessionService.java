package com.solace.sm.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/** In-memory, local-demo-only session service. No password or token is persisted. */
@Service
final class DemoSessionService {
    static final String SESSION_COOKIE = "SM_DEMO_SESSION";
    static final String CSRF_COOKIE = "SM_DEMO_CSRF";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_SECONDS = 4 * 60 * 60;

    private final DemoAccountStore accounts;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    DemoSessionService(DemoAccountStore accounts) { this.accounts = accounts; }

    boolean initialized() { return accounts.initialized(); }

    Login setup(String password) { return establish(accounts.setup(password)); }

    Login login(String submittedPassword) {
        return establish(accounts.unlock(submittedPassword));
    }

    private Login establish(byte[] vaultKey) {
        String sessionId = token();
        String csrf = token();
        sessions.put(sessionId, new Session(csrf, vaultKey, Instant.now().plusSeconds(SESSION_SECONDS)));
        return new Login(cookie(SESSION_COOKIE, sessionId, true, SESSION_SECONDS),
                cookie(CSRF_COOKIE, csrf, false, SESSION_SECONDS));
    }

    byte[] vaultKey(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || Instant.now().isAfter(session.expiresAt())) throw new DemoUnauthorizedException();
        return session.vaultKey().clone();
    }

    void require(String sessionId, String csrf, boolean mutation) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null || Instant.now().isAfter(session.expiresAt())
                || mutation && (csrf == null || !MessageDigest.isEqual(
                session.csrf().getBytes(StandardCharsets.UTF_8), csrf.getBytes(StandardCharsets.UTF_8)))) {
            if (sessionId != null) {
                clear(sessionId);
            }
            throw new DemoUnauthorizedException();
        }
    }

    ResponseCookie logout(String sessionId) {
        if (sessionId != null) {
            clear(sessionId);
        }
        return cookie(SESSION_COOKIE, "", true, 0);
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void clear(String sessionId) {
        Session removed = sessions.remove(sessionId);
        if (removed != null) {
            java.util.Arrays.fill(removed.vaultKey(), (byte) 0);
        }
    }

    private static ResponseCookie cookie(String name, String value, boolean httpOnly, long maxAge) {
        return ResponseCookie.from(name, value).httpOnly(httpOnly).sameSite("Strict")
                .path("/").maxAge(maxAge).build();
    }

    record Login(ResponseCookie session, ResponseCookie csrf) {}
    private record Session(String csrf, byte[] vaultKey, Instant expiresAt) {}
}

final class DemoUnauthorizedException extends RuntimeException {}
