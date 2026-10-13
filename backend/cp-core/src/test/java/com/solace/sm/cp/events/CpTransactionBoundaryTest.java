package com.solace.sm.cp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link CpTransactionBoundary} in isolation: whose transaction counts, and when the action runs. */
class CpTransactionBoundaryTest {

    private final FakeResourceTransactionManager cp = new FakeResourceTransactionManager();
    private final FakeResourceTransactionManager dp = new FakeResourceTransactionManager();
    private final CpTransactionBoundary boundary = new CpTransactionBoundary(cp);

    @Test
    void outsideAnyTransactionThereIsNothingToOrderAgainst() {
        assertThat(boundary.inTransaction()).isFalse();
    }

    @Test
    void onlyAControlPlaneTransactionCounts() {
        new TransactionTemplate(cp).executeWithoutResult(status -> assertThat(boundary.inTransaction()).isTrue());
        new TransactionTemplate(dp).executeWithoutResult(status ->
                assertThat(boundary.inTransaction()).as("a data-plane transaction is not ours").isFalse());
    }

    @Test
    void theActionRunsExactlyOnceAfterCommitAndNeverOnRollback() {
        List<String> ran = new ArrayList<>();

        new TransactionTemplate(cp).executeWithoutResult(status -> {
            boundary.afterCommit(() -> ran.add("committed"));
            assertThat(ran).isEmpty();
        });
        new TransactionTemplate(cp).executeWithoutResult(status -> {
            boundary.afterCommit(() -> ran.add("rolled back"));
            status.setRollbackOnly();
        });

        assertThat(ran).containsExactly("committed");
    }

    @Test
    void registeringOutsideATransactionFailsLoudlyAsTheContractSays() {
        assertThatThrownBy(() -> boundary.afterCommit(() -> { })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aManagerWithoutAResourceFactoryIsRefused() {
        assertThatThrownBy(() -> new CpTransactionBoundary(null)).isInstanceOf(NullPointerException.class);
    }
}
