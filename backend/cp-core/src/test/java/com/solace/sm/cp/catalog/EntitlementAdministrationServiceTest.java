package com.solace.sm.cp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.catalog.EntitlementAdministration.Entitlement;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.governance.GovernanceRepository;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link EntitlementAdministrationService} with every collaborator a fake: where each method is authorized (install
 * and then the named organization for a set, the organization for a read), that nothing is read before that, the
 * refusal below the declared count, and the idempotent no-op. The database half (the lock, the audit row, a retired instance not counting) is
 * {@code EntitlementAdministrationServiceIT}.
 */
class EntitlementAdministrationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.20", UUID.randomUUID());
    private static final Instant AT = Instant.parse("2026-10-08T01:02:03Z");

    private final List<String> calls = new ArrayList<>();
    private final List<ResourceRef> required = new ArrayList<>();
    private final List<Permission> permissions = new ArrayList<>();
    private final List<String> audits = new ArrayList<>();
    private final Map<String, Long> declared = new LinkedHashMap<>();
    private final Map<String, EntitlementRepository.Stored> rows = new LinkedHashMap<>();
    private RuntimeException refuse;
    /** When set, {@link #refuse} applies only to a require on this kind of resource. */
    private ResourceKind refuseOn;

    private final AuthorizationService authorization = (principal, permission, resource) -> {
        calls.add("authorize " + permission.id());
        permissions.add(permission);
        required.add(resource);
        if (refuse != null && (refuseOn == null || refuseOn == resource.kind())) {
            throw refuse;
        }
    };
    private final AuditContext auditContext = (actor, operation, correlation, client) ->
            audits.add(operation + "@" + client + "/" + actor);

    private final EntitlementRepository entitlements = new EntitlementRepository() {
        @Override
        public boolean exists(UUID organizationId, String solution) {
            return rows.containsKey(solution);
        }

        @Override
        public List<Stored> list(UUID organizationId) {
            calls.add("list");
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<Stored> find(UUID organizationId, String solution) {
            return Optional.ofNullable(rows.get(solution));
        }

        @Override
        public boolean insertIfAbsent(UUID organizationId, String solution, int maxInstances, UUID setBy,
                Instant setAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void upsert(UUID organizationId, String solution, int maxInstances, UUID setBy, Instant setAt) {
            calls.add("upsert " + solution + " " + maxInstances + " by " + (setBy.equals(ACTOR.id()) ? "actor" : "?"));
            rows.put(solution, new Stored(solution, maxInstances, setAt));
        }
    };

    private final GovernanceRepository governance = (GovernanceRepository) Proxy.newProxyInstance(
            GovernanceRepository.class.getClassLoader(), new Class<?>[] {GovernanceRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "lockEntitlement" -> {
                    calls.add("lock");
                    EntitlementRepository.Stored stored = rows.get((String) args[1]);
                    yield stored == null ? OptionalInt.empty() : OptionalInt.of(stored.maxInstances());
                }
                case "countInstances" -> {
                    calls.add("count");
                    yield declared.getOrDefault((String) args[1], 0L);
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private final EntitlementAdministration service = new EntitlementAdministrationService(entitlements, governance,
            authorization, auditContext, TransactionOperations.withoutTransaction(),
            Clock.fixed(AT, ZoneOffset.UTC));

    // ---------------------------------------------------------------- where each method is authorized

    @Test
    void aSetIsAuthorizedAtInstallScopeAndThenAtTheNamedOrganizationBeforeAnythingIsReadOrLocked() {
        service.set(ACTOR, ORIGIN, ORG, "cache", 3);

        assertThat(calls).first().isEqualTo("authorize entitlement.set");
        assertThat(calls.subList(0, 2)).containsExactly("authorize entitlement.set", "authorize entitlement.set");
        assertThat(permissions).containsExactly(Permission.ENTITLEMENT_SET, Permission.ENTITLEMENT_SET);
        assertThat(required).hasSize(2);
        assertThat(required.get(0).kind())
                .as("first the install-wide resource: a role definition resolves to the install scope")
                .isEqualTo(ResourceKind.ROLE_DEFINITION);
        assertThat(required.get(0).id()).isEqualTo(EntitlementAdministrationService.INSTALL_ANCHOR_ROLE);
        assertThat(required.get(1).kind())
                .as("then the organization the caller named, which is what compares it with the caller's own (I5)")
                .isEqualTo(ResourceKind.ORGANIZATION);
        assertThat(required.get(1).id()).isEqualTo(ORG);
    }

    @Test
    void aSetOnAnOrganizationTheOrganizationRequireRefusesAsAbsentTouchesNothing() {
        // The fake passes the install-anchor require and answers 404 for the organization one, as the real evaluator
        // does for another tenant's or a nonexistent organization (W2 review W2D-1).
        refuseOn = ResourceKind.ORGANIZATION;
        refuse = new ResourceNotFoundException(ResourceKind.ORGANIZATION);

        assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, "cache", 3))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(calls).containsExactly("authorize entitlement.set", "authorize entitlement.set");
        assertThat(audits).isEmpty();
        assertThat(rows).isEmpty();
    }

    @Test
    void aReadIsAuthorizedAtTheOrganizationWithSolutionViewBeforeTheRowsAreRead() {
        service.list(ACTOR, ORG);

        assertThat(calls).first().isEqualTo("authorize solution.view");
        assertThat(required).singleElement().satisfies(resource -> {
            assertThat(resource.kind()).isEqualTo(ResourceKind.ORGANIZATION);
            assertThat(resource.id()).isEqualTo(ORG);
        });
    }

    @Test
    void aRefusedAuthorizationTouchesNothing() {
        refuse = new AccessDeniedException(Permission.ENTITLEMENT_SET);

        assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, "cache", 3)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(ACTOR, ORG)).isInstanceOf(AccessDeniedException.class);

        assertThat(calls).containsExactly("authorize entitlement.set", "authorize solution.view");
        assertThat(audits).isEmpty();
        assertThat(rows).isEmpty();
    }

    // ---------------------------------------------------------------- the write

    @Test
    void aSetUpsertsAsTheActorAuditsEntitlementSetWithThatActorAndReturnsTheStoredRow() {
        declared.put("cache", 2L);

        Entitlement set = service.set(ACTOR, ORIGIN, ORG, "cache", 5);

        assertThat(set).isEqualTo(new Entitlement("cache", 5, 2, AT));
        assertThat(calls).containsExactly("authorize entitlement.set", "authorize entitlement.set", "lock", "count",
                "upsert cache 5 by actor");
        assertThat(audits).containsExactly(AuditOperations.ENTITLEMENT_SET + "@203.0.113.20/" + ACTOR.id());
    }

    @Test
    void aLimitBelowTheDeclaredCountIsRefusedWithTheBelowDeclaredReasonAndNothingIsWritten() {
        declared.put("cache", 3L);
        rows.put("cache", new EntitlementRepository.Stored("cache", 10, AT));

        assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, "cache", 2))
                .isInstanceOfSatisfying(EntitlementRefusedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(EntitlementRefusedException.Reason.BELOW_DECLARED);
                    assertThat(e.getMessage()).doesNotContain("cache");
                });

        assertThat(calls).doesNotContain("upsert cache 2 by actor");
        assertThat(rows.get("cache").maxInstances()).isEqualTo(10);
    }

    @Test
    void aLimitEqualToTheDeclaredCountIsAccepted() {
        declared.put("cache", 3L);

        assertThat(service.set(ACTOR, ORIGIN, ORG, "cache", 3).maxInstances()).isEqualTo(3);
    }

    @Test
    void settingTheLimitTheRowAlreadyHoldsWritesNothingAndSoLeavesNoAuditRowChange() {
        rows.put("cache", new EntitlementRepository.Stored("cache", 4, Instant.parse("2026-01-01T00:00:00Z")));

        Entitlement again = service.set(ACTOR, ORIGIN, ORG, "cache", 4);

        assertThat(again.setAt()).as("set_at is the original, not now").isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(calls).noneMatch(call -> call.startsWith("upsert"));
    }

    @Test
    void anInvalidSolutionOrLimitIsRefusedBeforeAuthorizationAndWithoutEchoingTheValue() {
        for (String solution : new String[] {"", "Cache", "-x", "a".repeat(65), "has space", "x/y"}) {
            assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, solution, 3))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(solution.isEmpty() ? "\0" : solution));
        }
        assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, "cache", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, "cache", -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.set(ACTOR, ORIGIN, ORG, "cache", EntitlementAdministration.MAX_INSTANCES_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        service.set(ACTOR, ORIGIN, ORG, "cache", EntitlementAdministration.MAX_INSTANCES_LIMIT);

        assertThat(calls).as("the only authorizations are the final, valid call's two").startsWith("authorize entitlement.set");
        assertThat(calls.stream().filter(c -> c.startsWith("authorize")).count()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- the read

    @Test
    void aReadReturnsEachRowWithItsDeclaredCountFromTheSameCountTheDeclarePathUses() {
        rows.put("cache", new EntitlementRepository.Stored("cache", 5, AT));
        rows.put("file-mesh", new EntitlementRepository.Stored("file-mesh", 2, AT));
        declared.put("cache", 4L);

        assertThat(service.list(ACTOR, ORG)).containsExactly(new Entitlement("cache", 5, 4, AT),
                new Entitlement("file-mesh", 2, 0, AT));
    }
}
