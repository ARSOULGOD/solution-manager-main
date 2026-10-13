package com.solace.sm.dp.metrics.push;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A package's {@code topicPattern}, re-checked by the Data Plane exactly as the schema checks it (plan 011 P11-5.1; defence in
 * depth: a package a validator was bypassed for must still not widen a subscription), and matched the way the broker matches.
 *
 * <p>Levels are joined by {@code /}; each is a literal ({@code [A-Za-z0-9_.-]{1,64}}) or {@code *}, which stands for exactly
 * ONE level; at most sixteen. <b>The last level is a literal</b> ({@code I6}): no {@code >}, no {@code *}, so a subscription
 * names its states and an event of another state (a {@code start} event may carry a plaintext broker password, facts F15/F16)
 * is never delivered to the Data Plane. There is no {@code #}, no {@code +}, no empty level and no substitution token: the
 * Data Plane subscribes once per distinct pattern and never per instance (OR-107).
 *
 * <p>Matching is <b>case-sensitive</b> and level for level (MF26, measured on the local broker, 2026-10-06).
 */
public final class TopicPattern {

    private static final Pattern LITERAL = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final int MAX_LEVELS = 16;

    private final String value;
    private final String[] levels;

    private TopicPattern(String value, String[] levels) {
        this.value = value;
        this.levels = levels;
    }

    /** @throws IllegalArgumentException with a fixed sentence naming no part of the input */
    public static TopicPattern of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("a topic pattern is not empty");
        }
        String[] levels = raw.split("/", -1);
        if (levels.length > MAX_LEVELS) {
            throw new IllegalArgumentException("a topic pattern has at most " + MAX_LEVELS + " levels");
        }
        for (int i = 0; i < levels.length; i++) {
            boolean last = i == levels.length - 1;
            boolean ok = LITERAL.matcher(levels[i]).matches() || (!last && "*".equals(levels[i]));
            if (!ok) {
                throw new IllegalArgumentException(last
                        ? "the last level of a topic pattern is a literal (no >, no *, no substitution)"
                        : "a level of a topic pattern is a literal or *");
            }
        }
        return new TopicPattern(raw, levels);
    }

    /** Whether {@code topic} is matched: the same number of levels, every literal equal (case-sensitive), a {@code *} for any one. */
    public boolean matches(String topic) {
        if (topic == null || topic.isEmpty()) {
            return false;
        }
        String[] actual = topic.split("/", -1);
        if (actual.length != levels.length) {
            return false;
        }
        for (int i = 0; i < levels.length; i++) {
            if (!"*".equals(levels[i]) && !levels[i].equals(actual[i])) {
                return false;
            }
            if (actual[i].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public String value() {
        return value;
    }

    public String lastLevel() {
        return levels[levels.length - 1];
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TopicPattern other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
