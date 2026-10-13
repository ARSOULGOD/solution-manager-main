package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcLoginThrottleRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OR-20}: the login throttle's SECOND dimension, keyed on the resolved client address, durable
 * and domain-separated from the username dimension in {@link LocalLoginIT}. Every address literal
 * here is unique to this class ({@code 198.51.100.x}, TEST-NET-2) so its accumulated failures never
 * collide with another test class sharing the {@code "012"} container.
 */
class LoginAddressThrottleIT {

    private final NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(SmControlPostgres.LATEST_VERSION);
    private final UUID organization = LoginFixtures.organization(jdbc);
    private final CredentialHasher hasher = new CredentialHasher();
    private final AuditContext auditContext = new JdbcAuditContext(jdbc);
    private final TransactionOperations transactions =
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));

    @Test
    void manyDifferentUsernamesFromOneAddressLockThatAddress() {
        String address = "198.51.100.10";
        RequestOrigin origin = new RequestOrigin(address, UUID.randomUUID());
        LocalLoginService login = login(looseUsernamePolicy(), new LoginRateLimitPolicy(3, Duration.ofMinutes(1),
                Duration.ofMinutes(5)));
        UUID realPrincipal = createLocal("addr-lock-real-" + UUID.randomUUID() + "@example.test",
                "correct credential".toCharArray());

        for (int i = 0; i < 3; i++) {
            String unknownUsername = "addr-lock-unknown-" + i + "-" + UUID.randomUUID() + "@example.test";
            assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), unknownUsername, "guess".toCharArray()), origin))
                    .isInstanceOf(LoginRefusedException.class);
        }

        // The address is now locked — even a CORRECT credential, for a THIRD username never guessed
        // against, is refused.
        assertThatThrownBy(() -> login.authenticate(
                principalCredentials(realPrincipal, "correct credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
    }

    @Test
    void anotherAddressIsUnaffectedByAFirstAddresssLockout() {
        String lockedAddress = "198.51.100.20";
        String otherAddress = "198.51.100.21";
        LoginRateLimitPolicy addressPolicy = new LoginRateLimitPolicy(3, Duration.ofMinutes(1), Duration.ofMinutes(5));
        LocalLoginService login = login(looseUsernamePolicy(), addressPolicy);
        String username = "addr-other-" + UUID.randomUUID() + "@example.test";
        createLocal(username, "correct credential".toCharArray());

        lockAddress(login, lockedAddress, addressPolicy.failureThreshold());

        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()),
                new RequestOrigin(lockedAddress, UUID.randomUUID())))
                .isInstanceOf(LoginRefusedException.class);
        assertThat(login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()),
                new RequestOrigin(otherAddress, UUID.randomUUID())))
                .isInstanceOf(LocalLoginResult.class);
    }

    /**
     * OR-23: the username dimension is an alarm, not a gate. Locking it — with failures from OTHER
     * addresses, never the one about to try the correct credential — must not refuse a correct
     * credential from a genuinely unlocked address. What stays true (and is proven in the same test,
     * per the owner's own framing of the fix) is that a genuinely LOCKED address still refuses it.
     */
    @Test
    void theUsernameLockoutIsAnAlarmNotAGateButALockedAddressStillRefuses() {
        LocalLoginService login = login(new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5)),
                new LoginRateLimitPolicy(3, Duration.ofMinutes(1), Duration.ofMinutes(5)));
        String lockedUsername = "addr-username-alarm-" + UUID.randomUUID() + "@example.test";
        createLocal(lockedUsername, "correct credential".toCharArray());

        // Lock the USERNAME (threshold 2) with failures from TWO DIFFERENT OTHER addresses — never
        // the address the correct credential is about to be tried from — so each of THOSE addresses
        // sees only ONE failure, nowhere near its own threshold of 3.
        for (int i = 0; i < 2; i++) {
            RequestOrigin otherAddressOrigin = new RequestOrigin("198.51.100.9" + i, UUID.randomUUID());
            assertThatThrownBy(() -> login.authenticate(
                    new LocalCredentials(organization.toString(), lockedUsername, "wrong credential".toCharArray()), otherAddressOrigin))
                    .isInstanceOf(LoginRefusedException.class);
        }

        // The username is now locked, but from a FRESH, unlocked address the correct credential
        // still succeeds.
        String freshAddress = "198.51.100.92";
        assertThat(login.authenticate(new LocalCredentials(organization.toString(), lockedUsername, "correct credential".toCharArray()),
                new RequestOrigin(freshAddress, UUID.randomUUID())))
                .isInstanceOf(LocalLoginResult.class);

        // The SAME (now-alarmed, but already cleared by the success above) username, from a
        // genuinely LOCKED address, is still refused — address is the only dimension that gates.
        String lockedAddress = "198.51.100.93";
        lockAddress(login, lockedAddress, 3);
        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(organization.toString(), lockedUsername, "correct credential".toCharArray()),
                new RequestOrigin(lockedAddress, UUID.randomUUID())))
                .isInstanceOf(LoginRefusedException.class);
    }

    @Test
    void aCorrectLoginFromALockedAddressIsRefused() {
        String address = "198.51.100.40";
        LoginRateLimitPolicy addressPolicy = new LoginRateLimitPolicy(3, Duration.ofMinutes(1), Duration.ofMinutes(5));
        LocalLoginService login = login(looseUsernamePolicy(), addressPolicy);
        String username = "addr-correct-from-locked-" + UUID.randomUUID() + "@example.test";
        createLocal(username, "correct credential".toCharArray());

        lockAddress(login, address, addressPolicy.failureThreshold());

        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()),
                new RequestOrigin(address, UUID.randomUUID())))
                .isInstanceOf(LoginRefusedException.class);
    }

    @Test
    void successDoesNotClearTheAddressCounter() {
        String address = "198.51.100.50";
        LoginRateLimitPolicy addressPolicy = new LoginRateLimitPolicy(3, Duration.ofMinutes(1), Duration.ofMinutes(5));
        LocalLoginService login = login(looseUsernamePolicy(), addressPolicy);
        String username = "addr-success-clears-username-only-" + UUID.randomUUID() + "@example.test";
        createLocal(username, "correct credential".toCharArray());

        // Two failures on the address dimension (one below its threshold of 3), then a SUCCESSFUL
        // login for the same username/address.
        for (int i = 0; i < 2; i++) {
            RequestOrigin origin = new RequestOrigin(address, UUID.randomUUID());
            assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray()),
                    origin)).isInstanceOf(LoginRefusedException.class);
        }
        assertThat(login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()),
                new RequestOrigin(address, UUID.randomUUID())))
                .isInstanceOf(LocalLoginResult.class);

        // If success had cleared the address counter, this THIRD address failure (for a brand new,
        // unrelated username) would leave the address at failure_count 1, not locked. It must instead
        // be the address's THIRD failure overall and trip the lockout.
        String otherUsername = "addr-success-clears-username-only-other-" + UUID.randomUUID() + "@example.test";
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), otherUsername, "guess".toCharArray()),
                new RequestOrigin(address, UUID.randomUUID())))
                .isInstanceOf(LoginRefusedException.class);

        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()),
                new RequestOrigin(address, UUID.randomUUID())))
                .as("the address is now locked purely from its own accumulated failures")
                .isInstanceOf(LoginRefusedException.class);
    }

    /**
     * (M1) The durable per-address throttle now keys IPv6 by its {@code /64}
     * ({@code ClientAddresses.throttleKey}), not the full address — matching the pre-auth rate
     * gate's own {@code /64} keying ({@code OR-24}). Two DIFFERENT IPv6 addresses sharing one
     * {@code /64} must accumulate their failures into the SAME lockout, not two separate ones: an
     * attacker who controls a whole {@code /64} (routine for IPv6) must not be able to rotate the
     * low 64 bits on every attempt and never trip the durable lockout at all.
     */
    @Test
    void twoDifferentIpv6AddressesInOneSlash64AccumulateIntoOneAddressLockout() {
        String firstAddress = "2001:db8:aaaa:bbbb::10";
        String secondAddress = "2001:db8:aaaa:bbbb::20";
        LoginRateLimitPolicy addressPolicy = new LoginRateLimitPolicy(3, Duration.ofMinutes(1), Duration.ofMinutes(5));
        LocalLoginService login = login(looseUsernamePolicy(), addressPolicy);
        UUID realPrincipal = createLocal("addr-v6-real-" + UUID.randomUUID() + "@example.test",
                "correct credential".toCharArray());

        // Both hash to the SAME throttle subject before any request is even made — the /64, not
        // either individual address, is what actually gets keyed.
        assertThat(LocalLoginService.addressSubjectHash(firstAddress))
                .as("both addresses in the /64 hash to the SAME throttle subject")
                .isEqualTo(LocalLoginService.addressSubjectHash(secondAddress));

        // Two guesses from the FIRST address in the /64...
        for (int i = 0; i < 2; i++) {
            String unknownUsername = "addr-v6-unknown-" + i + "-" + UUID.randomUUID() + "@example.test";
            RequestOrigin origin = new RequestOrigin(firstAddress, UUID.randomUUID());
            assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), unknownUsername, "guess".toCharArray()), origin))
                    .isInstanceOf(LoginRefusedException.class);
        }
        // ...and a THIRD, from a DIFFERENT address in the SAME /64, trips the /64's shared
        // lockout — even a correct credential is now refused, from either address.
        RequestOrigin secondOrigin = new RequestOrigin(secondAddress, UUID.randomUUID());
        String thirdUnknownUsername = "addr-v6-unknown-2-" + UUID.randomUUID() + "@example.test";
        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(organization.toString(), thirdUnknownUsername, "guess".toCharArray()), secondOrigin))
                .isInstanceOf(LoginRefusedException.class);
        assertThatThrownBy(() -> login.authenticate(
                principalCredentials(realPrincipal, "correct credential".toCharArray()), secondOrigin))
                .isInstanceOf(LoginRefusedException.class);
        assertThatThrownBy(() -> login.authenticate(
                principalCredentials(realPrincipal, "correct credential".toCharArray()),
                new RequestOrigin(firstAddress, UUID.randomUUID())))
                .as("the FIRST address is locked too — they share one /64 lockout")
                .isInstanceOf(LoginRefusedException.class);

        // Exactly ONE throttle row exists for the whole /64 — not two separate rows, one per address.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource("subjectHash", LocalLoginService.addressSubjectHash(firstAddress)),
                Integer.class)).isEqualTo(1);
    }

    private void lockAddress(LocalLoginService login, String address, int threshold) {
        for (int i = 0; i < threshold; i++) {
            String unknownUsername = "addr-lock-helper-" + i + "-" + UUID.randomUUID() + "@example.test";
            RequestOrigin origin = new RequestOrigin(address, UUID.randomUUID());
            assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), unknownUsername, "guess".toCharArray()), origin))
                    .isInstanceOf(LoginRefusedException.class);
        }
    }

    private static LoginRateLimitPolicy looseUsernamePolicy() {
        return new LoginRateLimitPolicy(100, Duration.ofMinutes(1), Duration.ofMinutes(5));
    }

    private LocalLoginService login(LoginRateLimitPolicy usernamePolicy, LoginRateLimitPolicy addressPolicy) {
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, clock);
        return new LocalLoginService(new JdbcPrincipalRepository(jdbc), sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), usernamePolicy, addressPolicy, auditContext, transactions, clock);
    }

    private UUID createLocal(String username, char[] credential) {
        return new JdbcPrincipalRepository(jdbc).createLocal(organization, username, hasher.hash(credential), false);
    }

    private LocalCredentials principalCredentials(UUID principalId, char[] credential) {
        String username = jdbc.queryForObject("SELECT username FROM sm_control.principal WHERE id = :id",
                new MapSqlParameterSource("id", principalId), String.class);
        return new LocalCredentials(organization.toString(), username, credential);
    }

    private static final class MutableClock extends java.time.Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            current = initial;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public java.time.Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
