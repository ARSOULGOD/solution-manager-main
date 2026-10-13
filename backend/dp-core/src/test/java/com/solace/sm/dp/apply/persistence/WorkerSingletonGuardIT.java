package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Task 4a's cross-process singleton guard, in isolation: R8's ruling ("no v1 lease") is
 * CONTINGENT on there being exactly one worker pool system-wide, and this is the mechanism that
 * makes that a guarantee rather than an assumption — proven here across TWO independent
 * {@link WorkerSingletonGuard} instances, each with its OWN {@link DataSource} (so each opens its
 * own physical connection, exactly as two separate application PROCESSES would).
 */
class WorkerSingletonGuardIT {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer<>("postgres:14");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    private DataSource freshDataSource() {
        // A NEW DriverManagerDataSource per guard, deliberately: it opens a fresh physical
        // connection on every getConnection() call rather than pooling, which is exactly what
        // simulates a SECOND process's own, independent session.
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    @Test
    void theSingletonWorkerTopologyIsEnforcedAtStartupNotAssumed() {
        WorkerSingletonGuard first = new WorkerSingletonGuard(freshDataSource());
        WorkerSingletonGuard second = new WorkerSingletonGuard(freshDataSource());

        assertDoesNotThrow(first::acquireOrRefuseToStart);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, second::acquireOrRefuseToStart);
        assertTrue(refused.getMessage().contains("single worker pool"),
                () -> "the refusal must name the property it protects: " + refused.getMessage());

        first.release();
    }

    @Test
    void releasingTheFirstGuardLetsASecondOneAcquire() {
        WorkerSingletonGuard first = new WorkerSingletonGuard(freshDataSource());
        WorkerSingletonGuard second = new WorkerSingletonGuard(freshDataSource());

        first.acquireOrRefuseToStart();
        first.release();

        assertDoesNotThrow(second::acquireOrRefuseToStart);
        second.release();
    }

    @Test
    void acquiringTwiceOnTheSameGuardIsANoOp() {
        WorkerSingletonGuard guard = new WorkerSingletonGuard(freshDataSource());

        guard.acquireOrRefuseToStart();
        assertDoesNotThrow(guard::acquireOrRefuseToStart);

        guard.release();
    }
}
