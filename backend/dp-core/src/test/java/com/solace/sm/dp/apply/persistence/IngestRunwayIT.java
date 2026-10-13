package com.solace.sm.dp.apply.persistence;

import static com.solace.sm.dp.apply.persistence.IngestFixture.batch;
import static com.solace.sm.dp.apply.persistence.IngestFixture.metric;
import static com.solace.sm.dp.apply.persistence.IngestFixture.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.apply.persistence.IngestFixture.Site;
import com.solace.sm.dp.metrics.ingest.IngestService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-3.1, Review Focus 5: when the partition runway runs out, ONLY metrics ingest degrades. It runs on a
 * database of its own because it drops a partition, which the shared database's other ITs must never see.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestRunwayIT {

    private static final List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> DEFS = List.of(
            metric("cache.size", "gauge", "entries", "cache", 30));

    @Test
    void runwayExhaustionDegradesIngestOnly() throws Exception {
        try (IngestFixture fx = IngestFixture.create("ingest_runway")) {
            // W2-10: ingest drops a sample more than five minutes past its clock, so the month that can lack a partition
            // is the NEXT one only in the last minutes of this one. The clock runs from two minutes before month end.
            YearMonth missing = YearMonth.now(ZoneOffset.UTC).plusMonths(1);
            Instant monthEnd = missing.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Clock nearMonthEnd = Clock.offset(Clock.systemUTC(),
                    Duration.between(Instant.now(), monthEnd.minus(Duration.ofMinutes(2))));
            IngestService service = fx.service(IngestFixture.fast(20, 500), nearMonthEnd);
            Site site = fx.site("via-ssh", "2.4", DEFS, List.of());
            String child = "metrics_raw_" + missing.format(DateTimeFormatter.ofPattern("yyyy_MM"));
            fx.db.asOwner("ALTER TABLE sm_metrics.metrics_raw DETACH PARTITION sm_metrics." + child,
                    "DROP TABLE sm_metrics." + child);
            Instant thisMonth = monthEnd.minus(Duration.ofMinutes(3));
            Instant inTheMissingMonth = monthEnd.plusSeconds(30);

            // One batch: a sample this month AND one next month. The whole batch is one transaction.
            IngestResult result = service.ingest(batch(site, sample("cache.size", thisMonth, 1, null),
                    sample("cache.size", inTheMissingMonth, 2, null)));

            IngestResult.RetryAfter retry = assertInstanceOf(IngestResult.RetryAfter.class, result, String.valueOf(result));
            assertTrue(retry.millis() > 0);
            assertTrue(service.runwayDegraded(), "the collector-health flag reads degraded");
            assertEquals(0, fx.points(site, "cache.size").size(), "no partial batch: the in-month sample was rolled back too");
            assertEquals(0, fx.seriesCount(site), "the series created for it was rolled back with it");
            assertTrue(fx.counted(site, "runway_degraded") >= 1, "counted once per failed attempt");

            // Nothing else is affected: a declare (definition copy and rows) and an ordinary sm_data read still work,
            // and a batch that stays inside the existing partitions still commits.
            Site another = fx.site("via-ssh", "2.4", DEFS, List.of());
            assertEquals(1, fx.jdbc.queryForObject("SELECT count(*)::int FROM sm_data.instance_state WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", another.tenant()), Integer.class));
            assertInstanceOf(IngestResult.Committed.class,
                    service.ingest(batch(another, sample("cache.size", thisMonth, 3, null))));

            // The operator's runway command comes back; the held batch is written, once, and the flag clears.
            new JdbcPartitionRunwayRepository(fx.db.runtimeJdbc()).ensure();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && fx.points(site, "cache.size").size() < 2) {
                Thread.sleep(50);
            }
            assertEquals(List.of(1.0, 2.0), fx.points(site, "cache.size").stream().map(IngestFixture.Point::value).toList());
            while (System.nanoTime() < deadline && service.runwayDegraded()) {
                Thread.sleep(50);
            }
            assertFalse(service.runwayDegraded());
            assertEquals(0, service.heldBatches());
        }
    }
}
