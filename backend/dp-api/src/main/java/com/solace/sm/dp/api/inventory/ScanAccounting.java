package com.solace.sm.dp.api.inventory;

/**
 * The caller's side of an edit's scan (review {@code W4C-3}). Whether an edit scans the host is decided by the data plane,
 * under the record it reads, and a decision made earlier by the caller (from {@link EditPlan}) can differ from it when a
 * concurrent edit moves the record. So the data plane TELLS the caller when it is about to scan and when the scan came
 * back, and the caller charges and audits from that, not from its plan: each scan is then charged and recorded exactly
 * once, whatever the plan said and however the edit ends.
 *
 * <p>Both calls are made on the request thread, with no lock held and no transaction open.
 */
public interface ScanAccounting {

    /** An accounting that does nothing: for a caller that does not charge scans (a test, an operator command). */
    ScanAccounting NONE = new ScanAccounting() {
        @Override
        public void beforeScan() {
        }
    };

    /**
     * Called once, before the data plane connects to the host the caller named, when the edit needs the host scanned. It
     * may throw to refuse the scan (a spent budget): the exception leaves the edit unchanged and nothing has been
     * scanned.
     */
    void beforeScan();

    /** Called once, when the scan returned the host's keys (before it is known whether the pin is among them). */
    default void afterScan() {
    }
}
