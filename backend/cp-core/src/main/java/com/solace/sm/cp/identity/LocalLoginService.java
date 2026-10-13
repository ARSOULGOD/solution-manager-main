package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.audit.ClientAddresses;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginOrganizationRequiredException;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/** CP-owned local login with generic failures and two configurable durable throttle dimensions. */
public final class LocalLoginService implements LocalAuthenticationService {

    /**
     * Every login subject hash is over this literal plus the organization and the username — never a bare
     * username — so it occupies its OWN subject-hash space, distinct from
     * {@link CredentialRotationService#rotationSubjectHash}'s {@code "credential-rotation:"}
     * space. Without this prefix, a local username literally equal to
     * {@code "credential-rotation:<principal-id>"} would hash to the exact same throttle key as
     * that principal's rotation lockout.
     */
    private static final String SUBJECT_PREFIX = "local-login:";

    /**
     * The client-address throttle dimension's own subject-hash space ({@code OR-20}) — domain-separated
     * from {@link #loginSubjectHash} and {@link CredentialRotationService#rotationSubjectHash} by this
     * distinct literal prefix, so an address lockout can never collide with either, in either direction.
     */
    private static final String ADDRESS_SUBJECT_PREFIX = "client-address:";

    private final PrincipalRepository principals;
    private final SessionService sessions;
    private final CredentialVerifier hasher;
    private final LoginThrottleRepository throttles;
    private final LoginRateLimitPolicy policy;
    private final LoginRateLimitPolicy addressPolicy;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final String unknownCredentialHash;

