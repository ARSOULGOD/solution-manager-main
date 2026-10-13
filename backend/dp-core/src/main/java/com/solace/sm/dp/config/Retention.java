package com.solace.sm.dp.config;

/**
 * How many of an instance's newest generations are kept (OR-33's "configurable", default 10, minimum 1:
 * {@code sm.dp.config.retained-generations}).
 *
 * <p><b>The effective bound, as ruled (owner ruling 2026-09-29, Q-R (a), OR-50):</b> N, plus one generation per
 * apply ever submitted for the instance, because a generation the journal names is audit evidence and is never
 * deleted. That grows with the journal, which is never pruned today; journal pruning is a later owner decision.
 * This class enforces only N. The two things that must never go whatever N is, the latest generation and every
 * journal-named one, are enforced by the database trigger, because a trigger cannot read application configuration
 * and must not hard-code N.
 */
public record Retention(int retainedGenerations) {

    public static final int DEFAULT = 10;

    public Retention {
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException(
                    "sm.dp.config.retained-generations must be at least 1: the latest generation is never deleted");
        }
    }
}
