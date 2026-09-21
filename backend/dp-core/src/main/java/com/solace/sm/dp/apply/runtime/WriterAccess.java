package com.solace.sm.dp.apply.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * How an adapter reaches the directory where it writes configuration.
 *
 * <p>This is deliberately not an SSH type. The core needs to know that an adapter's writer is local
 * or remote, but never receives a connection, credential or an operation it can use to bypass
 * {@link RuntimeAdapter}. `BE-D24` replaces the former falsely-universal local {@link Path}.
 */
public sealed interface WriterAccess permits WriterAccess.LocalFilesystem, WriterAccess.RemoteSftp {

    /** The adapter process opens this local directory itself. */
    record LocalFilesystem(Path directory) implements WriterAccess {
        public LocalFilesystem {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /** The adapter reaches this canonical directory only over its remote file transport. */
    record RemoteSftp(String canonicalDirectory) implements WriterAccess {
        public RemoteSftp {
            Objects.requireNonNull(canonicalDirectory, "canonical directory");
            if (!canonicalDirectory.startsWith("/") || canonicalDirectory.contains("..")
                    || canonicalDirectory.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("a remote config directory must be a canonical absolute path");
            }
        }
    }
}
