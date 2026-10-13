package com.solace.sm.dp.api.config;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a package declares about its MI's logger support (OR-103, {@code BE-D38}): the package has a {@code logs.loggers}
 * block, and this is the one fact the data plane needs from it to write the SM-owned overlay, its
 * {@code logs.baselineExposure}. The gateway resolves it from the declared package and passes it with the stage; the
 * data plane never reads a package (M8). A stage that carries none (a package declaring no loggers, or a copy-forward,
 * which keeps the overlay entries of the generation it copies) adds no overlay.
 *
 * <p>The baseline is part of the overlay's value because SM writes the whole exposure list, not an addition to it: the
 * SM-written file overrides the MI's own, and SM "cannot read the effective value" ({@code UI-D10}).
 *
 * @param baselineExposure the package's {@code logs.baselineExposure}: actuator endpoint ids the MI exposes today
 */
public record LoggerSupport(List<String> baselineExposure) {

    /** An actuator endpoint id as a Spring Boot property value: letters, digits and hyphens, no comma, no space. */
    public static final Pattern ENDPOINT_ID = Pattern.compile("[A-Za-z][A-Za-z0-9-]{0,63}");

    public LoggerSupport {
        baselineExposure = List.copyOf(Objects.requireNonNull(baselineExposure, "baseline exposure"));
        for (String id : baselineExposure) {
            if (id == null || !ENDPOINT_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("an exposed endpoint id is outside the endpoint grammar");
            }
        }
    }
}
