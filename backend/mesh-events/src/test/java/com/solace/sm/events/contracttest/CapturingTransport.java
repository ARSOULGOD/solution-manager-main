package com.solace.sm.events.contracttest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Test transport: captures the one (topic, body) pair handed over, or throws on demand. */
public final class CapturingTransport implements BiConsumer<String, String> {

    private final AtomicReference<Map.Entry<String, String>> sent = new AtomicReference<>();
    private RuntimeException failure;

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public void accept(String topic, String body) {
        if (failure != null) {
            throw failure;
        }
        sent.set(Map.entry(topic, body));
    }

    /** The captured (topic, body), or null if nothing reached the transport. */
    public Map.Entry<String, String> sent() {
        return sent.get();
    }
}
