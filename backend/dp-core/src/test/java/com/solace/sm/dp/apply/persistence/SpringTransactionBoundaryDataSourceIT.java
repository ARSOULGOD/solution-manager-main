package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 003's standing watch item, discharged: {@link SpringTransactionBoundary} under a REAL
 * {@link DataSourceTransactionManager} over a real PostgreSQL connection, rather than the
 * datastore-free {@code AbstractPlatformTransactionManager} its unit test uses.
 *
 * <p>Why it was left open: the unit test's recording manager runs the genuine synchronization
 * machinery but commits nothing, so it can prove the ORDER of the callback and not that the
 * callback fires after a durable commit. This class writes a row, and the after-commit action reads
 * whether that row is visible to a different connection.
 *
 * <p>It also settles what {@code SYNCHRONIZATION_NEVER} does — a case the class javadoc describes
 * and no test had ever executed.
 */
@Testcontainers(disabledWithoutDocker = true)
class SpringTransactionBoundaryDataSourceIT {

    private static NamedParameterJdbcTemplate runtime;
    private static DataSourceTransactionManager ptm;
    private static final SpringTransactionBoundary BOUNDARY = new SpringTransactionBoundary();

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        ptm = new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource());
    }

    @Test
    void insideARealDatabaseTransactionTheActionRunsOnlyAfterTheRowIsCommittedAndVisible() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        List<Boolean> rowVisibleWhenActionRan = new ArrayList<>();
        List<Boolean> ranBeforeTheTemplateReturned = new ArrayList<>();

        new TransactionTemplate(ptm).executeWithoutResult(status -> {
            assertTrue(BOUNDARY.inTransaction(),
                    "a DataSourceTransactionManager transaction must satisfy BOTH conjuncts");
            ApplyRows.insertScope(runtime, tenant, instance);
            assertFalse(scopeExistsOnAnotherConnection(tenant, instance),
                    "precondition: the insert must be INSIDE the transaction. A JdbcTemplate over a "
                            + "different DataSource instance than the transaction manager takes its "
                            + "own auto-commit connection and this whole test proves nothing "
                            + "(measured — see SmDataPostgres' caching note)");
            BOUNDARY.afterCommit(() -> {
                // A SEPARATE connection out of the pool — so visibility here means committed, not
                // merely written on this session.
                rowVisibleWhenActionRan.add(scopeExistsOnAnotherConnection(tenant, instance));
                ranBeforeTheTemplateReturned.add(Boolean.TRUE);
            });
            assertTrue(rowVisibleWhenActionRan.isEmpty(),
                    "nothing may run while the transaction is still open");
        });

        assertEquals(List.of(Boolean.TRUE), rowVisibleWhenActionRan,
                "the after-commit action must observe a COMMITTED row — that ordering is BE-D1 §3, "
                        + "and only a real transaction manager can prove it");
        assertEquals(1, ranBeforeTheTemplateReturned.size());
    }

    @Test
    void aRolledBackDatabaseTransactionRunsNoActionAndLeavesNoRow() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        List<String> ran = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(ptm).executeWithoutResult(status -> {
                    ApplyRows.insertScope(runtime, tenant, instance);
                    BOUNDARY.afterCommit(() -> ran.add("published"));
                    throw new IllegalStateException("business write failed");
                }));

        assertTrue(ran.isEmpty(), "a rolled-back write happened to nobody — no notice may go out");
        assertFalse(scopeExistsOnAnotherConnection(tenant, instance),
                "…and the row really did roll back, so the test is about a real rollback");
    }

    @Test
    void withPropagationNotSupportedThereIsNoTransactionToDeferAgainst() {
        TransactionTemplate template = new TransactionTemplate(ptm);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        template.executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isSynchronizationActive(),
                    "PROPAGATION_NOT_SUPPORTED still opens a synchronized scope…");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "…with no actual transaction in it");
            assertFalse(BOUNDARY.inTransaction(),
                    "publishing immediately is CORRECT here: there is no commit to order against. "
                            + "This is the case the second conjunct exists for");
        });
    }

    /**
     * {@code SYNCHRONIZATION_NEVER}, measured — and the measurement corrects the class javadoc.
     *
     * <p>{@code SpringTransactionBoundary} described this as "an actual transaction with
     * synchronization disabled", implying {@code isActualTransactionActive()} is true and the
     * synchronization conjunct is what degrades the boundary to immediate publish. It is not:
     * {@code AbstractPlatformTransactionManager.prepareSynchronization} is the ONLY place that sets
     * the actual-transaction flag, and it is skipped entirely when synchronization is never — so
     * BOTH conjuncts read false and the transaction, though genuinely open, is invisible to the
     * whole {@code TransactionSynchronizationManager} API.
     *
     * <p>The outcome the javadoc predicted (immediate publish, today's pre-decorator behaviour) is
     * right. Its stated mechanism was not.
     */
    @Test
    void withSynchronizationNeverTheOpenTransactionIsInvisibleToBothConjunctsNotJustOne() {
        DataSourceTransactionManager never =
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource());
        never.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();

        new TransactionTemplate(never).executeWithoutResult(status -> {
            // The transaction is REAL: this insert participates in it and is committed at the end.
            ApplyRows.insertScope(runtime, tenant, instance);

            assertFalse(TransactionSynchronizationManager.isSynchronizationActive(),
                    "synchronization is off — that much the mode's name promises");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "and the actual-transaction flag is ALSO false, because prepareSynchronization "
                            + "sets it and prepareSynchronization does not run in this mode");
            assertFalse(BOUNDARY.inTransaction(),
                    "so the boundary degrades to immediate publish, as the javadoc predicts — but "
                            + "on both conjuncts, not on the synchronization one alone");

            assertThrows(IllegalStateException.class, () -> BOUNDARY.afterCommit(() -> { }),
                    "registering anyway is refused: 'Transaction synchronization is not active'. "
                            + "The inTransaction() guard is what keeps AfterCommitEventPublisher off "
                            + "this path");
        });

        assertTrue(scopeExistsOnAnotherConnection(tenant, instance),
                "the transaction really did commit — this mode hides synchronization, not the write");
    }

    private static boolean scopeExistsOnAnotherConnection(UUID tenant, UUID instance) {
        return Boolean.TRUE.equals(SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_scope"
                        + " WHERE tenant_id = :t AND instance_id = :i)",
                ApplyRows.scope(tenant, instance), Boolean.class));
    }
}
