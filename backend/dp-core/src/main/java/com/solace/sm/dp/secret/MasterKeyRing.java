package com.solace.sm.dp.secret;

import java.util.Objects;
import java.util.Optional;

/**
 * The keys this process holds: the {@code current} one, which wraps every new data key, and, during a
 * rotation only, the {@code previous} one, loaded for unwrapping (D-KEY 5.2).
 */
public final class MasterKeyRing {

    private final MasterKey current;
    private final Optional<MasterKey> previous;

    public MasterKeyRing(MasterKey current, Optional<MasterKey> previous) {
        this.current = Objects.requireNonNull(current, "current");
        this.previous = Objects.requireNonNull(previous, "previous");
        if (previous.isPresent() && previous.get().id().equals(current.id())) {
            throw new IllegalArgumentException("the current and previous keys must have different ids");
        }
    }

    public MasterKey current() {
        return current;
    }

    public Optional<MasterKey> previous() {
        return previous;
    }

    /** The loaded key with this id, if any. */
    public Optional<MasterKey> find(KeyId id) {
        if (current.id().equals(id)) {
            return Optional.of(current);
        }
        return previous.filter(p -> p.id().equals(id));
    }

    @Override
    public String toString() {
        return "MasterKeyRing[current=" + current.id() + previous.map(p -> ", previous=" + p.id()).orElse("") + "]";
    }
}
