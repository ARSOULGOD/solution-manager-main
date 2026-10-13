package com.solace.sm.dp.meshbridge.ingest;

/**
 * One open session to a customer broker that the Data Plane subscribes on (plan 011 P11-5.1; OR-107). The seam over the broker
 * SDK, so the manager is unit-testable without a broker and the SDK stays inside {@code meshbridge} ({@code onlyMeshbridgeTouchesJcsmp}).
 */
public interface SampleSubscriber extends AutoCloseable {

    /** Adds a subscription and waits for the broker's confirmation. Idempotent. */
    void subscribe(String topicPattern) throws BrokerSessionException;

    void unsubscribe(String topicPattern) throws BrokerSessionException;

    /** {@code false} once the session is closed for good (a session that is merely reconnecting is still open). */
    boolean isOpen();

    @Override
    void close();
}
