package com.solace.sm.cp.audit;

/** A verb with an unruled consequence class cannot silently become an ordinary audit event. */
public final class ConsequenceClassUndecidedException extends RuntimeException {

    public ConsequenceClassUndecidedException() {
        super("an audit kind cannot be selected while the consequence class is UNDECIDED");
    }
}
