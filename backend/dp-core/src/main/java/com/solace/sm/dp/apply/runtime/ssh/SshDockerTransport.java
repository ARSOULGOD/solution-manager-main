package com.solace.sm.dp.apply.runtime.ssh;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Narrow, testable remote file-and-container transport. The adapter supplies only placement data;
 * no caller can pass an arbitrary shell command through this interface.
 */
interface SshDockerTransport extends HostProbe {

    record ContainerSnapshot(String containerId, String imageId, boolean running, String startedAt,
            String health) {
    }

    ContainerSnapshot inspect(SshDockerPlacement placement);

    /**
     * Read-only preflight check that the Docker daemon itself answers, before any container is named — so a
     * daemon that is down or unreachable classifies distinctly from a container that is merely absent. A
     * transport with nothing to check (the in-memory test double, a systemd-only transport) does nothing.
     */
    default void ensureDockerAvailable(SshDockerPlacement placement) {
    }

    /**
     * One read-only snapshot for status pages: container state plus the config file's bytes. The snapshot carries
     * <b>bytes, not a hash</b>: the keyed hash (OR-32) is computed in the DP by the adapter, so this interface
     * cannot express a hash computed on the customer host.
     */
    default StatusSnapshot statusSnapshot(SshDockerPlacement placement) {
        return new StatusSnapshot(inspect(placement), readFile(placement, placement.configFile()).orElse(null));
    }

    /** Read-only preflight check of the declared remote config directory. */
    void verifyConfigDirectoryWritable(SshDockerPlacement placement);

    Optional<byte[]> readFile(SshDockerPlacement placement, String absolutePath);

    void writeFileAtomically(SshDockerPlacement placement, String absolutePath, byte[] content);

    void deleteFile(SshDockerPlacement placement, String absolutePath);

    /**
     * The names (not paths) of the regular files in the placement's declared config directory whose name starts with a
     * dot, which is where SM keeps its pre-image, fingerprint and absence files (Fable APPLY-02). Bounded; read-only; the
     * adapter filters to its own fixed grammar before deleting anything. A transport that cannot list returns nothing,
     * which only means nothing is pruned.
     */
    default List<String> listHiddenFileNames(SshDockerPlacement placement) {
        return List.of();
    }

    boolean restart(SshDockerPlacement placement);

    /** Starts an existing stopped container without recreating it. */
    default boolean start(SshDockerPlacement placement) {
        throw new UnsupportedOperationException("docker start is not implemented by this transport");
    }

    boolean stop(SshDockerPlacement placement);

    @Override
    default ProbeResult probe(SshDockerPlacement placement, int port, String path, java.time.Duration timeout) {
        throw new UnsupportedOperationException("host probes are not implemented by this transport");
    }

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

    /** {@code configBytes} is null when the file is absent; the bytes are copied in and out. */
    record StatusSnapshot(ContainerSnapshot container, byte[] configBytes) {
        public StatusSnapshot {
            configBytes = configBytes == null ? null : configBytes.clone();
        }

        @Override
        public byte[] configBytes() {
            return configBytes == null ? null : configBytes.clone();
        }

        /** Length only: the bytes are configuration and never render into a log line (I6). */
        @Override
        public String toString() {
            return "StatusSnapshot[" + container + ", "
                    + (configBytes == null ? "absent" : configBytes.length + " bytes") + "]";
        }
    }
}
