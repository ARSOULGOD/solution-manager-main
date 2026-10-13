package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The keyed-hash bridge (plan 015 T3.1; {@code OR-32}, T1.2 review finding 7). The agent speaks the plain SHA-256 of the file on
 * its host; SM stores and compares only the KEYED {@code hmacsha256v<n>:<hex>} of {@link ConfigHasher}. A plain digest cannot be
 * turned into a keyed one without the bytes, so this keeps an in-memory {@code (placement, plain SHA) -> keyed hash} map, filled
 * whenever bytes are sent.
 *
 * <p>On a miss (SM restarted since the bytes were sent) it renders AT MOST the placement's candidate generations (intended and
 * last-applied), each once (a failed render again after a back-off), and learns the pairs: a status poll never renders, and twenty polls render at most the
 * candidates, once. A generation that fails to render is remembered as tried and is not retried until the process restarts (the
 * failure is classified by the caller's {@link RenderedConfigSource}, and its message is never read, {@code I6}).
 *
 * <p>Nothing here is persisted; the map is bounded per placement and in placements. Bytes are zeroed after hashing.
 */
public final class AgentHashBridge {

    private static final int PAIRS_PER_PLACEMENT = 16;
    private static final int MAX_PLACEMENTS = 4096;

    private static final class Pairs extends LinkedHashMap<String, String> {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > PAIRS_PER_PLACEMENT;
        }
    }

    private record Tried(PlacementRef placement, long generation) {
    }

    private final RenderedConfigSource renderer;
    private final ConfigHasher hasher;
    private final AgentConfigGenerations generations;
    private final Map<PlacementRef, Pairs> byPlacement = new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<PlacementRef, Pairs> eldest) {
            return size() > MAX_PLACEMENTS;
        }
    };
    /** A candidate that rendered is final; one that failed is tried again after {@link #RETRY_AFTER}, never once per poll. */
    private final Map<Tried, Instant> tried = new HashMap<>();
    private final Clock clock;
    static final Duration RETRY_AFTER = Duration.ofSeconds(30);
    private static final Instant NEVER = Instant.MAX;

    public AgentHashBridge(RenderedConfigSource renderer, ConfigHasher hasher, AgentConfigGenerations generations) {
        this(renderer, hasher, generations, Clock.systemUTC());
    }

    public AgentHashBridge(RenderedConfigSource renderer, ConfigHasher hasher, AgentConfigGenerations generations, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.generations = Objects.requireNonNull(generations, "generations");
    }

    /** {@code sha256:<hex>} of {@code bytes}: the DP canonical form of a plain digest. */
    public static String plainSha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Learns that {@code plainSha256} is {@code keyedHash} for this placement; called whenever bytes are sent. */
    public synchronized void record(PlacementRef placement, String plainSha256, String keyedHash) {
        byPlacement.computeIfAbsent(placement, p -> new Pairs()).put(plainSha256, keyedHash);
    }

    /** The keyed hash of the file whose plain digest the agent reported; empty when it matches no known generation. */
    public Optional<String> keyedFor(PlacementRef placement, String plainSha256) {
        Optional<String> known = lookup(placement, plainSha256, true);
        if (known.isPresent()) {
            return known;
        }
        learnCandidates(placement);
        return lookup(placement, plainSha256, true);
    }

    /** The plain digest of the generation whose keyed hash is {@code keyedHash} (REMATERIALIZE's expectation); empty when unknown. */
    public Optional<String> plainFor(PlacementRef placement, String keyedHash) {
        Optional<String> known = lookup(placement, keyedHash, false);
        if (known.isPresent()) {
            return known;
        }
        learnCandidates(placement);
        return lookup(placement, keyedHash, false);
    }

    private synchronized Optional<String> lookup(PlacementRef placement, String needle, boolean byPlain) {
        Pairs pairs = byPlacement.get(placement);
        if (pairs == null) {
            return Optional.empty();
        }
        if (byPlain) {
            return Optional.ofNullable(pairs.get(needle));
        }
        return pairs.entrySet().stream().filter(e -> e.getValue().equals(needle)).map(Map.Entry::getKey).findFirst();
    }

    private void learnCandidates(PlacementRef placement) {
        for (long generation : generations.candidates(placement)) {
            synchronized (this) {
                if (tried.size() >= 65_536) {
                    tried.clear();                  // bounded: worst case a generation renders once more
                }
                Tried candidate = new Tried(placement, generation);
                Instant retryAt = tried.get(candidate);
                if (retryAt != null && clock.instant().isBefore(retryAt)) {
                    continue;
                }
                tried.put(candidate, clock.instant().plus(RETRY_AFTER));      // provisional: a render in flight is not retried
            }
            byte[] bytes = null;
            try {
                RenderedConfig rendered = renderer.render(placement, generation);
                bytes = rendered.bytes();
                record(placement, plainSha256(bytes), hasher.hash(placement.tenant(), bytes));
                synchronized (this) {
                    tried.put(new Tried(placement, generation), NEVER);
                }
            } catch (RuntimeException e) {
                // tried and failed: tried again after RETRY_AFTER, never once per poll. The message is never read (I6).
            } finally {
                if (bytes != null) {
                    java.util.Arrays.fill(bytes, (byte) 0);
                }
            }
        }
    }

    /** A keyed hash of a digest the bridge cannot place: stable, distinct per content and equal to no generation's. */
    public String unknownContentHash(PlacementRef placement, String plainSha256) {
        return hasher.hash(placement.tenant(), ("agent-content-matches-no-known-generation:" + plainSha256)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "AgentHashBridge";
    }
}
