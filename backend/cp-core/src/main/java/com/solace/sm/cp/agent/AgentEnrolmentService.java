package com.solace.sm.cp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import com.solace.sm.cp.authz.ScopeStore;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.target.TargetRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Issues an enrolment token (plan 015 T2.2; the HTTP surface is T4.3). The token is 256 random bits, shown once, and
 * only its unkeyed SHA-256 is stored (OR-128 (9): the entropy is why an unkeyed digest suffices). The registration
 * records the envelope the issuing principal grants and the target name it reserves; the audit row names the principal.
 *
 * <p>Order: validate, then {@code agent.enrol} and {@code target.create} at the environment (OR-128 (4)) so a caller who
 * may not enrol never learns whether a name is taken, then one transaction: audit context
 * ({@code AGENT_ENROLMENT_ISSUED}, OR-25), the name pre-checks, the insert.
 */
public final class AgentEnrolmentService implements AgentEnrolment {

    /** {@code OR-136} (1): the defaults when no property sets them (T2.2's 1 hour and 7 days are superseded). */
    static final Duration DEFAULT_VALIDITY = com.solace.sm.cp.config.AgentEnrolmentProperties.DEFAULT_TOKEN_VALIDITY;
    static final Duration MAX_VALIDITY = com.solace.sm.cp.config.AgentEnrolmentProperties.MAX_TOKEN_VALIDITY;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRegistry registry;
    private final TargetRepository targets;
    private final ResourceScopeDirectory scopeDirectory;
    private final ScopeStore scopes;
    private final AuthorizationService authorization;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration defaultValidity;
    private final Duration maxValidity;

    /** With the {@code OR-136} defaults (24 hours, 30 days). */
    public AgentEnrolmentService(AgentRegistry registry, TargetRepository targets, ResourceScopeDirectory scopeDirectory,
            ScopeStore scopes, AuthorizationService authorization, AuditContext auditContext,
            TransactionOperations transactions, Clock clock, SecureRandom random) {
        this(registry, targets, scopeDirectory, scopes, authorization, auditContext, transactions, clock, random,
                DEFAULT_VALIDITY, MAX_VALIDITY);
    }

