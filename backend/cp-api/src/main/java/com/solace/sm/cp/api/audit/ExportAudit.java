package com.solace.sm.cp.api.audit;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.TenantId;
import java.util.UUID;

/**
 * The record that a metrics export ran (M-Q22 (7)): one high-consequence audit row for the verb
 * {@code metrics.export}, written by the control plane for the data plane's stream.
 *
 * <p>An export that was REFUSED ({@code 403}, {@code 422}, {@code 429}) is not an export and is not {@link #record}ed:
 * it is one {@code DENY} row of the same verb, written by {@link #refused} (OR-112 (6), {@code W4-04}). {@link #record}
 * is an export that was allowed, and how it ended.
 */
public interface ExportAudit {

    /** How the stream ended. */
    enum ExportResult {
        /** Every row was written and the stream closed normally. */
        COMPLETED("completed"),
        /** The data plane failed part-way. */
        FAILED("failed"),
        /** Cut short: the async timeout, or the client disconnecting. */
        CUT("cut");

        private final String status;

        ExportResult(String status) {
            this.status = status;
        }

        /** The value recorded in the audit row's {@code new_status}. */
        public String status() {
            return status;
        }
    }

    /**
     * Why an export was refused: exactly the statuses the owner named (OR-112 (6)). A closed set of fixed codes, each a
     * valid {@code reason_code} of an audit row; none carries a metric name, a range or an id from the request.
     */
    enum RefusalReason {
        /** {@code 403}: the caller does not hold {@code metrics.export}, or the session may not use it yet. */
        FORBIDDEN("forbidden"),
        /** {@code 422}: the export would exceed the row limit. */
        TOO_LARGE("export_too_large"),
        /** {@code 429}: the query rate, or the export concurrency, is spent. */
        RATE_LIMITED("rate_limited");

        private final String code;

        RefusalReason(String code) {
            this.code = code;
        }

        /** The value recorded in the audit row's {@code reason_code}. */
        public String code() {
            return code;
        }
    }

    /**
     * Records that an export was refused ({@code 403}, {@code 422} or {@code 429}) as one {@code DENY} row of the verb
     * {@code metrics.export}, in the same audit log as {@link #record}. Written by the gateway's refusal interceptor on the
     * request thread, once per refused request; no stream was ever opened, so there is no row count.
     *
     * @param origin        as for {@link #record}
     * @param organization  the caller's organization, also the resource audited
     * @param correlationId the refused request's correlation id, recorded in the row so it joins to the HTTP response
     */
    void refused(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID correlationId,
            RefusalReason reason);

    /**
     * @param origin        where the request came from; carried because every control-plane port that records an
     *                      action takes it, and not persisted ({@code audit_log} has no address column)
     * @param organization  the caller's organization ({@code Session.organizationId()}), also the resource audited
     * @param correlationId the export's correlation id, recorded in the row so it joins to the HTTP response
     * @param rows          how many rows were written to the client
     */
    void record(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID correlationId, long rows,
            ExportResult result);
}
