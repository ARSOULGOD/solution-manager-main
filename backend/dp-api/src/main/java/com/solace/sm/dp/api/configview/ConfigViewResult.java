package com.solace.sm.dp.api.configview;

import java.util.List;
import java.util.Objects;

/**
 * One answer of {@link ConfigViewService#view} (plan 017 task 5): either the allow-listed settings ({@link Ok}) or a refusal with
 * one fixed {@link Reason} ({@link Refused}). Closed on purpose, so the console maps every outcome to exactly one HTTP status.
 *
 * <p>Nothing here ever carries a file path, a host, a container, or a key or value the data plane did not re-check against the
 * allow list it sent (I6). Neither {@code toString} prints a key name or a value.
 */
public sealed interface ConfigViewResult permits ConfigViewResult.Ok, ConfigViewResult.Refused {

    /** Why a view was refused. {@link #message()} is fixed text and quotes nothing. */
    enum Reason {
        /** The placement is unknown, another organization's or instance's, retired, or its configuration file is missing. */
        NOT_FOUND("the placement's configuration was not found"),
        /** The file, or the allow-listed output, is over the agent's limits. */
        TOO_LARGE("the placement's configuration is too large to view"),
        /** The file is not valid YAML. */
        INVALID_YAML("the placement's configuration is not valid YAML"),
        /** No agent serves the placement right now: not connected, not capable, not leased, or it did not answer in time. */
        AGENT_UNAVAILABLE("the agent serving the placement is not available"),
        /** The placement is reached over SSH (or another transport); the view exists only for agent placements. */
        NOT_AN_AGENT_PLACEMENT("the configuration view is available only for agent placements"),
        /** Too many views are in flight, here or at the agent; nothing was queued. */
        BUSY("configuration views are busy; try again shortly"),
        /** The agent's answer was unusable: it failed the data plane's re-check, was malformed, or the agent could not serve it. */
        BAD_GATEWAY("the agent's answer could not be used");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        /** Fixed text for the caller; never a path, key or value. */
        public String message() {
            return message;
        }
    }

    /** One allow-listed scalar setting: its dotted path and its value as text. */
    record Entry(String path, String value) {
        public Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Entry[redacted]";
        }
    }

    /**
     * The settings whose paths matched the allow list (re-checked by the data plane), how many other settings the agent hid, and
     * whether the agent stopped early at its key limit.
     */
    record Ok(List<Entry> entries, int hiddenCount, boolean truncated) implements ConfigViewResult {
        public Ok {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            if (hiddenCount < 0) {
                throw new IllegalArgumentException("hiddenCount must not be negative");
            }
        }

        @Override
        public String toString() {
            return "Ok[entries=" + entries.size() + ", hiddenCount=" + hiddenCount + ", truncated=" + truncated + "]";
        }
    }

    /** A refusal: one fixed reason, nothing else. */
    record Refused(Reason reason) implements ConfigViewResult {
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static ConfigViewResult refused(Reason reason) {
        return new Refused(reason);
    }
}