    /**
     * @throws IllegalArgumentException when either validity is not positive or the default exceeds the maximum: the context
     *                                  does not start ({@code OR-136} (1))
     */
    public AgentEnrolmentService(AgentRegistry registry, TargetRepository targets, ResourceScopeDirectory scopeDirectory,
            ScopeStore scopes, AuthorizationService authorization, AuditContext auditContext,
            TransactionOperations transactions, Clock clock, SecureRandom random, Duration defaultValidity,
            Duration maxValidity) {
        Objects.requireNonNull(defaultValidity, "defaultValidity");
        Objects.requireNonNull(maxValidity, "maxValidity");
        if (defaultValidity.isZero() || defaultValidity.isNegative() || maxValidity.isZero() || maxValidity.isNegative()) {
            throw new IllegalArgumentException("sm.control.agent-gateway.enrolment: token-default-validity and "
                    + "token-max-validity must be positive");
        }
        if (defaultValidity.compareTo(maxValidity) > 0) {
            throw new IllegalArgumentException("sm.control.agent-gateway.enrolment.token-default-validity must not exceed "
                    + "token-max-validity");
        }
        this.defaultValidity = defaultValidity;
        this.maxValidity = maxValidity;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.scopeDirectory = Objects.requireNonNull(scopeDirectory, "scopeDirectory");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public IssuedToken issue(PrincipalRef actor, UUID organizationId, Request request, RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");

        String name = request.targetName().strip();
        if (name.isEmpty() || name.length() > AgentEnvelopeGrammar.MAX_TARGET_NAME) {
            throw new IllegalArgumentException("a target name is 1 to " + AgentEnvelopeGrammar.MAX_TARGET_NAME
                    + " characters");
        }
        if (!AgentEnvelopeGrammar.configRoot(request.allowedConfigRoot())) {
            throw new IllegalArgumentException("the allowed config root is outside the registerTarget grammar");
        }
        if (!AgentEnvelopeGrammar.containerPrefix(request.allowedContainerPrefix())) {
            throw new IllegalArgumentException("the allowed container prefix is outside the registerTarget grammar");
        }
        requireOneVersion(request.packageVersion());
        Duration validity = request.validFor() == null ? defaultValidity : request.validFor();
        if (validity.isZero() || validity.isNegative() || validity.compareTo(maxValidity) > 0) {
            throw new IllegalArgumentException("a token is valid for more than zero and at most " + maxValidity);
        }
        ScopePath environment = request.environment();
        if (environment.depth() != 3) {
            throw new IllegalArgumentException("an agent is enrolled into an environment");
        }
        if (!organizationId.equals(environment.organization())) {
            // Another organization's environment is exactly as absent as a nonexistent one (I5).
            throw new com.solace.sm.cp.api.authz.ResourceNotFoundException(ResourceKind.ENVIRONMENT);
        }
        ResourceRef owner = ResourceRef.of(ResourceKind.ENVIRONMENT, environment.environment(),
                new TenantId(organizationId));
        // The path the caller names must be the stored one: an environment under a different unit is as absent as an unknown
        // one (I5), and is refused here rather than as a misleading foreign-key failure on the registration. Before authz, so
        // the verbs are evaluated at a scope that exists.
        if (!scopeDirectory.findScopePath(owner).filter(environment::equals).isPresent()) {
            throw new com.solace.sm.cp.api.authz.ResourceNotFoundException(ResourceKind.ENVIRONMENT);
        }
        authorization.require(actor, Permission.AGENT_ENROL, owner);
        authorization.require(actor, Permission.TARGET_CREATE, owner);

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID agentId = UUID.randomUUID();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(validity);
        AgentRegistry.NewRegistration row = new AgentRegistry.NewRegistration(organizationId,
                environment.subOrganization(), environment.environment(), agentId, request.catalogSolutionId(),
                request.packageVersion(), name, request.allowedConfigRoot(), request.allowedContainerPrefix(),
                sha256(token), expiresAt, actor.id(), now);
        try {
            transactions.executeWithoutResult(status -> {
                auditContext.set(actor.id(), AuditOperations.AGENT_ENROLMENT_ISSUED, origin.correlationId(),
                        origin.clientAddress());
                // The environment's canonical scope row is minted lazily (V018), and the agent actor may not insert one (V021
                // allows AGENT_ENROLLED only on agent_registration and target), so it is made HERE, by the issuing principal,
                // before the agent's target is ever owned at it.
                scopes.canonicalize(environment);
                targets.lockName(organizationId, name);        // shared with registerTarget and the enrolment consumption
                if (targets.findByName(organizationId, name).filter(stored -> !stored.retired()).isPresent()) {
                    throw new IllegalStateException("the target name is already in use");
                }
                registry.insert(row);
            });
        } catch (DuplicateKeyException taken) {
            // agent_registration_pending_name: an unconsumed token already reserves this name.
            throw new IllegalStateException("the target name is already reserved by a pending enrolment");
        } catch (DataIntegrityViolationException notLoaded) {
            throw new IllegalArgumentException("the solution or its package version is not loaded in this organization");
        }
        return new IssuedToken(agentId, token, expiresAt);
    }

    /** One member of a loaded version array, as JSON: a number or a string, never an array or object (V021 trigger, review finding 2). */
    private static void requireOneVersion(String canonicalJson) {
        try {
            JsonNode node = JSON.readTree(canonicalJson);
            if (node == null || !(node.isNumber() || node.isTextual())) {
                throw new IllegalArgumentException("the package version must name one version (a number or a string)");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the package version must be JSON");
        }
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
