package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The declared binding for one placement on Docker — {@code SM-D21}'s "per-artifact, per-runtime"
 * config location, in the two views that matter, plus how the replicas are found.
 *
 * @param containerNamePrefix   the name filter that enumerates this placement's replicas
 * @param containerConfigPath   the directory the workload reads, as the workload sees it
 * @param localConfigDir        the same directory as the SM process sees it
 * @param fileName              the single file SM owns inside it
 * @param mechanism             {@code SM-D3}'s declared mechanism
 * @param retainedGenerations   how many rollback pre-images to keep ({@code B/D12}'s "last ~10")
 */
public record DockerPlacement(String containerNamePrefix, String containerConfigPath,
        Path localConfigDir, String fileName, ConfigLocation.Mechanism mechanism,
        int retainedGenerations) {

    public DockerPlacement {
        Objects.requireNonNull(containerNamePrefix, "container name prefix");
        Objects.requireNonNull(containerConfigPath, "container config path");
        Objects.requireNonNull(localConfigDir, "local config dir");
        Objects.requireNonNull(fileName, "file name");
        Objects.requireNonNull(mechanism, "mechanism");
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException("at least one pre-image must be retained");
        }
    }

    public ConfigLocation location() {
        return new ConfigLocation(containerConfigPath, new WriterAccess.LocalFilesystem(localConfigDir),
                fileName, mechanism);
    }
}
