package com.solace.sm.cp.identity;

import java.util.UUID;

/**
 * Sets the transaction-local context that the {@code sm_control.change_audit} trigger
 * ({@code OR-19}) reads. Its JDBC adapter belongs in {@code cp.persistence}.
 *
 * <p>⚠️ <b>Call INSIDE the same transaction as the writes it should attribute, before those writes
 * run.</b> The underlying setting is transaction-local ({@code set_config(..., true)}); it never
 * outlives the transaction it was set in, and a write that happens outside any transaction — or in
 * a different one — sees no context and is audited with {@code NULL} actor/operation/correlation/
 * client fields (tolerated, never a reason to fail the business write).
 */
public interface AuditContext {

    /**
     * @param actorPrincipalId the acting principal, or {@code null} when none is known or safe to
     *                         reveal (e.g. a refused login for an unknown username, or an operator
     *                         command that acts on nobody's behalf)
     * @param auditOperation   one of {@link AuditOperations}' closed vocabulary, or {@code null}
     * @param correlationId    the server-minted request correlation id
     * @param clientAddress    the resolved client address
     */
    void set(UUID actorPrincipalId, String auditOperation, UUID correlationId, String clientAddress);
}
