package com.solace.sm.dp.apply.engine;

/**
 * §2.2 rule 4's bounded retry, as configuration.
 *
 * <p>Obligation (m) splits the enforcement deliberately: {@code attempt_no_positive} is the database's
 * lower bound (and {@code smallint} a last-ditch backstop at 32767), while the CAP is the engine's,
 * because it is configuration the database cannot know.
 *
 * @param maxAttempts at least 1
 */
public record AttemptPolicy(int maxAttempts) {

    public AttemptPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "a step must be allowed at least one attempt; got " + maxAttempts);
        }
    }
}
