package com.solace.sm.cp.audit;

/** Append-only audit persistence port. Its JDBC adapter belongs in {@code cp.persistence}. */
public interface AuditLog {

    /** Records one entry within the caller's transaction. */
    void record(AuditEntry entry);
}
