package com.solace.sm.dp.apply.engine;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every engine service runs its unit of work in a transaction OF ITS OWN — never inside a caller's.
 *
 * <p>Measured before it was written (plan 004's adversarial review): with the default
 * {@code PROPAGATION_REQUIRED}, an {@code AttemptService.open()} invoked inside a caller's transaction
 * returned its row and the row was then rolled back with the caller — which silently voids §2.2 rule 0
 * ("an intent with no attempt row provably issued nothing"). Likewise a lost fence race inside
 * {@code admit} marks the transaction rollback-only, which under an enclosing transaction surfaces as
 * an {@code UnexpectedRollbackException} at the OUTER commit instead of as the refusal value.
 *
 * <p>So the services derive a {@code REQUIRES_NEW} template from the one they are given. The
 * property is not "may be composed into a larger transaction"; it is the opposite, and it is stated
 * here once rather than as a precondition in four javadocs.
 */
final class EngineTransactions {

    private EngineTransactions() {
    }

    /**
     * The same transaction manager, {@code PROPAGATION_REQUIRES_NEW}. Only the MANAGER is carried
     * over — an isolation level, timeout or read-only flag set on the supplied template is
     * deliberately not inherited, so the engine's transactions stay uniform whatever a caller does to
     * its own. {@code REQUIRES_NEW} also suspends the caller's transaction and borrows a SECOND pooled
     * connection for the engine's duration: a caller that holds its own transaction across
     * {@code open}/{@code close}/{@code land} costs two connections per in-flight step, which is the
     * pool-sizing input for whichever plan sets the apply concurrency.
     */
    static TransactionTemplate requiresNew(TransactionTemplate template) {
        TransactionTemplate own = new TransactionTemplate(template.getTransactionManager());
        own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return own;
    }
}
