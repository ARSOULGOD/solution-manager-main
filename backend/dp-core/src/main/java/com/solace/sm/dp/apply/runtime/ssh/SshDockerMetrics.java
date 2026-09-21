package com.solace.sm.dp.apply.runtime.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;

/** Reads the Cache Engine's fixed, local-only metric endpoints through pinned SSH. */
public final class SshDockerMetrics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SshDockerPlacementCatalog catalog;
    private final SshDockerTransport transport;

    public SshDockerMetrics(SshDockerPlacementCatalog catalog) {
        this(catalog, new SshjDockerTransport());
    }

    SshDockerMetrics(SshDockerPlacementCatalog catalog, SshDockerTransport transport) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
    }

    public MetricsSnapshot snapshot(PlacementRef placement) {
        SshDockerPlacement target = catalog.lookup(placement);
        SshDockerTransport.CacheMetricResponses responses = transport.cacheMetrics(target);
        JsonNode stats = document(responses.stats());
        long size = wholeNumber(responses.size());
        JsonNode memory = document(responses.heapUsed());
        JsonNode measurement = memory.path("measurements").path(0).path("value");
        if (!measurement.isNumber()) throw failure("ssh.metrics.heap.invalid");
        return new MetricsSnapshot(requiredNumber(stats, "hitCount"), requiredNumber(stats, "missCount"),
                requiredNumber(stats, "evictionCount"), requiredNumber(stats, "hitRate"), size,
                measurement.doubleValue());
    }

    private static JsonNode document(String raw) {
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (parsed == null || !parsed.isObject()) throw failure("ssh.metrics.document.invalid");
            return parsed;
        } catch (RuntimeAdapterException error) {
            throw error;
        } catch (Exception error) {
            throw failure("ssh.metrics.document.invalid");
        }
    }

    private static double requiredNumber(JsonNode document, String name) {
        JsonNode value = document.path(name);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) throw failure("ssh.metrics.value.invalid");
        return value.doubleValue();
    }

    private static long wholeNumber(String raw) {
        String value = raw.strip();
        if (!value.matches("[0-9]{1,10}")) throw failure("ssh.metrics.size.invalid");
        return Long.parseLong(value);
    }

    private static RuntimeAdapterException failure(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }

    public record MetricsSnapshot(double hitCount, double missCount, double evictionCount, double hitRate,
            long size, double heapUsed) {}
}
