package com.solace.sm.dp.apply.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * Everything the JVM LOGS while a block of code runs — not what it prints to the two streams the code
 * was handed.
 *
 * <p>The distinction is the whole point of the {@code I6} checks that use this. {@link
 * SmDataMigrationMain} controls its own two {@code PrintStream}s and can be held to "no URL, ever";
 * Flyway, HikariCP and Spring's {@code SimpleDriverDataSource} log through slf4j on their own account
 * and cannot be. Asserting only on the injected streams would therefore prove the weaker of the two
 * statements while looking like the stronger one. A {@code ListAppender} on the ROOT logger sees every
 * event from every library on the classpath, so what it returns is the honest answer to "did the URL
 * escape".
 *
 * <p>Root level is forced to TRACE for the duration so a library that only names the URL at DEBUG is
 * caught too, and restored afterwards.
 */
final class LogCapture {

    private LogCapture() {
    }

    /** One captured event, already formatted. */
    record Line(String level, String logger, String message) {

        @Override
        public String toString() {
            return level + " " + logger + " — " + message;
        }
    }

    /**
     * @param body the code to run with root logging captured
     * @return its value AND every event logged while it ran
     */
    static <T> Captured<T> during(Supplier<T> body) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();
        Collector collector = new Collector();
        collector.setContext(root.getLoggerContext());
        collector.start();
        root.addAppender(collector);
        root.setLevel(Level.TRACE);
        try {
            return new Captured<>(body.get(), List.copyOf(collector.lines));
        } finally {
            root.setLevel(original);
            root.detachAppender(collector);
            collector.stop();
        }
    }

    /** A result and the log it produced. */
    record Captured<T>(T value, List<Line> lines) {

        /** The formatted lines joined, for a substring assertion over the whole log. */
        String text() {
            StringBuilder b = new StringBuilder();
            for (Line l : lines) {
                b.append(l).append('\n');
            }
            return b.toString();
        }

        /** The first line containing {@code fragment}, or null. */
        String firstContaining(String fragment) {
            for (Line l : lines) {
                if (l.message().contains(fragment)) {
                    return l.toString();
                }
            }
            return null;
        }
    }

    private static final class Collector extends AppenderBase<ILoggingEvent> {

        private final List<Line> lines = new ArrayList<>();

        @Override
        protected synchronized void append(ILoggingEvent event) {
            // The formatted message AND the attached throwable chain: a URL-bearing Flyway or Hikari
            // exception logged as log.error(msg, ex) lives in the throwable, not the message, and a
            // capture that dropped it would be blind to exactly the leak it exists to catch (measured).
            StringBuilder text = new StringBuilder(event.getFormattedMessage());
            for (ch.qos.logback.classic.spi.IThrowableProxy t = event.getThrowableProxy(); t != null;
                    t = t.getCause()) {
                text.append(" | ").append(t.getClassName()).append(": ").append(t.getMessage());
            }
            lines.add(new Line(event.getLevel().toString(), event.getLoggerName(), text.toString()));
        }
    }
}
