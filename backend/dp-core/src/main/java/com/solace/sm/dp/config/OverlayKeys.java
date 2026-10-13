package com.solace.sm.dp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.config.LoggerSupport;
import com.solace.sm.dp.api.logs.LoggerLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The SM-owned overlay, as ONE function (OR-103, {@code BE-D37}; draft L-2 of {@code log-levels.md}): the properties SM
 * writes beside a package's fields, which are not package fields. {@code OR-34}'s "refuse an undeclared key" is a rule
 * about keys the CLIENT supplies and is untouched: the client never supplies one of these, and a stage that tries to is
 * refused ({@link CanonicalContent}).
 *
 * <p><b>Two callers, one list.</b> {@link #forLoggerSupport} is the only place that decides which keys SM adds and what
 * they say; {@link #owns} is the only place that decides which keys are SM's. The stage (which stores the entries) and the
 * renderer (which refuses an overlay-keyed entry at a path SM does not own) both go through them, and accept-runtime's
 * read-back (T10.3) strips by {@link #owns}. A second list, in the renderer or in the read-back, is the failure this
 * class exists to prevent: it would make accept treat SM's own keys as an operator's edits, or the reverse.
 *
 * <p><b>What it writes, and what it does not (the ruling's option Rd).</b> {@code loggers} is added to the exposure list
 * and the loggers endpoint is made read-only ({@code management.endpoint.loggers.access: read-only}, measured to answer
 * {@code 405} to a write, {@code log-levels.md} P-7). It writes NO management port and NO management address: the
 * actuator stays where the package says it is, so health and metrics do not move, and the endpoint adds no write
 * surface. A level change is configuration ({@code T9.4}), not a call.
 *
 * <p>The exposure value is the sorted union of the package's declared baseline and SM's required names, because the
 * SM-written file replaces the MI's own exposure list and SM cannot read the effective one ({@code UI-D10}).
 */
public final class OverlayKeys {

    /**
     * The reserved instance key the overlay is stored under in a generation's rendered set. It is not a component
     * instance: it has no draft, no field and no section, and the designer never shows it.
     */
    public static final String INSTANCE_KEY = "@sm-overlay";

    static final String EXPOSURE_PATH = "management.endpoints.web.exposure.include";
    static final String LOGGERS_ACCESS_PATH = "management.endpoint.loggers.access";

    /** The endpoint id SM requires exposed for a package that declares logger support. */
    static final String LOGGERS_ENDPOINT = "loggers";

    /** Every path {@link #forLoggerSupport} can emit, and nothing else. */
    private static final Set<String> OWNED = Set.of(EXPOSURE_PATH, LOGGERS_ACCESS_PATH);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OverlayKeys() {
    }

    /** One overlay entry: a dotted rendered path and its value as JSON text. */
    public record Entry(String path, String valueJson) {
        public Entry {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(valueJson, "value");
        }
    }

    /**
     * The entries a package that declares logger support gets, ordered by path. A pure function of the declaration, so
     * the same declaration gives the same entries, and a stage that repeats it is guard 1's "unchanged".
     */
    public static List<Entry> forLoggerSupport(LoggerSupport support) {
        Objects.requireNonNull(support, "support");
        TreeSet<String> exposed = new TreeSet<>(support.baselineExposure());
        exposed.add(LOGGERS_ENDPOINT);
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(LOGGERS_ACCESS_PATH, "\"read-only\""));
        entries.add(new Entry(EXPOSURE_PATH, "\"" + String.join(",", exposed) + "\""));
        entries.sort(java.util.Comparator.comparing(Entry::path));
        return List.copyOf(entries);
    }

    /**
     * Whether {@code renderedPath} is a key SM MAY write: the baseline overlay's two paths, or any configured level
     * ({@link #isLevelPath}). The stage's and the renderer's rule: a package field may not claim such a path, and only such
     * a path may sit under the overlay's reserved key. Exact: a path beneath an owned key is not the owned key. Which
     * level paths SM DID write is per generation ({@link #ownsIn}).
     */
    public static boolean owns(String renderedPath) {
        return renderedPath != null && (OWNED.contains(renderedPath) || isLevelPath(renderedPath));
    }

    // ---- T9.4: the configured level (OR-103, P2) --------------------------------------------------------------

    /** The property prefix of a Spring Boot logger level ({@code log-levels.md} section 2.2, M13). */
    public static final String LEVEL_PREFIX = "logging.level.";

    /** A dotted Java package or class name without {@code $}: each segment starts with a letter or underscore. */
    private static final Pattern LOGGER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private static final int MAX_LOGGER_NAME = 255;

    /**
     * Whether {@code name} may be a logger SM configures: a dotted identifier (never {@code $}, which a shell would
     * expand and a property key does not need), at most 255 characters, and not {@code ROOT}, which Boot treats specially
     * and a package has no business setting. The one grammar the stage, the renderer's ownership test and the live read
     * share.
     */
    public static boolean isLoggerName(String name) {
        return name != null && name.length() <= MAX_LOGGER_NAME && LOGGER_NAME.matcher(name).matches()
                && !name.equalsIgnoreCase("root");
    }

    /**
     * The one entry that configures a logger's level: {@code logging.level.<loggerPrefix>} with the level's name as a
     * JSON string. The logger is the package's declared {@code loggerPrefix}; the caller (the gateway) resolves it, and
     * it is checked here again because this is the only place that turns it into a property key.
     *
     * @throws IllegalArgumentException the prefix is outside the logger-name grammar
     */
    public static Entry forLogLevel(String loggerPrefix, LoggerLevel level) {
        Objects.requireNonNull(level, "level");
        if (!isLoggerName(loggerPrefix)) {
            throw new IllegalArgumentException("a logger name is a dotted identifier, and never the root logger");
        }
        return new Entry(LEVEL_PREFIX + loggerPrefix, "\"" + level.name() + "\"");
    }

    /** Whether {@code renderedPath} is one of the two baseline overlay paths (never a level). */
    public static boolean ownsBaseline(String renderedPath) {
        return renderedPath != null && OWNED.contains(renderedPath);
    }

    /**
     * Whether SM wrote {@code renderedPath} in a particular generation: a baseline overlay path, or one of the level
     * paths that generation carries. The read-back asks this (T10.3): a {@code logging.level.<other logger>} an operator
     * added on the host is the operator's, not SM's, though {@link #owns} would let SM write it.
     */
    public static boolean ownsIn(String renderedPath, java.util.Set<String> configuredLevelPaths) {
        return ownsBaseline(renderedPath) || (renderedPath != null && configuredLevelPaths.contains(renderedPath));
    }

    /** Whether {@code renderedPath} is {@code logging.level.<a usable logger name>}: a level SM wrote. */
    public static boolean isLevelPath(String renderedPath) {
        return renderedPath != null && renderedPath.startsWith(LEVEL_PREFIX)
                && isLoggerName(renderedPath.substring(LEVEL_PREFIX.length()));
    }

    /** The logger a level path configures. @throws IllegalArgumentException not a level path */
    public static String loggerOf(String levelPath) {
        if (!isLevelPath(levelPath)) {
            throw new IllegalArgumentException("not a level path");
        }
        return levelPath.substring(LEVEL_PREFIX.length());
    }

    /** The level a stored entry value names, or empty for anything that is not one of the contract's levels. */
    public static Optional<LoggerLevel> levelOf(String valueJson) {
        if (valueJson == null) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(valueJson);
            return node != null && node.isTextual() ? LoggerLevel.fromBoot(node.textValue()) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
