package com.solace.sm.dp.apply.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/**
 * Wraps a real {@link DataSource} so a test can read every SQL text it was asked to run, and whether the connection
 * was in auto-commit at that moment. Nothing is faked: every call is forwarded.
 */
final class SqlRecorder {

    record Executed(String sql, boolean autoCommit) {
    }

    private SqlRecorder() {
    }

    static MetricsPartitionMain.DataSourceFactory recording(List<Executed> log) {
        return (url, user, password) -> wrap(SmDataMigrationMain.dataSource(url, user, password), log);
    }

    private static DataSource wrap(DataSource real, List<Executed> log) {
        return (DataSource) Proxy.newProxyInstance(SqlRecorder.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = call(real, method, args);
                    return "getConnection".equals(method.getName()) ? wrap((Connection) result, log) : result;
                });
    }

    private static Connection wrap(Connection real, List<Executed> log) {
        return (Connection) Proxy.newProxyInstance(SqlRecorder.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    Object result = call(real, method, args);
                    if (method.getName().equals("createStatement")) {
                        return wrap((Statement) result, real, log, null, Statement.class);
                    }
                    if (method.getName().equals("prepareStatement")) {
                        return wrap((PreparedStatement) result, real, log, (String) args[0], PreparedStatement.class);
                    }
                    return result;
                });
    }

    private static Object wrap(Statement real, Connection connection, List<Executed> log, String prepared, Class<?> type) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("execute") || name.equals("addBatch")) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : prepared;
                if (sql != null) {
                    synchronized (log) {
                        log.add(new Executed(sql, connection.getAutoCommit()));
                    }
                }
            }
            return call(real, method, args);
        };
        return Proxy.newProxyInstance(SqlRecorder.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
