package com.solace.sm.dp.api.inventory;

/**
 * A host key scan found nothing: the host could not be reached, or it presented no SSH host key (plan 013 T1.2). The
 * message is fixed and names no host.
 */
public final class HostKeyScanUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HostKeyScanUnavailableException() {
        super("the host could not be reached or presented no SSH host key");
    }
}
