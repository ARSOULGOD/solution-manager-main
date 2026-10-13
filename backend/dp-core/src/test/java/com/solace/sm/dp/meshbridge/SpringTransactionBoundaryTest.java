package com.solace.sm.dp.meshbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.contracttest.ContractArtifacts;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Spring adapter under Spring's REAL synchronization dispatch: a minimal
 * {@link AbstractPlatformTransactionManager} (no datastore — begin/commit/rollback are recorded, and
 * the abstract base runs the genuine synchronization machinery) driven through
 * {@link TransactionTemplate}. Poking TransactionSynchronizationManager statics by hand would test
 * our understanding of that class, not the adapter's behaviour inside a managed transaction.
 */
class SpringTransactionBoundaryTest {

    /** Real AbstractPlatformTransactionManager, no resource — commit flips a flag the tests read. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        volatile boolean committed;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No resource to bind — the base class runs the synchronization lifecycle regardless.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Nothing to undo.
        }
    }

    /** Records each publish together with whether the transaction had committed at that moment. */
    static final class CommitAwarePublisher implements EventPublisher {
        final RecordingTransactionManager ptm;
        final List<Boolean> committedAtPublishTime = new ArrayList<>();

        CommitAwarePublisher(RecordingTransactionManager ptm) {
            this.ptm = ptm;
        }

        @Override
        public void publish(EventEnvelope event) {
            committedAtPublishTime.add(ptm.committed);
        }
    }

    private static EventEnvelope healthFixture() throws IOException {
        return EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/fixtures").resolve("health-transition.json")));
    }

    @Test
    void insideAManagedTransactionThePublishLandsAfterDoCommit() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        CommitAwarePublisher delegate = new CommitAwarePublisher(ptm);
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());
        EventEnvelope event = healthFixture();

        new TransactionTemplate(ptm).executeWithoutResult(status -> {
            p.publish(event);
            assertTrue(delegate.committedAtPublishTime.isEmpty(),
                    "nothing may reach the delegate while the transaction is still open");
        });

        assertEquals(List.of(Boolean.TRUE), delegate.committedAtPublishTime,
                "the delegate publish must observe an already-committed transaction — that ordering "
                        + "IS BE-D1 §3");
    }

    @Test
    void aRolledBackTransactionPublishesNothing() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        CommitAwarePublisher delegate = new CommitAwarePublisher(ptm);
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());
        EventEnvelope event = healthFixture();

        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(ptm).executeWithoutResult(status -> {
                    p.publish(event);
                    throw new IllegalStateException("business write failed");
                }));

        assertFalse(ptm.committed, "precondition: the transaction must really have rolled back");
        assertTrue(delegate.committedAtPublishTime.isEmpty(),
                "a rolled-back write happened to nobody — no notice may go out");
    }

    @Test
    void outsideAnyTransactionThePublishIsImmediate() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        CommitAwarePublisher delegate = new CommitAwarePublisher(ptm);
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());

        p.publish(healthFixture());

        assertEquals(1, delegate.committedAtPublishTime.size(),
                "no transaction on this thread — the boundary must not defer into nowhere");
    }

    @Test
    void twoEventsInOneTransactionArriveInOrderAfterCommit() throws IOException {
        RecordingTransactionManager ptm = new RecordingTransactionManager();
        RecordingListPublisher delegate = new RecordingListPublisher();
        EventPublisher p = new AfterCommitEventPublisher(delegate, new SpringTransactionBoundary());
        EventEnvelope health = healthFixture();
        EventEnvelope configState = EventJson.reader().readValue(Files.readString(
                ContractArtifacts.dir("events/fixtures").resolve("config-state.json")));

        new TransactionTemplate(ptm).executeWithoutResult(status -> {
            p.publish(health);
            p.publish(configState);
        });

        assertEquals(List.of(health, configState), delegate.published,
                "Spring runs registered synchronizations in registration order; the adapter must "
                        + "not reorder them");
    }

    static final class RecordingListPublisher implements EventPublisher {
        final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope event) {
            published.add(event);
        }
    }
}
