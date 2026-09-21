package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.RenderedConfig;
import java.time.Instant;
import java.util.Optional;

/**
 * Narrow, testable remote file-and-container transport. The adapter supplies only placement data;
 * no caller can pass an arbitrary shell command through this interface.
 */
interface SshDockerTransport {

    record ContainerSnapshot(String containerId, String imageId, boolean running, String startedAt,
            String health) {
    }

    ContainerSnapshot inspect(SshDockerPlacement placement);

    /** One read-only snapshot for status pages: container state plus config hash. */
    default StatusSnapshot statusSnapshot(SshDockerPlacement placement) {
        String hash = readFile(placement, placement.configFile())
                .map(bytes -> RenderedConfig.of(bytes).hash()).orElse(null);
        return new StatusSnapshot(inspect(placement), hash);
    }

    /** Read-only preflight check of the declared remote config directory. */
    void verifyConfigDirectoryWritable(SshDockerPlacement placement);

    Optional<byte[]> readFile(SshDockerPlacement placement, String absolutePath);

    void writeFileAtomically(SshDockerPlacement placement, String absolutePath, byte[] content);

    void deleteFile(SshDockerPlacement placement, String absolutePath);

    boolean restart(SshDockerPlacement placement);

    boolean stop(SshDockerPlacement placement);

    /** Bounded container log retrieval through the closed SSH command set. */
    default String logs(SshDockerPlacement placement, int tailLines) {
        throw new UnsupportedOperationException("diagnostics are not implemented by this transport");
    }

    /** Timestamp-bounded log retrieval used for paging and follow mode. */
    default String logs(SshDockerPlacement placement, int tailLines, Instant after, Instant before) {
        if (after != null || before != null) {
            throw new UnsupportedOperationException("timestamped diagnostics are not implemented by this transport");
        }
        return logs(placement, tailLines);
    }

    /** Fixed Cache Engine metric endpoints; no caller supplies a command or URL. */
    default String cacheStats(SshDockerPlacement placement) {
        throw new UnsupportedOperationException("metrics are not implemented by this transport");
    }

    default String cacheSize(SshDockerPlacement placement) {
        throw new UnsupportedOperationException("metrics are not implemented by this transport");
    }

    default String heapUsed(SshDockerPlacement placement) {
        throw new UnsupportedOperationException("metrics are not implemented by this transport");
    }

    /**
     * Reads all fixed Cache Engine endpoints in one authenticated SSH operation.
     * The default keeps focused transport tests small; the production transport
     * overrides it to avoid three SSH handshakes for a single live sample.
     */
    default CacheMetricResponses cacheMetrics(SshDockerPlacement placement) {
        return new CacheMetricResponses(cacheStats(placement), cacheSize(placement), heapUsed(placement));
    }

    record CacheMetricResponses(String stats, String size, String heapUsed) {}

    record StatusSnapshot(ContainerSnapshot container, String configHash) {}
}
