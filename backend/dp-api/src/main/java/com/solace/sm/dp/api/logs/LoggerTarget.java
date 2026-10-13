package com.solace.sm.dp.api.logs;

import java.util.Objects;

/**
 * What the gateway resolves from the instance's declared package and passes with a read: the component, its
 * {@code loggerPrefix} and the package's {@code logs.loggers.path} (BE-D38). The data plane never reads a package (M8),
 * and it does not trust these: the prefix and the path are checked against their grammars before any session opens.
 *
 * @param component    the package component type the request named
 * @param loggerPrefix the component's {@code loggerPrefix}: the logger whose level is read
 * @param path         the package's {@code logs.loggers.path}, default {@code /actuator/loggers}
 */
public record LoggerTarget(String component, String loggerPrefix, String path) {

    public LoggerTarget {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(loggerPrefix, "logger prefix");
        Objects.requireNonNull(path, "path");
    }
}
