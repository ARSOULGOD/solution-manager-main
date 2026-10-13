package com.solace.sm.dp.meshbridge.ingest;

/**
 * A broker session could not be opened or could not take a subscription (plan 011 P11-5.1). The message is a fixed sentence: the
 * SDK's own text may carry the host, the VPN or the client username, which a log has no business holding with a connection id.
 */
public final class BrokerSessionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BrokerSessionException(String message) {
        super(message, null, false, false);   // no cause, no stack: the SDK's exception is discarded by design
    }
}
