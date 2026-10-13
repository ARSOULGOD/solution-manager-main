package com.solace.sm.dp.apply.persistence;

import org.flywaydb.core.api.output.MigrateResult;

/** Both migration runs of {@link SmDataMigrationMain#runAll}: {@code sm_data} first, then {@code sm_metrics}. */
public record MigrationReport(MigrateResult smData, MigrateResult smMetrics) {
}
