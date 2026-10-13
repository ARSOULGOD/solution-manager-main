package com.solace.sm.dp.config.archfixture;

import com.solace.sm.dp.config.ConfigGenerationRows;

/**
 * Planted violation (plan 011 P11-4.1, M-Q15): a class that inserts a generation and never records its active metric
 * list. A generation born without one reads, to the collector, as "nothing is collectable", silently. There are two
 * places that mint a generation today; a third that forgot the list is what this rule is for.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class MintsAGenerationWithoutAList {

    private final ConfigGenerationRows rows;

    public MintsAGenerationWithoutAList(ConfigGenerationRows rows) {
        this.rows = rows;
    }

    public long mint() {
        return rows.insert(null);
    }
}
