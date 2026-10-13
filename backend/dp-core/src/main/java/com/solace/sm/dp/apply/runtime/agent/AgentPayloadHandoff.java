package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.api.agw.AgentOperation;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The in-memory hand-off of the bytes the apply engine already rendered (plan 015 T3.1; {@code OR-126} A5, global constraint 1):
 * SM persists no rendered byte, so the adapter parks the {@code RenderedConfig}'s bytes here and the pull reads them. Nothing is
 * written anywhere; an entry is dropped when the adapter's wait ends (acknowledged, refused, superseded, timed out or lost), and
 * the oldest is dropped when the bound is reached, in which case the pull re-renders ({@link HandoffAgentCommandPayloadSource}).
 *
 * <p><b>Keyed by the command's durable identity</b> {@code (tenant, intent key, operation)} rather than by the command id: the
 * adapter stages BEFORE it enqueues, so the bytes are in place before the command can possibly be offered, and a pull never races
 * the hand-off. The key is exactly what makes a command the same command ({@code agent_command_one_live_per_intent}).
 *
 * <p>Never logged: {@link Held#toString} names no byte and no digest ({@code I6}).
 */
public final class AgentPayloadHandoff {

    private record Key(UUID tenantId, String intentKey, AgentOperation operation) {
    }

    /** One held payload. {@code plainSha256} is {@code sha256:<hex>}; {@code expectedCurrentSha256} is REMATERIALIZE's only. */
    public static final class Held {
        private final byte[] bytes;
        private final String plainSha256;
        private final String expectedCurrentSha256;
        private UUID commandId;
        private java.time.Instant deadline;

        private Held(byte[] bytes, String plainSha256, String expectedCurrentSha256) {
            this.bytes = bytes;
            this.plainSha256 = plainSha256;
            this.expectedCurrentSha256 = expectedCurrentSha256;
        }

        public byte[] bytes() {
            return bytes.clone();
        }

        public String plainSha256() {
            return plainSha256;
        }

        public String expectedCurrentSha256() {
            return expectedCurrentSha256;
        }

        @Override
        public String toString() {
            return "Held[bytes=<redacted:" + bytes.length + ">, digests=<redacted>]";
        }
    }

    private final int maxEntries;
    private final java.time.Clock clock;
    private final Map<Key, Held> held = new LinkedHashMap<>();
    /** The command id an entry is bound to once the command is known: the key a pull finds it by ({@code AGW-D26} item 2). */
    private final Map<UUID, Key> byCommand = new java.util.HashMap<>();

    public AgentPayloadHandoff(int maxEntries) {
        this(maxEntries, java.time.Clock.systemUTC());
    }

    public AgentPayloadHandoff(int maxEntries, java.time.Clock clock) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("the hand-off holds at least one entry");
        }
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Parks a copy of {@code bytes} BEFORE the command is enqueued (so a pull can never race the hand-off), under the command's
     * durable identity; replaces an entry of the same key; drops the oldest beyond the bound.
     * {@link #bind} then re-keys it by command id once that is known.
     */
    public synchronized void stage(UUID tenantId, String intentKey, AgentOperation operation, byte[] bytes,
            String plainSha256, String expectedCurrentSha256) {
        Key key = new Key(Objects.requireNonNull(tenantId, "tenant"), Objects.requireNonNull(intentKey, "intent key"),
                Objects.requireNonNull(operation, "operation"));
        drop(key);
        held.put(key, new Held(bytes.clone(), Objects.requireNonNull(plainSha256, "plain digest"), expectedCurrentSha256));
        while (held.size() > maxEntries) {
            drop(held.keySet().iterator().next());
        }
    }

    /**
     * Binds a staged entry to its command: from here a pull finds it by command id, and it lives until the command ends
     * ({@link #release}: acknowledged, refused or superseded) or its deadline passes, whichever is first. A wait that merely
     * times out does not release it, so a pull after a timeout does not re-render ({@code AGW-D26} item 2).
     */
    public synchronized void bind(UUID tenantId, String intentKey, AgentOperation operation, UUID commandId, java.time.Instant deadline) {
        Key key = new Key(tenantId, intentKey, operation);
        Held entry = held.get(key);
        if (entry == null) {
            return;
        }
        entry.commandId = commandId;
        entry.deadline = deadline;
        byCommand.put(commandId, key);
    }

    /** By command id first (the entry is bound), else by the durable identity (a pull that beat the bind). */
    public synchronized Optional<Held> find(UUID tenantId, UUID commandId, String intentKey, AgentOperation operation) {
        Key bound = byCommand.get(commandId);
        Key key = bound != null && bound.tenantId().equals(tenantId) ? bound : new Key(tenantId, intentKey, operation);
        Held entry = held.get(key);
        if (entry != null && entry.deadline != null && !clock.instant().isBefore(entry.deadline)) {
            drop(key);                                        // its deadline passed: the command can no longer be delivered
            entry = null;
        }
        // a snapshot: a concurrent release() zeroes the stored array, and a pull must never send zeros
        return Optional.ofNullable(entry == null ? null
                : new Held(entry.bytes.clone(), entry.plainSha256, entry.expectedCurrentSha256));
    }

    /** As above, for a caller that knows only the durable identity (a notice's digest, tests). */
    public synchronized Optional<Held> find(UUID tenantId, String intentKey, AgentOperation operation) {
        return find(tenantId, new UUID(0, 0), intentKey, operation);
    }

    /** Drops (and zeroes) the entry; harmless when there is none. */
    public synchronized void release(UUID tenantId, String intentKey, AgentOperation operation) {
        drop(new Key(tenantId, intentKey, operation));
    }

    private void drop(Key key) {
        Held entry = held.remove(key);
        if (entry != null && entry.commandId != null) {
            byCommand.remove(entry.commandId);
        }
        zero(entry);
    }

    public synchronized int size() {
        return held.size();
    }

    private static void zero(Held entry) {
        if (entry != null) {
            Arrays.fill(entry.bytes, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "AgentPayloadHandoff";
    }
}
