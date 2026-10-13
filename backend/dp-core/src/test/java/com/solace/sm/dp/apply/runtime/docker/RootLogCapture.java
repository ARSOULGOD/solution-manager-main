package com.solace.sm.dp.apply.runtime.docker;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * Root-logger capture for the adapter's own secrecy sweep.
 *
 * <p>A local twin of the persistence package's {@code LogCapture}, which is package-private there.
 * Captures at TRACE so a bind-value or debug line cannot hide below the configured level — the same
 * reason plan 004's version does.
 */
final class RootLogCapture {

    private RootLogCapture() {
    }

    record Captured<T>(T value, String text) {
    }

    static <T> Captured<T> during(Supplier<T> body) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();
        Collector collector = new Collector();
        collector.setContext(root.getLoggerContext());
        collector.start();
        root.addAppender(collector);
        root.setLevel(Level.TRACE);
        try {
            T value = body.get();
            StringBuilder b = new StringBuilder();
            for (String line : collector.lines) {
                b.append(line).append('\n');
            }
            return new Captured<>(value, b.toString());
        } finally {
            root.setLevel(original);
            root.detachAppender(collector);
            collector.stop();
        }
    }

    private static final class Collector extends AppenderBase<ILoggingEvent> {

        private final List<String> lines = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            lines.add(event.getLevel() + " " + event.getLoggerName() + " — "
                    + event.getFormattedMessage());
        }
    }
}
