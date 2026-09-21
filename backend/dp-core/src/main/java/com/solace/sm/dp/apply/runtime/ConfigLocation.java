package com.solace.sm.dp.apply.runtime;

import java.util.Objects;

/**
 * The resolved config location for one placement — {@code SM-D21}'s "per-artifact, per-runtime,
 * discovered by pre-flight", in both views that matter.
 *
 * @param containerPath the directory the workload itself reads, as the workload sees it
 * @param writerAccess  how this adapter writes the config. Docker is local; a remote transport never
 *                      pretends the DP process can open the host path (`BE-D24`).
 * @param fileName      the file SM owns inside that directory
 * @param mechanism     {@code SM-D3}'s declared mechanism
 */
public record ConfigLocation(String containerPath, WriterAccess writerAccess, String fileName,
        Mechanism mechanism) {

    /** {@code SM-D3}'s two declared mechanisms. */
    public enum Mechanism { ADDITIONAL_LOCATION, OVERWRITE_IN_PLACE }

    public ConfigLocation {
        Objects.requireNonNull(containerPath, "container path");
        Objects.requireNonNull(writerAccess, "writer access");
        Objects.requireNonNull(fileName, "file name");
        Objects.requireNonNull(mechanism, "mechanism");
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("the config file name is a single path segment");
        }
    }

    public String containerFile() {
        return containerPath.endsWith("/") ? containerPath + fileName
                                           : containerPath + "/" + fileName;
    }
}
