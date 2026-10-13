package com.solace.sm.cp.connection;

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
import com.solace.sm.cp.api.connection.ConnectionConflictException;
import com.solace.sm.cp.api.connection.ConnectionConflictException.Kind;
import com.solace.sm.cp.api.connection.ConnectionRegistry.CreateRequest;
import com.solace.sm.cp.api.connection.ConnectionRegistry.UpdateRequest;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.identity.AuditContext;
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
 * Plan 013 T2 ({@code BE-D66}, {@code OR-130}), offline: authorization/scope enforcement ({@code I5}), optimistic
 * concurrency on {@code revision}, and the {@code usedByCount}-guarded delete, modeled on
 * {@code TargetServiceRetireTest}/{@code TargetServiceRegistrationRaceTest}'s own shape. The real index and trigger
 * are left to a future IT (plan 013 T2, DB-backed; not run here — see the task report for why).
 */
class ConnectionServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.9", UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    /** An in-memory stand-in for {@code sm_control.connection}, keyed by (org, scope, name) for uniqueness. */
    static final class Repository implements ConnectionRepository {
        final Map<UUID, ConnectionView> rows = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        Runnable beforeUpdate = () -> { };
        Runnable beforeDeactivate = () -> { };

        ConnectionView seed(String name, ScopePath scope, boolean active, int usedByCount, long revision,
                Map<String, Object> attributes) {
            ConnectionView view = new ConnectionView(UUID.randomUUID(), name, "solace-broker", 1, scope, attributes,
                    Map.of(), active, usedByCount, revision, NOW, NOW);
            rows.put(view.connectionId(), view);
            return view;
        }

        @Override
        public List<ConnectionView> list(UUID organizationId) {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<ConnectionView> findById(UUID organizationId, UUID connectionId) {
            calls.add("findById");
            return organizationId.equals(ORG) ? Optional.ofNullable(rows.get(connectionId)) : Optional.empty();
        }

        @Override
        public Optional<ConnectionView> findByName(UUID organizationId, ScopePath ownershipScope, String name) {
            calls.add("findByName");
            return rows.values().stream()
                    .filter(v -> v.ownershipScope().equals(ownershipScope) && v.name().equals(name))
                    .findFirst();
        }

        @Override
        public void insert(UUID organizationId, UUID connectionId, String name, String connectionType,
                int descriptorVersion, ScopePath ownershipScope, Map<String, Object> attributes,
                Map<String, Boolean> secretStatus, UUID createdBy, Instant createdAt) {
            calls.add("insert");
            rows.put(connectionId, new ConnectionView(connectionId, name, connectionType, descriptorVersion,
                    ownershipScope, attributes, secretStatus, true, 0, 1, createdAt, createdAt));
        }

        @Override
        public boolean update(UUID organizationId, UUID connectionId, long expectedRevision, String name,
                Map<String, Object> attributes, Map<String, Boolean> secretStatus, UUID updatedBy,
                Instant updatedAt) {
            calls.add("update");
            beforeUpdate.run();
            ConnectionView current = rows.get(connectionId);
            if (current == null || current.revision() != expectedRevision) {
                return false;
            }
            rows.put(connectionId, new ConnectionView(connectionId, name, current.connectionType(),
                    current.descriptorVersion(), current.ownershipScope(), attributes, secretStatus,
                    current.active(), current.usedByCount(), current.revision() + 1, current.createdAt(), updatedAt));
            return true;
        }

        @Override
        public boolean deactivate(UUID organizationId, UUID connectionId, long expectedRevision, UUID updatedBy,
                Instant updatedAt) {
            calls.add("deactivate");
            beforeDeactivate.run();
            ConnectionView current = rows.get(connectionId);
            if (current == null || current.revision() != expectedRevision || !current.active()) {
                return false;
            }
            rows.put(connectionId, new ConnectionView(connectionId, current.name(), current.connectionType(),
                    current.descriptorVersion(), current.ownershipScope(), current.attributes(),
                    current.secretStatus(), false, current.usedByCount(), current.revision() + 1,
                    current.createdAt(), updatedAt));
            return true;
        }

        @Override
        public int delete(UUID organizationId, UUID connectionId) {
            calls.add("delete");
            if (!organizationId.equals(ORG)) {
                return 0;
            }
            return rows.remove(connectionId) != null ? 1 : 0;
        }
    }

    private final Repository repository = new Repository();
    private final List<String> audits = new ArrayList<>();
    private final List<Permission> permissions = new ArrayList<>();
    private final List<ResourceRef> required = new ArrayList<>();
    private RuntimeException refusal;
    private final AuditContext audit = (actor, operation, correlation, client) -> audits.add(operation);
    private final ConnectionService service = new ConnectionService(repository, (principal, permission, resource) -> {
        permissions.add(permission);
        required.add(resource);
        if (refusal != null) {
            throw refusal;
        }
    }, audit, TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));

    // ---------------------------------------------------------------- create

    @Test
    void createChecksConnectionCreateAtTheBodysOwnershipScopeBeforeAnythingIsRead() {
        ScopePath scope = ScopePath.organization(ORG);
        CreateRequest request = new CreateRequest("broker-1", "solace-broker", 1, scope, Map.of("host", "a"), Map.of());

        ConnectionView created = service.create(ACTOR, ORG, request, ORIGIN);

        assertEquals(List.of(Permission.CONNECTION_CREATE), permissions);
        assertEquals(List.of(ResourceRef.of(ResourceKind.ORGANIZATION, ORG, new TenantId(ORG))), required);
        assertEquals("broker-1", created.name());
        assertEquals(1, created.revision());
        assertTrue(created.active());
        assertEquals(0, created.usedByCount());
        assertEquals(List.of("CONNECTION_CREATED"), audits);
    }

    /**
     * Integration plan 014: the declare reads one connection by id through {@code ConnectionDirectory.find}. It is the
     * repository's one-row read, scoped to the organization ({@code I5}), and asks no verb (the declare already did).
     */
    @Test
    void findReadsTheOneRowOfTheOrganizationAndAsksNoVerb() {
        ConnectionView seeded = repository.seed("remote-1", ScopePath.organization(ORG), true, 0, 1, Map.of());

        assertEquals(Optional.of(seeded), service.find(ORG, seeded.connectionId()));
        assertEquals(Optional.empty(), service.find(UUID.randomUUID(), seeded.connectionId()),
                "another organization's connection is not found");
        assertEquals(Optional.empty(), service.find(ORG, UUID.randomUUID()));
        assertEquals(List.of("findById", "findById", "findById"), repository.calls);
        assertEquals(List.of(), permissions);
    }

    @Test
    void aCallerWithoutConnectionCreateIsRefusedBeforeAnyRepositoryCall() {
        refusal = new AccessDeniedException(Permission.CONNECTION_CREATE);
        ScopePath scope = ScopePath.organization(ORG);
        CreateRequest request = new CreateRequest("broker-1", "solace-broker", 1, scope, Map.of(), Map.of());

        assertThrows(AccessDeniedException.class, () -> service.create(ACTOR, ORG, request, ORIGIN));

        assertEquals(List.of(), repository.calls);
        assertEquals(List.of(), audits);
    }

    @Test
    void creatingTheSameNameInTheSameScopeTwiceIsAConflictNotAReplay() {
        ScopePath scope = ScopePath.organization(ORG);
        repository.seed("broker-1", scope, true, 0, 1, Map.of());
        CreateRequest request = new CreateRequest("broker-1", "solace-broker", 1, scope, Map.of(), Map.of());

        ConnectionConflictException conflict = assertThrows(ConnectionConflictException.class,
                () -> service.create(ACTOR, ORG, request, ORIGIN));

        assertEquals(Kind.NAME_TAKEN, conflict.kind());
        assertFalse(repository.calls.contains("insert"), "nothing is written on a name clash");
    }

    @Test
    void theSameNameInADifferentScopeIsNotAConflict() {
        ScopePath orgScope = ScopePath.organization(ORG);
        UUID unit = UUID.randomUUID();
        ScopePath unitScope = ScopePath.subOrganization(ORG, unit);
        repository.seed("broker-1", orgScope, true, 0, 1, Map.of());

        CreateRequest request = new CreateRequest("broker-1", "solace-broker", 1, unitScope, Map.of(), Map.of());
        ConnectionView created = service.create(ACTOR, ORG, request, ORIGIN);

        assertEquals("broker-1", created.name());
        assertEquals(unitScope, created.ownershipScope());
    }

    // ---------------------------------------------------------------- update

    @Test
    void updateChecksConnectionEditAtTheConnectionsOwnScopeBeforeTheRowIsRead() {
        refusal = new AccessDeniedException(Permission.CONNECTION_EDIT);
        UUID id = UUID.randomUUID();

        assertThrows(AccessDeniedException.class,
                () -> service.update(ACTOR, ORG, id, new UpdateRequest("new-name", null, null), ORIGIN));

        assertEquals(List.of(Permission.CONNECTION_EDIT), permissions);
        assertEquals(List.of(), repository.calls);
    }

    @Test
    void updatingAnUnknownOrForeignConnectionIsNotFoundAndWritesNothing() {
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, Map.of());

        assertThrows(ResourceNotFoundException.class,
                () -> service.update(ACTOR, ORG, UUID.randomUUID(), new UpdateRequest("x", null, null), ORIGIN));
        assertThrows(ResourceNotFoundException.class,
                () -> service.update(ACTOR, UUID.randomUUID(), live.connectionId(), new UpdateRequest("x", null, null),
                        ORIGIN));

        assertFalse(repository.calls.contains("update"));
    }

    @Test
    void updateMergesAttributesNewOverExistingAndBumpsTheRevision() {
        Map<String, Object> existing = Map.of("host", "old-host", "port", 55555);
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, existing);

        ConnectionView updated = service.update(ACTOR, ORG, live.connectionId(),
                new UpdateRequest(null, Map.of("host", "new-host"), null), ORIGIN);

        assertEquals("new-host", updated.attributes().get("host"), "overlay wins");
        assertEquals(55555, updated.attributes().get("port"), "untouched keys survive the merge");
        assertEquals(2, updated.revision());
        assertEquals(List.of("CONNECTION_UPDATED"), audits);
    }

    @Test
    void renamingToAnAlreadyTakenNameInTheSameScopeIsAConflict() {
        ScopePath scope = ScopePath.organization(ORG);
        repository.seed("taken", scope, true, 0, 1, Map.of());
        ConnectionView mine = repository.seed("mine", scope, true, 0, 1, Map.of());

        ConnectionConflictException conflict = assertThrows(ConnectionConflictException.class,
                () -> service.update(ACTOR, ORG, mine.connectionId(), new UpdateRequest("taken", null, null), ORIGIN));

        assertEquals(Kind.NAME_TAKEN, conflict.kind());
    }

    @Test
    void renamingToItsOwnCurrentNameIsNotAConflictWithItself() {
        ScopePath scope = ScopePath.organization(ORG);
        ConnectionView mine = repository.seed("same-name", scope, true, 0, 1, Map.of());

        ConnectionView updated = service.update(ACTOR, ORG, mine.connectionId(),
                new UpdateRequest("same-name", Map.of("k", "v"), null), ORIGIN);

        assertEquals("same-name", updated.name());
    }

    @Test
    void aLostUpdateRaceIsRetriedOnceAndSucceedsOnTheSecondAttempt() {
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, Map.of());
        boolean[] fired = {false};
        repository.beforeUpdate = () -> {
            if (!fired[0]) {
                fired[0] = true;
                // a concurrent writer commits between our read and our write, on the FIRST attempt only
                repository.rows.put(live.connectionId(), new ConnectionView(live.connectionId(), live.name(),
                        live.connectionType(), live.descriptorVersion(), live.ownershipScope(), live.attributes(),
                        live.secretStatus(), live.active(), live.usedByCount(), 2, live.createdAt(), NOW));
            }
        };

        ConnectionView updated = service.update(ACTOR, ORG, live.connectionId(),
                new UpdateRequest(null, Map.of("k", "v"), null), ORIGIN);

        assertEquals(3, updated.revision(), "the race bumped it to 2, our retried write bumps it to 3");
        assertEquals(2, repository.calls.stream().filter("update"::equals).count(), "one run and exactly one retry");
    }

    @Test
    void aSecondLostRaceIsAnsweredAsStaleWriteNotRetriedForever() {
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, Map.of());
        // every attempt loses: the stored revision never matches what update() read
        repository.beforeUpdate = () -> repository.rows.put(live.connectionId(),
                new ConnectionView(live.connectionId(), live.name(), live.connectionType(), live.descriptorVersion(),
                        live.ownershipScope(), live.attributes(), live.secretStatus(), live.active(),
                        live.usedByCount(), repository.rows.get(live.connectionId()).revision() + 1, live.createdAt(),
                        NOW));

        ConnectionConflictException conflict = assertThrows(ConnectionConflictException.class,
                () -> service.update(ACTOR, ORG, live.connectionId(), new UpdateRequest(null, Map.of("k", "v"), null),
                        ORIGIN));

        assertEquals(Kind.STALE_WRITE, conflict.kind());
        assertEquals(2, repository.calls.stream().filter("update"::equals).count(), "one run and exactly one retry, no more");
    }

    // ---------------------------------------------------------------- deactivate

    @Test
    void deactivatingALiveConnectionMarksItInactiveAndBumpsTheRevision() {
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, Map.of());

        ConnectionView deactivated = service.deactivate(ACTOR, ORG, live.connectionId(), ORIGIN);

        assertFalse(deactivated.active());
        assertEquals(2, deactivated.revision());
        assertEquals(List.of("CONNECTION_DEACTIVATED"), audits);
    }

    @Test
    void deactivatingAnAlreadyInactiveConnectionIsAnAnswerAndWritesNothing() {
        ConnectionView inactive = repository.seed("broker-1", ScopePath.organization(ORG), false, 0, 3, Map.of());

        ConnectionView answer = service.deactivate(ACTOR, ORG, inactive.connectionId(), ORIGIN);

        assertEquals(inactive, answer);
        assertFalse(repository.calls.contains("deactivate"));
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteChecksConnectionDeleteAtTheConnectionsOwnScopeBeforeTheRowIsRead() {
        refusal = new AccessDeniedException(Permission.CONNECTION_DELETE);
        UUID id = UUID.randomUUID();

        assertThrows(AccessDeniedException.class, () -> service.delete(ACTOR, ORG, id, ORIGIN));

        assertEquals(List.of(Permission.CONNECTION_DELETE), permissions);
        assertEquals(List.of(), repository.calls);
    }

    @Test
    void deletingAConnectionWithNoReferencesRemovesTheRow() {
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, Map.of());

        service.delete(ACTOR, ORG, live.connectionId(), ORIGIN);

        assertFalse(repository.rows.containsKey(live.connectionId()));
        assertEquals(List.of("CONNECTION_DELETED"), audits);
    }

    @Test
    void deletingAConnectionThatIsStillReferencedIsRefusedAndWritesNothing() {
        ConnectionView referenced = repository.seed("broker-1", ScopePath.organization(ORG), true, 3, 1, Map.of());

        ConnectionConflictException conflict = assertThrows(ConnectionConflictException.class,
                () -> service.delete(ACTOR, ORG, referenced.connectionId(), ORIGIN));

        assertEquals(Kind.IN_USE, conflict.kind());
        assertTrue(repository.rows.containsKey(referenced.connectionId()), "nothing was deleted");
        assertFalse(repository.calls.contains("delete"));
    }

    @Test
    void deletingAnUnknownOrForeignConnectionIsNotFound() {
        ConnectionView live = repository.seed("broker-1", ScopePath.organization(ORG), true, 0, 1, Map.of());

        assertThrows(ResourceNotFoundException.class, () -> service.delete(ACTOR, ORG, UUID.randomUUID(), ORIGIN));
        assertThrows(ResourceNotFoundException.class,
                () -> service.delete(ACTOR, UUID.randomUUID(), live.connectionId(), ORIGIN));

        assertTrue(repository.rows.containsKey(live.connectionId()));
    }
}