    public LocalLoginService(PrincipalRepository principals, SessionService sessions, CredentialVerifier hasher,
                             LoginThrottleRepository throttles, LoginRateLimitPolicy policy,
                             LoginRateLimitPolicy addressPolicy, AuditContext auditContext,
                             TransactionOperations transactions, Clock clock) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.throttles = Objects.requireNonNull(throttles, "throttles");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "address policy");
        this.auditContext = Objects.requireNonNull(auditContext, "audit context");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        char[] unknownCredential = new char[32];
        try {
            unknownCredentialHash = hasher.hash(unknownCredential);
        } finally {
            Arrays.fill(unknownCredential, '\0');
        }
    }

    @Override
    public LocalLoginResult authenticate(LocalCredentials credentials, RequestOrigin origin) {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(origin, "origin");
        String addressSubjectHash = addressSubjectHash(origin.clientAddress());
        Instant now = clock.instant();
        char[] candidate = credentials.credential();
        try {
            // OR-23: the CLIENT-ADDRESS dimension is the only gate. A locked USERNAME is an alarm
            // (recorded and audited by recordFailure below, on every fresh failure) but is never
            // itself read here, and so can never by itself refuse a correct credential from an
            // unlocked address.
            if (throttles.isLocked(addressSubjectHash, now)) {
                // OR-24: no Argon2 verification and no principal lookup against a locked address —
                // the lock state this would hide is the caller's own. Probe fires the OR-19 audit
                // row AT MOST ONCE PER LOCKOUT (see LoginThrottleRepository#probe), never once per
                // refused request.
                transactions.executeWithoutResult(status -> {
                    auditContext.set(null, AuditOperations.LOGIN_REFUSED, origin.correlationId(),
                            origin.clientAddress());
                    throttles.probe(addressSubjectHash, now);
                });
                throw new LoginRefusedException();
            }
            // OR-125 (4): the organization the user typed, resolved here. An organization that does not exist is no
            // different from a user that does not exist: the same dummy verification, the same failure on both
            // dimensions, the same audit row, the same refusal. Nothing below branches on WHICH of the two it was.
            // W5D-1: the SAME two statements on every path. The organization resolves in one statement; the principal
            // lookup ALWAYS runs (under PrincipalRepository.NO_ORGANIZATION when nothing resolved, which matches
            // nothing), so the number of round trips tells a caller nothing about which of the two was wrong.
            Optional<UUID> organization = principals.resolveOrganization(credentials.organization());
            Optional<PrincipalRepository.Principal> principal = principals.findLocalByUsername(
                    organization.orElse(PrincipalRepository.NO_ORGANIZATION), credentials.username());
            if (credentials.organization() == null && organization.isEmpty()) {
                // OR-131 Q3: omitted, and the install does not hold exactly one organization. A request-shape fault,
                // raised after the same statements and before any credential work or throttle write.
                throw new LoginOrganizationRequiredException();
            }
            // W5D-3: the username dimension is keyed by the principal's id when it resolves, so case folding is the
            // database's (it found the row under lower(), whatever the locale) and never a second, Java-side rule.
            String usernameSubjectHash = principal.map(found -> principalSubjectHash(found.id()))
                    .or(() -> organization.map(id -> loginSubjectHash(id, credentials.username())))
                    .orElseGet(() -> unresolvedLoginSubjectHash(credentials.organization(), credentials.username()));
            String credentialHash = principal.map(PrincipalRepository.Principal::credentialHash)
                    .orElse(unknownCredentialHash);
            boolean verified = hasher.verify(candidate, credentialHash);
            if (principal.isEmpty() || !verified
                    || principal.orElseThrow().status() != PrincipalRepository.Status.ACTIVE) {
                // Never reveal whether the username exists by what is recorded: both dimensions get a
                // failure regardless of which check above failed. This is also how an ALREADY-locked
                // username stays audited on every fresh wrong attempt (OR-23 made it an alarm, not a
                // gate, but every failure against it still lands here and still fires OR-19).
                transactions.executeWithoutResult(status -> {
                    auditContext.set(null, AuditOperations.LOGIN_REFUSED, origin.correlationId(), origin.clientAddress());
                    throttles.recordFailure(usernameSubjectHash, policy, now);
                    throttles.recordFailure(addressSubjectHash, addressPolicy, now);
                });
                throw new LoginRefusedException();
            }
            UUID principalId = principal.orElseThrow().id();
            SessionToken token = transactions.execute(status -> {
                auditContext.set(principalId, AuditOperations.LOGIN_SUCCEEDED, origin.correlationId(),
                        origin.clientAddress());
                // The USERNAME dimension only (OR-20), cleared even if it happens to be locked right
                // now (OR-23: a correct credential from an unlocked address always succeeds, and
                // always clears the alarm it may have tripped). The address dimension is never
                // cleared on success — it is left to expire with its own window.
                throttles.clearFailures(usernameSubjectHash, now);
                return sessions.open(new PrincipalRef(principalId), origin);
            });
            Session session = sessions.resolve(token).orElseThrow(LoginRefusedException::new);
            CsrfToken csrf = sessions.csrfTokenFor(session.id()).orElseThrow(LoginRefusedException::new);
            return new LocalLoginResult(token, csrf, session);
        } finally {
            Arrays.fill(candidate, '\0');
        }
    }

    /**
     * The username dimension's subject for a principal that EXISTS (W5D-3): keyed by its id, so which spellings are
     * "the same user" is decided once, by the database's {@code lower()} under its locale (a {@code C}/{@code POSIX}
     * database folds ASCII only), and never again by Java.
     */
    public static String principalSubjectHash(UUID principalId) {
        Objects.requireNonNull(principalId, "principalId");
        return sha256(SUBJECT_PREFIX + "principal:" + principalId);
    }

    /**
     * The login throttle's fallback subject for a username that names NO principal of a RESOLVED organization (a
     * principal that exists is keyed by {@link #principalSubjectHash}). The username is lowercased by Java ({@code Locale.ROOT}) — the one place a
     * case rule is not the database's: nothing exists to ask — so that guesses at an absent name in different cases
     * share a counter. The organization is the id, so naming it by
     * id or by name is one counter, and the same username in two organizations is two.
     */
    public static String loginSubjectHash(UUID organizationId, String username) {
        Objects.requireNonNull(organizationId, "organizationId");
        return sha256(SUBJECT_PREFIX + "org:" + organizationId + ":" + username.toLowerCase(Locale.ROOT));
    }

    /**
     * The same space for an organization that did not resolve (it names nothing). It is still counted, exactly as a
     * wrong username is: a login against a missing organization must not be distinguishable from one against a missing
     * user, and the counter being absent for one of them would be that difference. The organization is length-prefixed
     * so no organization text can be read as a username.
     */
    public static String unresolvedLoginSubjectHash(String organization, String username) {
        String typed = organization.toLowerCase(Locale.ROOT);
        return sha256(SUBJECT_PREFIX + "unresolved:" + typed.length() + ":" + typed + ":"
                + username.toLowerCase(Locale.ROOT));
    }

    /**
     * The client-address throttle's own subject-hash space ({@code OR-20}) — keyed by
     * {@link ClientAddresses#throttleKey}, not the bare address, so an IPv6 attacker rotating bits
     * below the {@code /64} boundary still accumulates into ONE durable lockout (M1: the durable
     * throttle must key IPv6 the same way the pre-auth rate gate does, {@code OR-24}). An IPv4
     * address is unaffected — {@code throttleKey} returns it unchanged.
     *
     * <p>{@code clientAddress} is canonicalised HERE, defensively, before the key is derived: every
     * real caller already hands this a value {@link RequestOrigin} or {@link
     * ClientAddresses#canonicalize} itself produced, but {@code throttleKey} throws {@link
     * IllegalArgumentException} on anything that is not canonical — and a malformed address must
     * never make {@code authenticate()} throw instead of refusing the login normally. When the
     * input cannot be canonicalised at all, this falls back to hashing it as-is rather than
     * propagating that exception.
     */
    public static String addressSubjectHash(String clientAddress) {
        String key = ClientAddresses.canonicalize(clientAddress)
                .map(ClientAddresses::throttleKey)
                .orElse(clientAddress);
        return sha256(ADDRESS_SUBJECT_PREFIX + key);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
