package com.solace.sm.dp.api.config;

import java.util.List;
import java.util.Objects;

/**
 * Where an instance sits on the configuration-state ladder (OR-37), without a single configuration value (I6):
 * state, generation numbers and KEYED digests only (OR-32).
 *
 * <p>{@code desired} and {@code rendered} carry the newest generation's content digest (the same value, keyed). A
 * generation's file bytes need its secrets resolved, which a read never does, so {@code rendered} stays a digest of
 * the stored content. {@code persisted} and {@code restart-observed} carry the keyed hash of the file bytes the host
 * was read back holding (plan 010 T6.5): a different value, and nothing may compare it with the first two.
 *
 * @param rungs          all five rungs, in ladder order
 * @param reached        the highest rung the instance has reached (never a rung whose {@code present} is false,
 *                       except {@link LadderRung#DESIRED} on an instance with no generation yet)
 * @param degradedReason null when every rung is reported; otherwise why some are not. A degraded ladder is declared,
 *                       never silently short
 */
public record ConfigLadderView(List<Rung> rungs, LadderRung reached, String degradedReason) {

    public ConfigLadderView {
        rungs = List.copyOf(Objects.requireNonNull(rungs, "rungs"));
        Objects.requireNonNull(reached, "reached");
        if (rungs.size() != LadderRung.values().length) {
            throw new IllegalArgumentException("a ladder has exactly one entry per rung");
        }
        for (int i = 0; i < rungs.size(); i++) {
            if (rungs.get(i).rung() != LadderRung.values()[i]) {
                throw new IllegalArgumentException("the rungs are in ladder order");
            }
        }
    }

    /** @param generation null when the rung is not present; @param configHash the keyed digest, null when none */
    public record Rung(LadderRung rung, boolean present, Long generation, String configHash) {
        public Rung {
            Objects.requireNonNull(rung, "rung");
        }
    }
}
