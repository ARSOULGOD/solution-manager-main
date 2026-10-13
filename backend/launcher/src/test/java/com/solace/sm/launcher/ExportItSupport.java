package com.solace.sm.launcher;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** What the export ITs share (plan 011 P11-7.2): a large stored series, a counting authorization, a stalling client. */
final class ExportItSupport {

    static final int BULK_ROWS = 300_000;

    private ExportItSupport() {
    }

    /** The first instant of the current UTC month: the bulk series' first sample (the current month's partition exists). */
    static Instant monthStart() {
        return Instant.now().atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Inserts {@link #BULK_ROWS} one-second samples of a gauge {@code cache.bulk} on one placement, straight into the store
     * (the real ingest seam is exercised by every other test; this is the volume a slow client needs to back the server's
     * writes up). Returns nothing: the export is read back over HTTP.
     */
    static void bulkSeries(NamedParameterJdbcTemplate jdbc, World world, UUID instance, UUID placement) {
        Long series = jdbc.queryForObject("INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name,"
                + " metric_type, unit, source_discriminator, labels_fingerprint, created_at) VALUES (:t, :i, :p,"
                + " 'cache.bulk', 'gauge', 'count', 'dp-poll', :fp, now()) RETURNING series_id",
                Map.of("t", world.organization(), "i", instance, "p", placement, "fp", "sha256:" + "0".repeat(64)),
                Long.class);
        jdbc.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment, received_at)"
                + " SELECT CAST(:t AS uuid), CAST(:s AS bigint), CAST(:start AS timestamptz) + (g * interval '1 second'),"
                + " g, NULL, now() FROM generate_series(0, CAST(:n AS int) - 1) AS g",
                Map.of("t", world.organization(), "s", series,
                        "start", java.sql.Timestamp.from(monthStart()), "n", BULK_ROWS));
    }

    /** Counts how many times the export verb is asked "held anywhere": one export must be one decision. */
    static final AtomicInteger EXPORT_AUTHORIZATIONS = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    static class CountingAuthorization {
        @Bean
        static BeanPostProcessor countReachChecks() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof ReachAuthorization reach && !(bean instanceof Counting)) {
                        return new Counting(reach);
                    }
                    return bean;
                }
            };
        }
    }

    private static final class Counting implements ReachAuthorization {
        private final ReachAuthorization delegate;

        Counting(ReachAuthorization delegate) {
            this.delegate = delegate;
        }

        @Override
        public void requireHeldAnywhere(PrincipalRef principal, Permission permission) {
            if (permission == Permission.METRICS_EXPORT) {
                EXPORT_AUTHORIZATIONS.incrementAndGet();
            }
            delegate.requireHeldAnywhere(principal, permission);
        }

        @Override
        public Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind,
                Collection<UUID> ids) {
            return delegate.authorizedSubset(principal, permission, kind, ids);
        }
    }

    /** What a stalling client saw. */
    record Streamed(int status, Map<String, List<String>> headers, long dataRows, boolean endedEarly) {
    }

    /**
     * GETs {@code path}, waits {@code stall} after the headers arrive without reading a byte (so the server's writes back up
     * against a full socket), then reads to the end and counts the data rows it got. A read that fails part-way is
     * {@code endedEarly}: the server cut the stream.
     */
    static Streamed stalledExport(int port, Login login, String path, Duration stall) throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session()).GET()
                .timeout(Duration.ofMinutes(5)).build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Thread.sleep(stall.toMillis());
        long lines = 0;
        boolean early = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                lines++;
            }
        } catch (IOException cut) {
            early = true;
        }
        return new Streamed(response.statusCode(), response.headers().map(), Math.max(0, lines - 1), early);
    }
}
