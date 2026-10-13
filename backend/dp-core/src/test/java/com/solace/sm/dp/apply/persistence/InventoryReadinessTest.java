package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;

/**
 * Plan 013 T1.4: the inventory readiness stage now also refuses a V021 schema, on which the first registration's INSERT
 * would fail. Offline: the data source answers each readiness query with a count the test chooses.
 */
class InventoryReadinessTest {

    private final List<String> asked = new ArrayList<>();

    private DataSource dataSource(int inventoryTables, int v022Objects, int v026Objects, int v027Objects) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (p, m, a) -> {
                    if (!m.getName().equals("getConnection")) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("close")) {
                                    return null;
                                }
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] {Statement.class}, (sp, sm, sa) -> {
                                            if (sm.getName().equals("close")) {
                                                return null;
                                            }
                                            String sql = (String) sa[0];
                                            asked.add(sql);
                                            int count = sql.contains("host_key_algorithm") ? v027Objects
                                                    : sql.contains("retired_by") ? v026Objects
                                                    : sql.contains("target_connection_event") ? v022Objects
                                                    : inventoryTables;
                                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                                    new Class<?>[] {ResultSet.class}, (rp, rm, ra) -> {
                                                        if (rm.getName().equals("next")) {
                                                            return true;
                                                        }
                                                        if (rm.getName().equals("getInt")) {
                                                            return count;
                                                        }
                                                        return null;
                                                    });
                                        });
                            });
                });
    }

    private InitializingBean readiness(int inventoryTables, int v022Objects) {
        return readiness(inventoryTables, v022Objects, 2);
    }

    private InitializingBean readiness(int inventoryTables, int v022Objects, int v026Objects) {
        return readiness(inventoryTables, v022Objects, v026Objects, 1);
    }

    private InitializingBean readiness(int inventoryTables, int v022Objects, int v026Objects, int v027Objects) {
        return new InventoryPersistenceConfiguration()
                .inventorySchemaReadiness(dataSource(inventoryTables, v022Objects, v026Objects, v027Objects));
    }

    @Test
    void aSchemaWithTheInventoryTablesAndV022IsReady() {
        assertDoesNotThrow(() -> readiness(3, 2).afterPropertiesSet());
        assertTrue(asked.stream().anyMatch(q -> q.contains("credential_public_fingerprint")), asked.toString());
    }

    @Test
    void aV021SchemaIsRefusedAndTheMessageNamesTheMigrationStep() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> readiness(3, 0).afterPropertiesSet());

        assertTrue(refused.getMessage().contains("V022") && refused.getMessage().contains("SmDataMigrationMain"),
                refused.getMessage());
    }

    @Test
    void halfOfV022IsStillRefused() {
        assertThrows(IllegalStateException.class, () -> readiness(3, 1).afterPropertiesSet());
    }

    @Test
    void theInventoryTablesAreStillCheckedFirst() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> readiness(2, 2).afterPropertiesSet());

        assertTrue(refused.getMessage().contains("inventory tables"), refused.getMessage());
    }

    @Test
    void aV025SchemaWithoutThePlacementActorColumnsIsRefusedAndTheMessageNamesV026() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> readiness(3, 2, 0).afterPropertiesSet());

        assertTrue(refused.getMessage().contains("V026") && refused.getMessage().contains("SmDataMigrationMain"),
                refused.getMessage());
        assertThrows(IllegalStateException.class, () -> readiness(3, 2, 1).afterPropertiesSet(),
                "only one of created_by and retired_by is still not V026");
    }

    @Test
    void aV026SchemaWithoutThePinnedKeyTypeColumnIsRefusedAndTheMessageNamesV027() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> readiness(3, 2, 2, 0).afterPropertiesSet());

        assertTrue(refused.getMessage().contains("V027") && refused.getMessage().contains("SmDataMigrationMain"),
                refused.getMessage());
    }
}
