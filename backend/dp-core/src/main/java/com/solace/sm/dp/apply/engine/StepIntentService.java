package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.Constraints;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import com.solace.sm.dp.apply.persistence.StepIntentRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §2.2 rule 2, executable: exactly one durable intent per logical {@code (apply, step, replica)},
 * with the composite UNIQUE as the ARBITER rather than a lock.
 *
 * <p><b>Two transactions, and the second one is not optional.</b> The find-then-insert runs at READ
 * COMMITTED in transaction one. When a concurrent creator wins, PostgreSQL raises 23505 and
 * <i>aborts that transaction</i> — every subsequent statement on it fails with 25P02. So the losing
 * side cannot re-read inside the transaction it lost in; it must open a NEW one. That is measured, not
 * inferred: {@code StepIntentIT}'s arbiter race counts the re-finds this class performs.
 *
 * <p><b>Which index is the arbiter is a MEASURED fact, and it is not the one the design first
 * named.</b> Design §2.2 rule 2 originally called the composite {@code (tenant, apply, step, replica)}
 * UNIQUE "the arbiter" and treated a conflict on {@code idempotency_key} as the protocol error. But a
 * genuine concurrent same-step create presents the SAME key, so it violates BOTH indexes, and
 * PostgreSQL reports whichever it reaches first — which is {@code apply_step_intent_idempotency_key_key},
 * because an inline column {@code UNIQUE} is created before the table-level ones and index insertion
 * follows that order ({@code StepIntentIT} records the name it actually got). Classifying on the name
 * alone would therefore turn every legitimate race into {@code IdempotencyKeyReused}.
 *
 * <p>So the classification is by <b>outcome of the re-read</b>, with the name only deciding whether a
 * re-read is warranted at all: either 23505 sends this caller into a fresh transaction, and what it
 * finds there settles the story. A row for this logical step means the race happened — compare and
 * return it. NO row for this logical step, with one of the two keys nevertheless taken, means the key
 * belongs to some other step, which is the protocol error no retry can fix. Both endings are the ones
 * §2.2 rule 2 asks for; only the discriminator moved. {@code ON CONFLICT … DO NOTHING} remains
 * unusable for exactly the reason the design gives.
 *
 * <p><b>Obligation (q) is engine-enforced and the database has no artifact for it</b> — the FK only
 * says the request exists, not that it is still open. {@link TerminalRequest} is that rule, and
 * {@code StepIntentIT} proves the raw repository insert against a terminal request still succeeds, so
 * the claim "engine-enforced" is measured rather than asserted.
 *
 * <p><b>(q) is proven for the sequential case only.</b> {@link #ensureIntent} takes no scope-row
 * lock: it reads {@code request.state()}, finds it non-terminal, and inserts — in its own
 * {@code REQUIRES_NEW} transaction at READ COMMITTED — with nothing serializing those two steps
 * against a concurrent {@link OutcomeService#land}, which {@link OutcomeService}'s javadoc states
 * takes the scope's {@code apply_scope} row lock first for every state-changing operation.
 * {@link #ensureIntent} is outside that branch rule. There is no production path today that calls
 * this concurrently with {@code land} (there is no step loop yet), so this is not a defect in this
 * plan — but it is a real check-then-act race with no database artifact once a step loop exists.
 * Plan 005's step loop must decide whether {@link #ensureIntent} needs the scope lock.
 */
public final class StepIntentService {

    private final TransactionTemplate tx;
    private final ApplyRequestRepository requests;
    private final StepIntentRepository intents;
    private final Clock clock;
    private final AtomicLong arbiterReFinds = new AtomicLong();

    public StepIntentService(TransactionTemplate tx, ApplyRequestRepository requests,
            StepIntentRepository intents, Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.requests = requests;
        this.intents = intents;
        this.clock = clock;
    }

    /**
     * The one durable intent for this logical step, creating it if this caller is the first.
     *
     * @throws IntentMismatch        the recorded intent carries a different key or hash
     * @throws IdempotencyKeyReused  the key already belongs to a different step
     * @throws TerminalRequest       the request is terminal — obligation (q). Proven for the
     *                               sequential case only: this method takes no scope-row lock, so a
     *                               concurrent {@link OutcomeService#land} can commit a terminal
     *                               state between the read and the insert below — see the class
     *                               javadoc.
     */
    public StepIntentRow ensureIntent(TenantId t, ApplyId id, Step step, ReplicaRef replica,
            String idempotencyKey, String configHash) {
        Objects.requireNonNull(idempotencyKey, "idempotency key");
        StepIntentRow settled = tx.execute(status -> {
            Optional<StepIntentRow> found = intents.find(t, id, step, replica);
            if (found.isPresent()) {
                return found.get();
            }
            ApplyRequestRow request = requests.find(t, id).orElseThrow(
                    () -> new IllegalStateException("no apply_request " + id.value()));
            if (request.state().isTerminal()) {
                throw new TerminalRequest("apply_request " + id.value() + " is terminal ("
                        + request.state() + "); obligation (q) forbids new intents on it");
            }
            StepIntentRow fresh = new StepIntentRow(Uuid7.next(), id, step, replica, idempotencyKey,
                    configHash, clock.instant());
            try {
                intents.insert(t, fresh);
                return fresh;
            } catch (PgConstraintViolation e) {
                if (e.namesAnyOf(Constraints.INTENT_STEP_REPLICA_UNIQUE,
                        Constraints.INTENT_IDEMPOTENCY_KEY_UNIQUE)) {
                    // Somebody else holds one of the two keys. This transaction is now ABORTED, so
                    // the read-compare cannot happen here — signal with null and let a NEW one run.
                    status.setRollbackOnly();
                    return null;
                }
                throw e;
            }
        });

        if (settled != null) {
            return compare(settled, idempotencyKey, configHash);
        }
        arbiterReFinds.incrementAndGet();
        Optional<StepIntentRow> winner = tx.execute(status -> intents.find(t, id, step, replica));
        if (winner.isEmpty()) {
            // No row for THIS logical step, yet one of the two UNIQUEs refused: the only remaining
            // explanation is that the key belongs to some other step. That is the protocol error.
            // The key itself is caller-supplied free text and is not echoed (I6 posture: identifiers
            // in messages, never values) — the step, replica and apply identify the collision.
            throw new IdempotencyKeyReused("the supplied idempotency key already belongs to another "
                    + "step; no intent exists for " + step + "/" + replica.value() + " on apply "
                    + id.value());
        }
        return compare(winner.get(), idempotencyKey, configHash);
    }

    /** Every intent of one request, oldest first — the intent half of design §3.1's resume read. */
    public java.util.List<StepIntentRow> intentsOf(TenantId t, ApplyId id) {
        return tx.execute(status -> intents.forRequest(t, id));
    }

    /**
     * How many times the arbiter race sent this instance down the re-find path. A measurement hook,
     * not behaviour: {@code StepIntentIT} needs to prove the loser really re-read rather than
     * accidentally finding the row on its first {@code find}.
     */
    public long arbiterReFinds() {
        return arbiterReFinds.get();
    }

    private static StepIntentRow compare(StepIntentRow recorded, String key, String hash) {
        if (!recorded.idempotencyKey().equals(key)) {
            throw new IntentMismatch("intent " + recorded.intentId()
                    + " is bound to a different idempotency key than the one supplied");
        }
        if (!Objects.equals(recorded.configHash(), hash)) {
            throw new IntentMismatch("intent " + recorded.intentId() + " intends hash "
                    + recorded.configHash() + ", not " + hash + " (hashes are identifiers, echoed)");
        }
        return recorded;
    }

    /** A retry presented the recorded key with a different hash, or a different key entirely. */
    public static final class IntentMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IntentMismatch(String message) {
            super(message);
        }
    }

    /** {@code idempotency_key} is globally unique; this one already names another step. */
    public static final class IdempotencyKeyReused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdempotencyKeyReused(String message) {
            super(message);
        }
    }

    /** Obligation (q): intents exist only for requests that are still open. */
    public static final class TerminalRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TerminalRequest(String message) {
            super(message);
        }
    }
}
