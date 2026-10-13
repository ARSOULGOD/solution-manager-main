package com.solace.sm.gateway.archfixture.monitoring;

import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.ConfigSecretServiceImpl;
import com.solace.sm.dp.config.RollbackTargetRows;
import com.solace.sm.dp.inventory.InventoryService;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 011 P11-4.1): production code building a command, or a service, through the constructor that
 * carries NO metrics. A declare through it would silently declare no metrics; a stage would record an empty active list,
 * which the collector reads as "nothing is collectable". Calls the secondary constructor of each of the five types
 * {@code MetricsDeclarationConstructorTest} guards, so the rule is proved to fire on every one of them.
 *
 * <p>Never executed: it exists only to be analysed. <b>Do not copy this pattern into production code.</b>
 */
public final class DeclaresWithoutMetrics {

    private DeclaresWithoutMetrics() {
    }

    public static Object declare() {
        return new DeclareInstanceCommand(null, null, null, null, null, null, null, null, (UUID) null);
    }

    public static Object stage() {
        return new StageCommand(0L, null, null, null, null, List.of(), null, null, (UUID) null);
    }

    public static Object inventory() {
        return new InventoryService(null, null, null, null, null, null);
    }

    public static Object records() {
        return new ConfigRecordServiceImpl(null, null, null, null, null, (RollbackTargetRows) null);
    }

    public static Object secrets() {
        return new ConfigSecretServiceImpl(null, null, null, null, null, null);
    }
}
