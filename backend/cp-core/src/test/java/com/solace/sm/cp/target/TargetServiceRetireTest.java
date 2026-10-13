package com.solace.sm.cp.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 013 T1.5, the control plane's half of {@code retireTarget} (D-REG section 5.1 T7; OQ-11 (b)), offline: the verb is
 * {@code target.edit} at the target's own scope and is checked before anything is read or written; the retirement is one
 * audited write ({@code TARGET_RETIRED}); retiring a retired target is an answer and writes nothing; a target of another
 * organization is as absent as an unknown one. The real index and trigger are {@code TargetServiceIT}.
 */
class TargetServiceRetireTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.7", UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    static final class Repository implements TargetRepository {
        final Map<UUID, Stored> rows = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        Runnable beforeRetire = () -> { };

        TargetView add(String name, Instant retiredAt) {
            TargetView view = new TargetView(UUID.randomUUID(), name, "docker", "ssh-docker", ScopePath.organization(ORG));
            rows.put(view.targetId(), new Stored(view, retiredAt));
            return view;
        }

        @Override
        public List<TargetView> listLive(UUID organizationId) {
            return List.of();
        }

        @Override
        public Optional<Stored> findByName(UUID organizationId, String name) {
            return Optional.empty();
        }

        @Override
        public Optional<Stored> findById(UUID organizationId, UUID targetId) {
            calls.add("findById");
            return organizationId.equals(ORG) ? Optional.ofNullable(rows.get(targetId)) : Optional.empty();
        }

        @Override
        public void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
                ScopePath ownershipScope, UUID createdBy, Instant createdAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markRetired(UUID organizationId, UUID targetId, Instant at) {
            calls.add("markRetired");
            beforeRetire.run();
            Stored stored = rows.get(targetId);
            if (stored == null || stored.retired()) {
                return 0;
            }
            rows.put(targetId, new Stored(stored.view(), at));
            return 1;
        }
    }

    private final Repository repository = new Repository();
    private final List<String> audits = new ArrayList<>();
    private final List<ResourceRef> required = new ArrayList<>();
    private final List<Permission> permissions = new ArrayList<>();
    private RuntimeException refusal;
    private final TargetService service = new TargetService(repository, (principal, permission, resource) -> {
        permissions.add(permission);
        required.add(resource);
        if (refusal != null) {
            throw refusal;
        }
    }, (actor, operation, correlation, client) -> audits.add(operation + "/" + correlation + "/" + client),
            TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void retiringALiveTargetChecksTargetEditAtTheTargetsOwnScopeThenWritesOnceUnderTheRetiredAudit() {
        TargetView live = repository.add("vm-1", null);

        TargetRegistration.Retired retired = service.retire(ACTOR, ORG, live.targetId(), ORIGIN);

        assertEquals(List.of(Permission.TARGET_EDIT), permissions);
        assertEquals(List.of(ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, live.targetId(), new TenantId(ORG))), required);
        assertEquals(live, retired.view());
        assertEquals(NOW, retired.retiredAt());
        assertFalse(retired.alreadyRetired());
        assertEquals(List.of("findById", "markRetired"), repository.calls);
        assertEquals(List.of(AuditOperations.TARGET_RETIRED + "/" + ORIGIN.correlationId() + "/203.0.113.7"), audits,
                "the audit context is set (OR-25) with the request's own origin");
        assertTrue(repository.rows.get(live.targetId()).retired());
    }

    @Test
    void retiringARetiredTargetIsAnAnswerAndWritesNothing() {
        Instant earlier = Instant.parse("2026-10-01T00:00:00Z");
        TargetView gone = repository.add("vm-old", earlier);

        TargetRegistration.Retired retired = service.retire(ACTOR, ORG, gone.targetId(), ORIGIN);

        assertTrue(retired.alreadyRetired());
        assertEquals(earlier, retired.retiredAt(), "the ORIGINAL retirement time, not now");
        assertFalse(repository.calls.contains("markRetired"));
    }

    @Test
    void aCallerWithoutTheVerbIsRefusedBeforeTheRowIsRead() {
        TargetView live = repository.add("vm-1", null);
        refusal = new AccessDeniedException(Permission.TARGET_EDIT);

        assertThrows(AccessDeniedException.class, () -> service.retire(ACTOR, ORG, live.targetId(), ORIGIN));

        assertEquals(List.of(), repository.calls);
        assertEquals(List.of(), audits);
    }

    @Test
    void anUnknownOrForeignTargetIsNotFoundAndWritesNothing() {
        TargetView live = repository.add("vm-1", null);

        assertThrows(ResourceNotFoundException.class, () -> service.retire(ACTOR, ORG, UUID.randomUUID(), ORIGIN));
        assertThrows(ResourceNotFoundException.class, () -> service.retire(ACTOR, UUID.randomUUID(), live.targetId(), ORIGIN));

        assertFalse(repository.calls.contains("markRetired"));
        assertFalse(repository.rows.get(live.targetId()).retired());
    }

    @Test
    void aConcurrentRetirementBetweenTheReadAndTheWriteIsTheSameAnswer() {
        TargetView live = repository.add("vm-1", null);
        Instant theirs = Instant.parse("2026-10-08T11:59:59Z");
        repository.beforeRetire = () -> repository.rows.put(live.targetId(), new TargetRepository.Stored(live, theirs));

        TargetRegistration.Retired retired = service.retire(ACTOR, ORG, live.targetId(), ORIGIN);

        assertTrue(retired.alreadyRetired());
        assertEquals(theirs, retired.retiredAt());
    }
}
