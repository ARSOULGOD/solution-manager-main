package com.solace.sm.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Records what the named loggers say while a test runs (plan 010 T6.6), without changing any logger's level: the
 * resilience scenarios assert on the ABSENCE of a worker or engine complaint ("no exception reaches the engine") and on
 * the PRESENCE of the publisher's counted-drop warning, and a test that cannot see the log cannot say either.
 *
 * <p>Only formatted messages are read, never an argument that could carry a value (I6): the engine and the worker log
 * exception CLASSES, and so does everything this watches.
 */
final class LogWatch implements AutoCloseable {

    private final List<Logger> loggers = new ArrayList<>();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    LogWatch(Class<?>... sources) {
        appender.start();
        for (Class<?> source : sources) {
            Logger logger = (Logger) LoggerFactory.getLogger(source);
            logger.addAppender(appender);
            loggers.add(logger);
        }
    }

    /** Formatted messages at {@code atLeast} or above, oldest first. */
    List<String> messages(Level atLeast) {
        List<ILoggingEvent> events;
        synchronized (appender.list) {
            events = new ArrayList<>(appender.list);
        }
        return events.stream().filter(e -> e.getLevel().isGreaterOrEqual(atLeast)).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    long countContaining(Level atLeast, String fragment) {
        return messages(atLeast).stream().filter(m -> m.contains(fragment)).count();
    }

    @Override
    public void close() {
        for (Logger logger : loggers) {
            logger.detachAppender(appender);
        }
        appender.stop();
    }
}
