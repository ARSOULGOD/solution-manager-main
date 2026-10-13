package com.solace.sm.dp.apply.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * Wraps a real {@link DataSource} (plan 011 P11-6.2's ITs) so a test can read, for every prepared statement, the SQL text and
 * the three facts PgJDBC's cursor streaming depends on AT THE MOMENT it executes: the fetch size, the result-set type and
 * whether the connection was in auto-commit. It also counts open connections, so "the disconnect released the connection"
 * is a measurement. Nothing is faked: every call is forwarded.
 */
final class ExportProbe {

    /** One executed prepared statement. */
    record Executed(String sql, int fetchSize, int resultSetType, boolean autoCommit, boolean readOnly) {
    }

    private final List<Executed> executed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger open = new AtomicInteger();
    private final DataSource wrapped;

    ExportProbe(DataSource real) {
        this.wrapped = (DataSource) Proxy.newProxyInstance(ExportProbe.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = call(real, method, args);
                    if ("getConnection".equals(method.getName())) {
                        open.incrementAndGet();
                        return wrap((Connection) result);
                    }
                    return result;
                });
    }

    DataSource dataSource() {
        return wrapped;
    }

    int openConnections() {
        return open.get();
    }

    /** Every executed statement whose text contains {@code fragment}. */
    List<Executed> executedContaining(String fragment) {
        synchronized (executed) {
            return executed.stream().filter(e -> e.sql().contains(fragment)).toList();
        }
    }

    private Connection wrap(Connection real) {
        return (Connection) Proxy.newProxyInstance(ExportProbe.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = call(real, method, args);
                    if ("close".equals(method.getName())) {
                        open.decrementAndGet();
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        return wrap((PreparedStatement) result, real, (String) args[0]);
                    }
                    return result;
                });
    }

    private PreparedStatement wrap(PreparedStatement real, Connection connection, String sql) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().startsWith("execute")) {
                executed.add(new Executed(sql, real.getFetchSize(), real.getResultSetType(), connection.getAutoCommit(),
                        connection.isReadOnly()));
            }
            return call(real, method, args);
        };
        return (PreparedStatement) Proxy.newProxyInstance(ExportProbe.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, handler);
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
