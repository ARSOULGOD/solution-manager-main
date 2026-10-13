package com.solace.sm.cp.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.identity.AuditContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 013 T1.4, D-REG section 8: two identical {@code POST}s at once both find no row and one loses the insert on the
 * partial unique index {@code target_name_once}. The loser's transaction is run again and answers as the REPLAY it is, not
 * as a {@code 500} and not as a {@code 409}. (The database half, with the real index, is {@code TargetServiceIT}.)
 */
class TargetServiceRegistrationRaceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("127.0.0.1", UUID.randomUUID());

    /** A repository whose first {@code findByName} sees nothing and whose first insert loses a race to {@code winner}. */
    static class RacingRepository implements TargetRepository {
        final List<String> calls = new ArrayList<>();
        TargetView winner;
        int duplicateInserts;
        boolean winnerCommitted;

        @Override
        public List<TargetView> listLive(UUID organizationId) {
            return List.of();
        }

        @Override
        public Optional<Stored> findByName(UUID organizationId, String name) {
            calls.add("find");
            return winnerCommitted ? Optional.of(new Stored(winner, null)) : Optional.empty();
        }

        @Override
        public Optional<Stored> findById(UUID organizationId, UUID targetId) {
            return Optional.empty();
        }

        @Override
        public void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
                ScopePath ownershipScope, UUID createdBy, Instant createdAt) {
            calls.add("insert");
            if (duplicateInserts > 0) {
                duplicateInserts--;
                winnerCommitted = true;      // the other request's transaction committed first
                throw new DuplicateKeyException("target_name_once");
            }
        }

        @Override
        public int markRetired(UUID organizationId, UUID targetId, Instant at) {
            throw new UnsupportedOperationException("registration never retires");
        }
    }

    private final RacingRepository repository = new RacingRepository();
    private final List<String> audits = new ArrayList<>();
    private final AuditContext audit = (actor, operation, correlation, client) -> audits.add(operation);
    private final TargetService service = new TargetService(repository, (principal, permission, resource) -> { },
            audit, TransactionOperations.withoutTransaction(), Clock.systemUTC());

    private TargetRegistration.Request request(String name, ScopePath scope) {
        return new TargetRegistration.Request(null, name, "docker", "ssh-docker", scope);
    }

    @Test
    void theLoserOfTheInsertRaceAnswersTheWinnersRowAsAReplay() {
        ScopePath scope = ScopePath.organization(ORG);
        repository.winner = new TargetView(UUID.randomUUID(), "vm-1", "docker", "ssh-docker", scope);
        repository.duplicateInserts = 1;

        TargetView answered = service.register(ACTOR, ORG, request("vm-1", scope), ORIGIN);

        assertSame(repository.winner, answered, "the winner's row, not a new one");
        assertEquals(List.of("find", "insert", "find"), repository.calls, "run again: the second run finds the row");
    }

    @Test
    void aLoserWhoseNameWasTakenByADifferentRegistrationIsRefusedNotReplayed() {
        ScopePath scope = ScopePath.organization(ORG);
        repository.winner = new TargetView(UUID.randomUUID(), "vm-1", "docker", "ssh-docker",
                ScopePath.subOrganization(ORG, UUID.randomUUID()));
        repository.duplicateInserts = 1;

        assertThrows(IllegalStateException.class, () -> service.register(ACTOR, ORG, request("vm-1", scope), ORIGIN));
    }

    @Test
    void aSecondLossIsNotRetriedForever() {
        repository.duplicateInserts = 2;
        repository.winner = new TargetView(UUID.randomUUID(), "vm-1", "docker", "ssh-docker", ScopePath.organization(ORG));

        // the first run loses, the second finds the (now committed) row: so two losses cannot happen in this model;
        // a repository that keeps raising it is a defect that must surface, not loop
        RacingRepository stubborn = new RacingRepository() {
            @Override
            public void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
                    ScopePath ownershipScope, UUID createdBy, Instant createdAt) {
                calls.add("insert");
                throw new DuplicateKeyException("always");
            }
        };
        TargetService stubbornService = new TargetService(stubborn, (principal, permission, resource) -> { }, audit,
                TransactionOperations.withoutTransaction(), Clock.systemUTC());

        assertThrows(DuplicateKeyException.class,
                () -> stubbornService.register(ACTOR, ORG, request("vm-9", ScopePath.organization(ORG)), ORIGIN));
        assertEquals(2, stubborn.calls.stream().filter("insert"::equals).count(), "one run and exactly one retry");
    }

    @Test
    void anUncontendedRegistrationInsertsOnceAndAuditsOnce() {
        TargetView view = service.register(ACTOR, ORG, request("vm-1", ScopePath.organization(ORG)), ORIGIN);

        assertEquals("vm-1", view.name());
        assertEquals(List.of("find", "insert"), repository.calls);
        assertEquals(1, audits.size());
    }
}
